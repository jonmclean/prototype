use axum::{
    body::Body,
    extract::{Request, State},
    http::{header, HeaderValue, Response, StatusCode},
    Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use dav_server::{davpath::DavPath, memls::MemLs, DavHandler};

use crate::filesystem::EncryptedFs;

#[derive(Clone)]
struct Bridge {
    handler: DavHandler,
    authorization: String,
}

pub fn router(filesystem: EncryptedFs, password: &str) -> Router {
    let bridge = Bridge {
        handler: DavHandler::builder()
            .filesystem(Box::new(filesystem))
            .locksystem(MemLs::new())
            .build_handler(),
        authorization: format!(
            "Basic {}",
            STANDARD.encode(format!("encrypted-spaces:{password}"))
        ),
    };
    Router::new().fallback(handle).with_state(bridge)
}

async fn handle(State(bridge): State<Bridge>, request: Request) -> Response<Body> {
    if request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok())
        != Some(bridge.authorization.as_str())
    {
        return Response::builder()
            .status(StatusCode::UNAUTHORIZED)
            .header(
                header::WWW_AUTHENTICATE,
                "Basic realm=\"Encrypted Spaces\", charset=\"UTF-8\"",
            )
            .body(Body::empty())
            .unwrap();
    }
    if protected_operation(&request) {
        return Response::builder()
            .status(StatusCode::FORBIDDEN)
            .body(Body::empty())
            .unwrap();
    }
    let mut response = bridge.handler.handle(request).await.map(Body::new);
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

fn protected_operation(request: &Request) -> bool {
    let Ok(source) = DavPath::new(request.uri().path()) else {
        return false;
    };
    let source = source.as_bytes();
    let source = source.strip_suffix(b"/").unwrap_or(source);
    match request.method().as_str() {
        "DELETE" => source.is_empty(),
        "MOVE" | "COPY" => {
            let destination = request
                .headers()
                .get("destination")
                .and_then(|h| h.to_str().ok())
                .and_then(|h| h.parse::<axum::http::Uri>().ok())
                .and_then(|uri| DavPath::new(uri.path()).ok());
            let Some(destination) = destination else {
                return false;
            };
            let destination = destination.as_bytes();
            let destination = destination.strip_suffix(b"/").unwrap_or(destination);
            // dav-server may recursively delete the destination before rename/copy.
            // Reject overlapping trees before it can delete the source or root.
            source.is_empty()
                || destination.is_empty()
                || descendant(source, destination)
                || descendant(destination, source)
        }
        _ => false,
    }
}

fn descendant(parent: &[u8], child: &[u8]) -> bool {
    child
        .strip_prefix(parent)
        .is_some_and(|suffix| suffix.starts_with(b"/"))
}
