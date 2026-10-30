# Encrypted Spaces in Nemo

A standalone Rust client exposes an encrypted file space over a loopback-only,
authenticated WebDAV endpoint. GVfs's existing DAV backend mounts it; Nemo
displays the mount in its sidebar without a Nemo extension or custom GVfs backend.
File contents are encrypted/decrypted by the SDK, and filenames and file metadata
are encrypted in the server's `entries` table. Entry IDs and parent IDs are public.

This uses the repository's experimental cryptography and placeholder backend
authentication. **Do not use it to protect sensitive data.**

## Requirements

Linux, the repository's Rust/build prerequisites, and a running desktop session
with Nemo, GIO and the GVfs DAV backend. On Mint/Ubuntu:

```sh
sudo apt install nemo gvfs-backends gvfs-fuse libglib2.0-bin
```

`gvfs-fuse` is optional: it provides compatibility for applications that do not
use GIO. Run the demo as your desktop user, not with `sudo`.
GVfs needs session D-Bus.

## Run

From the repository root, build the client and backend:

```sh
RISC0_SKIP_BUILD=1 cargo build -p encrypted-spaces-nemo -p encrypted-spaces-backend-server
```

Use a **separate backend** configured with this client's schema, not the Tauri
demo's schema:

```sh
RISC0_SKIP_BUILD=1 target/debug/encrypted-spaces-backend-server \
  --schema demos/nemo/app_schema.kdl \
  --space-root "$HOME/.local/share/encrypted-spaces-nemo/server"
```

Create a space once, then start the bridge and mount it in Nemo:

```sh
target/debug/encrypted-spaces-nemo init --server ws://127.0.0.1:8080/ws
target/debug/encrypted-spaces-nemo serve
```

`serve` starts the local WebDAV server, mounts it with GVfs, and waits for an
`quit` command in the same terminal.

Select the new WebDAV mount in Nemo's sidebar. You can also open it directly:

```sh
nemo "$(target/debug/encrypted-spaces-nemo uri)"
```

Use Nemo's bookmark command to retain a named "Encrypted Spaces" shortcut
between sessions. A bookmark does not start the bridge automatically.
Type `quit` in the bridge's terminal to unmount before stopping the server.
Ctrl+C, SIGTERM, and closed stdin also trigger cleanup. Fatal errors and unwinding
panics attempt to unmount before exiting; cleanup failures are reported. SIGKILL
and process aborts cannot run cleanup.

On subsequent runs, use `serve`, not `init`. The backend's
process must remain running, and the client's profile must be preserved.
**The reference backend currently keeps metadata/history in memory: restarting
it loses the space even with `--space-root`.** That flag preserves file blobs,
not the directory tree or cryptographic history. Client bridge restarts are
supported while the backend remains alive; durable backend recovery is separate
work.

`--profile PATH` selects a different client profile for any command. The default
is `$XDG_STATE_HOME/encrypted-spaces/nemo`, falling back to
`$HOME/.local/state/encrypted-spaces/nemo`. `init --port PORT` or `join --port PORT`
changes the bridge's port (default 9843). Each user/space needs its own profile,
and bridges running on the same machine need different ports.
Build both client and backend with `--features avl` if using AVL storage.
`RISC0_SKIP_BUILD=1` is for the repository's development proof mode. Set it
when running the backend as well as building: without it, the backend attempts
to generate a fast-forward proof after its first batch of writes, which requires
the skipped RISC Zero guest build.

## Existing servers and inviting users

To create a **new space on an existing server**, use
`init --server wss://your-server.example/ws`. The server must support the Nemo
schema (`demos/nemo/app_schema.kdl`); this does not connect to a Tauri space.
Use `wss://` with a system-trusted certificate for remote connections.
To reopen your own existing space, preserve its profile and run `serve`, not
`init`. A server URL alone does not grant access to someone else's space.

To invite another user while mounted, enter this command in the running `serve`
terminal (not a separate shell):

```text
invite --output ./bob-invite.json
```

The bridge stays mounted and uses its existing SDK session to create the invite
and save updated state. Paths containing spaces can be quoted, for example
`invite --output "./Bob invites/invite.json"`. Command and invitation errors are
reported without ending the terminal session; type `quit` to unmount and stop.

Alternatively, when `serve` is stopped, use the standalone command, leaving the
backend running:

```sh
target/debug/encrypted-spaces-nemo invite --output ./bob-invite.json
target/debug/encrypted-spaces-nemo serve
```

Send `bob-invite.json` securely to the new user. It includes the backend URL and
**secret provisional membership keys**; anyone holding it can attempt to join.
The file is created with mode 0600 and must not already exist. Generate a
separate invite for each user; do not share your own profile or snapshot.

On the new user's machine:

```sh
target/debug/encrypted-spaces-nemo join --invite ./bob-invite.json
target/debug/encrypted-spaces-nemo serve
```

`join` creates a new local profile, rotates the invited member's provisional
keys, and generates a separate local WebDAV password. It refuses to overwrite an
existing profile. `join --server wss://your-server.example/ws` overrides the URL
in the invite, for example when the inviter used a loopback address. Only accept
invites from trusted people and check the server URL before joining. Remove the
invite file after a successful join.

For two users on the same machine, add `--profile /path/to/bob-profile` to each
of Bob's commands and `--port 9844` to his `join` command. Refresh Nemo to see
other members' changes. Coordinate writes: inviting/joining enables membership
and shared access, but does not add cross-client namespace locking (see below).

## Storage and behavior

- Supports directory listing, reading, creating, replacing, copying, renaming,
  moving and deleting files/directories, plus HTTP ranges and WebDAV locks.
- Transfers are buffered in RAM, with a **64 MiB limit per file**. An aborted
  upload is not committed; a stale overlapping upload cannot replace a newer
  completed upload. There is no plaintext file-content staging directory.
- File bytes use `space.file().upload/download`; metadata uses verified SDK
  table operations. Directory moves change a parent reference, not every child.
- A private profile contains `snapshot.json` (SDK keys/state) and
  `connection.json` (backend URL and generated local WebDAV password). The
  directory is mode 0700 and files are 0600. These files are **not encrypted at
  rest** and SDK state can include decrypted metadata. Protect and back up the
  profile. One bridge at a time may use a profile.
- State is atomically saved after each completed mutation. The bridge only
  listens on `127.0.0.1`, requires its generated password, and supplies credentials
  to GIO through stdin rather than exposing them in command arguments or URIs.
  Use `wss://` for a remote backend with a system-trusted certificate.

## Prototype limitations

There is no custom `encrypted-spaces://` URI scheme, automatic startup,
graphical invite/join UI, offline mode, symlinks, Unix permission model, trash, or remote
change notifications. Refresh Nemo to see changes. The implementation is intended
for one standalone writer, not concurrently mounted clients sharing a space:
namespace uniqueness is checked client-side, not enforced across clients.

Recursive copy/delete and overwrite moves involve multiple SDK operations and
are not transactional; failure can leave partial results. Deleting a file removes
its reference, not an assurance of secure blob erasure or retention-key deletion.
Nemo, GVfs, editors, thumbnailers and other applications can cache plaintext
locally; this bridge does not control those caches.

## Development

```sh
RISC0_SKIP_BUILD=1 cargo test -p encrypted-spaces-nemo
cargo fmt -p encrypted-spaces-nemo --check
RISC0_SKIP_BUILD=1 cargo clippy -p encrypted-spaces-nemo --all-targets --no-deps
```

The integration suite exercises authenticated WebDAV against an in-process
encrypted-spaces backend, including persistence/restoration, Unicode names,
range reads, conditional overwrites, directory moves/copies/deletes, interrupted
transfers, concurrent upload conflicts, protected root operations and profile
locking, plus private invite export, joined-user restoration, onward invitations,
and file access by different members. CLI tests use a fake GIO executable to check
mount/unmount ordering and cleanup after quit, interruption, errors and panics.
Terminal tests also create invitations through the live filesystem session and
check joined-user access, quoted paths, command errors and invitation failures.
A desktop installation is still needed to exercise Nemo itself.
