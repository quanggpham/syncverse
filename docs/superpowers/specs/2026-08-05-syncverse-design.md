# SyncVerse Design Specification

**Date:** 2026-08-05

**Status:** Approved design

**Scope:** Assignment demo and code review

**Runtime:** Java 17, Maven, one Central Server, multiple Client daemons

## 1. Context

SyncVerse synchronizes files between multiple client workstations through a Central Server over HTTP REST. Clients must not open ports or embed a web server. Each client watches one flat directory, uploads local changes, receives remote changes through polling or long-polling, and catches up after being offline.

The design is intentionally production-shaped rather than production-sized. Correctness mechanisms that prevent data loss belong in scope; infrastructure for high availability, internet-facing security, and large-scale operations does not.

## 2. Goals

- Satisfy the five assignment messages: `HELLO`, `HEARTBEAT`, `FILE_CHANGE`, `RECONNECT`, and `DELTA_REQUEST`.
- Produce two independent executable artifacts named `server.jar` and `client.jar`.
- Keep every client outbound-only with no listening port or embedded web server.
- Synchronize create, update, and delete operations for regular files in a flat directory.
- Enforce a decoded file-size limit of 1,048,576 bytes.
- Persist canonical file state and revision history across server restarts.
- Recover after client network loss or process restart.
- Detect concurrent edits and preserve both contents through conflict copies.
- Make retries idempotent so a lost HTTP response cannot create duplicate revisions.
- Keep the architecture small enough to understand and review as an assignment.

## 3. Non-goals

- Multiple Central Server instances, clustering, or high availability.
- PostgreSQL, distributed locking, Redis, or a message broker.
- Authentication, authorization, TLS, secret management, or internet exposure.
- Spring Cloud, Spring Security, WebFlux, JPA, or Hibernate.
- Recursive directory synchronization, symlink synchronization, or files larger than 1 MB.
- Semantic file merging, Operational Transformation, or CRDTs.
- An admin UI or a conflict-resolution UI.
- Change-log retention, quotas, backups, or disaster recovery.
- Backward-compatible protocol version negotiation.

If SyncVerse becomes a real internal product, these non-goals must be reassessed rather than assumed safe.

## 4. Technical decisions

| Concern | Decision |
|---|---|
| Server framework | Spring Boot 4.1.0 with Spring MVC |
| Client framework | Plain Java 17; no Spring and no web server |
| HTTP client | JDK `java.net.http.HttpClient` |
| JSON | Jackson on both client and server |
| Persistence | H2 in file mode |
| Data access | Spring JDBC with explicit SQL/JdbcTemplate-style repositories |
| File change detection | `java.nio.file.WatchService` plus checksum-based rescans |
| Checksum | SHA-256, lowercase hexadecimal |
| Live updates | Blocking long-polling, appropriate for a few demo clients |
| Conflict handling | Optimistic version check plus server-created conflict copy |
| Write ordering | One in-memory global mutation lock serializes `FILE_CHANGE` transactions |
| Build | Spring Boot executable JAR for server; shaded executable JAR for client |
| Logging | SLF4J and Logback with contextual fields; no file content in logs |

Spring Boot 4.1.0 supports Java 17. Dependency versions managed by the Spring Boot BOM should be used where possible so Jackson, SLF4J, Logback, H2, and test dependencies remain compatible.

## 5. Maven modules and dependency direction

```text
syncverse-parent
├── syncverse-common
├── syncverse-server  → syncverse-common
└── syncverse-client  → syncverse-common
```

There is no dependency from `common` to either application and no dependency between client and server.

### 5.1 `syncverse-common`

Contains protocol contracts only:

- Enums such as `MessageType`, `FileOperation`, and file-change outcomes.
- Request and response records.
- Value objects such as `FileRevision`, `FileManifestEntry`, and `ApiError`.

It must not contain Spring annotations, controllers, repositories, filesystem code, database code, or application services.

### 5.2 `syncverse-server`

Suggested package responsibilities:

```text
api/          REST controllers and exception mapping
service/      session, sync, delta, and validation policies
repository/   explicit JDBC queries and row mapping
config/       typed application configuration
```

Controllers translate HTTP to application calls. `SyncService` exclusively owns accept, conflict, and idempotency rules. Repositories execute SQL but do not make business decisions.

### 5.3 `syncverse-client`

Suggested package responsibilities:

```text
cli/          process entry point and argument validation
http/         typed Central Server API client
state/        atomic local-state persistence
watcher/      WatchService event collection and rescanning
sync/         lifecycle, reconciliation, upload, and remote apply
```

`SyncCoordinator` owns startup, reconnect, reconciliation, and the transition between online and offline states.

## 6. Runtime configuration and artifacts

Required commands remain compatible with the assignment:

```bash
java -jar server.jar AlphaServer
java -jar client.jar Alice_Node ./workspace_alice
```

Defaults:

- Server HTTP port: `8080`.
- Client server URL: `http://localhost:8080`.
- Heartbeat interval: 4 seconds.
- Session expiry: 15 seconds.
- Long-poll timeout: 25 seconds.
- Watch debounce: 300 milliseconds per filename.
- Delta batch size: at most 20 revisions.
- Decoded file limit: 1,048,576 bytes.
- HTTP request limit: approximately 2 MB to accommodate Base64 and JSON overhead.

Environment overrides:

- `SYNCVERSE_SERVER_URL` for the client.
- `SYNCVERSE_DATA_DIR` for the server, defaulting to `./syncverse-data`.

`AlphaServer` is a validated logical server name used in logs; it is not interpolated into a filesystem path. The H2 database defaults to `./syncverse-data/syncverse.mv.db`.

The build may create `syncverse-common` as an intermediate Maven artifact, but the runnable deliverables are exactly:

```text
server.jar
client.jar
```

## 7. Identity and session model

`clientName` is supplied by the CLI, remains stable across restarts, appears in logs, and contributes to conflict-copy names. It must match `[A-Za-z0-9_-]{1,64}`.

`sessionId` is a random UUID issued by the server for one active connection lifecycle. Sessions live only in server memory. A server restart or 15 seconds without heartbeat invalidates them. An unknown or expired session produces HTTP `410 SESSION_EXPIRED`, causing the client to run `RECONNECT`.

The demo assumes client names are unique. Registering or reconnecting the same name replaces its previous in-memory session.

## 8. REST protocol

| Message | Endpoint | Purpose |
|---|---|---|
| `HELLO` | `POST /api/register` | Register a first-time client and issue a session |
| `HEARTBEAT` | `POST /api/heartbeat` | Refresh in-memory session liveness |
| `FILE_CHANGE` | `POST /api/files/changes` | Submit create, update, or delete |
| `RECONNECT` | `POST /api/reconnect` | Issue a new session for a client with local state |
| `DELTA_REQUEST` | `GET /api/deltas?since={globalVersion}` | Return or wait for later revisions |

The assignment calls `DELTA_REQUEST` bidirectional, but the concrete exchange is a client request followed by a server response on the same HTTP connection.

### 8.1 HELLO

Request:

```json
{
  "messageType": "HELLO",
  "clientName": "Alice_Node"
}
```

Response includes `clientName`, `sessionId`, and `currentGlobalVersion`. A new client requests deltas from zero to obtain the server snapshot before initial reconciliation.

### 8.2 HEARTBEAT

Heartbeat carries `sessionId`. It updates only the in-memory session registry and does not write H2.

### 8.3 FILE_CHANGE

```json
{
  "messageType": "FILE_CHANGE",
  "sessionId": "uuid",
  "operationId": "uuid",
  "filename": "config.json",
  "operation": "UPDATE",
  "baseFileVersion": 37,
  "checksum": "lowercase-sha256",
  "contentBase64": "..."
}
```

For `DELETE`, checksum and content are null. Results are:

- `APPLIED`: the expected file version matched.
- `CONFLICT_COPY_CREATED`: a stale create/update was stored under a conflict filename.
- `DUPLICATE`: the same operation was already processed; the original result is returned.
- `CONFLICT_REJECTED`: a stale delete was not applied.

### 8.4 RECONNECT

Carries `clientName` and `lastSeenGlobalVersion`. It issues a new session and returns the server's current global version. The client still fetches actual revisions with `DELTA_REQUEST`.

### 8.5 DELTA_REQUEST

```http
GET /api/deltas?since=42
X-Session-Id: uuid
```

If revisions later than 42 exist, the server returns up to 20 immediately. Otherwise it waits up to 25 seconds and returns either newly committed revisions or an empty list. Empty timeout responses are normal and do not indicate an offline server.

File content is transported as Base64 inside JSON. The roughly 33% expansion is acceptable under the 1 MB assignment limit and keeps upload and delta DTOs uniform.

## 9. Version semantics

`globalVersion` is a strictly increasing server cursor allocated for each committed change-log entry. Identity values may contain gaps after rollbacks. Clients query `global_version > lastSeenGlobalVersion` and must never require contiguity.

`fileVersion` is the global version of the latest committed change for one filename. It is used as the optimistic concurrency token.

An upload ACK must not advance `lastSeenGlobalVersion`. Another client's revision may exist between the client's cursor and its own upload revision. Only ordered delta consumption advances the global cursor. An ACK may update the acknowledged file version; the client must still consume its own change from the delta stream, recognize its checksum/version as already applied, and advance the cursor without rewriting the file.

The client advances its cursor only after all revisions in sequence through that cursor have been reconciled and local state has been atomically persisted.

## 10. Persistence model

### 10.1 `file_state`

Stores the latest canonical state per filename:

- `filename` primary key.
- `content` BLOB, null for deletion.
- `checksum`, null for deletion.
- `size_bytes`.
- `file_version`.
- `deleted` tombstone flag.
- `updated_by` and `updated_at`.

Deletes retain a row as a tombstone so offline clients can learn that the file disappeared.

### 10.2 `change_log`

Stores immutable revisions:

- `global_version` identity primary key.
- `filename` and `operation`.
- Content snapshot, checksum, and size for create/update.
- Source client and timestamp.

Keeping content snapshots makes delta responses and revision recovery straightforward for the assignment. No cleanup policy is implemented.

### 10.3 `operation_receipt`

Stores the result for each `operationId`, including rejected stale deletes:

- `operation_id` primary key.
- Client and requested filename.
- Outcome and accepted filename.
- Optional committed global version.
- Timestamp.

This table ensures a retry receives the original outcome even when no change-log row was created.

### 10.4 Schema initialization

One idempotent `schema.sql` uses `CREATE TABLE IF NOT EXISTS`. Flyway is deliberately excluded because the assignment has one schema version and no rolling upgrades.

## 11. Server transaction and concurrency

One `FILE_CHANGE` executes as follows:

1. Acquire the server's single in-memory mutation lock.
2. Look up `operation_receipt`; return the stored result if present.
3. Load current `file_state` and compare `baseFileVersion`.
4. Decide applied, conflict copy, or stale-delete rejection.
5. For a state change, insert `change_log` and obtain its global version.
6. Upsert `file_state` with that version as `fileVersion`.
7. Insert `operation_receipt`.
8. Commit the transaction.
9. Release the mutation lock and notify delta waiters only after commit.

Any database error rolls back the entire unit. A rejected stale delete commits only its operation receipt and does not notify delta waiters.

All `FILE_CHANGE` transactions are serialized, including changes to different filenames. This guarantees that global-version allocation order matches commit visibility order. Without this rule, one transaction could allocate version 43, another allocate and commit version 44 first, and a client advancing its cursor to 44 could miss the late commit of 43.

The throughput cost is acceptable for a few assignment clients and 1 MB files. A real multi-instance or high-throughput deployment would replace this process-local lock with a database-backed commit-ordering design or a durable ordered log.

## 12. Conflict policy

For normal update/delete, `baseFileVersion` must equal the server's current file version. For initial creation, the absence of both server state and a positive base version is expected.

When create/update is stale, the server preserves the canonical file and writes the submitted bytes as a new conflict file. The deterministic form is:

```text
{stem}.conflict-{sanitizedClientName}-{first8OperationId}{extension}
```

Example:

```text
config.conflict-Bob_Node-a1b2c3d4.json
```

The operation ID makes retries resolve to the same accepted filename. The conflict copy receives its own global/file version and is synchronized to all clients.

A stale delete is rejected with HTTP `409 STALE_DELETE`; the newer canonical file remains. The client reconciles to the canonical state and does not automatically retry the delete against a newer base version.

No semantic merge is attempted because SyncVerse treats file contents as arbitrary bytes.

## 13. Initial synchronization and three-way reconciliation

Client state is stored outside the watched directory, for example:

```text
workspace_alice/
workspace_alice.syncverse-state.json
```

The state contains its format version, client name, last-seen global cursor, per-file version/checksum/tombstone data, and at most one persisted pending operation. Dirty files need not be persisted because startup scanning can rediscover them.

For a new client with no state, each filename is reconciled as follows:

| Server | Local | Result |
|---|---|---|
| Missing | Present | Upload create |
| Present | Missing | Download canonical |
| Same checksum | Same checksum | Record version; no transfer |
| Different checksum | Different checksum | Keep canonical; upload local bytes as conflict copy |

If the server is entirely empty, the first client uploads all valid local files and the server assigns versions.

For a returning client, reconciliation compares:

1. The last persisted manifest (base).
2. Current local filesystem state.
3. Current server state derived from ordered deltas.

Local-only change is uploaded, remote-only change is applied, identical change is a no-op, and changes on both sides invoke the conflict policy.

## 14. Client lifecycle and threading

State machine:

```text
STARTING → RECONCILING → ONLINE ⇄ OFFLINE
```

`OFFLINE` continues watching the local directory. A successful reconnect always enters `RECONCILING` before returning to `ONLINE`.

Client workers:

- Heartbeat scheduler every 4 seconds.
- Watch thread blocking on `WatchService`.
- Long-poll thread blocking on the server.
- One single-threaded `SyncExecutor` for filesystem writes, uploads, reconciliation, and local-state persistence.

The watch and long-poll workers submit work but do not mutate sync state directly. This serialization prevents a remote apply and local upload from racing on the same file.

## 15. Filesystem event handling

Raw `WatchService` events are hints, not truth. The client debounces each filename for 300 milliseconds, then reads current filesystem state and computes its checksum. Editor-generated temporary event sequences therefore collapse into the final desired state.

`OVERFLOW` triggers a full root-directory rescan. Only the root directory is registered; subdirectories, symbolic links, and non-regular files are ignored and logged.

Remote create/update is written to a temporary sibling file and moved with `ATOMIC_MOVE` plus `REPLACE_EXISTING`, falling back to a replace move where atomic move is unsupported. Remote delete and manifest update run inside the same serialized sync task.

Feedback-loop suppression is checksum/version based. After remote apply persists the new manifest, a later watch event rescans and sees that local bytes already equal known server state, producing no upload. No timing-based ignore flag is used.

## 16. Pending operations, retry, and shutdown

Before HTTP submission, the client creates an operation ID, captures the exact payload, and atomically persists it as the pending operation. A lost response is retried with the same ID and bytes. On ACK, the pending entry is cleared and file state is updated.

If a file changes again while its operation is pending, the filename is treated as dirty. After ACK, the client rescans and creates the next operation from the acknowledged file version. Because uploads are serialized, at most one operation must be persisted as in-flight.

Network failure, HTTP `429`, and HTTP `5xx` use exponential reconnect/retry delays of 1, 2, 4, 8, 16, then at most 30 seconds. Validation `4xx` responses are not retried unchanged. HTTP `410` triggers reconnect. A successful empty long-poll response immediately starts the next poll.

Local state is written to a temporary sibling and atomically replaced. A shutdown hook stops new watch work, cancels long-polling, stops heartbeat, waits a bounded time for the active sync task, closes the watch service, and leaves any unacknowledged operation persisted.

## 17. Long-poll implementation

Blocking long-polling is selected instead of WebFlux because the demo has only a few clients. `DeltaService` first queries H2. If no later revision exists, it waits on an in-memory `ChangeNotifier` for up to 25 seconds, then queries H2 again.

The notifier is only a wake-up optimization; H2 is the source of truth. Sync code signals the notifier after transaction commit. This prevents clients from observing revisions that later roll back. The query-register race is handled by checking the notifier's latest committed version while entering the wait and by querying H2 again after wake-up.

## 18. Validation and error contract

Accepted names are single base filenames with no slash, backslash, `..`, NUL, or path traversal. Files must be regular files read without following links. The server verifies decoded length and SHA-256 rather than trusting JSON metadata.

Uniform error body:

```json
{
  "code": "FILE_TOO_LARGE",
  "message": "File exceeds 1048576 bytes",
  "requestId": "uuid",
  "timestamp": "2026-08-05T00:00:00Z"
}
```

| HTTP | Code | Client action |
|---|---|---|
| 400 | `INVALID_REQUEST` | Log; do not retry unchanged |
| 409 | `STALE_DELETE` | Reconcile; do not auto-delete newer version |
| 410 | `SESSION_EXPIRED` | Reconnect |
| 413 | `FILE_TOO_LARGE` | Record local error until file becomes valid |
| 429 | `TOO_MANY_REQUESTS` | Backoff and retry |
| 500/503 | `SERVER_ERROR` | Keep pending operation and retry |

Unexpected exceptions are logged server-side and mapped without stack traces or SQL details in the response.

## 19. Logging

Logs include useful correlation fields where applicable:

- `requestId`, `clientName`, and `operationId`.
- Requested and accepted filenames.
- Outcome, base file version, current file version, and accepted global version.

Logs must not include file bytes, Base64 payloads, complete session IDs, or internal exception details in client-visible messages. Plain Logback patterns with MDC are sufficient; a JSON logging encoder is out of scope.

## 20. Testing strategy

### 20.1 Unit tests

- Three-way reconciliation matrix.
- Conflict filename generation.
- Version comparison and stale-delete policy.
- Retry/backoff sequence.
- Checksum and filesystem-state decisions.
- Filename, regular-file, symlink, and size validation.
- Client-state serialization and atomic replacement behavior.

### 20.2 Server integration tests

Run Spring Boot against a real H2 database in a temporary directory:

- Register, reconnect, heartbeat, and expiration.
- Create, update, delete, and tombstone behavior.
- Duplicate operation ID returns the original result.
- Transaction rollback leaves no partial state.
- Concurrent stale update creates a conflict copy.
- Stale delete returns conflict without deleting canonical state.
- Delta ordering, gaps, and batches of at most 20.
- Long-poll wakes only after commit and times out normally.

`Clock` and timeout properties are injectable so tests do not sleep for production intervals.

### 20.3 End-to-end acceptance tests

Milestone 1:

- Register several clients.
- Heartbeats keep sessions alive.
- Advancing a fake clock expires a stopped client.

Milestone 2:

- Alice creates, updates, and deletes files.
- Bob receives ordered deltas and converges to matching checksums/tombstones.
- Applying Bob's remote changes does not cause feedback uploads.

Milestone 3:

- Bob goes offline while Alice makes multiple changes.
- Bob reconnects from an older cursor and converges.
- Alice and Bob concurrently edit one file; canonical plus conflict copy preserve both contents.
- Stopping and restarting the server with the same data directory preserves files, tombstones, receipts, and version progression.

Temporary directories isolate all tests. Testcontainers is unnecessary because H2 is both the test and runtime database.

## 21. Completion criteria

`mvn clean verify` must:

- Pass unit, integration, and end-to-end tests.
- Produce executable `server.jar` and `client.jar`.
- Require no dependency directory beside either JAR.
- Demonstrate the assignment commands and all three milestones.

The implementation is complete only when the two built JARs are smoke-tested as separate processes with temporary server data and client workspaces.

## 22. Production hardening intentionally deferred

A real internal deployment would require authentication and authorization, TLS or mTLS, an external production database with backups, migration tooling, retention and quotas, metrics/traces/alerts, protocol compatibility, HA, rate limits, secret management, and an operator workflow for resolving conflicts. These are explicit future concerns, not hidden assumptions in the assignment implementation.
