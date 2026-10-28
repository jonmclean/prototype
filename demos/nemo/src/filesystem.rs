use bytes::{Buf, Bytes};
use dav_server::davpath::DavPath;
use dav_server::fs::{
    DavDirEntry, DavFile, DavFileSystem, DavMetaData, FsError, FsFuture, FsResult, FsStream,
    OpenOptions, ReadDirMeta,
};
use encrypted_spaces_sdk::File;
use futures_util::stream;
use serde::{Deserialize, Serialize};
use std::io::{Cursor, Read, Seek, SeekFrom, Write};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::Mutex;

use crate::session::Session;

pub const MAX_FILE_SIZE: usize = 64 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Entry {
    id: Option<i64>,
    parent_id: i64,
    name: String,
    directory: i64,
    size: i64,
    modified: i64,
    content: File,
}

impl Entry {
    fn is_dir(&self) -> bool {
        self.directory == 1
    }

    fn root() -> Self {
        Self {
            id: Some(0),
            parent_id: 0,
            name: "Encrypted Spaces".into(),
            directory: 1,
            size: 0,
            modified: 0,
            content: File::from_hash("0".repeat(64)),
        }
    }

    fn revision(&self) -> String {
        format!(
            "{:?}:{}:{}:{}:{}",
            self.id,
            self.parent_id,
            self.name,
            self.modified,
            self.content.hash().unwrap_or_default()
        )
    }
}

impl DavMetaData for Entry {
    fn len(&self) -> u64 {
        self.size.max(0) as u64
    }

    fn modified(&self) -> FsResult<SystemTime> {
        Ok(UNIX_EPOCH + Duration::from_secs(self.modified.max(0) as u64))
    }

    fn is_dir(&self) -> bool {
        self.directory == 1
    }

    fn etag(&self) -> Option<String> {
        // Content hashes change on every upload, including same-sized writes.
        Some(format!(
            "{}-{}",
            self.id.unwrap_or_default(),
            self.content.hash().unwrap_or_default()
        ))
    }
}

impl DavDirEntry for Entry {
    fn name(&self) -> Vec<u8> {
        self.name.as_bytes().to_vec()
    }

    fn metadata(&self) -> FsFuture<'_, Box<dyn DavMetaData>> {
        Box::pin(async { Ok(Box::new(self.clone()) as Box<dyn DavMetaData>) })
    }
}

fn failure(error: impl std::fmt::Display) -> FsError {
    eprintln!("encrypted-spaces: {error}");
    FsError::GeneralFailure
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

fn components(path: &DavPath) -> FsResult<Vec<String>> {
    let text = std::str::from_utf8(path.as_bytes()).map_err(|_| FsError::Forbidden)?;
    text.split('/')
        .filter(|s| !s.is_empty())
        .map(|part| {
            if matches!(part, "." | "..") || part.contains('\0') {
                Err(FsError::Forbidden)
            } else {
                Ok(part.to_owned())
            }
        })
        .collect()
}

fn resolve(entries: &[Entry], parts: &[String]) -> FsResult<Entry> {
    let mut current = Entry::root();
    for part in parts {
        if !current.is_dir() {
            return Err(FsError::Forbidden);
        }
        let parent_id = current.id.unwrap();
        let mut matches = entries
            .iter()
            .filter(|e| e.parent_id == parent_id && e.name == *part);
        current = matches.next().cloned().ok_or(FsError::NotFound)?;
        if matches.next().is_some() {
            return Err(FsError::GeneralFailure);
        }
    }
    Ok(current)
}

fn destination(entries: &[Entry], parts: &[String]) -> FsResult<(i64, String)> {
    let (name, parent) = parts.split_last().ok_or(FsError::Forbidden)?;
    let parent = resolve(entries, parent)?;
    if !parent.is_dir() {
        return Err(FsError::Forbidden);
    }
    Ok((parent.id.unwrap(), name.clone()))
}

#[derive(Clone)]
pub struct EncryptedFs {
    session: Arc<Mutex<Session>>,
}

impl EncryptedFs {
    pub fn new(session: Session) -> Self {
        Self {
            session: Arc::new(Mutex::new(session)),
        }
    }

    async fn entries(session: &Session) -> FsResult<Vec<Entry>> {
        session.sync().await.map_err(failure)?;
        session
            .space
            .table::<Entry>("entries")
            .select()
            .all()
            .await
            .map_err(failure)
    }

    async fn remove(&self, path: &DavPath, directory: bool) -> FsResult<()> {
        let parts = components(path)?;
        let session = self.session.lock().await;
        let entries = Self::entries(&session).await?;
        let entry = resolve(&entries, &parts)?;
        if entry.id == Some(0) || entry.is_dir() != directory {
            return Err(FsError::Forbidden);
        }
        if directory && entries.iter().any(|e| Some(e.parent_id) == entry.id) {
            return Err(FsError::Exists);
        }
        session
            .space
            .table::<Entry>("entries")
            .delete()
            .where_eq("id", entry.id.unwrap())
            .execute()
            .await
            .map_err(failure)?;
        session.save().await.map_err(failure)
    }
}

impl DavFileSystem for EncryptedFs {
    fn open<'a>(
        &'a self,
        path: &'a DavPath,
        options: OpenOptions,
    ) -> FsFuture<'a, Box<dyn DavFile>> {
        Box::pin(async move {
            if options.size.is_some_and(|s| s > MAX_FILE_SIZE as u64) {
                return Err(FsError::TooLarge);
            }
            let parts = components(path)?;
            let session = self.session.lock().await;
            let entries = Self::entries(&session).await?;
            let (parent_id, name) = destination(&entries, &parts)?;
            let existing = match resolve(&entries, &parts) {
                Ok(entry) => Some(entry),
                Err(FsError::NotFound) => None,
                Err(e) => return Err(e),
            };
            if existing.as_ref().is_some_and(|e| e.is_dir()) {
                return Err(FsError::Forbidden);
            }
            if options.create_new && existing.is_some() {
                return Err(FsError::Exists);
            }
            if existing.is_none() && !(options.write && (options.create || options.create_new)) {
                return Err(FsError::NotFound);
            }
            let expected = existing.as_ref().map(Entry::revision);
            let data = if let Some(entry) = existing.as_ref().filter(|_| !options.truncate) {
                if entry.size < 0 || entry.size as u64 > MAX_FILE_SIZE as u64 {
                    return Err(FsError::TooLarge);
                }
                let data = session
                    .space
                    .file()
                    .download(&entry.content)
                    .await
                    .map_err(failure)?
                    .into_data()
                    .map_err(failure)?;
                if data.len() > MAX_FILE_SIZE {
                    return Err(FsError::TooLarge);
                }
                data
            } else {
                Vec::new()
            };
            let mut cursor = Cursor::new(data);
            if options.append {
                cursor.set_position(cursor.get_ref().len() as u64);
            }
            let entry = existing.unwrap_or(Entry {
                id: None,
                parent_id,
                name,
                directory: 0,
                size: 0,
                modified: now(),
                content: File::from_hash("0".repeat(64)),
            });
            let dirty = options.write && (expected.is_none() || options.truncate);
            Ok(Box::new(EncryptedFile {
                fs: self.clone(),
                parts,
                entry,
                expected,
                cursor,
                writable: options.write,
                dirty,
            }) as Box<dyn DavFile>)
        })
    }

    fn read_dir<'a>(
        &'a self,
        path: &'a DavPath,
        _meta: ReadDirMeta,
    ) -> FsFuture<'a, FsStream<Box<dyn DavDirEntry>>> {
        Box::pin(async move {
            let parts = components(path)?;
            let session = self.session.lock().await;
            let entries = Self::entries(&session).await?;
            let parent = resolve(&entries, &parts)?;
            if !parent.is_dir() {
                return Err(FsError::Forbidden);
            }
            let children: Vec<_> = entries
                .into_iter()
                .filter(|e| Some(e.parent_id) == parent.id)
                .map(|e| Ok(Box::new(e) as Box<dyn DavDirEntry>))
                .collect();
            Ok(Box::pin(stream::iter(children)) as FsStream<Box<dyn DavDirEntry>>)
        })
    }

    fn metadata<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, Box<dyn DavMetaData>> {
        Box::pin(async move {
            let parts = components(path)?;
            let session = self.session.lock().await;
            let entries = Self::entries(&session).await?;
            Ok(Box::new(resolve(&entries, &parts)?) as Box<dyn DavMetaData>)
        })
    }

    fn create_dir<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move {
            let parts = components(path)?;
            let session = self.session.lock().await;
            let entries = Self::entries(&session).await?;
            let (parent_id, name) = destination(&entries, &parts)?;
            match resolve(&entries, &parts) {
                Ok(_) => return Err(FsError::Exists),
                Err(FsError::NotFound) => {}
                Err(e) => return Err(e),
            }
            session
                .space
                .table::<Entry>("entries")
                .insert(&Entry {
                    id: None,
                    parent_id,
                    name,
                    directory: 1,
                    size: 0,
                    modified: now(),
                    content: File::from_hash("0".repeat(64)),
                })
                .execute()
                .await
                .map_err(failure)?;
            session.save().await.map_err(failure)
        })
    }

    fn remove_dir<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(self.remove(path, true))
    }

    fn remove_file<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(self.remove(path, false))
    }

    fn rename<'a>(&'a self, from: &'a DavPath, to: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move {
            let from = components(from)?;
            let to = components(to)?;
            if from.is_empty() || to.is_empty() || (to.starts_with(&from) && to != from) {
                return Err(FsError::Forbidden);
            }
            let session = self.session.lock().await;
            let entries = Self::entries(&session).await?;
            let source = resolve(&entries, &from)?;
            if from == to {
                return Ok(());
            }
            let (parent_id, name) = destination(&entries, &to)?;
            // dav-server handles Overwrite by removing the destination first.
            match resolve(&entries, &to) {
                Ok(_) => return Err(FsError::Exists),
                Err(FsError::NotFound) => {}
                Err(e) => return Err(e),
            }
            session
                .space
                .table::<Entry>("entries")
                .update()
                .set("parent_id", parent_id)
                .set("name", name)
                .set("modified", now())
                .where_eq("id", source.id.unwrap())
                .execute()
                .await
                .map_err(failure)?;
            session.save().await.map_err(failure)
        })
    }

    fn copy<'a>(&'a self, from: &'a DavPath, to: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move {
            let from = components(from)?;
            let to = components(to)?;
            let session = self.session.lock().await;
            let entries = Self::entries(&session).await?;
            let source = resolve(&entries, &from)?;
            if source.is_dir() {
                return Err(FsError::Forbidden);
            }
            let (parent_id, name) = destination(&entries, &to)?;
            match resolve(&entries, &to) {
                Ok(_) => return Err(FsError::Exists),
                Err(FsError::NotFound) => {}
                Err(e) => return Err(e),
            }
            session
                .space
                .table::<Entry>("entries")
                .insert(&Entry {
                    id: None,
                    parent_id,
                    name,
                    modified: now(),
                    ..source
                })
                .execute()
                .await
                .map_err(failure)?;
            session.save().await.map_err(failure)
        })
    }
}

struct EncryptedFile {
    fs: EncryptedFs,
    parts: Vec<String>,
    entry: Entry,
    expected: Option<String>,
    cursor: Cursor<Vec<u8>>,
    writable: bool,
    dirty: bool,
}

impl std::fmt::Debug for EncryptedFile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EncryptedFile").finish_non_exhaustive()
    }
}

impl DavFile for EncryptedFile {
    fn metadata(&mut self) -> FsFuture<'_, Box<dyn DavMetaData>> {
        Box::pin(async {
            let mut entry = self.entry.clone();
            entry.size = self.cursor.get_ref().len() as i64;
            Ok(Box::new(entry) as Box<dyn DavMetaData>)
        })
    }

    fn write_buf(&mut self, mut buf: Box<dyn Buf + Send>) -> FsFuture<'_, ()> {
        Box::pin(async move {
            if buf.remaining() > MAX_FILE_SIZE {
                return Err(FsError::TooLarge);
            }
            self.write_bytes(buf.copy_to_bytes(buf.remaining())).await
        })
    }

    fn write_bytes(&mut self, buf: Bytes) -> FsFuture<'_, ()> {
        Box::pin(async move {
            if !self.writable {
                return Err(FsError::Forbidden);
            }
            if self.cursor.position().saturating_add(buf.len() as u64) > MAX_FILE_SIZE as u64 {
                return Err(FsError::TooLarge);
            }
            self.cursor.write_all(&buf).map_err(failure)?;
            self.dirty = true;
            Ok(())
        })
    }

    fn read_bytes(&mut self, count: usize) -> FsFuture<'_, Bytes> {
        Box::pin(async move {
            let available = self
                .cursor
                .get_ref()
                .len()
                .saturating_sub(self.cursor.position() as usize);
            let mut data = vec![0; count.min(available)];
            self.cursor.read_exact(&mut data).map_err(failure)?;
            Ok(Bytes::from(data))
        })
    }

    fn seek(&mut self, pos: SeekFrom) -> FsFuture<'_, u64> {
        Box::pin(async move { self.cursor.seek(pos).map_err(failure) })
    }

    fn flush(&mut self) -> FsFuture<'_, ()> {
        Box::pin(async move {
            if !self.dirty {
                return Ok(());
            }
            let session = self.fs.session.lock().await;
            let entries = EncryptedFs::entries(&session).await?;
            let current = match resolve(&entries, &self.parts) {
                Ok(entry) => Some(entry),
                Err(FsError::NotFound) => None,
                Err(e) => return Err(e),
            };
            // A transfer is staged in RAM; never replace a newer completed write.
            if current.as_ref().map(Entry::revision) != self.expected {
                return Err(FsError::Exists);
            }
            let (parent_id, name) = destination(&entries, &self.parts)?;
            let content = session
                .space
                .file()
                .upload(File::from_data(self.cursor.get_ref().clone()))
                .await
                .map_err(failure)?;
            self.entry.parent_id = parent_id;
            self.entry.name = name;
            self.entry.content = content;
            self.entry.size = self.cursor.get_ref().len() as i64;
            self.entry.modified = now();
            let table = session.space.table::<Entry>("entries");
            if let Some(id) = self.entry.id {
                table
                    .update()
                    .set("content", self.entry.content.hash().map_err(failure)?)
                    .set("size", self.entry.size)
                    .set("modified", self.entry.modified)
                    .where_eq("id", id)
                    .execute()
                    .await
                    .map_err(failure)?;
            } else {
                self.entry.id = Some(table.insert(&self.entry).execute().await.map_err(failure)?);
            }
            session.save().await.map_err(failure)?;
            self.expected = Some(self.entry.revision());
            self.dirty = false;
            Ok(())
        })
    }
}
