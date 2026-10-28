use anyhow::{bail, Context, Result};
use encrypted_spaces_sdk::{SdkErrorType, Space, SpaceInvite};
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

#[derive(Clone, Serialize, Deserialize)]
pub struct Config {
    pub server: String,
    pub port: u16,
    pub password: String,
}

#[derive(Serialize, Deserialize)]
pub struct InviteBundle {
    pub server: String,
    pub invite: SpaceInvite,
}

impl Config {
    pub fn dav_uri(&self) -> String {
        format!("dav://encrypted-spaces@127.0.0.1:{}/", self.port)
    }

    pub fn load(profile: &Path) -> Result<Self> {
        serde_json::from_slice(&fs::read(profile.join("connection.json"))?)
            .context("invalid connection profile")
    }
}

pub struct Profile {
    pub path: PathBuf,
    _lock: File,
}

impl Profile {
    pub fn ensure_empty(&self) -> Result<()> {
        if self.path.join("snapshot.json").exists() || self.path.join("connection.json").exists() {
            bail!("profile already exists; use serve or choose another --profile");
        }
        Ok(())
    }

    pub async fn initialize(&self, space: Space, server: String, port: u16) -> Result<()> {
        self.ensure_empty()?;
        Session {
            space,
            snapshot_path: self.path.join("snapshot.json"),
        }
        .save()
        .await?;
        write_private_json(
            &self.path.join("connection.json"),
            &Config {
                server,
                port,
                password: uuid::Uuid::new_v4().to_string(),
            },
        )
    }

    pub fn lock(path: PathBuf) -> Result<Self> {
        fs::create_dir_all(&path)?;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700))?;
        let lock = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .mode(0o600)
            .open(path.join("profile.lock"))?;
        lock.try_lock_exclusive()
            .context("profile is already in use; unmount and stop its bridge first")?;
        Ok(Self { path, _lock: lock })
    }
}

pub struct Session {
    pub space: Space,
    pub snapshot_path: PathBuf,
}

impl Session {
    pub async fn sync(&self) -> Result<()> {
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            match self.space.sync().await {
                Ok(()) => return Ok(()),
                Err(error @ SdkErrorType::FastForwardRequired { .. })
                    if tokio::time::Instant::now() < deadline =>
                {
                    // Broadcast-driven recovery can overlap a foreground sync.
                    eprintln!("encrypted-spaces: retrying sync: {error}");
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                }
                Err(error) => return Err(error.into()),
            }
        }
    }

    pub async fn export_invite(&self, path: &Path, server: String) -> Result<()> {
        // Reserve a new private file before changing membership; never overwrite keys or invites.
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(path)
            .context("cannot create invite file (it must not already exist)")?;
        self.sync().await?;
        let invite = self.space.invite_user().await?;
        self.save().await?;
        file.write_all(&serde_json::to_vec_pretty(&InviteBundle {
            server,
            invite,
        })?)?;
        file.sync_all()?;
        Ok(())
    }

    pub async fn save(&self) -> Result<()> {
        write_private_json(&self.snapshot_path, &self.space.snapshot().await?)
    }
}

pub fn write_private_json(path: &Path, value: &impl Serialize) -> Result<()> {
    let temporary = path.with_extension("tmp");
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .mode(0o600)
        .open(&temporary)?;
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.write_all(&serde_json::to_vec(value)?)?;
    file.sync_all()?;
    fs::rename(&temporary, path)?;
    File::open(path.parent().context("profile path has no parent")?)?.sync_all()?;
    Ok(())
}
