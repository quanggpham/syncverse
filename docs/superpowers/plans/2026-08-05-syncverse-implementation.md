# SyncVerse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify SyncVerse as one persistent Central Server and multiple outbound-only Client daemons, delivered as `server.jar` and `client.jar`.

**Architecture:** `syncverse-common` owns plain Java protocol records. `syncverse-server` uses Spring Boot MVC, explicit JDBC repositories, H2 file persistence, and one global mutation lock. `syncverse-client` is plain Java 17 using JDK HttpClient, WatchService, atomic state, heartbeat, long-polling, and one serialized sync executor.

**Tech Stack:** Java 17 release target, Maven 3.9+, Spring Boot 4.1.0, Spring MVC, Spring JDBC, H2, Jackson, JUnit 5, SLF4J/Logback.

## Global Constraints

- Build with `maven.compiler.release=17`; local verification may run on JDK 21.
- Client has no Spring, servlet, embedded server, JDBC, or listening port.
- Sync only root regular files; ignore child directories and symlinks.
- Decoded content is at most 1,048,576 bytes; checksum is lowercase SHA-256.
- Persist server data in `${SYNCVERSE_DATA_DIR:-./syncverse-data}/syncverse.mv.db`.
- Serialize all server `FILE_CHANGE` transactions so global allocation matches commit visibility.
- Upload ACK updates file state but never advances the client's global delta cursor.
- Stale create/update creates a deterministic conflict copy; stale delete preserves canonical state and returns 409.
- Persist an operation before HTTP submission and retry the exact ID and bytes.
- Do not add authentication, TLS, JPA, WebFlux, PostgreSQL, Docker, brokers, recursive sync, or UI.
- Do not edit `README.md`; use the assignment and approved design spec.
- Every task follows red-green-refactor, ends with focused verification, and creates one reviewable commit.
- Targeted reactor tests append `-Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` so upstream modules without the named test do not fail spuriously.

## File Structure Map

### Build and common

- Modify `pom.xml` and all three module POMs for the Boot BOM, Java 17, tests, and exact executable names.
- Create protocol enums under `syncverse-common/src/main/java/com/internship/syncverse/common/protocol/`.
- Create request/response records under `syncverse-common/src/main/java/com/internship/syncverse/common/dto/`.

### Server

- `SyncVerseServer.java`: Boot entry point and logical server-name validation.
- `config/SyncProperties.java`: typed timeouts, limits, batch size, and data path.
- `session/`: in-memory session lifecycle using injected `Clock`.
- `api/`: four controllers, request-ID filter, and global exception mapping.
- `persistence/`: schema, row records, and three explicit JDBC repositories.
- `sync/`: validation, global mutation lock, conflict naming, and transaction policy.
- `delta/`: committed-version notifier and blocking long-poll service.

### Client

- `SyncVerseClient.java` and `cli/CliArguments.java`: process entry and argument validation.
- `http/ServerApiClient.java`: typed JDK HttpClient calls.
- `state/`: manifest, pending operation, client state, and atomic JSON store.
- `fs/`: scanning, watching, checksums, and atomic remote apply.
- `sync/`: state machine, retry policy, reconciliation, and serialized coordinator.

### Tests

- Unit tests mirror each module package under `src/test/java`.
- Server integration tests end in `IT` and use real temporary H2 databases.
- Process-level tests live under `syncverse-server/src/test/java/com/internship/syncverse/e2e/`.

---

## Phase 1: Build Foundation and Protocol

### Task 1: Maven build, packaging, and shared protocol

**Files:**
- Modify: `pom.xml`, `syncverse-common/pom.xml`, `syncverse-server/pom.xml`, `syncverse-client/pom.xml`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/protocol/MessageType.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/protocol/FileOperation.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/protocol/ChangeOutcome.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/RegisterRequest.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/RegisterResponse.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/HeartbeatRequest.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/ReconnectRequest.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/FileChangeRequest.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/FileChangeResponse.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/FileRevision.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/DeltaResponse.java`
- Create: `syncverse-common/src/main/java/com/internship/syncverse/common/dto/ApiError.java`
- Test: `syncverse-common/src/test/java/com/internship/syncverse/common/dto/ProtocolContractTest.java`

**Interfaces:**
- Consumes: approved design sections 4-9.
- Produces: immutable shared records, parent-managed compatible dependencies, Boot server repackage, and shaded client artifact.

- [ ] **Step 1: Write failing protocol tests**

```java
@Test
void updateCarriesIdempotencyAndConcurrencyFields() {
    UUID session = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    var request = new FileChangeRequest(MessageType.FILE_CHANGE, session, operation,
            "config.json", FileOperation.UPDATE, 37L,
            "a".repeat(64), Base64.getEncoder().encodeToString(new byte[]{1}));
    assertAll(
            () -> assertEquals(session, request.sessionId()),
            () -> assertEquals(operation, request.operationId()),
            () -> assertEquals(37L, request.baseFileVersion()));
}

@Test
void deleteCarriesNoChecksumOrContent() {
    var request = new FileChangeRequest(MessageType.FILE_CHANGE, UUID.randomUUID(),
            UUID.randomUUID(), "notes.txt", FileOperation.DELETE, 12L, null, null);
    assertNull(request.checksum());
    assertNull(request.contentBase64());
}
```

- [ ] **Step 2: Verify red**

Run: `mvn -pl syncverse-common -am -Dtest=ProtocolContractTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure because protocol types do not exist.

- [ ] **Step 3: Configure Maven and implement records**

Import `org.springframework.boot:spring-boot-dependencies:4.1.0`, set release 17, pin Compiler/Surefire/Failsafe/Boot/ Shade plugins, and declare only directly used dependencies. Use exact enum values:

```java
public enum MessageType { HELLO, HEARTBEAT, FILE_CHANGE, RECONNECT, DELTA_REQUEST }
public enum FileOperation { CREATE, UPDATE, DELETE }
public enum ChangeOutcome { APPLIED, CONFLICT_COPY_CREATED, DUPLICATE, CONFLICT_REJECTED }
```

Use `UUID` for identities, `Instant` for API timestamps, primitive `long` for required versions, and boxed `Long` only when no committed version is a valid outcome.

Use these exact record signatures:

```java
record RegisterRequest(MessageType messageType, String clientName) {}
record RegisterResponse(String clientName, UUID sessionId, long currentGlobalVersion) {}
record HeartbeatRequest(MessageType messageType, UUID sessionId) {}
record ReconnectRequest(MessageType messageType, String clientName, long lastSeenGlobalVersion) {}
record FileChangeRequest(MessageType messageType, UUID sessionId, UUID operationId,
        String filename, FileOperation operation, long baseFileVersion,
        String checksum, String contentBase64) {}
record FileChangeResponse(ChangeOutcome outcome, String requestedFilename,
        String acceptedFilename, Long globalVersion, long fileVersion) {}
record FileRevision(long globalVersion, String filename, FileOperation operation,
        long fileVersion, String checksum, long sizeBytes, String contentBase64) {}
record DeltaResponse(long fromExclusive, long latestGlobalVersion,
        List<FileRevision> changes) {}
record ApiError(String code, String message, String requestId, Instant timestamp) {}
```

- [ ] **Step 4: Verify green and dependency boundaries**

Run:

```bash
mvn test
mvn -pl syncverse-server dependency:tree -Dincludes=org.slf4j
mvn -pl syncverse-client dependency:tree -Dincludes=org.springframework,jakarta.servlet,org.apache.tomcat,org.eclipse.jetty,io.netty
git diff --check
```

**Phase 1 gate metrics:** reactor failures/errors `0`; protocol scenarios at least `2/2` passing; one SLF4J major per runtime; client forbidden-server dependency count `0`; diff-check violations `0`.

- [ ] **Step 5: Commit**

```bash
git add pom.xml syncverse-common syncverse-server/pom.xml syncverse-client/pom.xml
git commit -m "build: establish SyncVerse protocol and packaging"
```

---

## Phase 2: Milestone 1 — Handshake and Heartbeat

### Task 2: Server session lifecycle and registration APIs

**Files:**
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/SyncVerseServer.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/config/SyncProperties.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/session/ClientSession.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/session/SessionService.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/api/RegistrationController.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/api/HeartbeatController.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/api/GlobalExceptionHandler.java`
- Create: `syncverse-server/src/main/resources/application.properties`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/server/session/SessionServiceTest.java`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/server/api/RegistrationApiIT.java`

**Interfaces:**
- Consumes: common register/reconnect/heartbeat DTOs.
- Produces: `SessionService.register(String)`, `reconnect(String,long)`, `heartbeat(UUID)`, `requireActive(UUID)` and the three assignment endpoints.

- [ ] **Step 1: Write failing session tests**

```java
@Test
void sessionExpiresAtConfiguredBoundary() {
    MutableClock clock = MutableClock.at(Instant.parse("2026-08-05T00:00:00Z"));
    SessionService service = new SessionService(clock, Duration.ofSeconds(15));
    ClientSession session = service.register("Alice_Node");
    clock.advance(Duration.ofSeconds(15));
    assertThrows(SessionExpiredException.class,
            () -> service.requireActive(session.sessionId()));
}
```

Also cover heartbeat refresh, reconnect issuing a new UUID, and duplicate client name invalidating its previous session.

- [ ] **Step 2: Run red, then implement session service**

Run red: `mvn -pl syncverse-server -am -Dtest=SessionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Implement with concurrent maps, injected `Clock`, name regex `[A-Za-z0-9_-]{1,64}`, and explicit expiry boundary. Run the same command green.

- [ ] **Step 3: Write failing API integration tests**

Using `@SpringBootTest(webEnvironment = RANDOM_PORT)`, assert register returns 201 plus session, valid heartbeat returns 204, reconnect returns a new session, invalid name returns `400 INVALID_REQUEST`, and expired session returns `410 SESSION_EXPIRED`.

- [ ] **Step 4: Implement entry point, properties, controllers, and error mapping**

`SyncVerseServer.main` requires one non-option logical server name. Defaults are heartbeat 4 seconds and expiry 15 seconds. Controllers hold no mutable state.

- [ ] **Step 5: Verify and commit server handshake**

Run: `mvn -pl syncverse-server -am -Dtest=SessionServiceTest -Dit.test=RegistrationApiIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify`

```bash
git add syncverse-server
git commit -m "feat(server): add registration and heartbeat sessions"
```

### Task 3: Client CLI, typed API, heartbeat, and connection modes

**Files:**
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/SyncVerseClient.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/cli/CliArguments.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/http/ServerApiClient.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/sync/ClientMode.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/sync/RetryPolicy.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/sync/ConnectionManager.java`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/cli/CliArgumentsTest.java`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/sync/RetryPolicyTest.java`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/sync/ConnectionManagerTest.java`

**Interfaces:**
- Consumes: Task 2 HTTP endpoints.
- Produces: CLI parsing, register/reconnect/heartbeat calls, and STARTING/RECONCILING/ONLINE/OFFLINE transitions.

- [ ] **Step 1: Write failing CLI and backoff tests**

```java
@Test
void backoffCapsAtThirtySeconds() {
    RetryPolicy policy = RetryPolicy.exponential(Duration.ofSeconds(1), Duration.ofSeconds(30));
    assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L),
            IntStream.range(0, 7).mapToObj(i -> policy.delay(i).toSeconds()).toList());
}
```

Also test exactly two CLI arguments, valid client name, normalized absolute workspace, missing-directory creation, and rejection when workspace is a regular file.

- [ ] **Step 2: Implement CLI, HttpClient, and retry mapping**

Reuse one JDK HttpClient and one Jackson mapper. Resolve `SYNCVERSE_SERVER_URL`, default `http://localhost:8080`. Treat network/429/5xx as retryable, 410 as reconnect, and other validation 4xx as permanent.

- [ ] **Step 3: Test connection transitions without sleeping**

Use a fake `ServerApiClient` and manually invoked heartbeat tick. Assert register success → ONLINE, heartbeat failure → OFFLINE, reconnect success → RECONCILING, and shutdown cancels heartbeat.

- [ ] **Step 4: Implement bootstrap and shutdown hook**

Install a shutdown hook and a 4-second scheduled heartbeat. At this phase RECONCILING may transition directly to ONLINE because filesystem reconciliation is added in Phase 5.

- [ ] **Step 5: Review Milestone 1 metrics and commit**

Run: `mvn clean verify`

**Phase 2 gate metrics:** session scenarios `4/4`; REST scenarios valid/invalid/expired all pass; client mode scenarios at least `4/4`; wall-clock sleeps in tests `0`; full build failures/errors `0`; client forbidden dependency count `0`.

```bash
git add syncverse-client
git commit -m "feat(client): add CLI registration and heartbeat"
```

---

## Phase 3: Milestone 2A — Durable Server File Changes

### Task 4: H2 schema and explicit JDBC repositories

**Files:**
- Create: `syncverse-server/src/main/resources/schema.sql`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/persistence/FileState.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/persistence/ChangeRecord.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/persistence/OperationReceipt.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/persistence/FileStateRepository.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/persistence/ChangeLogRepository.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/persistence/OperationReceiptRepository.java`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/server/persistence/JdbcRepositoriesIT.java`

**Interfaces:**
- Consumes: Boot DataSource and H2.
- Produces: `find/upsert` file state, `append/findAfter/maxVersion` change log, and `find/insert` operation receipt methods.

- [ ] **Step 1: Write failing repository integration tests**

```java
long version = changes.append("config.json", FileOperation.CREATE,
        bytes, checksum, bytes.length, "Alice_Node", now);
files.upsert(FileState.present("config.json", bytes, checksum, bytes.length,
        version, "Alice_Node", now));
assertEquals(version, files.find("config.json").orElseThrow().fileVersion());
assertArrayEquals(bytes, changes.findAfter(0, 20).getFirst().content());
```

Also test tombstones, ordered query with identity gaps, 20-row limit, receipt round-trip, and closing/reopening a context on the same H2 path.

- [ ] **Step 2: Verify red and create the schema**

Run: `mvn -pl syncverse-server -am -Dit.test=JdbcRepositoriesIT -Dfailsafe.failIfNoSpecifiedTests=false verify`

Expected: missing repository/schema failure. Create exactly `file_state`, `change_log`, and `operation_receipt` with H2 BLOBs, identity global version, constraints, and UTC timestamps. Set `spring.sql.init.mode=always`.

- [ ] **Step 3: Implement focused repositories**

Keep SQL in repository classes. `append` returns the generated key; `findAfter(cursor,limit)` orders ascending and never assumes contiguity. Copy mutable byte arrays at boundaries.

- [ ] **Step 4: Verify persistence and commit**

Run: `mvn -pl syncverse-server -am -Dit.test=JdbcRepositoriesIT -Dfailsafe.failIfNoSpecifiedTests=false verify`

Expected: all repository and same-path restart scenarios pass.

```bash
git add syncverse-server/src/main/resources syncverse-server/src/main/java/com/internship/syncverse/server/persistence syncverse-server/src/test/java/com/internship/syncverse/server/persistence
git commit -m "feat(server): persist file state and change history"
```

### Task 5: Validation, idempotency, conflict, and global mutation transaction

**Files:**
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/sync/GlobalMutationLock.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/sync/FileChangeValidator.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/sync/ConflictNameGenerator.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/sync/SyncService.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/api/FileChangeController.java`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/server/sync/FileChangeValidatorTest.java`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/server/sync/SyncServiceIT.java`

**Interfaces:**
- Consumes: Task 4 repositories and Task 2 active-session lookup.
- Produces: `SyncService.apply(FileChangeRequest)` and `POST /api/files/changes`.

- [ ] **Step 1: Write failing validation tests**

```java
@ParameterizedTest
@ValueSource(strings = {"../secret", "a/b.txt", "a\\b.txt", "..", "\u0000bad"})
void rejectsNonFlatNames(String filename) {
    assertThrows(InvalidFileChangeException.class,
            () -> validator.validateFilename(filename));
}

@Test
void enforcesDecodedLimit() {
    validator.validateDecodedSize(1_048_576);
    assertThrows(FileTooLargeException.class,
            () -> validator.validateDecodedSize(1_048_577));
}
```

Also cover invalid Base64, SHA-256 mismatch, and DELETE requiring null checksum/content.

- [ ] **Step 2: Run red, implement validator, run green**

Run: `mvn -pl syncverse-server -am -Dtest=FileChangeValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected after implementation: every validation boundary passes.

- [ ] **Step 3: Write failing transaction-policy tests**

Required assertions:

```java
FileChangeResponse first = service.apply(createRequest);
FileChangeResponse duplicate = service.apply(createRequest);
assertEquals(first, duplicate);
assertEquals(1, changeLog.count());
assertEquals(1, receipts.count());
```

Also test update/delete, stale update preserving canonical and conflict bytes, deterministic conflict name, stale delete preserving canonical, and injected rollback leaving no partial rows.

- [ ] **Step 4: Implement one global lock and TransactionTemplate**

Hold one fair `ReentrantLock` across receipt lookup, decision, writes, and commit completion. Allocate global version inside the lock. Rejected stale delete inserts only its receipt. Do not release before transaction completion.

- [ ] **Step 5: Implement deterministic conflict naming and controller mapping**

`config.json`, Bob, operation `a1b2c3d4-...` becomes `config.conflict-Bob_Node-a1b2c3d4.json`. Return 200 for applied/duplicate/conflict-copy, 409 for stale delete, 413 for size, and 400 for other validation.

- [ ] **Step 6: Verify concurrency and Phase 3 metrics**

Run: `mvn -pl syncverse-server -am -Dtest=FileChangeValidatorTest -Dit.test=SyncServiceIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify`

Run a 20-request executor scenario and assert unique increasing versions, one receipt per operation, and every content canonical or conflict-preserved.

**Phase 3 gate metrics:** 1 MB accepted and +1 byte rejected; 10 retries produce 1 change/1 receipt; stale update retains 2 byte arrays; stale delete changes 0 canonical bytes and adds 0 change rows; rollback leaves 0 partial rows; restart preserves max version; failures/errors `0`.

- [ ] **Step 7: Commit**

```bash
git add syncverse-server/src/main/java/com/internship/syncverse/server/sync syncverse-server/src/main/java/com/internship/syncverse/server/api/FileChangeController.java syncverse-server/src/test/java/com/internship/syncverse/server/sync
git commit -m "feat(server): apply idempotent versioned file changes"
```

---

## Phase 4: Milestone 2B — Client Watch, Upload, and Live Delta

### Task 6: Atomic client state, scanner, watcher, and pending upload

**Files:**
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/state/FileManifestEntry.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/state/PendingOperation.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/state/ClientState.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/state/AtomicClientStateStore.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/fs/FileSnapshot.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/fs/DirectoryScanner.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/fs/DirectoryWatcher.java`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/state/AtomicClientStateStoreTest.java`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/fs/DirectoryScannerTest.java`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/fs/DirectoryWatcherTest.java`

**Interfaces:**
- Consumes: common change DTOs and Task 3 API client.
- Produces: atomic `load/save`, root snapshot map, debounced filename callbacks, OVERFLOW full rescan, and persisted pending upload.

- [ ] **Step 1: Write failing state-store tests**

Persist cursor, manifest, and exact pending payload; reload and assert equality. Inject replacement failure and assert the previous JSON remains parseable. State path is the workspace sibling `{workspace-name}.syncverse-state.json`.

- [ ] **Step 2: Implement atomic JSON state**

Write UTF-8 to sibling `.tmp`, close it, then `ATOMIC_MOVE, REPLACE_EXISTING`; fall back to replace move only when atomic move is unsupported. Never write state inside the watched root.

- [ ] **Step 3: Write scanner/watcher tests**

Create regular files, child directory, optional symlink, and oversized file. Assert only valid root regular files are returned. Inject a controllable debounce scheduler; repeated same-name events produce one callback, and OVERFLOW produces one full-rescan callback without long sleeps.

- [ ] **Step 4: Implement SHA-256 scan and root-only WatchService**

Debounce 300 ms per filename. Raw event type is a hint; callback rescans final state. Register CREATE/MODIFY/DELETE only on the root and never follow links.

- [ ] **Step 5: Persist before upload**

Before `ServerApiClient.fileChange`, persist one `PendingOperation` containing operation ID and exact payload. Retry it unchanged. ACK clears pending and updates file manifest but not global cursor. A further event marks the name dirty in memory and rescans after ACK.

- [ ] **Step 6: Verify and commit**

Run: `mvn -pl syncverse-client -am -Dtest=AtomicClientStateStoreTest,DirectoryScannerTest,DirectoryWatcherTest,ConnectionManagerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: all state/watch/pending scenarios pass; no fixed test sleep above 100 ms.

```bash
git add syncverse-client
git commit -m "feat(client): persist and upload watched file changes"
```

### Task 7: Server delta query and safe blocking long-poll

**Files:**
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/delta/ChangeNotifier.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/delta/DeltaService.java`
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/api/DeltaController.java`
- Modify: `syncverse-server/src/main/java/com/internship/syncverse/server/sync/SyncService.java`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/server/delta/DeltaServiceIT.java`

**Interfaces:**
- Consumes: ordered repository query and post-commit version.
- Produces: `DeltaService.poll(UUID,long)` and `GET /api/deltas?since=`.

- [ ] **Step 1: Write failing immediate/timeout/commit/rollback tests**

Use 100 ms configured timeout. Existing rows return immediately, empty DB returns empty near timeout, committed change wakes a blocked poll, and forced rollback does not wake it.

- [ ] **Step 2: Implement race-safe notifier**

Protect latest committed version with `ReentrantLock` and `Condition`. Check latest under lock before waiting; initialize from repository maximum. `signalCommitted` applies `max` and signals all.

- [ ] **Step 3: Implement double-query long-poll**

Query H2; if empty, await notifier; query H2 again; return actual rows, never event payload. Limit to 20. Notify only after commit while global mutation ordering is still protected.

- [ ] **Step 4: Verify and commit**

Run: `mvn -pl syncverse-server -am -Dit.test=DeltaServiceIT,SyncServiceIT -Dfailsafe.failIfNoSpecifiedTests=false verify`

Expected: immediate, bounded timeout, commit wake, rollback silence, ordering, and batch cases pass.

```bash
git add syncverse-server
git commit -m "feat(server): serve committed changes by long polling"
```

### Task 8: Client remote apply and continuous live sync

**Files:**
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/fs/RemoteFileApplier.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/sync/SyncCoordinator.java`
- Modify: `syncverse-client/src/main/java/com/internship/syncverse/client/{SyncVerseClient.java,http/ServerApiClient.java}`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/fs/RemoteFileApplierTest.java`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/sync/SyncCoordinatorTest.java`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/e2e/LiveSyncIT.java`

**Interfaces:**
- Consumes: Tasks 6-7.
- Produces: serialized remote apply, correct cursor persistence, and continuous long-poll.

- [ ] **Step 1: Write failing remote-apply tests**

Assert create/update temporary sibling replacement, delete, checksum rejection before canonical mutation, and later scan yielding no upload because manifest matches.

- [ ] **Step 2: Implement RemoteFileApplier**

Decode and validate, write sibling temp, move atomically with fallback, then persist manifest in the same SyncExecutor task. Delete persists a tombstone. Do not add timing-based suppression.

- [ ] **Step 3: Write coordinator ordering tests**

Feed revisions 43/44 and assert ordered apply and no cursor advance when 43 fails. ACK local version 44 while cursor is 42 and assert cursor remains 42 until deltas 43/44 are consumed.

- [ ] **Step 4: Implement one SyncExecutor and long-poll worker**

Long-poll submits a batch and waits for task completion before polling again. Heartbeat, watcher, and poll workers never mutate state directly. Shutdown preserves pending work.

- [ ] **Step 5: Run two-client live integration**

Use temp H2 and workspaces. Alice create/update/delete must converge to Bob through `eventually(Duration.ofSeconds(5), condition)`. Count receipts and prove Bob remote apply produces zero uploads.

- [ ] **Step 6: Review Milestone 2 and commit**

Run: `mvn clean verify`

**Phase 4 gate metrics:** create/update/delete convergence within 5 test seconds; feedback operation count `0`; ordered cursor scenarios `100%`; delta batch size `1-20`; timeout deviation within 250 ms of configured test timeout; full build failures/errors `0`.

```bash
git add syncverse-client syncverse-server/src/test/java/com/internship/syncverse/e2e/LiveSyncIT.java
git commit -m "feat(client): apply live server deltas"
```

---

## Phase 5: Milestone 3 — Reconnect and Auto-Healing

### Task 9: Pure three-way reconciler and restart-safe catch-up

**Files:**
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/sync/ReconciliationAction.java`
- Create: `syncverse-client/src/main/java/com/internship/syncverse/client/sync/Reconciler.java`
- Modify: `syncverse-client/src/main/java/com/internship/syncverse/client/sync/SyncCoordinator.java`
- Modify: `syncverse-client/src/main/java/com/internship/syncverse/client/state/ClientState.java`
- Test: `syncverse-client/src/test/java/com/internship/syncverse/client/sync/ReconcilerTest.java`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/e2e/ReconnectE2EIT.java`

**Interfaces:**
- Consumes: persisted base manifest, current local scan, ordered remote snapshot, pending operation.
- Produces: `NO_OP`, `UPLOAD_LOCAL`, `APPLY_REMOTE`, `UPLOAD_CONFLICT`, and `APPLY_DELETE` decisions.

- [ ] **Step 1: Write the failing reconciliation matrix**

```text
base missing, local present, remote missing    -> UPLOAD_LOCAL
base missing, local missing, remote present    -> APPLY_REMOTE
base missing, local/remote same                -> NO_OP + record version
base missing, local/remote different           -> UPLOAD_CONFLICT
base present, only local changed               -> UPLOAD_LOCAL
base present, only remote changed              -> APPLY_REMOTE
base present, both changed to same checksum    -> NO_OP + record version
base present, both changed differently         -> UPLOAD_CONFLICT
remote tombstone, local unchanged              -> APPLY_DELETE
remote changed, local stale deletion           -> reject delete + APPLY_REMOTE
```

Implement each as a named parameterized case with expected action and target checksum/version.

- [ ] **Step 2: Verify red, implement pure function, verify green**

Run: `mvn -pl syncverse-client -am -Dtest=ReconcilerTest -Dsurefire.failIfNoSpecifiedTests=false test`

The reconciler accepts nullable immutable descriptors and performs no I/O.

- [ ] **Step 3: Integrate startup/reconnect ordering**

New client: register, fetch all deltas from zero, scan local, reconcile. Returning client: load base/pending, reconnect, scan local before remote apply, fetch all deltas from cursor, reconcile, persist cursor, then enter ONLINE.

- [ ] **Step 4: Write E2E offline, conflict, dropped-response, and restart tests**

Scenarios:

- Bob stops polling; Alice performs create/update/delete; Bob reconnects and converges.
- Alice and offline Bob edit the same file; canonical and deterministic Bob conflict copy preserve both byte arrays.
- Bob stale-deletes a file Alice updated; Alice bytes remain and Bob converges.
- First response for a persisted pending operation is dropped; retry creates one change/receipt.
- Server restarts on the same H2 directory; earlier revisions and max version remain queryable.

- [ ] **Step 5: Implement orchestration and verify**

Run: `mvn -pl syncverse-client -am -Dtest=ReconcilerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `mvn -pl syncverse-server -am -Dit.test=ReconnectE2EIT -Dfailsafe.failIfNoSpecifiedTests=false verify`

Expected: all bounded offline/restart/conflict cases pass.

- [ ] **Step 6: Review Milestone 3 metrics**

**Phase 5 gate metrics:** three missed operations applied in ascending order; client manifests converge within 5 test seconds; conflict data-loss count `0`; stale-delete automatic retry count `0`; dropped response leaves 1 receipt/1 revision; post-restart max version is not lower and all prior rows remain; fixed test sleeps above 100 ms count `0`.

- [ ] **Step 7: Commit**

```bash
git add syncverse-client syncverse-server/src/test/java/com/internship/syncverse/e2e/ReconnectE2EIT.java
git commit -m "feat: reconcile clients after offline changes"
```

---

## Phase 6: Hardening, Packaging, and Final Review

### Task 10: Error contract, correlated logs, process smoke test, and metrics report

**Files:**
- Create: `syncverse-server/src/main/java/com/internship/syncverse/server/api/RequestIdFilter.java`
- Modify: `syncverse-server/src/main/java/com/internship/syncverse/server/api/GlobalExceptionHandler.java`
- Create: `syncverse-server/src/main/resources/logback-spring.xml`
- Create: `syncverse-client/src/main/resources/logback.xml`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/server/api/ErrorContractIT.java`
- Test: `syncverse-server/src/test/java/com/internship/syncverse/e2e/PackagedJarsE2EIT.java`
- Create: `docs/verification/2026-08-05-syncverse-acceptance.md`

**Interfaces:**
- Consumes: complete application and final Maven artifacts.
- Produces: safe errors/logs, packaged-process evidence, and measured acceptance report.

- [ ] **Step 1: Write failing error-contract tests**

Assert malformed → 400, stale delete → 409, expired session → 410, oversized → 413, and injected unexpected exception → 500. Every body contains non-empty `code`, safe `message`, `requestId`, and `timestamp`; no stack trace, SQL, Base64, or full session ID appears.

- [ ] **Step 2: Implement request ID and safe mappings**

Generate or accept `X-Request-Id`, put it in MDC for request duration, return it in `ApiError`, and clear MDC in `finally`. Contextual logs include client, operation, filename, outcome, and versions but never file bytes or encoded payload.

- [ ] **Step 3: Configure concise Logback output**

Include timestamp, level, thread, logger, request/client/operation context, and message. Do not add a JSON encoder dependency.

- [ ] **Step 4: Write packaged-JAR process test**

After package, start `server.jar AlphaServer` on a random port and temp data directory, wait for HTTP readiness, start two `client.jar` processes with temp workspaces and `SYNCVERSE_SERVER_URL`, mutate Alice files, and assert Bob convergence. Terminate gracefully and capture logs on failure.

- [ ] **Step 5: Run clean full verification**

Run: `mvn clean verify`

Expected: reactor `BUILD SUCCESS`; 0 test failures/errors/unexpected skips; `syncverse-server/target/server.jar` and `syncverse-client/target/client.jar` exist and execute.

- [ ] **Step 6: Inspect artifacts and runtime dependencies**

Run:

```bash
mvn -pl syncverse-client dependency:tree
mvn -pl syncverse-server dependency:tree
jar tf syncverse-server/target/server.jar
jar tf syncverse-client/target/client.jar
```

**Artifact gate metrics:** client forbidden runtime dependency count `0`; server SLF4J major/provider count `1/1`; correct Main-Class in both manifests; external dependency directory required `false`; all earlier correctness metrics remain green.

- [ ] **Step 7: Review surgical diff**

Run:

```bash
git diff --check
git status --short
git diff --stat HEAD~1
```

Review every changed file against the spec. Remove only imports/files made unused by this implementation. Do not edit README or unrelated files.

- [ ] **Step 8: Write measured acceptance report**

Record exact commands, test totals/durations, JAR paths/sizes, process smoke result, measured convergence times, duplicate row counts, conflict byte-preservation, restart persistence, and deferred production hardening in `docs/verification/2026-08-05-syncverse-acceptance.md`.

- [ ] **Step 9: Commit final hardening/evidence**

```bash
git add syncverse-server syncverse-client docs/verification/2026-08-05-syncverse-acceptance.md
git commit -m "test: verify packaged SyncVerse workflow"
```

- [ ] **Step 10: Final fresh review gate**

Run:

```bash
mvn clean verify
git status --short
git log --oneline --decorate -12
```

Completion requires: clean working tree, all tests green, packaged process smoke green, exactly two runnable deliverables, and every acceptance metric recorded as an observed value rather than an estimate.
