use crate::{diagnostics, Client, Connection};
use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use jni::{
    objects::{JClass, JString},
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
    SetUserName {
        user_name: String,
    },
    Snapshot,
    Disconnect,
}

static RUNTIME: OnceLock<std::result::Result<tokio::runtime::Runtime, std::io::Error>> =
    OnceLock::new();
static CLIENT: Mutex<Option<Client>> = Mutex::new(None);

fn execute(request: Request) -> Result<Value> {
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
                    let client = client.as_mut().context("Connect to a space first")?;
                    match request {
                        Request::List { parent_id } => {
                            Ok(serde_json::to_value(client.list(parent_id).await?)?)
                        }
                        Request::Read { id } => {
                            let bytes = client.read(id).await?;
                            Ok(json!({"base64": STANDARD.encode(bytes)}))
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
        .context("Remote operation timed out; reconnect and retry")?
    })
}

#[no_mangle]
pub extern "system" fn Java_org_encryptedspaces_android_NativeBridge_request(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    diagnostics::initialize();
    let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value> {
        let request: String = env.get_string(&request)?.into();
        execute(serde_json::from_str(&request)?)
    }));
    let envelope = match response {
        Ok(Ok(data)) => json!({"ok": true, "data": data}),
        Ok(Err(error)) => {
            diagnostics::log_failure("SDK request failed", &error);
            json!({"ok": false, "error": format!("{error:#}")})
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
