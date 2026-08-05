# SyncVerse Acceptance Report

Date: 2026-08-05  
Branch: `codex/syncverse-implementation`  
Runtime used for verification: Java 21.0.12; Maven compiler target: Java 17

## Outcome

SyncVerse meets the assignment scope as one persistent central server and multiple
outbound-only clients. The build produces exactly two self-contained runnable
deliverables: `server.jar` and `client.jar`.

## Verification commands and observed results

| Command | Observed result |
|---|---|
| `mvn clean verify` | `BUILD SUCCESS`; 102 tests; 0 failures; 0 errors; 0 skipped; Maven wall time 33.278 s |
| `mvn -pl syncverse-server -am "-Dit.test=PackagedJarsE2EIT" "-Dfailsafe.failIfNoSpecifiedTests=false" verify` | Packaged-process smoke test 1/1 passed |
| `mvn -pl syncverse-client dependency:tree` | 0 Spring, servlet, Tomcat, Jetty, Netty, JDBC, or H2 dependencies in the client tree |
| `mvn -pl syncverse-server dependency:tree` | SLF4J API 2.0.18 appears once; Logback Classic 1.5.34 is the single runtime provider |
| `git diff --check` | 0 whitespace errors |

Test distribution and summed test-case durations from JUnit XML:

| Module | Tests | Test-case time |
|---|---:|---:|
| `syncverse-common` | 2 | 0.183 s |
| `syncverse-client` | 48 | 1.922 s |
| `syncverse-server` unit + integration | 52 | 17.613 s |
| **Total** | **102** | **19.718 s** |

## Packaged-process evidence

`PackagedJarsE2EIT` starts the built server and two built clients as three separate
operating-system processes, using a random server port, a temporary file-backed H2
directory, and two temporary workspaces. Alice then creates, updates, and deletes a
root file; Bob converges after every mutation.

Observed convergence times from the packaged JAR run:

| Mutation | Alice-to-Bob convergence |
|---|---:|
| Create | 440 ms |
| Update | 377 ms |
| Delete | 378 ms |

All three are below the 5-second acceptance threshold. Before controlled shutdown,
the server and both clients were alive. Captured server logs contained no
`contentBase64` payload.

## Artifact evidence

| Artifact | Size | Manifest entry point | External dependency directory |
|---|---:|---|---|
| `syncverse-server/target/server.jar` | 23,844,203 bytes | Boot `JarLauncher`; `Start-Class: com.internship.syncverse.server.SyncVerseServer` | Not required |
| `syncverse-client/target/client.jar` | 3,564,676 bytes | `Main-Class: com.internship.syncverse.client.SyncVerseClient` | Not required |

The manifests report the local build JDK as 21 while Maven compiles with
`maven.compiler.release=17`; runtime bytecode compatibility therefore targets Java 17.

## Correctness metrics

| Area | Observed evidence |
|---|---|
| Protocol and limits | Flat-name, Base64, checksum, DELETE shape, 1,048,576-byte acceptance, and +1-byte rejection tests pass |
| Ordering | A 20-request concurrency test produces 20 unique increasing committed versions |
| Idempotency | Replaying the same persisted operation returns its original outcome and leaves exactly 1 change row and 1 receipt |
| Live sync | Create/update/delete converge; remote apply produces 0 feedback uploads |
| Reconnect | Three missed revisions are consumed in ascending order and converge within the bounded test window |
| Conflict safety | Canonical and conflict copies preserve both byte arrays; dropped conflict ACK does not duplicate unchanged content |
| Edit-after-ACK-loss safety | A later local edit produces one additional intentional conflict; both old pending bytes and newer bytes survive |
| Stale delete | Canonical bytes remain unchanged, no change-log row is added for the rejected delete, and the client reconciles to server state |
| Restart durability | Reopening the same H2 path preserves earlier revisions and does not lower the maximum global version |
| Error contract | HTTP 400/409/410/413/500 return safe `ApiError` bodies with matching `X-Request-Id`; no stack trace, SQL detail, Base64 field, or complete session ID is returned; the client classifies 409 stale delete as permanent/reconcile rather than retryable |
| Test timing | Fixed sleeps above 100 ms: 0; polling helpers use 25-50 ms checks with explicit deadlines |

## Phase review record

Each milestone passed a test-metric gate and an independent code review. Phase 5's
final review reported no Critical, Important, or Minor findings after the
reconciliation barrier, session-aware completion, idempotent conflict replay, and
edit-after-response-loss cases were covered.

## Deliberately deferred production hardening

These are not required for the assignment demo and were intentionally excluded to
avoid over-engineering:

- Authentication, authorization, TLS termination, and tenant isolation.
- Multi-instance server coordination or a distributed ordered log.
- PostgreSQL, schema migration tooling, backups, retention, quotas, and disaster recovery.
- Recursive directory sync, symbolic-link following, large-file chunking, and a UI.
- Production deployment packaging, monitoring/alerting, and operational runbooks.

For a real deployment, security and recovery requirements should be derived from
the actual trust boundary, data sensitivity, user count, and recovery objectives
before selecting any of those additions.
