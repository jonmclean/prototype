# Android file client

A native Kotlin Android client with a Rust JNI bridge to `encrypted-spaces-sdk`.
It connects to an existing Encrypted Spaces server, lists the desktop demo's
`inodes` table, browses folders, and reads/decrypts files **in memory only**.
There is no embedded server, file upload/edit/delete API, or prover.

**Research demo, not production software.** The repository's authentication and
default fake fast-forward-proof limitations still apply. The SDK verifies table
proofs and the ciphertext content hash before decrypting a file.

## Build on Linux

Install Rust/rustup, Perl, unzip, and JDK 17 or 21. Install these pinned Android
tools (or use equivalent existing installations). No separate Gradle installation
is needed: the checked-in wrapper downloads and caches Gradle 8.11.1 automatically.

```bash
cd demos/android
mkdir -p .tools/android-sdk/cmdline-tools
curl -fL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
  -o .tools/commandlinetools.zip
unzip -q .tools/commandlinetools.zip -d .tools/android-sdk/cmdline-tools
mv .tools/android-sdk/cmdline-tools/cmdline-tools .tools/android-sdk/cmdline-tools/latest
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME="$PWD/.tools/android-sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018"
./build.sh debug arm64-v8a
```

`build.sh` compiles the SDK/JNI library using the NDK and then packages the APK
with `./gradlew`. OpenSSL is vendored for Android; it needs no device-side OpenSSL
installation. First builds download Gradle, Rust, and Maven dependencies and can
take several minutes. All local tool downloads/builds are ignored by Git.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Minimum Android: 8.0 (API 26).

For an x86-64 emulator, run `./build.sh debug x86_64`. Each APK includes only
the selected ABI, and debug/release native libraries are stored separately
so a release cannot accidentally package a debug SDK.
For release, run `./build.sh release arm64-v8a`. The release APK is unsigned;
configure your own signing before distributing it. Release mode rejects `ws://`.

Override `ANDROID_HOME` or `ANDROID_NDK_HOME` to use installed Android tools.
`build.sh` defaults `GRADLE_USER_HOME` to `.tools/gradle-home`; override it to use
a different Gradle cache. The script currently supports Linux x86-64 build hosts.
Rust output is isolated in the repository's `target/android`.

```bash
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

## Connect to remote storage

1. In the desktop demo, create/join a space on the remote server and upload files
   through its Files screen. Wait for the desktop's periodic snapshot save.
1. Transfer its `chat_snapshot.json` to a location the Android document picker can
   read, using a trusted channel. The default desktop snapshot is under the
   app-data directory for `com.encrypted-spaces.demo` (see the desktop Tauri
   configuration for the identifier in your build).
1. Open Spaces Files. Enter the server endpoint, for example
   `wss://storage.example.org:8443/ws`, and tap **Import snapshot**. If the endpoint
   field is empty, the address in the snapshot is used.
1. Tap folders to browse; **Up** returns to the parent and **Refresh** synchronizes
   with the server. Tap a file to read it. Text/JSON/XML and images have simple
   previews. Other file types are read in memory and show a byte-count message.
1. **Reconnect** restores the privately saved connection after restarting the app.
   **Forget space** removes that connection state.

Snapshots contain private identity/key material and pin the schema, initial
commitment, and FF image ID used by that deployment. Import only trusted
snapshots. This first version restores the **same member identity** as the
desktop; it does not consume invitation JSON or create a new member. For a
separate phone identity, first join a separate desktop instance using an invite,
then transfer that instance's snapshot. Avoid concurrently writing with a
cloned identity. Android does not write user profiles/channels or modify files.

Delete the transferred snapshot from public/shared storage after import.
Only the connection/cryptographic snapshot is persisted in app-private,
no-backup storage; the SDK's transient table cache is not serialized.

### No decrypted files on disk

Reading returns bytes from the SDK to JNI/Kotlin memory. There is **no plaintext
file cache, export/save action, share action, or clipboard-copy action**.
Screenshots and recent-task captures are blocked with `FLAG_SECURE`. Byte buffers
are cleared when closing the preview, but managed/native allocators can retain
transient copies in RAM; this is not a guarantee of forensic memory erasure.

The initial demo limits reads to 32 MiB based on verified file metadata and
checks the decrypted length against that metadata. Text previews show the first
256 KiB, and images are downsampled to at most 2048 pixels on either axis.
The SDK currently buffers a whole ciphertext response, so the size limit is not
a hardened defense against an untrusted oversized HTTP response.

### TLS and local testing

Kotlin loads Android's `AndroidCAStore` and supplies its roots to vendored
OpenSSL. The **same connector** verifies both the WebSocket and HTTPS blob
requests. Certificate chains and hostnames are always checked.

For a self-signed development server, tap **Trust certificate** and import its
PEM/DER public certificate, then connect/reconnect. Additional trust certificates
are session-only and must be imported again after restarting. Never import its
private key. Its SAN must match the hostname/IP you enter.

Debug builds allow `ws://` for an isolated test server. From the standard Android
emulator, use `ws://10.0.2.2:8080/ws` to reach the host's localhost server.
On a physical device, use a reachable LAN address or `adb reverse tcp:8080
tcp:8080` and `ws://127.0.0.1:8080/ws`. The server runs **outside** the phone:

```bash
# From repository root
RISC0_SKIP_BUILD=1 cargo run -p encrypted-spaces-backend-server -- \
  --schema demos/tauri/app_schema.kdl
```

## Checks

```bash
# Repository root; host-side core checks, not a device/integration test
RISC0_SKIP_BUILD=1 cargo test -p encrypted-spaces-android --locked
uv run --with textual==8.2.8 python -m unittest discover \
  -s demos/tauri -p test_demo_launcher.py

# Real existing server; listing + in-memory file read, no plaintext output file
RISC0_SKIP_BUILD=1 cargo run -p encrypted-spaces-android --example remote_files -- \
  /path/to/chat_snapshot.json wss://storage.example.org/ws 123 /path/to/public-cert.pem

# Android JNI instrumentation checks, after building the x86_64 debug library
cd demos/android
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME="$PWD/.tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
./gradlew --no-daemon -PspacesAbi=x86_64 connectedDebugAndroidTest
```

The certificate and inode arguments to `remote_files` are optional. This helper
prints metadata and the decrypted byte count, never file contents or keys.
APK compilation, emulator JNI checks, and integration against the user's actual
remote storage are separate checks; the latter requires a reachable endpoint
and a valid deployment snapshot.

### Reproducible loopback protocol checks

`seed_remote` is a **host-only** test fixture. It creates a new test space using
the desktop KDL schema, uploads a small test file, and checks root/nested listing,
in-memory reads, folder rejection, and restore through the real WebSocket/HTTP
protocol. Its output file contains connection state, **not decrypted files**.
Host test/example dependencies can include the reference server and guest
methods; none of these are regular Android runtime dependencies.

```bash
# Terminal 1, repository root: use an isolated test storage directory
RISC0_SKIP_BUILD=1 cargo run -p encrypted-spaces-backend-server -- \
  --schema demos/tauri/app_schema.kdl --port 18082 \
  --space-root demos/android/.tools/test-spaces

# Terminal 2, repository root
RISC0_SKIP_BUILD=1 cargo run -p encrypted-spaces-android --example seed_remote -- \
  ws://127.0.0.1:18082/ws demos/android/.tools/test-connection.json
demos/android/.tools/android-sdk/platform-tools/adb push \
  demos/android/.tools/test-connection.json /data/local/tmp/android-connection.json

cd demos/android
./gradlew --no-daemon -PspacesAbi=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.remoteSnapshot=/data/local/tmp/android-connection.json \
  connectedDebugAndroidTest
```

The remote instrumentation test is explicitly skipped if `remoteSnapshot` is
not supplied. It compares app-private files before and after SDK reads, and
the UI preview test also checks that previews create no local files and that
screenshots are blocked. To exercise TLS, start a separate TLS server, pass a
public PEM/DER certificate as the optional final argument to `seed_remote`,
and supply `remoteAddress=wss://10.0.2.2:PORT/ws` and
`remoteTrustCert=/data/local/tmp/public-cert.pem` runner arguments. Its SAN must
include `10.0.2.2`. These are local integration checks, not evidence of access
to any particular remote deployment.
