const LOG_CHUNK_BYTES: usize = 3000;

fn log_chunks(message: &str, mut write: impl FnMut(&str)) {
    for line in message.split('\n') {
        let mut remaining = line;
        while remaining.len() > LOG_CHUNK_BYTES {
            let mut end = LOG_CHUNK_BYTES;
            while !remaining.is_char_boundary(end) {
                end -= 1;
            }
            write(&remaining[..end]);
            remaining = &remaining[end..];
        }
        write(remaining);
    }
}

#[cfg(target_os = "android")]
pub fn log_error(message: &str) {
    log_message(6, message);
}

fn connection_message(operation: &str, address: &str) -> anyhow::Result<String> {
    use anyhow::Context;

    let mut endpoint = url::Url::parse(address).context("Invalid WebSocket address")?;
    endpoint.set_query(None);
    endpoint.set_fragment(None);
    endpoint
        .set_username("")
        .map_err(|_| anyhow::anyhow!("WebSocket address cannot contain credentials"))?;
    endpoint
        .set_password(None)
        .map_err(|_| anyhow::anyhow!("WebSocket address cannot contain credentials"))?;
    Ok(format!(
        "WebSocket {operation} attempt: endpoint={:?}, host={:?}, port={:?}",
        endpoint.as_str(),
        endpoint.host_str(),
        endpoint.port_or_known_default()
    ))
}

#[cfg(target_os = "android")]
pub fn log_connection(operation: &str, address: &str) -> anyhow::Result<()> {
    log_message(4, &connection_message(operation, address)?);
    Ok(())
}

#[cfg(target_os = "android")]
fn log_message(priority: std::ffi::c_int, message: &str) {
    use std::ffi::{c_char, c_int, CString};

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    log_chunks(&message.replace('\0', "\\0"), |chunk| {
        let text = CString::new(chunk).expect("Log message contains no NUL");
        // Android copies these NUL-terminated strings before returning.
        unsafe {
            __android_log_write(priority, c"SpacesNative".as_ptr(), text.as_ptr());
        }
    });
}

#[cfg(target_os = "android")]
pub fn initialize() {
    use std::{backtrace::Backtrace, sync::Once};

    static INITIALIZE: Once = Once::new();
    INITIALIZE.call_once(|| {
        let previous_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            // Capture before catch_unwind discards the original panic stack.
            log_error(&format!(
                "SDK panic: {info}\n{:#}",
                Backtrace::force_capture()
            ));
            previous_hook(info);
        }));
    });
}

#[cfg(target_os = "android")]
pub fn log_failure(operation: &str, error: &anyhow::Error) {
    use std::backtrace::{Backtrace, BacktraceStatus};

    let mut message = format!("{operation}: {error}");
    for (index, cause) in error.chain().skip(1).enumerate() {
        message.push_str(&format!("\nCaused by: [{index}] {cause}"));
    }
    if error.backtrace().status() == BacktraceStatus::Captured {
        message.push_str(&format!("\n\nStack backtrace:\n{:#}", error.backtrace()));
    } else {
        message.push_str(&format!(
            "\nError backtrace unavailable; JNI reporting stack:\n{:#}",
            Backtrace::force_capture()
        ));
    }
    log_error(&message);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connection_logs_include_endpoint_and_effective_port_without_secrets() {
        let message = connection_message(
            "join",
            "wss://user:password@storage.example/ws?auth=secret#private",
        )
        .unwrap();
        assert!(message.contains("WebSocket join attempt"));
        assert!(message.contains("endpoint=\"wss://storage.example/ws\""));
        assert!(message.contains("host=Some(\"storage.example\")"));
        assert!(message.contains("port=Some(443)"));
        for secret in ["user", "password", "auth", "secret", "private"] {
            assert!(!message.contains(secret));
        }
        let message = connection_message("restore", "ws://[::1]:18082/ws").unwrap();
        assert!(message.contains("WebSocket restore attempt"));
        assert!(message.contains("ws://[::1]:18082/ws"));
        assert!(message.contains("port=Some(18082)"));
        assert!(connection_message("join", "not a URL").is_err());
    }

    #[test]
    fn preserves_every_line_and_long_utf8_messages() {
        let long_line = format!("a{}", "\u{20ac}".repeat(4000));
        let message = format!("outer error\ninner error\n{long_line}\n");
        let mut chunks = Vec::new();
        log_chunks(&message, |chunk| chunks.push(chunk.to_owned()));
        assert_eq!(chunks[0], "outer error");
        assert_eq!(chunks[1], "inner error");
        assert!(chunks.iter().all(|chunk| chunk.len() <= LOG_CHUNK_BYTES));
        assert_eq!(chunks[2..chunks.len() - 1].concat(), long_line);
        assert_eq!(chunks.last().unwrap(), "");
    }

    #[test]
    fn preserves_short_and_empty_messages() {
        for message in ["", "error", "a\n\nb"] {
            let mut chunks = Vec::new();
            log_chunks(message, |chunk| chunks.push(chunk.to_owned()));
            assert_eq!(chunks.join("\n"), message);
        }
    }
}
