use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use encrypted_spaces_sdk::{File, QueryParam, Space, SpaceInvite, WebSocketTransport};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[cfg(target_os = "android")]
mod jni_bridge;

#[cfg(any(target_os = "android", test))]
mod diagnostics;

include!(concat!(env!("OUT_DIR"), "/sdk_codegen.rs"));

pub const MAX_READ_BYTES: i64 = 32 * 1024 * 1024;

#[cfg(any(target_os = "android", test))]
fn is_transport_failure(error: &anyhow::Error) -> bool {
    error.chain().any(|cause| {
        matches!(
            cause.downcast_ref::<encrypted_spaces_sdk::SdkErrorType>(),
            Some(encrypted_spaces_sdk::SdkErrorType::TransportError(_))
        ) || cause.is::<tokio::time::error::Elapsed>()
    })
}

/// App-private reconnect state, never accepted by the invitation importer.
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
    pub mtime: i64,
    pub mime_type: String,
    pub file_hash: File,
}

pub struct Client {
    space: Space,
    connection: Connection,
}

#[derive(Deserialize, Serialize)]
struct UserMetadata {
    id: i64,
    name: String,
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

pub fn parse_invitation(invitation: Value) -> Result<SpaceInvite> {
    if invitation.get("user_info").is_some() || invitation.get("space_snapshot").is_some() {
        bail!("Desktop snapshots cannot be imported. Select an invitation file instead");
    }
    serde_json::from_value(invitation).context("Invalid invitation file")
}

fn tls_connector(certificates: &[String]) -> Result<tokio_native_tls::TlsConnector> {
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
    Ok(tokio_native_tls::TlsConnector::from(tls.build()?))
}

impl Client {
    pub async fn join(
        invitation: Value,
        address: &str,
        certificates: &[String],
        allow_cleartext: bool,
    ) -> Result<Self> {
        validate_address(address, allow_cleartext)?;
        #[cfg(target_os = "android")]
        diagnostics::log_connection("join", address)?;
        let invite = parse_invitation(invitation)?;
        let connector = tls_connector(certificates)?;
        let transport =
            WebSocketTransport::new_with_trust_connector(address, Some(connector)).await?;
        let space = Space::join(transport, invite, sdk_codegen::application_schema())
            .await
            .context("Cannot join space; check the invitation, endpoint, and deployment schema")?;
        let connection = Connection {
            user_info: serde_json::json!({
                "user_id": space.uid(),
                "ws_address": address,
            }),
            space_snapshot: space.snapshot().await?,
        };
        Ok(Self { space, connection })
    }

    pub async fn restore(
        mut connection: Connection,
        address: &str,
        certificates: &[String],
        allow_cleartext: bool,
    ) -> Result<Self> {
        validate_address(address, allow_cleartext)?;
        #[cfg(target_os = "android")]
        diagnostics::log_connection("restore", address)?;
        if !connection.user_info.is_object() {
            bail!("Invalid saved connection: user_info must be an object");
        }
        if connection.space_snapshot.get("space_id").is_none() {
            bail!("Invalid saved connection: missing space_id");
        }
        let connector = tls_connector(certificates)?;
        let transport =
            WebSocketTransport::new_with_trust_connector(address, Some(connector)).await?;
        let space = Space::restore(transport, connection.space_snapshot.clone())
            .await
            .context("Cannot restore saved connection; check endpoint and membership")?;
        if space.get_table_schema("inodes").is_none() {
            bail!("This space has no inodes table; use the file-enabled demo deployment");
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

    pub async fn set_user_name(&mut self, user_name: &str) -> Result<()> {
        let name = user_name.trim();
        if name.is_empty() {
            bail!("Enter a user name");
        }
        let id = i64::from(self.space.uid().context("Missing connected user id")?);
        self.space
            .sync()
            .await
            .context("Cannot sync space before setting user name")?;
        let existing: Option<UserMetadata> = self
            .space
            .table("users_meta")
            .select()
            .where_eq("id", id)
            .first()
            .await
            .context("Cannot read member display name")?;
        if let Some(existing) = existing {
            if existing.name != name {
                let updated = self
                    .space
                    .table::<UserMetadata>("users_meta")
                    .update()
                    .set("name", name)
                    .where_eq("id", id)
                    .execute()
                    .await
                    .context("Cannot update member display name")?;
                if updated != 1 {
                    bail!("Member display name was not updated");
                }
            }
        } else {
            self.space
                .table("users_meta")
                .insert(&UserMetadata {
                    id,
                    name: name.into(),
                })
                .execute()
                .await
                .context("Cannot create member display name")?;
        }
        self.connection.user_info["user_name"] = name.into();
        Ok(())
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

    pub async fn stat(&self, id: i64) -> Result<Inode> {
        if id <= 0 {
            bail!("Invalid document id");
        }
        self.space.sync().await?;
        self.inode(id).await
    }

    pub async fn create(&self, parent_id: i64, name: &str, mime_type: &str) -> Result<Inode> {
        Self::validate_name(name)?;
        self.list(parent_id).await?;
        let folder = mime_type == "vnd.android.document/directory";
        let file = if folder {
            File::from_hash("0".repeat(64))
        } else {
            self.space.file().upload(File::from_data(vec![])).await?
        };
        let now = Self::timestamp()?;
        let fields = vec![
            ("parent_id".into(), QueryParam::Integer(parent_id)),
            (
                "author_id".into(),
                QueryParam::Integer(i64::from(
                    self.space.uid().context("Missing connected user id")?,
                )),
            ),
            ("name".into(), QueryParam::Text(name.into())),
            (
                "type".into(),
                QueryParam::Integer(if folder { 2 } else { 1 }),
            ),
            ("size".into(), QueryParam::Integer(0)),
            ("ctime".into(), QueryParam::Integer(now)),
            ("mtime".into(), QueryParam::Integer(now)),
            ("mime_type".into(), QueryParam::Text(mime_type.into())),
            ("file_hash".into(), QueryParam::Text(file.hash()?.into())),
        ];
        let id = self.space.call_insert_action("add_inode", fields).await?;
        self.inode(id).await
    }

    pub async fn write(&self, id: i64, bytes: Vec<u8>, expected_hash: &str) -> Result<Inode> {
        if bytes.len() as i64 > MAX_READ_BYTES {
            bail!("Files are limited to 32 MiB");
        }
        let inode = self.stat(id).await?;
        validate_read(&inode)?;
        if inode.file_hash.hash()? != expected_hash {
            bail!("File changed since it was opened; reopen it before saving");
        }
        let size = bytes.len() as i64;
        let file = self.space.file().upload(File::from_data(bytes)).await?;
        let updated = self
            .space
            .call_update_action(
                "write_inode",
                id,
                vec![
                    ("file_hash".into(), QueryParam::Text(file.hash()?.into())),
                    ("size".into(), QueryParam::Integer(size)),
                    ("mtime".into(), QueryParam::Integer(Self::timestamp()?)),
                ],
            )
            .await
            .context("Cannot save file; the server must support the write_inode schema action")?;
        if updated != 1 {
            bail!("File was not saved");
        }
        self.inode(id).await
    }

    pub async fn rename(&self, id: i64, name: &str) -> Result<Inode> {
        Self::validate_name(name)?;
        self.stat(id).await?;
        let updated = self
            .space
            .call_update_action(
                "rename_inode",
                id,
                vec![
                    ("name".into(), QueryParam::Text(name.into())),
                    ("mtime".into(), QueryParam::Integer(Self::timestamp()?)),
                ],
            )
            .await?;
        if updated != 1 {
            bail!("Document was not renamed");
        }
        self.inode(id).await
    }

    pub async fn delete(&self, id: i64) -> Result<()> {
        self.stat(id).await?;
        // Walk before deleting anything: malformed/cyclic trees must not recurse forever.
        let mut pending = vec![id];
        let mut ordered = Vec::new();
        let mut seen = std::collections::HashSet::new();
        while let Some(current) = pending.pop() {
            if !seen.insert(current) {
                bail!("Cycle in document tree");
            }
            let children: Vec<Inode> = self
                .space
                .table("inodes")
                .select()
                .where_eq("parent_id", current)
                .all()
                .await?;
            pending.extend(children.into_iter().map(|child| child.id));
            ordered.push(current);
        }
        for current in ordered.into_iter().rev() {
            let deleted = self
                .space
                .table::<Inode>("inodes")
                .delete()
                .where_eq("id", current)
                .execute()
                .await?;
            if deleted != 1 {
                bail!("Document {current} was not deleted; a folder deletion may be partial");
            }
        }
        Ok(())
    }

    pub async fn read(&self, id: i64) -> Result<Vec<u8>> {
        Ok(self.read_version(id).await?.1)
    }

    pub async fn read_version(&self, id: i64) -> Result<(Inode, Vec<u8>)> {
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
        Ok((inode, bytes))
    }

    fn timestamp() -> Result<i64> {
        Ok(std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_secs()
            .try_into()?)
    }

    fn validate_name(name: &str) -> Result<()> {
        if name.trim().is_empty() || matches!(name, "." | "..") || name.contains(['/', '\0']) {
            bail!("Enter a non-empty document name without slashes or NUL characters");
        }
        Ok(())
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

    #[tokio::test]
    async fn recovery_classifies_transport_and_timeout_without_parsing_messages() {
        use encrypted_spaces_sdk::SdkErrorType;
        let transport = anyhow::Error::new(SdkErrorType::TransportError("closed".into()))
            .context("Read failed");
        assert!(is_transport_failure(&transport));
        let timeout = tokio::time::timeout(std::time::Duration::ZERO, std::future::pending::<()>())
            .await
            .unwrap_err();
        assert!(is_transport_failure(
            &anyhow::Error::new(timeout).context("Read failed")
        ));
        for error in [
            SdkErrorType::AccessDenied("denied".into()),
            SdkErrorType::ValidationError("connection closed".into()),
            SdkErrorType::DatabaseError("remote error".into()),
            SdkErrorType::RootHashError("invalid proof".into()),
            SdkErrorType::DecryptionError("invalid ciphertext".into()),
        ] {
            assert!(!is_transport_failure(
                &anyhow::Error::new(error).context("Read failed")
            ));
        }
    }

    #[tokio::test]
    async fn cloud_documents_crud_and_stale_write_rejection() {
        let transport = encrypted_spaces_sdk::local_transport::LocalTransport::from_schema_file(
            concat!(env!("CARGO_MANIFEST_DIR"), "/../../tauri/app_schema.kdl"),
        )
        .await
        .unwrap();
        let space = Space::create(transport, sdk_codegen::application_schema())
            .await
            .unwrap();
        let client = Client {
            connection: Connection {
                user_info: serde_json::json!({"user_id": space.uid().unwrap()}),
                space_snapshot: space.snapshot().await.unwrap(),
            },
            space,
        };
        let folder = client
            .create(0, "Documents", "vnd.android.document/directory")
            .await
            .unwrap();
        let file = client
            .create(folder.id, "draft.txt", "text/plain")
            .await
            .unwrap();
        assert!(client.read(file.id).await.unwrap().is_empty());
        let original_hash = file.file_hash.hash().unwrap().to_string();
        let written = client
            .write(file.id, b"encrypted cloud content".to_vec(), &original_hash)
            .await
            .unwrap();
        assert_eq!(written.size, 23);
        assert_eq!(
            client.read(file.id).await.unwrap(),
            b"encrypted cloud content"
        );
        assert!(!serde_json::to_string(&client.snapshot().await.unwrap())
            .unwrap()
            .contains("encrypted cloud content"));
        assert!(client
            .write(
                file.id,
                vec![0; MAX_READ_BYTES as usize + 1],
                written.file_hash.hash().unwrap()
            )
            .await
            .is_err());
        assert!(client
            .write(file.id, b"stale edit".to_vec(), &original_hash)
            .await
            .is_err());
        assert_eq!(
            client.read(file.id).await.unwrap(),
            b"encrypted cloud content"
        );
        let renamed = client.rename(file.id, "final.txt").await.unwrap();
        assert_eq!(renamed.id, file.id);
        assert_eq!(renamed.name, "final.txt");
        assert!(client.create(file.id, "child", "text/plain").await.is_err());
        assert!(client.create(-1, "child", "text/plain").await.is_err());
        assert!(client
            .write(folder.id, vec![], &"0".repeat(64))
            .await
            .is_err());
        assert!(client.read(folder.id).await.is_err());
        for invalid in ["", " ", ".", "..", "a/b", "a\0b"] {
            assert!(client.create(0, invalid, "text/plain").await.is_err());
            assert!(client.rename(file.id, invalid).await.is_err());
        }
        assert!(client.delete(0).await.is_err());
        let cleared = client
            .write(file.id, vec![], written.file_hash.hash().unwrap())
            .await
            .unwrap();
        assert_eq!(cleared.size, 0);
        assert!(client.read(file.id).await.unwrap().is_empty());
        let entries = client.list(folder.id).await.unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "final.txt");
        let nested = client
            .create(folder.id, "Nested", "vnd.android.document/directory")
            .await
            .unwrap();
        let descendant = client
            .create(nested.id, "nested.txt", "text/plain")
            .await
            .unwrap();
        client.delete(folder.id).await.unwrap();
        assert!(client.list(0).await.unwrap().is_empty());
        assert!(client.stat(file.id).await.is_err());
        assert!(client.stat(folder.id).await.is_err());
        assert!(client.stat(descendant.id).await.is_err());
    }

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

    #[test]
    fn invitation_import_rejects_snapshots_and_malformed_files() {
        let snapshot = serde_json::json!({
            "user_info": {"ws_address": "wss://example.org/ws"},
            "space_snapshot": {"space_id": "example"}
        });
        let error = parse_invitation(snapshot).err().unwrap().to_string();
        assert!(error.contains("Desktop snapshots cannot be imported"));
        for invalid in [
            Value::Null,
            serde_json::json!({}),
            serde_json::json!({"user": {}}),
        ] {
            assert!(parse_invitation(invalid).is_err());
        }
    }

    #[tokio::test]
    async fn invitation_import_accepts_sdk_invitations_and_rejects_snapshot_envelopes() {
        let transport = encrypted_spaces_sdk::local_transport::LocalTransport::from_schema_file(
            concat!(env!("CARGO_MANIFEST_DIR"), "/../../tauri/app_schema.kdl"),
        )
        .await
        .unwrap();
        let space = Space::create(transport, sdk_codegen::application_schema())
            .await
            .unwrap();
        let invite = serde_json::to_value(space.invite_user().await.unwrap()).unwrap();
        let parsed = parse_invitation(invite.clone()).unwrap();
        assert_eq!(serde_json::to_value(parsed).unwrap(), invite);
        let mut disguised_snapshot = invite;
        disguised_snapshot["space_snapshot"] = serde_json::json!({});
        assert!(parse_invitation(disguised_snapshot).is_err());
    }

    #[tokio::test]
    async fn user_name_is_published_for_the_connected_member_and_saved() {
        let transport = encrypted_spaces_sdk::local_transport::LocalTransport::from_schema_file(
            concat!(env!("CARGO_MANIFEST_DIR"), "/../../tauri/app_schema.kdl"),
        )
        .await
        .unwrap();
        let space = Space::create(transport, sdk_codegen::application_schema())
            .await
            .unwrap();
        let id = i64::from(space.uid().unwrap());
        let mut client = Client {
            connection: Connection {
                user_info: serde_json::json!({"user_id": id}),
                space_snapshot: space.snapshot().await.unwrap(),
            },
            space,
        };
        assert!(client.set_user_name(" \n ").await.is_err());
        for name in ["  My phone  ", "My phone", "New name"] {
            client.set_user_name(name).await.unwrap();
            let rows: Vec<UserMetadata> = client
                .space
                .table("users_meta")
                .select()
                .all()
                .await
                .unwrap();
            assert_eq!(rows.len(), 1);
            assert_eq!(rows[0].id, id);
            assert_eq!(rows[0].name, name.trim());
            let snapshot = client.snapshot().await.unwrap();
            assert_eq!(snapshot.user_info["user_name"], name.trim());
            assert_eq!(snapshot.user_info["user_id"], id);
        }
        assert!(client.set_user_name("").await.is_err());
        assert_eq!(
            client.snapshot().await.unwrap().user_info["user_name"],
            "New name"
        );
    }
}
