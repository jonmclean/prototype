use crate::Space;
use encrypted_spaces_backend::error::{Result, SdkError};
use encrypted_spaces_crypto::encryption::{
    ciphertext_key_id, decrypt_field_with_progress, encrypt_field, EncryptionKey,
};
use encrypted_spaces_key_manager::SimpleKeyId;
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use sha2::{Digest, Sha256};
use std::sync::Arc;

/// A file reference that can hold either pending data (for upload) or a hash (already uploaded).
///
/// Use this type in your row structs for `FileRef` columns.
///
/// Upload files explicitly via [`FileHandle::upload`] before inserting
/// the row, and download via [`FileHandle::download`] after selecting.
///
/// # Example
///
/// ```ignore
/// // Upload first, then insert the row with the resulting hash
/// let file_hash = space.file().upload(File::from_data(file_bytes)).await?;
/// table.insert(&Attachment {
///     id: None,
///     message_id: 1,
///     file_hash,
///     filename: "photo.png".into(),
/// }).execute().await?;
///
/// // Select returns the hash; download to get the data
/// let row: Attachment = table.select().first().await?.unwrap();
/// let data = space.file().download(&row.file_hash).await?;
/// ```
#[derive(Debug, Clone)]
pub enum File {
    /// Pending upload — contains raw plaintext bytes.
    Data(Vec<u8>),
    /// Already uploaded — contains the hex content hash.
    Hash(String),
}

impl File {
    /// Create a file from raw data (for upload on insert).
    pub fn from_data(data: Vec<u8>) -> Self {
        Self::Data(data)
    }

    /// Create a file from an existing hash (already uploaded).
    pub fn from_hash(hash: String) -> Self {
        Self::Hash(hash)
    }

    /// Get the hash. Returns an error if the file hasn't been uploaded yet.
    pub fn hash(&self) -> Result<&str> {
        match self {
            File::Hash(h) => Ok(h),
            File::Data(_) => Err(SdkError::ValidationError(
                "File has not been uploaded yet — call upload() first".into(),
            )),
        }
    }

    /// Get the data. Returns an error if the file is a hash.
    pub fn data(&self) -> Result<&[u8]> {
        match self {
            File::Data(d) => Ok(d),
            File::Hash(_) => Err(SdkError::ValidationError(
                "File contains a hash, not data — call download() first".into(),
            )),
        }
    }

    /// Consume the file and return the data bytes. Returns an error if the file is a hash.
    pub fn into_data(self) -> Result<Vec<u8>> {
        match self {
            File::Data(d) => Ok(d),
            File::Hash(_) => Err(SdkError::ValidationError(
                "File contains a hash, not data — call download() first".into(),
            )),
        }
    }
}

/// Serialize: always serializes the hash string.
/// `File::Data` must be uploaded first via `FileHandle::upload()`.
impl Serialize for File {
    fn serialize<S: Serializer>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> {
        match self {
            File::Hash(hash) => serializer.serialize_str(hash),
            File::Data(_) => Err(serde::ser::Error::custom(
                "File::Data must be uploaded before serialization — call space.file().upload() first",
            )),
        }
    }
}

/// Deserialize: always produces Hash variant (data comes from the database as a hash string).
impl<'de> Deserialize<'de> for File {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> std::result::Result<Self, D::Error> {
        let s = String::deserialize(deserializer)?;
        Ok(File::Hash(s))
    }
}

/// Handle for uploading and downloading encrypted files.
///
/// Files are encrypted client-side using the space's current encryption key,
/// then uploaded to the server's content-addressed file store. The content
/// hash (SHA-256 of the encrypted data) is used as the file identifier.
pub struct FileHandle {
    pub(crate) space: Arc<Space>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FileStage {
    Preparing,
    Downloading,
    Verifying,
    RetrievingKey,
    Decrypting,
}

/// Progress is stage-local. A byte count reaching its total does not mark a
/// stage successful; `complete` is emitted only after that stage succeeds.
#[derive(Clone, Copy, Debug)]
pub struct FileProgress {
    pub stage: FileStage,
    pub processed: u64,
    pub total: Option<u64>,
    pub complete: bool,
}

pub type FileProgressCallback<'a> = dyn Fn(FileProgress) -> Result<()> + Send + Sync + 'a;

impl FileHandle {
    /// Encrypt and upload a file's data. Takes a `File::Data` and returns
    /// a `File::Hash` with the content hash. No-op if already a `File::Hash`.
    pub async fn upload(&self, file: File) -> Result<File> {
        let data = match file {
            File::Data(data) => data,
            File::Hash(h) => return Ok(File::Hash(h)),
        };
        let key = crate::crypto::current_encryption_key(&self.space).await?;
        let encrypted = encrypt_field(&data, &key);
        let hash = hex::encode(Sha256::digest(&encrypted));
        self.space.transport.file_upload(&hash, encrypted).await?;
        Ok(File::Hash(hash))
    }

    /// Download and decrypt a file. Takes a `File::Hash` and returns
    /// a `File::Data` with the decrypted content. Returns a clone if
    /// the file already contains data.
    pub async fn download(&self, file: &File) -> Result<File> {
        self.download_with_progress(file, &|_, _| Ok(())).await
    }

    /// Download, verify, and decrypt a file while reporting ciphertext transfer
    /// progress. The byte total can be unknown; a completed transfer does not
    /// imply that verification or decryption has succeeded.
    pub async fn download_with_progress(
        &self,
        file: &File,
        progress: &crate::transport::DownloadProgress<'_>,
    ) -> Result<File> {
        if let File::Data(d) = file {
            progress(d.len() as u64, Some(d.len() as u64))?;
            return Ok(File::Data(d.clone()));
        }
        self.download_with_stages(file, &|event| {
            if event.stage == FileStage::Downloading && !event.complete {
                progress(event.processed, event.total)?;
            }
            Ok(())
        })
        .await
    }

    /// Download with separate transfer, integrity verification, key retrieval,
    /// and decryption stages. Plaintext is returned only after all succeed.
    pub async fn download_with_stages(
        &self,
        file: &File,
        progress: &FileProgressCallback<'_>,
    ) -> Result<File> {
        if let File::Data(d) = file {
            return Ok(File::Data(d.clone()));
        }
        let hash = file.hash()?;
        progress(FileProgress {
            stage: FileStage::Downloading,
            processed: 0,
            total: None,
            complete: false,
        })?;
        let encrypted = self
            .space
            .transport
            .file_download_with_progress(hash, &|processed, total| {
                progress(FileProgress {
                    stage: FileStage::Downloading,
                    processed,
                    total,
                    complete: false,
                })
            })
            .await?;
        let report = |stage, processed: usize, total: usize, complete| {
            progress(FileProgress {
                stage,
                processed: processed as u64,
                total: Some(total as u64),
                complete,
            })
        };
        report(
            FileStage::Downloading,
            encrypted.len(),
            encrypted.len(),
            true,
        )?;
        // The server is untrusted: verify the content hash before decrypting.
        // Files are content-addressed by sha256(ciphertext), and encrypt_field
        // uses AES-256-CTR (no MAC), so without this check a malicious server
        // could flip arbitrary plaintext bits. The hash is committed in the
        // changelog Merkle tree, so this re-derivation is the integrity anchor.
        report(FileStage::Verifying, 0, encrypted.len(), false)?;
        let mut hasher = Sha256::new();
        let mut processed = 0;
        for chunk in encrypted.chunks(256 * 1024) {
            hasher.update(chunk);
            processed += chunk.len();
            report(FileStage::Verifying, processed, encrypted.len(), false)?;
        }
        if hex::encode(hasher.finalize()) != hash {
            return Err(SdkError::DecryptionError(
                "file content hash mismatch: server returned tampered or wrong data".into(),
            ));
        }
        report(FileStage::Verifying, encrypted.len(), encrypted.len(), true)?;
        progress(FileProgress {
            stage: FileStage::RetrievingKey,
            processed: 0,
            total: None,
            complete: false,
        })?;
        let key_id: SimpleKeyId = ciphertext_key_id(&encrypted).ok_or_else(|| {
            SdkError::DecryptionError("invalid file ciphertext: missing key_id".into())
        })?;
        let builder = self.space.retention_builder();
        let km = self.space.key_manager.lock().await;
        let key = km
            .data_key_for_key_id(&key_id, &builder)
            .await
            .map(|bytes| EncryptionKey::new(bytes, &key_id))
            .map_err(|_| SdkError::DecryptionError(format!("missing key for key_id {key_id:?}")))?;
        progress(FileProgress {
            stage: FileStage::RetrievingKey,
            processed: 0,
            total: None,
            complete: true,
        })?;
        let plaintext = decrypt_field_with_progress(&encrypted, &key, |processed, total| {
            report(FileStage::Decrypting, processed, total, false).map_err(|error| {
                encrypted_spaces_crypto::error::EncryptionError::Progress(error.to_string())
            })
        })
        .map_err(|error| SdkError::DecryptionError(error.to_string()))?;
        report(
            FileStage::Decrypting,
            plaintext.len(),
            plaintext.len(),
            true,
        )?;
        Ok(File::Data(plaintext))
    }
}

#[cfg(all(test, feature = "local-transport"))]
mod tests {
    use super::*;
    use crate::local_transport::LocalTransport;
    use crate::schema::ApplicationSchema;
    use crate::Space;
    use encrypted_spaces_backend::error::Result;

    fn schema() -> ApplicationSchema {
        ApplicationSchema::for_testing(vec![], crate::testing::initial_internal_data_commitment())
    }

    async fn create_space() -> Result<Space> {
        let transport = LocalTransport::in_memory().await?;
        Space::create(transport, schema()).await
    }

    #[tokio::test]
    async fn upload_download_roundtrip() -> Result<()> {
        let space = create_space().await?;
        let handle = space.file();

        let data = b"hello encrypted file world";
        let uploaded = handle.upload(File::from_data(data.to_vec())).await?;
        assert_eq!(uploaded.hash()?.len(), 64);
        assert!(uploaded.hash()?.chars().all(|c| c.is_ascii_hexdigit()));

        let downloaded = handle.download(&uploaded).await?;
        assert_eq!(downloaded.data()?, data);
        Ok(())
    }

    #[tokio::test]
    async fn upload_large_data() -> Result<()> {
        let space = create_space().await?;
        let handle = space.file();
        let data = vec![0x42u8; 1024 * 1024];
        let uploaded = handle.upload(File::from_data(data.clone())).await?;
        let downloaded = handle.download(&uploaded).await?;
        assert_eq!(downloaded.data()?, &data);
        Ok(())
    }

    #[tokio::test]
    async fn staged_download_reports_independent_byte_progress_and_success() -> Result<()> {
        let space = create_space().await?;
        let handle = space.file();
        for size in [0, 512 * 1024 + 17] {
            let data = vec![42; size];
            let uploaded = handle.upload(File::from_data(data.clone())).await?;
            let updates = std::sync::Mutex::new(Vec::new());
            let downloaded = handle
                .download_with_stages(&uploaded, &|event| {
                    updates.lock().unwrap().push(event);
                    Ok(())
                })
                .await?;
            assert_eq!(downloaded.data()?, data);
            let updates = updates.into_inner().unwrap();
            let completed: Vec<_> = updates
                .iter()
                .filter(|event| event.complete)
                .map(|event| event.stage)
                .collect();
            assert_eq!(
                completed,
                vec![
                    FileStage::Downloading,
                    FileStage::Verifying,
                    FileStage::RetrievingKey,
                    FileStage::Decrypting
                ]
            );
            for stage in [FileStage::Verifying, FileStage::Decrypting] {
                let events: Vec<_> = updates
                    .iter()
                    .filter(|event| event.stage == stage)
                    .collect();
                assert_eq!(events[0].processed, 0);
                let last = events.last().unwrap();
                assert!(last.complete);
                assert_eq!(last.total, Some(last.processed));
                assert!(events[..events.len() - 1]
                    .iter()
                    .all(|event| !event.complete));
                if size > 0 {
                    assert!(events
                        .iter()
                        .any(|event| event.processed > 0 && Some(event.processed) < event.total));
                }
            }
        }
        Ok(())
    }

    #[tokio::test]
    async fn staged_download_does_not_decrypt_or_complete_verification_after_tampering(
    ) -> Result<()> {
        use crate::Transport;
        let space = create_space().await?;
        let handle = space.file();
        let file = handle.upload(File::from_data(vec![42; 512 * 1024])).await?;
        let transport = space
            .transport
            .as_any()
            .downcast_ref::<LocalTransport>()
            .unwrap();
        let mut encrypted = transport.file_download(file.hash()?).await?;
        *encrypted.last_mut().unwrap() ^= 1;
        transport.file_upload(file.hash()?, encrypted).await?;
        let updates = std::sync::Mutex::new(Vec::new());
        let result = handle
            .download_with_stages(&file, &|event| {
                updates.lock().unwrap().push(event);
                Ok(())
            })
            .await;
        assert!(matches!(result, Err(SdkError::DecryptionError(_))));
        let updates = updates.into_inner().unwrap();
        assert!(updates
            .iter()
            .any(|event| event.stage == FileStage::Downloading && event.complete));
        assert!(!updates
            .iter()
            .any(|event| event.stage == FileStage::Verifying && event.complete));
        assert!(!updates.iter().any(|event| matches!(
            event.stage,
            FileStage::RetrievingKey | FileStage::Decrypting
        )));
        Ok(())
    }

    #[tokio::test]
    async fn same_data_different_hashes() -> Result<()> {
        let space = create_space().await?;
        let handle = space.file();
        let data = b"same content";
        let b1 = handle.upload(File::from_data(data.to_vec())).await?;
        let b2 = handle.upload(File::from_data(data.to_vec())).await?;
        assert_ne!(b1.hash()?, b2.hash()?);
        assert_eq!(handle.download(&b1).await?.data()?, data);
        assert_eq!(handle.download(&b2).await?.data()?, data);
        Ok(())
    }

    #[tokio::test]
    async fn download_nonexistent_file_fails() -> Result<()> {
        let space = create_space().await?;
        let fake = File::from_hash("0".repeat(64));
        let result = space.file().download(&fake).await;
        assert!(result.is_err());
        Ok(())
    }

    #[tokio::test]
    async fn file_works_after_add_user() -> Result<()> {
        let transport = LocalTransport::in_memory().await?;
        let alice_space = Space::create(transport.clone(), schema()).await?;
        let b1 = alice_space
            .file()
            .upload(File::from_data(b"before invite".into()))
            .await?;
        let invite = alice_space.invite_user().await?;
        let bob_space = Space::join(transport, invite, schema()).await?;
        let b2 = alice_space
            .file()
            .upload(File::from_data(b"after invite".into()))
            .await?;
        assert_eq!(
            alice_space.file().download(&b1).await?.data()?,
            b"before invite"
        );
        assert_eq!(
            alice_space.file().download(&b2).await?.data()?,
            b"after invite"
        );
        assert_eq!(
            bob_space.file().download(&b1).await?.data()?,
            b"before invite"
        );
        assert_eq!(
            bob_space.file().download(&b2).await?.data()?,
            b"after invite"
        );
        Ok(())
    }

    #[test]
    fn file_serialize_hash() {
        let file = File::from_hash("ab".repeat(32));
        let json = serde_json::to_string(&file).unwrap();
        assert!(json.contains(&"ab".repeat(32)));
    }

    #[test]
    fn file_serialize_data_errors() {
        let file = File::from_data(b"hello".to_vec());
        assert!(serde_json::to_string(&file).is_err());
    }

    #[test]
    fn file_deserialize_produces_hash() {
        let hash = "ab".repeat(32);
        let file: File = serde_json::from_str(&format!("\"{hash}\"")).unwrap();
        assert_eq!(file.hash().unwrap(), hash);
    }
}
