use crate::{diagnostics, Client, Connection};
use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use encrypted_spaces_sdk::file::{FileProgress, FileProgressCallback, FileStage};
use jni::{
    objects::{JClass, JObject, JString, JValue},
    sys::jstring,
    JNIEnv,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    sync::{Mutex, OnceLock},
    time::Duration,
};

#[derive(Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
enum Request {
    Join {
        invitation: Value,
        address: String,
        certificates: Vec<String>,
    },
    Restore {
        connection: Connection,
        address: String,
        certificates: Vec<String>,
    },
    List {
        parent_id: i64,
    },
    Read {
        id: i64,
    },
    Stat {
        id: i64,
    },
    Create {
        parent_id: i64,
        name: String,
        mime_type: String,
    },
    Write {
        id: i64,
        base64: String,
        expected_hash: String,
    },
    Rename {
        id: i64,
        name: String,
    },
    Delete {
        id: i64,
    },
    SetUserName {
        user_name: String,
    },
    Snapshot,
    Disconnect,
}

static RUNTIME: OnceLock<std::result::Result<tokio::runtime::Runtime, std::io::Error>> =
    OnceLock::new();
static CLIENT: Mutex<Option<Client>> = Mutex::new(None);

fn execute(request: Request, progress: &FileProgressCallback<'_>) -> Result<Value> {
    let runtime = RUNTIME
        .get_or_init(tokio::runtime::Runtime::new)
        .as_ref()
        .context("Cannot start SDK runtime")?;
    let mut client = CLIENT
        .lock()
        .map_err(|_| anyhow::anyhow!("SDK state lock poisoned"))?;
    runtime.block_on(async {
        tokio::time::timeout(Duration::from_secs(60), async {
            match request {
                Request::Join {
                    invitation,
                    address,
                    certificates,
                } => {
                    *client = None;
                    let new_client =
                        Client::join(invitation, &address, &certificates, cfg!(debug_assertions))
                            .await?;
                    let snapshot = new_client.snapshot().await?;
                    *client = Some(new_client);
                    Ok(serde_json::to_value(snapshot)?)
                }
                Request::Restore {
                    connection,
                    address,
                    certificates,
                } => {
                    // A failed replacement must not leave the previous account active.
                    *client = None;
                    let new_client = Client::restore(
                        connection,
                        &address,
                        &certificates,
                        cfg!(debug_assertions),
                    )
                    .await?;
                    let snapshot = new_client.snapshot().await?;
                    *client = Some(new_client);
                    Ok(serde_json::to_value(snapshot)?)
                }
                Request::Disconnect => {
                    *client = None;
                    Ok(Value::Null)
                }
                request => {
                    let client = client.as_mut().ok_or_else(|| {
                        encrypted_spaces_sdk::SdkErrorType::TransportError(
                            "Connect to a space first".into(),
                        )
                    })?;
                    match request {
                        Request::List { parent_id } => {
                            Ok(serde_json::to_value(client.list(parent_id).await?)?)
                        }
                        Request::Read { id } => {
                            let (inode, bytes) =
                                client.read_version_with_progress(id, progress).await?;
                            Ok(json!({"base64": STANDARD.encode(bytes), "inode": inode}))
                        }
                        Request::Stat { id } => Ok(serde_json::to_value(client.stat(id).await?)?),
                        Request::Create {
                            parent_id,
                            name,
                            mime_type,
                        } => Ok(serde_json::to_value(
                            client.create(parent_id, &name, &mime_type).await?,
                        )?),
                        Request::Write {
                            id,
                            base64,
                            expected_hash,
                        } => {
                            anyhow::ensure!(
                                base64.len() <= (crate::MAX_READ_BYTES as usize).div_ceil(3) * 4,
                                "Files are limited to 32 MiB"
                            );
                            let bytes = STANDARD.decode(base64).context("Invalid file encoding")?;
                            Ok(serde_json::to_value(
                                client.write(id, bytes, &expected_hash).await?,
                            )?)
                        }
                        Request::Rename { id, name } => {
                            Ok(serde_json::to_value(client.rename(id, &name).await?)?)
                        }
                        Request::Delete { id } => {
                            client.delete(id).await?;
                            Ok(Value::Null)
                        }
                        Request::Snapshot => Ok(serde_json::to_value(client.snapshot().await?)?),
                        Request::SetUserName { user_name } => {
                            client.set_user_name(&user_name).await?;
                            Ok(serde_json::to_value(client.snapshot().await?)?)
                        }
                        _ => unreachable!(),
                    }
                }
            }
        })
        .await
        .context("Remote operation timed out; changes may already have been applied")?
    })
}

#[no_mangle]
pub extern "system" fn Java_org_encryptedspaces_android_NativeBridge_request(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    handle_request(env, request, None)
}

#[no_mangle]
pub extern "system" fn Java_org_encryptedspaces_android_NativeBridge_requestWithProgress(
    env: JNIEnv,
    _class: JClass,
    request: JString,
    listener: JObject,
) -> jstring {
    handle_request(env, request, Some(listener))
}

fn handle_request(mut env: JNIEnv, request: JString, listener: Option<JObject>) -> jstring {
    diagnostics::initialize();
    let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value> {
        let request: String = env.get_string(&request)?.into();
        let vm = env.get_java_vm()?;
        let listener = listener
            .map(|value| env.new_global_ref(value))
            .transpose()?;
        let progress = |event: FileProgress| {
            let Some(listener) = &listener else {
                return Ok(());
            };
            let notify = || -> Result<()> {
                let mut env = vm.attach_current_thread()?;
                let stage = env.new_string(match event.stage {
                    FileStage::Preparing => "preparing",
                    FileStage::Downloading => "downloading",
                    FileStage::Verifying => "verifying",
                    FileStage::RetrievingKey => "retrieving_key",
                    FileStage::Decrypting => "decrypting",
                })?;
                let result = env.call_method(
                    listener.as_obj(),
                    "onProgress",
                    "(Ljava/lang/String;JJZ)V",
                    &[
                        JValue::Object(stage.as_ref()),
                        JValue::Long(event.processed.try_into()?),
                        JValue::Long(event.total.map(i64::try_from).transpose()?.unwrap_or(-1)),
                        JValue::Bool(event.complete.into()),
                    ],
                );
                if matches!(result, Err(jni::errors::Error::JavaException)) {
                    env.exception_describe()?;
                    env.exception_clear()?;
                }
                result.context("Download progress listener failed")?;
                Ok(())
            };
            notify().map_err(|error| {
                encrypted_spaces_sdk::SdkErrorType::ValidationError(format!(
                    "Cannot report download progress: {error:#}"
                ))
            })
        };
        let request = serde_json::from_str(&request)?;
        if matches!(request, Request::Read { .. }) {
            progress(FileProgress {
                stage: FileStage::Preparing,
                processed: 0,
                total: None,
                complete: false,
            })?;
        }
        execute(request, &progress)
    }));
    let envelope = match response {
        Ok(Ok(data)) => json!({"ok": true, "data": data}),
        Ok(Err(error)) => {
            diagnostics::log_failure("SDK request failed", &error);
            json!({
                "ok": false,
                "error": format!("{error:#}"),
                "transport_failure": crate::is_transport_failure(&error),
            })
        }
        Err(_) => json!({"ok": false, "error": "SDK panicked; restart the app before retrying"}),
    };
    match env.new_string(envelope.to_string()) {
        Ok(value) => value.into_raw(),
        Err(error) => {
            let error = anyhow::Error::new(error);
            diagnostics::log_failure("JNI response failed", &error);
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("JNI response failed: {error}"),
            );
            std::ptr::null_mut()
        }
    }
}
