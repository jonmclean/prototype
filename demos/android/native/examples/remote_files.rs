use anyhow::{Context, Result};
use spaces_android::{Client, Connection};
use std::time::Duration;

/// Exercises the phone's client core without saving decrypted file contents.
#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    anyhow::ensure!(
        (3..=5).contains(&args.len()),
        "Usage: remote_files SNAPSHOT ADDRESS [INODE_ID] [TRUST_CERT]"
    );
    let connection: Connection = serde_json::from_slice(&std::fs::read(&args[1])?)?;
    let certificates = if args.len() == 5 {
        use base64::Engine;
        let bytes = std::fs::read(&args[4])?;
        let certificate = native_tls::Certificate::from_pem(&bytes)
            .or_else(|_| native_tls::Certificate::from_der(&bytes))?;
        vec![base64::engine::general_purpose::STANDARD.encode(certificate.to_der()?)]
    } else {
        vec![]
    };
    tokio::time::timeout(Duration::from_secs(60), async {
        let client =
            Client::connect(connection, &args[2], &certificates, cfg!(debug_assertions)).await?;
        for entry in client.list(0).await? {
            println!(
                "{}: {} (type {}, {} bytes)",
                entry.id, entry.name, entry.inode_type, entry.size
            );
        }
        if args.len() >= 4 {
            let bytes = client.read(args[3].parse()?).await?;
            println!(
                "Read and decrypted {} bytes in memory; nothing saved.",
                bytes.len()
            );
        }
        Ok::<_, anyhow::Error>(())
    })
    .await
    .context("Remote operation timed out")??;
    Ok(())
}
