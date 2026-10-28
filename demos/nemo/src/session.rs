use anyhow::{Context, Result};
use encrypted_spaces_sdk::Space;
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
            .context("profile is already in use by another bridge")?;
        Ok(Self { path, _lock: lock })
    }
}

pub struct Session {
    pub space: Space,
    pub snapshot_path: PathBuf,
}

impl Session {
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
