use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use encrypted_spaces_sdk::{File, Space, WebSocketTransport};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[cfg(target_os = "android")]
mod jni_bridge;

pub const MAX_READ_BYTES: i64 = 32 * 1024 * 1024;

/// Same envelope as the desktop demo; the SDK snapshot remains opaque.
#[derive(Deserialize, Serialize)]
pub struct Connection {
    pub user_info: Value,
    pub space_snapshot: Value,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Inode {
    pub id: i64,
    pub parent_id: i64,
    pub name: String,
    #[serde(rename = "type")]
    pub inode_type: i64,
    pub size: i64,
    pub mime_type: String,
    pub file_hash: File,
}

pub struct Client {
    space: Space,
    connection: Connection,
}

pub fn validate_address(address: &str, allow_cleartext: bool) -> Result<()> {
    let url = url::Url::parse(address).context("Invalid WebSocket address")?;
    if url.scheme() != "wss" && !(allow_cleartext && url.scheme() == "ws") {
        bail!("Use wss:// for remote storage; ws:// is allowed only in debug builds");
    }
    if url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || url.query().is_some()
    {
        bail!("Use a WebSocket endpoint without credentials, query parameters, or fragments");
    }
    Ok(())
}

impl Client {
    pub async fn connect(
        mut connection: Connection,
        address: &str,
        certificates: &[String],
        allow_cleartext: bool,
    ) -> Result<Self> {
        validate_address(address, allow_cleartext)?;
        if !connection.user_info.is_object() {
            bail!("Invalid desktop snapshot: user_info must be an object");
        }
        if connection.space_snapshot.get("space_id").is_none() {
            bail!("Invalid desktop snapshot: missing space_id");
        }
        let mut tls = native_tls::TlsConnector::builder();
        #[cfg(target_os = "android")]
        {
            // Vendored OpenSSL cannot discover Android's system CA store.
            // Kotlin supplies AndroidCAStore roots for both WS and HTTPS.
            if certificates.is_empty() {
                bail!("Android certificate trust store is empty");
            }
            tls.disable_built_in_roots(true);
        }
        for encoded in certificates {
            let der = STANDARD
                .decode(encoded)
                .context("Invalid certificate encoding")?;
            tls.add_root_certificate(
                native_tls::Certificate::from_der(&der).context("Invalid DER certificate")?,
            );
        }
        let connector = tokio_native_tls::TlsConnector::from(tls.build()?);
        let transport =
            WebSocketTransport::new_with_trust_connector(address, Some(connector)).await?;
        let space = Space::restore(transport, connection.space_snapshot.clone())
            .await
            .context("Cannot restore space; check endpoint, membership, and snapshot")?;
        if space.get_table_schema("inodes").is_none() {
            bail!(
                "This space has no inodes table; use a snapshot from the file-enabled desktop demo"
            );
        }
        connection.user_info["ws_address"] = address.into();
        Ok(Self { space, connection })
    }

    pub async fn list(&self, parent_id: i64) -> Result<Vec<Inode>> {
        if parent_id < 0 {
            bail!("Invalid folder id");
        }
        self.space
            .sync()
            .await
            .context("Cannot sync remote space")?;
        if parent_id != 0 {
            let parent = self.inode(parent_id).await?;
            if parent.inode_type != 2 {
                bail!("Selected entry is not a folder");
            }
        }
        let mut rows: Vec<Inode> = self
            .space
            .table("inodes")
            .select()
            .where_eq("parent_id", parent_id)
            .all()
            .await?;
        rows.sort_by(|a, b| {
            b.inode_type
                .cmp(&a.inode_type)
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
                .then_with(|| a.id.cmp(&b.id))
        });
        Ok(rows)
    }

    async fn inode(&self, id: i64) -> Result<Inode> {
        self.space
            .table("inodes")
            .select()
            .where_eq("id", id)
            .first()
            .await?
            .ok_or_else(|| anyhow::anyhow!("File or folder {id} no longer exists"))
    }

    pub async fn read(&self, id: i64) -> Result<Vec<u8>> {
        self.space.sync().await?;
        let inode = self.inode(id).await?;
        validate_read(&inode)?;
        let bytes = self
            .space
            .file()
            .download(&inode.file_hash)
            .await
            .context("Cannot download or decrypt file")?
            .into_data()?;
        if bytes.len() as i64 != inode.size {
            bail!("Downloaded file size does not match its verified metadata");
        }
        Ok(bytes)
    }

    pub async fn snapshot(&self) -> Result<Connection> {
        Ok(Connection {
            user_info: self.connection.user_info.clone(),
            space_snapshot: self.space.snapshot().await?,
        })
    }
}

fn validate_read(inode: &Inode) -> Result<()> {
    if inode.inode_type != 1 {
        bail!("Selected entry is not a file");
    }
    if inode.size < 0 || inode.size > MAX_READ_BYTES {
        bail!("This initial demo supports files up to 32 MiB");
    }
    let hash = inode.file_hash.hash()?;
    if hash.len() != 64 || !hash.bytes().all(|c| c.is_ascii_hexdigit()) {
        bail!("Invalid file content hash");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_validation() {
        assert!(validate_address("wss://example.org:8443/ws", false).is_ok());
        assert!(validate_address("ws://10.0.2.2:8080/ws", true).is_ok());
        for address in [
            "ws://example.org/ws",
            "https://example.org/ws",
            "wss://user:password@example.org/ws",
            "wss://example.org/ws?auth=secret",
            "wss://example.org/ws#fragment",
            "not a url",
        ] {
            assert!(validate_address(address, false).is_err(), "{address}");
        }
    }

    #[test]
    fn metadata_matches_desktop_file_schema() {
        let inode: Inode = serde_json::from_value(serde_json::json!({
            "id": 4, "parent_id": 0, "author_id": 1, "name": "../hello.txt",
            "type": 1, "size": 5, "ctime": 1, "mtime": 1,
            "mime_type": "text/plain", "file_hash": "ab".repeat(32)
        }))
        .unwrap();
        assert_eq!(inode.file_hash.hash().unwrap(), "ab".repeat(32));
        assert!(validate_read(&inode).is_ok());
        let mut invalid = inode;
        invalid.size = MAX_READ_BYTES + 1;
        assert!(validate_read(&invalid).is_err());
        invalid.size = -1;
        assert!(validate_read(&invalid).is_err());
        invalid.size = 5;
        invalid.inode_type = 2;
        assert!(validate_read(&invalid).is_err());
        invalid.inode_type = 1;
        invalid.file_hash = File::from_hash("invalid".into());
        assert!(validate_read(&invalid).is_err());
    }

    #[test]
    fn snapshot_envelope_preserves_opaque_sdk_state() {
        let input = serde_json::json!({
            "user_info": {"ws_address": "wss://example.org/ws", "user_name": "demo"},
            "space_snapshot": {"future_sdk_field": [1, 2, 3]}
        });
        let connection: Connection = serde_json::from_value(input.clone()).unwrap();
        assert_eq!(serde_json::to_value(connection).unwrap(), input);
    }
}
