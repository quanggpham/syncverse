# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Full build incl. all tests (unit + integration) — produces both JARs
mvn clean verify

# Build only (skip integration tests)
mvn clean package

# Unit tests only (surefire, *Test.java)
mvn test -pl syncverse-client
mvn test -pl syncverse-client -Dtest=CliArgumentsTest   # single unit test

# Integration tests (failsafe, *IT.java — run during the verify phase)
mvn verify -pl syncverse-server -Dit.test=LiveSyncIT
```

Note: the server and client JARs are renamed via `<finalName>` to `server.jar` / `client.jar` (not the `1.0.0` artifact names).

Run (see `docs/RUN_AND_DEMO.md` for a full Vietnamese walkthrough):

```bash
# Server (Terminal 1)
$env:SYNCVERSE_DATA_DIR = "$PWD\runtime-data"   # H2 persistence location
java -jar syncverse-server/target/server.jar AlphaServer

# Client (Terminals 2+)
$env:SYNCVERSE_SERVER_URL = "http://localhost:8080"
java -jar syncverse-client/target/client.jar Alice_Node ./workspace_alice
```

Environment variables: `SYNCVERSE_DATA_DIR` (H2 file location, default `./syncverse-data`), `SYNCVERSE_SERVER_URL` (client; must be absolute http/https), `SERVER_PORT` (server override).

## Architecture

A zero-config HTTP-based file sync system: a central server plus lightweight client daemons. **Clients never listen on ports** — all communication is outbound HTTP, so the server has no way to reach clients; delivery relies on client polling.

### Modules

- **`syncverse-common`** — protocol contracts only: `MessageType` (HELLO/HEARTBEAT/FILE_CHANGE/RECONNECT/DELTA_REQUEST), `FileOperation`, `ChangeOutcome`, and the DTO records (`FileChangeRequest`, `DeltaResponse`, `FileRevision`, etc.). No Spring, no filesystem, no DB code — client and server both depend on it, they never depend on each other.
- **`syncverse-server`** — Spring Boot 4.1 + H2 (file mode) + Spring JDBC with explicit SQL repositories. Packages: `api/` (controllers + exception mapping), `session/` (in-memory sessions), `sync/` (mutation rules), `delta/` (long-polling), `persistence/` (JdbcTemplate-style), `config/` (`SyncProperties` bound to `syncverse.*`).
- **`syncverse-client`** — plain Java 17, no Spring, no web server. Shaded executable JAR. Packages: `cli/`, `http/` (typed `ServerApiClient` over JDK `HttpClient`), `fs/` (`DirectoryWatcher` via `WatchService`, `DirectoryScanner`), `state/` (persisted client state), `sync/` (orchestration).

### Sync model (the part that requires reading multiple files to understand)

- **Append-only versioned change log.** Every accepted mutation appends a `change_log` row with a monotonically increasing `global_version`. Clients track a cursor (`lastSeenGlobalVersion`) and pull deltas `since` their cursor from `GET /api/deltas`. `schema.sql` defines the three tables (`file_state`, `change_log`, `operation_receipt`) with CHECK constraints that enforce shape invariants (e.g. DELETE rows carry no content).
- **Idempotency.** Each `FILE_CHANGE` carries a client-generated `operationId`; the server stores receipts in `operation_receipt` and replays the stored response on duplicate submission, so a lost HTTP response can't create duplicate revisions.
- **Optimistic concurrency.** `FileChangeRequest.baseFileVersion` must equal the server's current version. Stale non-delete mutations become a **conflict copy** (`<name>.conflict-<client>-<op>.txt`) instead of overwriting the canonical file; stale deletes are rejected outright (409 `STALE_DELETE`). `GlobalMutationLock` (single `ReentrantLock`) serializes all mutation transactions.
- **Long-polling.** `DeltaService` blocks on `ChangeNotifier` (a `Condition` signaled after each commit) up to `syncverse.long-poll-timeout` (default 25s), then returns whatever is available.
- **Client orchestration** (`SyncCoordinator`): on session start it reconciles (fetch all deltas through the server's current version, diff against local scan, upload local-only changes), then runs a poll loop that applies each delta batch and drains dirty filenames from the watcher. `Reconciler` is the pure function deciding per file between NO_OP / UPLOAD_LOCAL / APPLY_REMOTE / APPLY_DELETE / UPLOAD_CONFLICT by comparing base (manifest) vs local (disk snapshot) vs remote (revision) — all compared by presence + SHA-256 checksum, never by content.
- **Client state** is persisted atomically (format version, client name, cursor, file manifest, one `pendingOperation`) to `<workspace>.syncverse-state.json` beside the workspace, after **every** applied revision — that's what makes crash recovery possible. Never hand-edit these files while a client runs. Workspace state is bound to a client name; starting a client against a workspace whose state belongs to another name fails.
- **Session model.** Sessions live in server memory (`SessionService`), keyed by UUID, refreshed by heartbeats (~4s) and expiring after `syncverse.session-expiry` (default 15s). Expired/unknown session → 410 `SESSION_EXPIRED`; the client then reconnects (new session) and does a full catch-up via the same delta path.

### Constraints and invariants to preserve

- Flat directory only: no subdirectories, no symlinks. Filenames must be a single base name (validator rejects `/`, `\`, `..`, NUL, >255 chars).
- Max decoded file size 1 MiB (`FileChangeValidator.MAX_FILE_SIZE`); payloads are base64-encoded in JSON. Oversized → 413 `FILE_TOO_LARGE`.
- SHA-256 lowercase hex checksums verified server-side against decoded content.
- Delta batches of 20 revisions; a response must start exactly at the client's cursor — clients throw if versions are non-contiguous or non-increasing.
- No file content is ever logged (server logs metadata fields only; `PackagedJarsE2EIT` asserts `contentBase64` never appears in logs).
- `syncverse-common` must stay dependency-free of Spring.

### Tests

- Unit tests (`*Test.java`) run via surefire; integration tests (`*IT.java`) via failsafe during `mvn verify`.
- Server ITs (`LiveSyncIT`, `ReconnectE2EIT`, `DeltaServiceIT`, ...) use `@SpringBootTest` with `RANDOM_PORT` and in-memory H2; they drive a real `SyncCoordinator` over HTTP.
- `PackagedJarsE2EIT` spawns the actual packaged JARs as separate processes (requires `mvn clean verify` to have produced them first) and asserts create/update/delete convergence between two clients. `SERVER_PORT=0` selects a random port.
- The conflict-recovery tests in `LiveSyncIT` are the authoritative spec for offline conflict behavior (canonical wins + conflict copy on the *originating* client, which then syncs the copy to others).

Docs in `docs/`: `assignment.md` (the original assignment), `superpowers/specs/` and `superpowers/plans/` (design spec + implementation plan), `verification/` (acceptance verification), `RUN_AND_DEMO.md` (local run/demo guide).
