# Android file client

A native Kotlin Android client with a Rust JNI bridge to `encrypted-spaces-sdk`.
It joins an existing Encrypted Spaces server using an invitation, lists the desktop demo's
`inodes` table, browses folders, and reads/decrypts files **in memory only**.
There is no embedded server, file upload/edit/delete API, or prover.

**Research demo, not production software.** The repository's authentication and
default fake fast-forward-proof limitations still apply. The SDK verifies table
proofs and the ciphertext content hash before decrypting a file.

## Build on Linux

Install Rust/rustup, Perl, unzip, and a full JDK 17 or newer (21 or 25 recommended).
`build.sh` detects the JDK from `javac` on `PATH`, resolving symlinks, and exports
`JAVA_HOME` for Gradle. A valid `JAVA_HOME` takes precedence; an invalid path is
reported and falls back to detection. Install these pinned Android
tools (or use equivalent existing installations). No separate Gradle installation
is needed: the checked-in wrapper downloads and caches Gradle 9.8.2 automatically.
The build uses Android Gradle plugin 9.5.0 with built-in Kotlin support.
Native builds use NDK r30 LTS (`30.0.16248370`).
It compiles against Android 17 SDK 37.2 and targets API 37; the minimum supported
version remains Android 8.0 (API 26).

```bash
cd demos/android
mkdir -p .tools/android-sdk/cmdline-tools
curl -fL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
  -o .tools/commandlinetools.zip
unzip -q .tools/commandlinetools.zip -d .tools/android-sdk/cmdline-tools
mv .tools/android-sdk/cmdline-tools/cmdline-tools .tools/android-sdk/cmdline-tools/latest
export ANDROID_HOME="$PWD/.tools/android-sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-37.2" "build-tools;37.0.0" "ndk;30.0.16248370"
./build.sh debug arm64-v8a
```

Invitation joins embed the desktop KDL schema, its initial commitment, and the
trusted fast-forward guest image ID using the same build-time codegen as the
desktop demo. Codegen runs on the build host; no server or prover is bundled
in the APK. Install the RISC Zero build prerequisites used by the desktop demo
to compile its guest, or set `ENCRYPTED_SPACES_FF_IMAGE_ID_FILE` to a trusted
image-ID file from the server deployment and use `RISC0_SKIP_BUILD=1` to skip
the local guest build. Skipping without that file produces stub trust anchors,
which are suitable for compilation checks but not joining a working deployment.
The local server needs its actual guest ELF and RISC Zero executor even when
using development-mode proofs. Never obtain a trust anchor from the invitation
or an untrusted server.

`build.sh` compiles the SDK/JNI library using the NDK and then packages the APK
with `./gradlew`. OpenSSL is vendored for Android; it needs no device-side OpenSSL
installation. First builds download Gradle, Rust, and Maven dependencies and can
take several minutes. All local tool downloads/builds are ignored by Git.

Android Studio and direct `./gradlew` builds do **not** rebuild the Rust library;
they only check that a library exists for the selected ABI (ARM64 by default).
After updating Rust/SDK code, rerun `./build.sh debug arm64-v8a` (or `x86_64`
for that ABI) before installing. An `unknown variant 'join'` error means the APK
contains the old snapshot-only JNI library, not that the invitation is invalid.

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
   through its Files screen. Export a **new invitation** for the Android member.
1. Transfer the invitation JSON to a location the Android document picker can
   read, using a trusted channel.
1. Open Spaces Files. Enter the server endpoint, for example
   `wss://storage.example.org:8443/ws`, and tap **Import invitation**. The server
   address is required; the SDK invitation does not contain an endpoint.
1. After selecting the invitation, confirm the **Connect to space as** dialog.
   The user name defaults to the phone's configured device name (or its model
   if no name is configured). Edit it to choose the display name other members
   will see. Empty names are not accepted; cancelling does not start a connection.
1. Browse the file explorer's folder-first list, with file icons, types, and sizes.
   Tap a folder to open it, use the breadcrumb to jump to an ancestor, or tap **Up**
   to return to the parent. **Refresh** synchronizes with the server.
1. Tap a file for a full-screen, read-only preview. Text/JSON/XML files use a
   monospace reader. Markdown (`.md`, `.markdown`, `.mdown`, or Markdown MIME type)
   is rendered with headings, lists, emphasis, and code blocks; **View source**
   switches to the original text. Filename detection also recognizes text files
   when their MIME type is missing or generic. Images have a downsampled preview.
   Other file types show a byte-count message. Links are not interactive and
   remote images are not fetched.
1. **Reconnect** restores the privately saved connection after restarting the app.
   It also prompts for a name, allowing the existing member's display name to change.
   **Forget space** removes that connection state.

Connection settings collapse after joining so the file list has more room.
Tap **Connection** to reopen the server, invitation, certificate, and reconnect controls.

The phone joins as the **invited member**, not as the desktop member. The SDK
rotates the invitation's provisional keys to fresh permanent keys during join,
so a consumed invitation cannot be used to join again. Invitations contain secret
bootstrap credentials: import only trusted invitations and delete the transferred
file from public/shared storage after joining. Use a fresh invitation after
**Forget space** or clearing app data.

**Desktop snapshots (`chat_snapshot.json`) cannot be imported.** Reconnect reads
only this app's privately saved `invited-connection.json` in no-backup storage.
Legacy `connection.json` files from the snapshot-import version are not restored;
join again with a fresh invitation. The SDK's transient table cache is not
serialized. Android publishes the chosen display name to the connected member's
`users_meta` row, but does not create channels or modify files. The invitation
still determines the member identity; entering a name does not select another
account. Joining updates the invited member's authentication/key state on the
server. New credentials are saved before publishing the name, so a failed name
update can be retried with **Reconnect** without reusing a consumed invitation.

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
On Android 17 and later, the app requests nearby-device (`ACCESS_LOCAL_NETWORK`)
permission before network operations, including import/reconnect and file reads.
Allow it for LAN or emulator-host storage. If denied, the app explains how to
enable it in system settings and offers an explicit internet-only attempt;
public internet storage does not require this permission. Older Android versions
do not show this permission prompt.
On a physical device, use a reachable LAN address or `adb reverse tcp:8080
tcp:8080` and `ws://127.0.0.1:8080/ws`. The server runs **outside** the phone:

```bash
# From repository root
cargo run -p encrypted-spaces-backend-server -- \
  --schema demos/tauri/app_schema.kdl
```

## Error diagnostics

All exceptions handled by the Kotlin UI are logged at error priority under the
`SpacesAndroid` tag, including their stack traces, causes, and suppressed exceptions.
This covers startup state loading, document/certificate imports, worker operations,
and file previews. Long messages are split to avoid Logcat truncation, and DNS
exceptions are included. Uncaught Java/Kotlin exceptions retain Android's standard
`AndroidRuntime` crash logging. Native failures also appear in the UI log when their
JNI error response is handled; the original Rust trace remains under `SpacesNative`.

SDK/JNI failures are logged at error priority under the `SpacesNative` tag,
including the complete error cause chain and Rust backtrace. Backtrace capture
is enabled before the native library is loaded. Panics are logged before stack
unwinding, with their message, source location, and stack. Long diagnostics are
split across log entries to avoid Logcat's per-entry truncation. The UI error
message and JNI response format are unchanged.

Join and reconnect attempts log the configured WebSocket endpoint (scheme,
host, effective port, and path) at info priority under the same tag. Credentials,
query parameters, and fragments are excluded. This is the configured destination,
not the DNS-resolved IP address.

```bash
adb logcat 'SpacesNative:I' 'SpacesAndroid:E' 'AndroidRuntime:E' '*:S'
```

Rebuild and reinstall the APK after changing native code or packaging settings.
Debug APKs retain native DWARF symbols and extract the library during installation
so Rust can resolve function names and source file/line locations directly in
Logcat. These debug APKs and their installed libraries are substantially larger.
Release APKs remain stripped, so their traces may contain addresses rather than
source lines. Logs may include sensitive details from underlying errors; review them
before sharing. Request bodies, invitations, and decrypted files are not
explicitly logged.

## Checks

```bash
# Repository root; build-script JDK detection, no Android/Rust build required
python3 -m unittest discover -s demos/android -p test_build.py

# Repository root; host-side core checks, not a device/integration test
RISC0_SKIP_BUILD=1 cargo test -p encrypted-spaces-android --locked
uv run --with textual==8.2.8 python -m unittest discover \
  -s demos/tauri -p test_demo_launcher.py

# Real existing server; listing + in-memory file read, no plaintext output file
cargo run -p encrypted-spaces-android --example remote_files -- \
  /path/to/fresh-invitation.json wss://storage.example.org/ws 123 /path/to/public-cert.pem

# Android JNI instrumentation checks, after building the x86_64 debug library
cd demos/android
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME="$PWD/.tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
./gradlew --no-daemon -PspacesAbi=x86_64 connectedDebugAndroidTest
```

The certificate and inode arguments to `remote_files` are optional. This helper
consumes the supplied invitation for a disposable test member; it does not save
reconnect state. Use a different fresh invitation for the phone. It prints
metadata and the decrypted byte count, never file contents or keys.
APK compilation, emulator JNI checks, and integration against the user's actual
remote storage are separate checks; the latter requires a reachable endpoint
and a fresh invitation for that deployment.

### Reproducible loopback protocol checks

`seed_remote` is a **host-only** test fixture. It creates a new test space using
the desktop KDL schema, uploads a small test file, and checks root/nested listing,
invitation join/reuse rejection, in-memory reads, folder rejection, and private
state restore through the real WebSocket/HTTP protocol. Its output file contains
a separate fresh invitation for the emulator, **not decrypted files**.
Host test/example dependencies can include the reference server and guest
methods; none of these are regular Android runtime dependencies.

```bash
# Terminal 1, repository root: use an isolated test storage directory
cargo run -p encrypted-spaces-backend-server -- \
  --schema demos/tauri/app_schema.kdl --port 18082 \
  --space-root demos/android/.tools/test-spaces

# Terminal 2, repository root
cargo run -p encrypted-spaces-android --example seed_remote -- \
  ws://127.0.0.1:18082/ws demos/android/.tools/test-invitation.json
demos/android/.tools/android-sdk/platform-tools/adb push \
  demos/android/.tools/test-invitation.json /data/local/tmp/android-invitation.json

cd demos/android
./gradlew --no-daemon -PspacesAbi=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.remoteInvitation=/data/local/tmp/android-invitation.json \
  connectedDebugAndroidTest
```

The remote instrumentation test is explicitly skipped if `remoteInvitation` is
not supplied. Generate a fresh invitation with `seed_remote` before each run.
It joins through the invitation document-import flow, reconnects using the
resulting private connection state, and compares app-private files before and
after SDK reads. The UI preview test also checks that previews create no local files and that
screenshots are blocked. To exercise TLS, start a separate TLS server, pass a
public PEM/DER certificate as the optional final argument to `seed_remote`,
and supply `remoteAddress=wss://10.0.2.2:PORT/ws` and
`remoteTrustCert=/data/local/tmp/public-cert.pem` runner arguments. Its SAN must
include `10.0.2.2`. These are local integration checks, not evidence of access
to any particular remote deployment.
