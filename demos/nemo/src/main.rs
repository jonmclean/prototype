use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use encrypted_spaces_nemo::{
    filesystem::EncryptedFs,
    sdk_codegen, server,
    session::{Config, InviteBundle, Profile, Session},
};
use encrypted_spaces_sdk::{Space, WebSocketTransport};
use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};

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
    /// Run the loopback-only WebDAV bridge. Keep running while mounted.
    Serve,
    /// Mount with GVfs; appears in Nemo's sidebar. Requires a desktop session.
    Mount,
    /// Unmount the GVfs filesystem.
    Unmount,
    /// Print the GVfs URI (no password).
    Uri,
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
            println!("Created encrypted file space. Run serve, then mount in another terminal.");
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
            println!("Joined encrypted file space. Run serve, then mount in another terminal.");
        }
        Operation::Invite { output } => {
            let profile = Profile::lock(path)?;
            let config = Config::load(&profile.path).context("run init or join first")?;
            let session = restore_session(&profile, &config).await?;
            session.export_invite(&output, config.server).await?;
            println!(
                "Invite saved to {}. Share it securely with one new user; it contains secret keys.",
                output.display()
            );
        }
        Operation::Serve => {
            let profile = Profile::lock(path)?;
            let config = Config::load(&profile.path).context("run init or join first")?;
            let session = restore_session(&profile, &config).await?;
            let listener = tokio::net::TcpListener::bind(("127.0.0.1", config.port)).await?;
            println!("Encrypted Spaces bridge ready: {}", config.dav_uri());
            axum::serve(
                listener,
                server::router(EncryptedFs::new(session), &config.password),
            )
            .with_graceful_shutdown(shutdown())
            .await?;
            drop(profile);
        }
        Operation::Mount => {
            let config = Config::load(&path).context("run init or join first")?;
            let mut child = Command::new("gio")
                .args(["mount", &config.dav_uri()])
                .stdin(Stdio::piped())
                .spawn()
                .context("gio is required; install libglib2.0-bin and gvfs-backends")?;
            let mut stdin = child.stdin.take().context("gio stdin unavailable")?;
            // Supply credentials through a pipe, never process arguments or the URI.
            stdin.write_all(format!("{}\n", config.password).as_bytes())?;
            drop(stdin);
            if !child.wait()?.success() {
                bail!("GVfs mount failed; ensure serve is running in this desktop session");
            }
            if !Command::new("gio")
                .args(["info", &config.dav_uri()])
                .stdout(Stdio::null())
                .status()?
                .success()
            {
                bail!("GVfs mounted but the filesystem is not accessible");
            }
            println!("Mounted in Nemo's sidebar: {}", config.dav_uri());
        }
        Operation::Unmount => {
            let config = Config::load(&path)?;
            if !Command::new("gio")
                .args(["mount", "--unmount", &config.dav_uri()])
                .status()?
                .success()
            {
                bail!("GVfs unmount failed");
            }
        }
        Operation::Uri => println!("{}", Config::load(&path)?.dav_uri()),
    }
    Ok(())
}

async fn shutdown() {
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("install SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {},
        _ = terminate.recv() => {},
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
