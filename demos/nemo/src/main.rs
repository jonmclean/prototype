use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use encrypted_spaces_nemo::{
    filesystem::EncryptedFs,
    sdk_codegen, server,
    session::{Config, InviteBundle, Profile, Session},
};
use encrypted_spaces_sdk::{Space, WebSocketTransport};
use futures_util::FutureExt;
use std::future::Future;
use std::io::BufRead;
use std::panic::AssertUnwindSafe;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

#[derive(Parser)]
#[command(about = "Mount encrypted-spaces in Nemo using GVfs WebDAV")]
struct Args {
    /// Client keys and connection settings (defaults to $XDG_STATE_HOME/encrypted-spaces/nemo).
    #[arg(long, global = true)]
    profile: Option<PathBuf>,
    #[command(subcommand)]
    command: Operation,
}

#[derive(Subcommand)]
enum Operation {
    /// Create a new space and save its client keys. Run once.
    Init {
        #[arg(long, default_value = "ws://127.0.0.1:8080/ws")]
        server: String,
        #[arg(long, default_value_t = 9843, value_parser = clap::value_parser!(u16).range(1..))]
        port: u16,
    },
    /// Join an existing space using a private invite file. Run once per new profile.
    Join {
        #[arg(long)]
        invite: PathBuf,
        /// Override the backend URL included in the invite.
        #[arg(long)]
        server: Option<String>,
        #[arg(long, default_value_t = 9843, value_parser = clap::value_parser!(u16).range(1..))]
        port: u16,
    },
    /// Invite a new member. Stop this profile's bridge first; share the file securely.
    Invite {
        /// New private file to create; existing files are never overwritten.
        #[arg(long)]
        output: PathBuf,
    },
    /// Start the bridge and mount in Nemo; type quit to unmount and stop.
    Serve,
    /// Print the GVfs URI (no password).
    Uri,
}

#[derive(Parser)]
#[command(no_binary_name = true, about = "Commands for the mounted Nemo session")]
struct TerminalArgs {
    #[command(subcommand)]
    command: TerminalCommand,
}

#[derive(Subcommand)]
enum TerminalCommand {
    /// Create a private invitation without unmounting.
    Invite {
        #[arg(long)]
        output: PathBuf,
    },
    /// Unmount and stop the bridge.
    Quit,
}

fn print_invite(output: &Path) {
    println!(
        "Invite saved to {}. Share it securely with one new user; it contains secret keys.",
        output.display()
    );
}

fn default_profile() -> Result<PathBuf> {
    let state = match std::env::var_os("XDG_STATE_HOME").filter(|p| !p.is_empty()) {
        Some(path) => PathBuf::from(path),
        None => PathBuf::from(std::env::var_os("HOME").context("HOME is unset; use --profile")?)
            .join(".local/state"),
    };
    Ok(state.join("encrypted-spaces/nemo"))
}

async fn restore_session(profile: &Profile, config: &Config) -> Result<Session> {
    let snapshot_path = profile.path.join("snapshot.json");
    let snapshot = serde_json::from_slice(&std::fs::read(&snapshot_path)?)?;
    let transport = WebSocketTransport::new(&config.server).await?;
    let space = Space::restore(transport, snapshot).await?;
    let session = Session {
        space,
        snapshot_path,
    };
    session.sync().await?;
    session.save().await?;
    Ok(session)
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    let path = match args.profile {
        Some(path) => path,
        None => default_profile()?,
    };
    match args.command {
        Operation::Init { server, port } => {
            let profile = Profile::lock(path)?;
            profile.ensure_empty()?;
            let transport = WebSocketTransport::new(&server).await?;
            let space = Space::create(transport, sdk_codegen::application_schema()).await?;
            profile.initialize(space, server, port).await?;
            println!("Created encrypted file space. Run serve to mount it in Nemo.");
        }
        Operation::Join {
            invite,
            server,
            port,
        } => {
            let profile = Profile::lock(path)?;
            profile.ensure_empty()?;
            let bundle: InviteBundle =
                serde_json::from_slice(&std::fs::read(invite).context("cannot read invite file")?)
                    .context("invalid Nemo invite file")?;
            let server = server.unwrap_or(bundle.server);
            let transport = WebSocketTransport::new(&server).await?;
            let space =
                Space::join(transport, bundle.invite, sdk_codegen::application_schema()).await?;
            profile.initialize(space, server, port).await?;
            println!("Joined encrypted file space. Run serve to mount it in Nemo.");
        }
        Operation::Invite { output } => {
            let profile = Profile::lock(path)?;
            let config = Config::load(&profile.path).context("run init or join first")?;
            let session = restore_session(&profile, &config).await?;
            session.export_invite(&output, config.server).await?;
            print_invite(&output);
        }
        Operation::Serve => {
            let profile = Profile::lock(path)?;
            let config = Config::load(&profile.path).context("run init or join first")?;
            let session = restore_session(&profile, &config).await?;
            let listener = tokio::net::TcpListener::bind(("127.0.0.1", config.port)).await?;
            let interrupted = shutdown()?;
            println!("Encrypted Spaces bridge ready: {}", config.dav_uri());
            let (stop, stopped) = oneshot::channel();
            let filesystem = EncryptedFs::new(session);
            let router = server::router(filesystem.clone(), &config.password);
            let bridge = tokio::spawn(async move {
                axum::serve(listener, router)
                    .with_graceful_shutdown(async {
                        let _ = stopped.await;
                    })
                    .await
            });
            mounted_session(
                bridge,
                stop,
                &config,
                Path::new("gio"),
                terminal_session(&filesystem, &config.server),
                interrupted,
            )
            .await?;
            drop(profile);
        }
        Operation::Uri => println!("{}", Config::load(&path)?.dav_uri()),
    }
    Ok(())
}

async fn mount(config: &Config, gio: &Path) -> Result<()> {
    let mut child = Command::new(gio)
        .args(["mount", &config.dav_uri()])
        .stdin(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .context("gio is required; install libglib2.0-bin and gvfs-backends")?;
    let mut stdin = child.stdin.take().context("gio stdin unavailable")?;
    // Supply credentials through a pipe, never process arguments or the URI.
    stdin
        .write_all(format!("{}\n", config.password).as_bytes())
        .await?;
    drop(stdin);
    if !child.wait().await?.success() {
        bail!("GVfs mount failed; ensure GVfs is available in this desktop session");
    }
    if !Command::new(gio)
        .args(["info", &config.dav_uri()])
        .stdout(Stdio::null())
        .kill_on_drop(true)
        .status()
        .await?
        .success()
    {
        bail!("GVfs mounted but the filesystem is not accessible");
    }
    println!("Mounted in Nemo's sidebar: {}", config.dav_uri());
    println!("Type quit to unmount and stop the bridge (or press Ctrl+C).");
    println!("Type invite --output PATH to create an invitation without unmounting.");
    Ok(())
}

async fn unmount(config: &Config, gio: &Path) -> Result<()> {
    if !Command::new(gio)
        .args(["mount", "--unmount", &config.dav_uri()])
        .status()
        .await
        .context("cannot run gio to unmount the filesystem")?
        .success()
    {
        bail!("GVfs unmount failed");
    }
    Ok(())
}

fn bridge_result(result: Result<std::io::Result<()>, tokio::task::JoinError>) -> Result<()> {
    result
        .context("WebDAV bridge task failed")?
        .context("WebDAV bridge failed")
}

fn cleanup_result(result: Result<()>, cleanup: Result<()>) -> Result<()> {
    match (result, cleanup) {
        (Ok(()), cleanup) => cleanup,
        (Err(error), Ok(())) => Err(error),
        (Err(error), Err(cleanup)) => {
            Err(error.context(format!("cleanup also failed: {cleanup:#}")))
        }
    }
}

async fn mounted_session(
    mut bridge: JoinHandle<std::io::Result<()>>,
    stop: oneshot::Sender<()>,
    config: &Config,
    gio: &Path,
    commands: impl Future<Output = Result<()>>,
    interrupted: impl Future<Output = Result<()>>,
) -> Result<()> {
    let mut mount_attempted = false;
    let mut bridge_finished = false;
    let session = AssertUnwindSafe(async {
        tokio::select! {
            result = &mut bridge => {
                bridge_finished = true;
                bridge_result(result)?;
                bail!("WebDAV bridge stopped unexpectedly");
            }
            result = async {
                mount_attempted = true;
                mount(config, gio).await?;
                commands.await
            } => result,
            result = interrupted => result,
        }
    })
    .catch_unwind()
    .await;
    let mut result = match session {
        Ok(result) => result,
        Err(panic) => {
            let message = panic
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| panic.downcast_ref::<&str>().copied())
                .unwrap_or("unknown panic");
            Err(anyhow::anyhow!("mounted session panicked: {message}"))
        }
    };

    // Keep serving while GVfs disconnects, including after a partial mount failure.
    if mount_attempted {
        result = cleanup_result(result, unmount(config, gio).await);
    }
    let _ = stop.send(());
    if !bridge_finished {
        let stopped = match tokio::time::timeout(Duration::from_secs(5), &mut bridge).await {
            Ok(result) => bridge_result(result),
            Err(_) => {
                bridge.abort();
                let _ = bridge.await;
                Err(anyhow::anyhow!("WebDAV bridge shutdown timed out"))
            }
        };
        result = cleanup_result(result, stopped);
    }
    result
}

fn read_commands(input: impl BufRead, send: mpsc::Sender<Result<String>>) {
    for line in input.lines() {
        let line = line.context("cannot read terminal command");
        let done = line.as_ref().map_or(true, |line| line.trim() == "quit");
        if send.blocking_send(line).is_err() || done {
            break;
        }
    }
}

async fn terminal_session(filesystem: &EncryptedFs, server: &str) -> Result<()> {
    let (send, received) = mpsc::channel(16);
    // Tokio stdin can block runtime shutdown after a signal while awaiting a line.
    std::thread::Builder::new()
        .name("nemo-terminal-commands".into())
        .spawn(move || {
            read_commands(std::io::stdin().lock(), send);
        })
        .context("cannot start terminal command reader")?;
    run_commands(received, filesystem, server).await
}

fn parse_terminal_command(line: &str) -> Result<TerminalCommand> {
    let words = shlex::split(line).context("invalid command: unmatched quotes or invalid input")?;
    Ok(TerminalArgs::try_parse_from(words)?.command)
}

async fn run_commands(
    mut received: mpsc::Receiver<Result<String>>,
    filesystem: &EncryptedFs,
    server: &str,
) -> Result<()> {
    while let Some(line) = received.recv().await {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        match parse_terminal_command(&line) {
            Ok(TerminalCommand::Quit) => return Ok(()),
            Ok(TerminalCommand::Invite { output }) => {
                match filesystem.export_invite(&output, server.to_owned()).await {
                    Ok(()) => print_invite(&output),
                    Err(error) => eprintln!("Cannot create invitation: {error:#}"),
                }
            }
            Err(error) => eprintln!("{error}"),
        }
    }
    // A closed terminal must not leave the filesystem mounted.
    Ok(())
}

fn shutdown() -> Result<impl Future<Output = Result<()>>> {
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .context("cannot install SIGTERM handler")?;
    let mut interrupt = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::interrupt())
        .context("cannot install SIGINT handler")?;
    Ok(async move {
        tokio::select! {
            _ = interrupt.recv() => Ok(()),
            _ = terminate.recv() => Ok(()),
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::fs::PermissionsExt;
    use tempfile::TempDir;
    use tokio::io::AsyncReadExt;

    static GIO_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

    struct Gio {
        directory: TempDir,
        executable: PathBuf,
        config: Config,
        _process_lock: tokio::sync::MutexGuard<'static, ()>,
    }

    impl Gio {
        async fn new(mount_status: u8, info_status: u8, unmount_status: u8) -> Self {
            // Concurrent forks can inherit writable script descriptors and cause ETXTBSY.
            let process_lock = GIO_LOCK.lock().await;
            let directory = tempfile::tempdir().unwrap();
            let executable = directory.path().join("gio");
            std::fs::write(
                &executable,
                format!(
                    "#!/bin/sh\n\
                     printf '%s\\n' \"$*\" >> \"$(dirname \"$0\")/calls\"\n\
                     if [ \"$2\" = --unmount ]; then exit {unmount_status}; fi\n\
                     if [ \"$1\" = info ]; then exit {info_status}; fi\n\
                     read -r password\n\
                     [ \"$password\" = test-password ] || exit 99\n\
                     exit {mount_status}\n"
                ),
            )
            .unwrap();
            std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
            Self {
                directory,
                executable,
                config: Config {
                    server: "unused".into(),
                    port: 9843,
                    password: "test-password".into(),
                },
                _process_lock: process_lock,
            }
        }

        fn calls(&self) -> String {
            std::fs::read_to_string(self.directory.path().join("calls")).unwrap()
        }

        async fn bridge(&mut self) -> (JoinHandle<std::io::Result<()>>, oneshot::Sender<()>) {
            let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
                .await
                .unwrap();
            self.config.port = listener.local_addr().unwrap().port();
            let (stop, stopped) = oneshot::channel();
            let calls = self.directory.path().join("calls");
            let bridge = tokio::spawn(async move {
                let result = axum::serve(listener, axum::Router::new())
                    .with_graceful_shutdown(async {
                        let _ = stopped.await;
                    })
                    .await;
                let mut log = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(calls)?;
                writeln!(log, "bridge stopped")?;
                result
            });
            (bridge, stop)
        }
    }

    #[test]
    fn combined_command_arguments() {
        assert!(matches!(
            Args::try_parse_from(["nemo", "serve"]).unwrap().command,
            Operation::Serve
        ));
        assert!(Args::try_parse_from(["nemo", "mount"]).is_err());
        assert!(Args::try_parse_from(["nemo", "unmount"]).is_err());
    }

    #[test]
    fn quit_commands_and_closed_or_invalid_input() {
        let mut input = std::io::Cursor::new(b"help\n quit \nstill here\n");
        let (send, mut received) = mpsc::channel(16);
        read_commands(&mut input, send);
        assert_eq!(received.try_recv().unwrap().unwrap(), "help");
        assert_eq!(received.try_recv().unwrap().unwrap(), " quit ");
        assert_eq!(
            &input.get_ref()[input.position() as usize..],
            b"still here\n"
        );
        assert!(received.try_recv().is_err());
        let (send, mut received) = mpsc::channel(16);
        read_commands(&b""[..], send);
        assert!(received.try_recv().is_err());
        let (send, mut received) = mpsc::channel(16);
        read_commands(&b"\xff\n"[..], send);
        assert!(received.try_recv().unwrap().is_err());
    }

    #[test]
    fn terminal_command_arguments() {
        match parse_terminal_command("invite --output 'Bob invites/invite.json'").unwrap() {
            TerminalCommand::Invite { output } => {
                assert_eq!(output, PathBuf::from("Bob invites/invite.json"));
            }
            _ => panic!("expected invite"),
        }
        assert!(matches!(
            parse_terminal_command(" quit ").unwrap(),
            TerminalCommand::Quit
        ));
        for invalid in [
            "invite",
            "mount",
            "exit",
            "quit extra",
            "invite --output 'unclosed",
        ] {
            assert!(parse_terminal_command(invalid).is_err());
        }
    }

    #[tokio::test]
    async fn terminal_invites_share_the_live_filesystem_session() {
        use dav_server::{davpath::DavPath, fs::DavFileSystem};
        use encrypted_spaces_sdk::local_transport::LocalTransport;

        let directory = tempfile::tempdir().unwrap();
        let transport = LocalTransport::from_schema_file(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/app_schema.kdl"
        ))
        .await
        .unwrap();
        let space = Space::create(transport.clone(), sdk_codegen::application_schema())
            .await
            .unwrap();
        let snapshot_path = directory.path().join("snapshot.json");
        let filesystem = EncryptedFs::new(Session {
            space,
            snapshot_path: snapshot_path.clone(),
        });
        filesystem
            .create_dir(&DavPath::new("/before-invite").unwrap())
            .await
            .unwrap();
        let invite_path = directory.path().join("Bob invite.json");
        let second_path = directory.path().join("second.json");
        let (send, received) = mpsc::channel(16);
        for line in [
            "unknown".to_owned(),
            "invite".to_owned(),
            format!("invite --output '{}'", invite_path.display()),
            format!("invite --output '{}'", invite_path.display()),
            format!("invite --output '{}'", second_path.display()),
            "quit".to_owned(),
            format!(
                "invite --output '{}'",
                directory.path().join("after-quit").display()
            ),
        ] {
            send.send(Ok(line)).await.unwrap();
        }
        drop(send);
        run_commands(received, &filesystem, "ws://example.test/ws")
            .await
            .unwrap();
        assert!(second_path.exists());
        assert!(!directory.path().join("after-quit").exists());
        let bundle: InviteBundle =
            serde_json::from_slice(&std::fs::read(&invite_path).unwrap()).unwrap();
        assert_eq!(bundle.server, "ws://example.test/ws");
        assert_eq!(
            std::fs::metadata(&invite_path)
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        let joined = Space::join(
            transport.clone(),
            bundle.invite,
            sdk_codegen::application_schema(),
        )
        .await
        .unwrap();
        let joined_fs = EncryptedFs::new(Session {
            space: joined,
            snapshot_path: directory.path().join("joined.json"),
        });
        assert!(joined_fs
            .metadata(&DavPath::new("/before-invite").unwrap())
            .await
            .is_ok());
        filesystem
            .create_dir(&DavPath::new("/after-invite").unwrap())
            .await
            .unwrap();
        assert!(joined_fs
            .metadata(&DavPath::new("/after-invite").unwrap())
            .await
            .is_ok());
        let snapshot = serde_json::from_slice(&std::fs::read(snapshot_path).unwrap()).unwrap();
        Space::restore(transport, snapshot).await.unwrap();

        let (send, received) = mpsc::channel(1);
        drop(send);
        run_commands(received, &filesystem, "ws://example.test/ws")
            .await
            .unwrap();
        let (send, received) = mpsc::channel(1);
        send.send(Err(anyhow::anyhow!("terminal read failed")))
            .await
            .unwrap();
        drop(send);
        assert!(run_commands(received, &filesystem, "ws://example.test/ws")
            .await
            .unwrap_err()
            .to_string()
            .contains("terminal read failed"));
    }

    #[tokio::test]
    async fn quit_unmounts_before_stopping_the_live_bridge() {
        let mut gio = Gio::new(0, 0, 0).await;
        let (bridge, stop) = gio.bridge().await;
        let port = gio.config.port;
        mounted_session(
            bridge,
            stop,
            &gio.config,
            &gio.executable,
            async move {
                let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", port)).await?;
                stream
                    .write_all(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .await?;
                let mut response = String::new();
                stream.read_to_string(&mut response).await?;
                assert!(response.starts_with("HTTP/1.1 404"));
                Ok(())
            },
            std::future::pending(),
        )
        .await
        .unwrap();
        let uri = gio.config.dav_uri();
        assert_eq!(
            gio.calls(),
            format!("mount {uri}\ninfo {uri}\nmount --unmount {uri}\nbridge stopped\n")
        );
    }

    #[tokio::test]
    async fn mount_and_access_errors_still_unmount() {
        for (mount, info) in [(1, 0), (0, 1)] {
            let mut gio = Gio::new(mount, info, 0).await;
            let (bridge, stop) = gio.bridge().await;
            let error = mounted_session(
                bridge,
                stop,
                &gio.config,
                &gio.executable,
                std::future::pending(),
                std::future::pending(),
            )
            .await
            .unwrap_err();
            assert!(
                error.to_string().contains(if mount != 0 {
                    "GVfs mount failed"
                } else {
                    "not accessible"
                }),
                "{error:#}"
            );
            assert!(gio.calls().ends_with(&format!(
                "mount --unmount {}\nbridge stopped\n",
                gio.config.dav_uri()
            )));
        }
    }

    #[tokio::test]
    async fn panic_and_bridge_failure_still_unmount() {
        for panic in [false, true] {
            let mut gio = Gio::new(0, 0, 0).await;
            let (bridge, stop) = gio.bridge().await;
            let abort = bridge.abort_handle();
            let error = mounted_session(
                bridge,
                stop,
                &gio.config,
                &gio.executable,
                async move {
                    if panic {
                        panic!("fatal session error");
                    }
                    abort.abort();
                    std::future::pending().await
                },
                std::future::pending(),
            )
            .await
            .unwrap_err();
            assert!(
                error.to_string().contains(if panic {
                    "fatal session error"
                } else {
                    "bridge task failed"
                }),
                "{error:#}"
            );
            assert!(gio
                .calls()
                .contains(&format!("mount --unmount {}\n", gio.config.dav_uri())));
        }
    }

    #[tokio::test]
    async fn interruption_and_input_failure_still_unmount() {
        for interrupted in [false, true] {
            let mut gio = Gio::new(0, 0, 0).await;
            let (bridge, stop) = gio.bridge().await;
            let (mounted, ready) = oneshot::channel();
            let result = mounted_session(
                bridge,
                stop,
                &gio.config,
                &gio.executable,
                async move {
                    if interrupted {
                        mounted.send(()).unwrap();
                        std::future::pending().await
                    } else {
                        Err(anyhow::anyhow!("cannot read terminal command"))
                    }
                },
                async move {
                    ready.await?;
                    Ok(())
                },
            )
            .await;
            if interrupted {
                result.unwrap();
            } else {
                assert!(result
                    .unwrap_err()
                    .to_string()
                    .contains("cannot read terminal"));
            }
            assert!(gio.calls().ends_with(&format!(
                "mount --unmount {}\nbridge stopped\n",
                gio.config.dav_uri()
            )));
        }
    }

    #[tokio::test]
    async fn unmount_failure_is_reported_on_normal_exit() {
        let mut gio = Gio::new(0, 0, 1).await;
        let (bridge, stop) = gio.bridge().await;
        let error = mounted_session(
            bridge,
            stop,
            &gio.config,
            &gio.executable,
            async { Ok(()) },
            std::future::pending(),
        )
        .await
        .unwrap_err();
        assert!(
            error.to_string().contains("GVfs unmount failed"),
            "{error:#}"
        );
        assert!(gio.calls().ends_with("bridge stopped\n"));
    }

    #[tokio::test]
    async fn cleanup_errors_preserve_the_original_failure() {
        let mut gio = Gio::new(0, 1, 1).await;
        let (bridge, stop) = gio.bridge().await;
        let error = mounted_session(
            bridge,
            stop,
            &gio.config,
            &gio.executable,
            std::future::pending(),
            std::future::pending(),
        )
        .await
        .unwrap_err();
        let message = format!("{error:#}");
        assert!(message.contains("GVfs unmount failed"));
        assert!(message.contains("not accessible"));
        assert!(gio.calls().ends_with("bridge stopped\n"));
    }

    #[test]
    fn invite_and_join_arguments() {
        let args = Args::try_parse_from([
            "nemo",
            "--profile",
            "/tmp/bob",
            "join",
            "--invite",
            "bob.json",
            "--server",
            "wss://example.test/ws",
            "--port",
            "9844",
        ])
        .unwrap();
        assert_eq!(args.profile, Some(PathBuf::from("/tmp/bob")));
        match args.command {
            Operation::Join {
                invite,
                server,
                port,
            } => {
                assert_eq!(invite, PathBuf::from("bob.json"));
                assert_eq!(server.as_deref(), Some("wss://example.test/ws"));
                assert_eq!(port, 9844);
            }
            _ => panic!("expected join"),
        }
        assert!(Args::try_parse_from(["nemo", "invite", "--output", "bob.json"]).is_ok());
        assert!(Args::try_parse_from(["nemo", "join", "--invite", "bob.json"]).is_ok());
        assert!(Args::try_parse_from(["nemo", "join"]).is_err());
        assert!(Args::try_parse_from(["nemo", "invite"]).is_err());
        assert!(
            Args::try_parse_from(["nemo", "join", "--invite", "bob.json", "--port", "0",]).is_err()
        );
    }
}
