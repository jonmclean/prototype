use axum::{
    body::{to_bytes, Body},
    http::{header, Request, StatusCode},
    Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use bytes::Bytes;
use dav_server::{
    davpath::DavPath,
    fs::{DavFileSystem, FsError, OpenOptions},
};
use encrypted_spaces_nemo::{
    filesystem::{EncryptedFs, MAX_FILE_SIZE},
    sdk_codegen, server,
    session::{Profile, Session},
};
use encrypted_spaces_sdk::{local_transport::LocalTransport, Space};
use std::os::unix::fs::PermissionsExt;
use tempfile::TempDir;
use tower::ServiceExt;

struct Fixture {
    dir: TempDir,
    transport: LocalTransport,
    fs: EncryptedFs,
    router: Router,
}

impl Fixture {
    async fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let transport = LocalTransport::from_schema_file(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/app_schema.kdl"
        ))
        .await
        .unwrap();
        let space = Space::create(transport.clone(), sdk_codegen::application_schema())
            .await
            .unwrap();
        let session = Session {
            space,
            snapshot_path: dir.path().join("snapshot.json"),
        };
        session.save().await.unwrap();
        let fs = EncryptedFs::new(session);
        let router = server::router(fs.clone(), "test-password");
        Self {
            dir,
            transport,
            fs,
            router,
        }
    }

    async fn restore(&mut self) {
        let snapshot_path = self.dir.path().join("snapshot.json");
        let snapshot = serde_json::from_slice(&std::fs::read(&snapshot_path).unwrap()).unwrap();
        let space = Space::restore(self.transport.clone(), snapshot)
            .await
            .unwrap();
        self.fs = EncryptedFs::new(Session {
            space,
            snapshot_path,
        });
        self.router = server::router(self.fs.clone(), "test-password");
    }

    async fn request(
        &self,
        method: &str,
        path: &str,
        body: &str,
        headers: &[(&str, &str)],
    ) -> (StatusCode, axum::http::HeaderMap, Bytes) {
        let mut builder = Request::builder()
            .method(method)
            .uri(path)
            .header(header::HOST, "localhost")
            .header(
                header::AUTHORIZATION,
                format!(
                    "Basic {}",
                    STANDARD.encode("encrypted-spaces:test-password")
                ),
            )
            .header(header::CONTENT_LENGTH, body.len());
        for (key, value) in headers {
            builder = builder.header(*key, *value);
        }
        let response = self
            .router
            .clone()
            .oneshot(builder.body(Body::from(body.to_owned())).unwrap())
            .await
            .unwrap();
        let (parts, body) = response.into_parts();
        let body = to_bytes(body, usize::MAX).await.unwrap();
        (parts.status, parts.headers, body)
    }
}

#[tokio::test]
async fn dav_crud_and_restart() {
    let mut f = Fixture::new().await;
    assert_eq!(
        f.request("MKCOL", "/Documents", "", &[]).await.0,
        StatusCode::CREATED
    );
    let path = "/Documents/r%C3%A9sum%C3%A9%20%26.txt";
    assert_eq!(
        f.request("PUT", path, "hello", &[]).await.0,
        StatusCode::CREATED
    );
    let (status, headers, body) = f.request("GET", path, "", &[]).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, "hello");
    let old_etag = headers[header::ETAG].clone();
    assert_eq!(
        f.request("PROPFIND", "/Documents/", "", &[("Depth", "1")])
            .await
            .0,
        StatusCode::MULTI_STATUS
    );
    let (_, headers, _) = f.request("HEAD", path, "", &[]).await;
    assert_eq!(headers[header::CONTENT_LENGTH], "5");
    assert_eq!(
        f.request("GET", path, "", &[("Range", "bytes=1-3")])
            .await
            .2,
        "ell"
    );
    assert_eq!(
        f.request("PUT", path, "world", &[]).await.0,
        StatusCode::NO_CONTENT
    );
    assert_ne!(
        f.request("HEAD", path, "", &[]).await.1[header::ETAG],
        old_etag
    );
    assert_eq!(
        f.request(
            "MOVE",
            "/Documents",
            "",
            &[("Destination", "http://localhost/Renamed")]
        )
        .await
        .0,
        StatusCode::CREATED
    );
    assert_eq!(
        f.request("GET", "/Renamed/r%C3%A9sum%C3%A9%20%26.txt", "", &[])
            .await
            .2,
        "world"
    );
    assert_eq!(
        f.request(
            "COPY",
            "/Renamed",
            "",
            &[("Destination", "http://localhost/Copied")]
        )
        .await
        .0,
        StatusCode::CREATED
    );
    f.restore().await;
    assert_eq!(
        f.request("GET", "/Copied/r%C3%A9sum%C3%A9%20%26.txt", "", &[])
            .await
            .2,
        "world"
    );
    assert_eq!(
        f.request("DELETE", "/Renamed", "", &[]).await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        f.request("GET", "/Renamed/r%C3%A9sum%C3%A9%20%26.txt", "", &[])
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        f.request("GET", "/Copied/r%C3%A9sum%C3%A9%20%26.txt", "", &[])
            .await
            .2,
        "world"
    );
}

#[tokio::test]
async fn overwrite_move_and_copy_obey_preconditions() {
    let f = Fixture::new().await;
    f.request("PUT", "/a", "source", &[]).await;
    f.request("PUT", "/b", "destination", &[]).await;
    assert_eq!(
        f.request(
            "COPY",
            "/a",
            "",
            &[("Destination", "http://localhost/b"), ("Overwrite", "F")]
        )
        .await
        .0,
        StatusCode::PRECONDITION_FAILED
    );
    assert_eq!(f.request("GET", "/b", "", &[]).await.2, "destination");
    assert_eq!(
        f.request("COPY", "/a", "", &[("Destination", "http://localhost/b")])
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(f.request("GET", "/b", "", &[]).await.2, "source");
    assert_eq!(
        f.request("MOVE", "/a", "", &[("Destination", "http://localhost/b")])
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        f.request("GET", "/a", "", &[]).await.0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(f.request("GET", "/b", "", &[]).await.2, "source");
    let etag = f.request("HEAD", "/b", "", &[]).await.1[header::ETAG].clone();
    assert_eq!(
        f.request("PUT", "/b", "stale", &[("If-Match", "\"old-etag\"")])
            .await
            .0,
        StatusCode::PRECONDITION_FAILED
    );
    assert_eq!(f.request("HEAD", "/b", "", &[]).await.1[header::ETAG], etag);
}

#[tokio::test]
async fn incomplete_and_conflicting_uploads_do_not_destroy_files() {
    let f = Fixture::new().await;
    f.request("PUT", "/file", "original", &[]).await;
    let path = DavPath::new("/file").unwrap();
    let options = OpenOptions {
        write: true,
        create: true,
        truncate: true,
        ..Default::default()
    };
    let mut interrupted = f.fs.open(&path, options.clone()).await.unwrap();
    interrupted
        .write_bytes(Bytes::from("partial"))
        .await
        .unwrap();
    drop(interrupted);
    assert_eq!(f.request("GET", "/file", "", &[]).await.2, "original");
    let mut first = f.fs.open(&path, options.clone()).await.unwrap();
    let mut second = f.fs.open(&path, options.clone()).await.unwrap();
    first.write_bytes(Bytes::from("first")).await.unwrap();
    first.flush().await.unwrap();
    second.write_bytes(Bytes::from("second")).await.unwrap();
    assert_eq!(second.flush().await.unwrap_err(), FsError::Exists);
    assert_eq!(f.request("GET", "/file", "", &[]).await.2, "first");
    let new_path = DavPath::new("/unfinished").unwrap();
    drop(f.fs.open(&new_path, options.clone()).await.unwrap());
    assert_eq!(
        f.request("HEAD", "/unfinished", "", &[]).await.0,
        StatusCode::NOT_FOUND
    );
    let oversized = OpenOptions {
        size: Some(MAX_FILE_SIZE as u64 + 1),
        ..options
    };
    assert_eq!(
        f.fs.open(&new_path, oversized).await.unwrap_err(),
        FsError::TooLarge
    );
}

#[tokio::test]
async fn authentication_root_and_tree_constraints() {
    let f = Fixture::new().await;
    for authorization in [None, Some("Basic d3Jvbmc6d3Jvbmc=")] {
        let mut request = Request::builder().method("PROPFIND").uri("/");
        if let Some(value) = authorization {
            request = request.header(header::AUTHORIZATION, value);
        }
        let response = f
            .router
            .clone()
            .oneshot(request.body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        assert!(response.headers().contains_key(header::WWW_AUTHENTICATE));
    }
    assert_eq!(f.request("OPTIONS", "/", "", &[]).await.0, StatusCode::OK);
    assert_eq!(
        f.request("DELETE", "/", "", &[]).await.0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        f.request("PUT", "/missing/file", "test", &[]).await.0,
        StatusCode::CONFLICT
    );
    f.request("MKCOL", "/folder", "", &[]).await;
    f.request("MKCOL", "/folder/child", "", &[]).await;
    f.request("PUT", "/folder/child/file", "keep me", &[]).await;
    assert_eq!(
        f.request("DELETE", "/", "", &[]).await.0,
        StatusCode::FORBIDDEN
    );
    for (from, to) in [
        ("/folder/child", "http://localhost/folder"),
        ("/folder", "http://localhost/"),
    ] {
        for method in ["MOVE", "COPY"] {
            assert_eq!(
                f.request(method, from, "", &[("Destination", to)]).await.0,
                StatusCode::FORBIDDEN
            );
        }
    }
    assert_eq!(
        f.request("GET", "/folder/child/file", "", &[]).await.2,
        "keep me"
    );
    assert_eq!(
        f.request(
            "MOVE",
            "/folder",
            "",
            &[("Destination", "http://localhost/folder/child/moved")]
        )
        .await
        .0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        f.request("PUT", "/folder", "not a directory", &[]).await.0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        f.request("GET", "/%FF", "", &[]).await.0,
        StatusCode::FORBIDDEN
    );
}

#[test]
fn profile_is_private_and_exclusive() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("profile");
    let first = Profile::lock(path.clone()).unwrap();
    assert!(Profile::lock(path.clone()).is_err());
    assert_eq!(
        std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
        0o700
    );
    drop(first);
    assert!(Profile::lock(path).is_ok());
}
