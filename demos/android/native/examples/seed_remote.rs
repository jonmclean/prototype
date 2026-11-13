//! Host-only fixture for checking the phone client against a real test server.
//! Never builds into the APK. Creates an isolated test space, not production data.
use anyhow::{Context, Result};
use encrypted_spaces_ffproof::EXTEND_FF_ID;
use encrypted_spaces_sdk::{
    local_transport::LocalTransport, ApplicationSchema, File, QueryParam, Space, WebSocketTransport,
};
use spaces_android::Client;
use std::time::Duration;

const CONTENT: &[u8] = b"Android in-memory read: hello encrypted spaces!\n";
const SCHEMA: &[u8] = include_bytes!("../../../tauri/app_schema.kdl");

async fn add_inode(space: &Space, parent: i64, name: &str, kind: i64, file: File) -> Result<i64> {
    let fields = vec![
        ("parent_id".into(), QueryParam::Integer(parent)),
        (
            "author_id".into(),
            QueryParam::Integer(i64::from(space.uid().context("Missing uid")?)),
        ),
        ("name".into(), QueryParam::Text(name.into())),
        ("type".into(), QueryParam::Integer(kind)),
        (
            "size".into(),
            QueryParam::Integer(if kind == 1 { CONTENT.len() as i64 } else { 0 }),
        ),
        ("ctime".into(), QueryParam::Integer(1)),
        ("mtime".into(), QueryParam::Integer(1)),
        ("mime_type".into(), QueryParam::Text("text/plain".into())),
        ("file_hash".into(), QueryParam::Text(file.hash()?.into())),
    ];
    Ok(space.call_insert_action("add_inode", fields).await?)
}

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    anyhow::ensure!(
        args.len() == 3 || args.len() == 4,
        "Usage: seed_remote ENDPOINT INVITATION_OUTPUT [TRUST_CERT]"
    );
    let (connector, certificates) = if args.len() == 4 {
        use base64::Engine;
        let bytes = std::fs::read(&args[3])?;
        let cert = native_tls::Certificate::from_pem(&bytes)
            .or_else(|_| native_tls::Certificate::from_der(&bytes))?;
        (
            Some(encrypted_spaces_sdk::load_trust_cert(&args[3])?),
            vec![base64::engine::general_purpose::STANDARD.encode(cert.to_der()?)],
        )
    } else {
        (None, vec![])
    };
    tokio::time::timeout(Duration::from_secs(120), async {
        let schema_path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../tauri/app_schema.kdl");
        let baseline = LocalTransport::from_schema_file(schema_path).await?;
        let commitment = baseline.get_root_hash().await?;
        let space = Space::create(
            WebSocketTransport::new_with_trust_connector(&args[1], connector).await?,
            ApplicationSchema::FromBytes(SCHEMA, commitment, EXTEND_FF_ID),
        ).await?;
        let folder = add_inode(&space, 0, "Documents", 2, File::from_hash("0".repeat(64))).await?;
        let uploaded = space.file().upload(File::from_data(CONTENT.to_vec())).await?;
        let root_file = add_inode(&space, 0, "hello.txt", 1, uploaded.clone()).await?;
        let nested_file = add_inode(&space, folder, "nested.txt", 1, uploaded).await?;
        let invite = space.invite_user().await?;
        let invite_json = serde_json::to_value(&invite)?;
        let client = Client::join(invite_json.clone(), &args[1], &certificates, true).await?;
        anyhow::ensure!(
            client.snapshot().await?.user_info["user_id"] != serde_json::json!(space.uid()),
            "Invitation reused the owner's identity"
        );
        anyhow::ensure!(
            Client::join(invite_json, &args[1], &certificates, true).await.is_err(),
            "Consumed invitation was reusable"
        );
        let root = client.list(0).await?;
        anyhow::ensure!(root.len() == 2 && root[0].inode_type == 2, "Root listing mismatch");
        let nested = client.list(folder).await?;
        anyhow::ensure!(nested.len() == 1 && nested[0].id == nested_file, "Nested listing mismatch");
        anyhow::ensure!(client.read(root_file).await? == CONTENT, "Root file contents mismatch");
        anyhow::ensure!(client.read(nested_file).await? == CONTENT, "Nested file contents mismatch");
        anyhow::ensure!(client.read(folder).await.is_err(), "Folder incorrectly readable as a file");
        let snapshot = client.snapshot().await?;
        anyhow::ensure!(!serde_json::to_string(&snapshot)?.contains(std::str::from_utf8(CONTENT)?),
            "Snapshot unexpectedly contains plaintext file contents");
        let restored = Client::restore(snapshot, &args[1], &certificates, true).await?;
        anyhow::ensure!(restored.read(root_file).await? == CONTENT, "Restored read mismatch");
        let phone_invite = space.invite_user().await?;
        std::fs::write(&args[2], serde_json::to_vec_pretty(&phone_invite)?)?;
        println!("Protocol checks passed: invitation join/reuse rejection, root/nested listing, in-memory reads, folder rejection, restore.");
        println!("Fixture folder={folder} root_file={root_file} nested_file={nested_file}");
        Ok::<_, anyhow::Error>(())
    }).await.context("Fixture operation timed out")??;
    Ok(())
}
