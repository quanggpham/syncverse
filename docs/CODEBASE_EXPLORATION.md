# SyncVerse — Khám phá Codebase

> Tài liệu này hướng dẫn đọc hiểu toàn bộ mã nguồn SyncVerse: mô-đun nào có gì, các file liên kết với nhau ra sao, và đâu là những đoạn logic quan trọng cần nắm trước khi sửa code.
>
> **Đọc nhanh:** [Thuật ngữ](#thuật-ngữ-cần-nắm-từ-đầu) → [Tổng quan](#1-tổng-quan-kiến-trúc) → [Sơ đồ liên kết file](#2-sơ-đồ-liên-kết-file) → [3 luồng xử lý chính](#3-ba-luồng-xử-lý-chính) → [Điểm logic quan trọng](#4-điểm-logic-quan-trọng-cần-nắm)

---

## Thuật ngữ cần nắm từ đầu

> Giải thích nôm na **theo ngữ cảnh SyncVerse** — không phải định nghĩa từ điển. Đọc thuật ngữ trước, phần còn lại sẽ dễ hiểu hơn nhiều.

| Thuật ngữ | Giải thích |
|---|---|
| **Delta** | "Phần thay đổi". Trong SyncVerse: một *phiên bản mới* của một file (hoặc sự xóa) mà client đã bỏ lỡ. Khi gọi `GET /api/deltas?since=N`, server trả danh sách các thay đổi có số thứ tự lớn hơn N. Client dùng nó để bắt kịp những gì xảy ra lúc offline. |
| **Cursor** | "Con trỏ chỉ vị trí đọc" — chính là `lastSeenGlobalVersion`: một con số cho biết **client đã biết đến mutation mới nhất nào** trong `change_log`. Gửi `since=cursor` nghĩa là "cho tôi mọi thứ mới hơn con số này". Cursor được lưu vào file state nên client restart vẫn biết mình đang ở đâu. |
| **Global version** | Số thứ tự toàn cục, tăng dần cho **MỌI mutation của MỌI client** trên server (cột `global_version`, identity). Không phải version riêng của một file — là một trục thời gian duy nhất để mọi client so sánh thứ tự. |
| **File version** | Version riêng của **một file cụ thể** trong `file_state`: file A bị sửa lần thứ 3 thì `file_version=3` dù cả hệ thống đã có 100 mutation. Dùng để phát hiện **stale** (xem dưới). |
| **Manifest** | "Sổ đăng bộ" của client: danh sách mọi file client đang quản lý, mỗi file ghi **checksum + fileVersion + deleted**. Đây là "cái bóng" của thư mục local — dùng để so sánh disk có đổi không (so với manifest) và trạng thái server (so với remote). |
| **Checksum (SHA-256)** | "Vân tay" duy nhất của nội dung file (64 ký tự hex thấp). **SyncVerse so checksum chứ KHÔNG so chuỗi nội dung** để biết file có đổi hay giống. Server re-tính checksum từ bytes nhận được → không thể gửi checksum giả. |
| **Base version / Base** | Version mà client **tin rằng server đang có** cho một file, lấy từ manifest. Khi upload, client gửi kèm `baseFileVersion` để server kiểm chứng "bạn đang nhìn ở bản nào". Nếu khác version server hiện tại → tranh chấp (conflict), không đè mù. |
| **Stale / Stale delete** | Mutation dựa trên thông tin CŨ: version trong manifest (base) không còn bằng version server (do client khác đã sửa). **Stale delete** = client đòi xóa file nhưng base stale → server từ chối (409 `STALE_DELETE`) để không xóa nhầm nội dung mới hơn. |
| **Conflict copy** | Khi 2 client sửa cùng một file ở hai mốc khác nhau (không bên nào biết bên kia), server **không đè nhau**: mutation tới sau bị chuyển sang file phụ `<tên>.conflict-<client>-<op>.txt` thay vì ghi đè file chính. Cả 2 nội dung đều được giữ, không ai mất dữ liệu. |
| **Reconciliation (đối chiếu)** | Bước client "so 3 trạng thái" sau khi có session để rà đầy đủ: **base = manifest / local = disk / remote = delta**. So sánh từng file để biết phải upload thay đổi local, kéo thay đổi remote, hay để nguyên. Lý do có cái tên này nằm ở phần `Reconciler` bên dưới. |
| **Polling / Long-polling** | Vì client không có port nên không ai đẩy được → client phải **hỏi** server theo chu kỳ. Polling thường: hỏi đều đặn dù có gì hay không. **Long-polling**: giữ câu hỏi treo trên server tối đa 25s — có mutation mới thì trả ngay, hết 25s mà chưa có mới trả (rỗng). Vừa tiết kiệm tần suất hỏi, vừa phản ứng gần như tức thì. |
| **Session / Session ID** | "Phiên làm việc" giữa một client và server, định danh bằng UUID. Server giữ trong bộ nhớ; client phải **heartbeat mỗi 4s** để giữ phiên sống. Hết 15s (session-expiry) không heartbeat → session chết, mọi API trả 410 `SESSION_EXPIRED`; client phải làm lại RECONNECT. |
| **HELLO / REGISTER** | Message đầu tiên client gửi để đăng ký tên + lấy session mới (`POST /api/register`). Client chưa từng chạy = HELLO; client đã có state cũ = RECONNECT. |
| **RECONNECT** | Đăng ký lại khi client offline quay về: gửi tên + cursor cuối, server cấp session mới và cho biết version toàn cục hiện tại để client biết cần catch-up đến đâu. |
| **Catch-up / auto-healing** | Flow khi offline: lặp "gọi delta + áp dụng" đến khi cursor chạy kịp version server. Đây chính là **mục tiêu của Milestone 3** — đảm bảo client không mất dữ liệu dù từng offline, rồi tự hội tụ trở lại. |
| **Tombstone (dấu xóa)** | Khi xóa một file, server KHÔNG xóa hẳn row mà đánh dấu `deleted=true` + content NULL trong `file_state`. Lý do: client khác vẫn cần nhận delta "xóa" để biết và xóa cục bộ. Nhớ rằng `deleted=true` là tín hiệu "đã bị xóa", không phải file thật tồn tại. |
| **Pending operation** | Một upload đang dở mà client ghi vào state **TRƯỚC** khi gửi (gồm operationId, tên file, op, baseVersion, checksum, content). Nếu client crash ngay sau đó, khi khởi lại tự gửi lại (server dedup nhờ operationId). Nó chính là "đừng mất request giữa chừng". |

> **Mẹo đọc:** Nếu đọc thấy phần nào chưa rõ, quay lại bảng này. Các từ in đậm trong tài liệu (base, remote, stale, manifest, cursor...) đều được giải thích ở đây.

---

## 1. Tổng quan kiến trúc

SyncVerse là hệ thống đồng bộ file client–server theo HTTP. **Client không mở port** — mọi giao tiếp là HTTP đi ra từ client, server không có cách nào đẩy dữ liệu xuống; việc nhận cập nhật dựa vào client *polling*.

```
┌──────────────┐  HELLO / HEARTBEAT / FILE_CHANGE / RECONNECT   ┌──────────────┐
│  Client      │ ─────────────────────────────────────────────► │  Central     │
│  (Java 17,   │                                                │  Server      │
│   no Spring) │ ◄───────────────────────────────────────────── │  (Spring     │
│              │  DELTA_REQUEST / delta (long-poll)             │   Boot + H2) │
└──────────────┘                                                └──────────────┘
```

| Đặc điểm | Client | Server |
|---|---|---|
| Công nghệ | Java 17 thuần, JDK `HttpClient`, Jackson | Spring Boot 4.1, Spring MVC, Spring JDBC |
| Web server | **Không** (zero-port) | Tomcat `:8080` |
| Lưu trữ | State JSON cạnh workspace | H2 file mode, 3 bảng |
| Đóng gói | Shaded JAR (`client.jar`) | Spring Boot JAR (`server.jar`) |

### Mô-đun Maven (parent `pom.xml` → 3 module)

```
syncverse-parent
├── syncverse-common   ← DTO + enum thuần, KHÔNG có Spring/DB/filesystem
├── syncverse-server   → phụ thuộc syncverse-common
└── syncverse-client   → phụ thuộc syncverse-common   (client ↔ server KHÔNG phụ thuộc nhau)
```

- **`syncverse-common`** — "hợp đồng giao thức" dùng chung cả 2 phía:
  - `protocol/MessageType.java` — 5 message: `HELLO, HEARTBEAT, FILE_CHANGE, RECONNECT, DELTA_REQUEST`
  - `protocol/FileOperation.java` — `CREATE / UPDATE / DELETE`
  - `protocol/ChangeOutcome.java` — `APPLIED / CONFLICT_COPY_CREATED / DUPLICATE / CONFLICT_REJECTED`
  - `dto/` — các record request/response: `RegisterRequest/Response`, `HeartbeatRequest`, `FileChangeRequest/Response`, `DeltaResponse`, `FileRevision`, `ReconnectRequest`, `ApiError`
- **`syncverse-server`** — phân lớp theo package rõ ràng:
  - `api/` — REST controllers + exception mapping + filters (HTTP layer)
  - `session/` — quản lý session in-memory (`SessionService`)
  - `sync/` — **luật nghiệp vụ mutation** (`SyncService`) + validation + khóa toàn cục
  - `delta/` — long-polling (`DeltaService`, `ChangeNotifier`)
  - `persistence/` — SQL viết tay, không chứa logic nghiệp vụ
  - `config/` — `SyncProperties` (bind `syncverse.*` properties)
- **`syncverse-client`** — phân lớp theo trách nhiệm:
  - `cli/` — entry point + parse tham số (`CliArguments`, `SyncVerseClient`)
  - `http/` — client API thuần (interface `ServerApiClient` + `JdkServerApiClient`)
  - `fs/` — quét thư mục + watcher (`DirectoryScanner`, `DirectoryWatcher`, `RemoteFileApplier`)
  - `state/` — state local bền vững (`AtomicClientStateStore`, `ClientState`, `FileManifestEntry`, `PendingOperation`)
  - `sync/` — **điều phối toàn bộ** (`SyncCoordinator`, `Reconciler`, `ConnectionManager`, `UploadService`)

---

## 2. Sơ đồ liên kết file

### 2.1 Client — bộ não điều phối

```
                        ┌──────────────────────────────────────┐
                        │  SyncVerseClient (main)             │  khởi tạo toàn bộ
                        │  SyncVerseClient.java:27-74         │
                        └───┬────────────┬──────────────┬──────┘
                            │            │              │
              ┌─────────────▼──┐  ┌──────▼─────────┐  ┌─▼──────────────────┐
              │  ConnectionManager│  │  SyncCoordinator  │  │ AtomicClientStateStore │
              │  (sync/)        │  │  (sync/)           │  │ (state/)               │
              │  HELLO→RECONCILING│  │  pollLoop + reconcile│  │ <workspace>.syncverse- │
              │  HEARTBEAT 4s   │  │  + drainDirty        │  │ state.json (atomic)    │
              │  offline→reconnect│  └───┬───────┬─────┬───┘  └────────────────────────┘
              └───────┬─────────┘      │       │     │
                      │                │       │     │
        ┌─────────────▼──────┐  ┌─────▼────┐ ┌─▼──────────┐ ┌─▼──────────────────┐
        │   ServerApiClient   │  │ Reconciler│ │ UploadService│ │  RemoteFileApplier  │
        │   (http/)           │  │ (sync/)   │ │ (sync/)      │ │  (fs/)              │
        │   └ JdkServerApiClient│  │ quyết định │ │ upload + cập │ │ ghi revision remote │
        │   POST/GET HTTP JSON│  │ per-file   │ │ nhật manifest│ │ vào workspace       │
        └─────────────────────┘  └───────────┘ └──────────────┘ └─────────────────────┘
              ▲                                                              ▲
              │ HTTP                                  ┌──────────────────────┼──────────────┐
              │                                      │                      │              │
        ┌─────┴───────────────────┐         ┌─────────▼────────┐  ┌──────────▼───────┐  ┌───▼────────────┐
        │  SERVER (Spring Boot)   │         │ DirectoryScanner │  │ DirectoryWatcher │  │  FileSnapshot  │
        │  api/*Controller        │         │ (fs/) scan/snapshot│  │ (fs/) WatchService│  │ (fs/) checksum │
        └─────────────────────────┘         └──────────────────┘  └──────────────────┘  └────────────────┘
```

**Luồng khởi động (SyncVerseClient.java:27-74):**
1. `CliArguments.parse` → validate client name `[A-Za-z0-9_-]{1,64}` + workspace (tự tạo nếu chưa có)
2. `ServerApiClient.http(serverUri)` — server URL từ env `SYNCVERSE_SERVER_URL`, mặc định `http://localhost:8080`
3. `AtomicClientStateStore.load()` — đọc state cũ (nếu có); **lỗi nếu state thuộc client name khác**
4. Tạo `ConnectionManager` (heartbeat 4s, retry exponential 1s→30s) + `SyncCoordinator`, rồi `connection.start()`

### 2.2 Server — Spring Boot DI: Controller → Service → Repository → H2

```
 HTTP request
   │
   ▼
 RequestIdFilter (X-Request-Id → MDC) ──► HttpRequestSizeLimitFilter (chặn body > 2MB)
   │
   ▼
 ┌───────────────────────────────  API layer ───────────────────────────────┐
 │  RegistrationController  HeartbeatController  FileChangeController  DeltaController │
 │  POST /api/register     POST /api/heartbeat  POST /api/files/changes  GET /api/deltas │
 │  POST /api/reconnect    (yêu cầu messageType) (nếu CONFLICT_REJECTED  ?since=N        │
 │  (yêu cầu messageType)  (204)                 → ném StaleDeleteException 409)  X-Session-Id │
 └──────────────┬─────────────────────┬───────────────────────┬──────────────────┘
                │                     │                       │
                ▼                     ▼                       ▼
        ┌─ SessionService ──┐   ┌─ SyncService ─────────┐  ┌─ DeltaService ────────┐
        │ register /        │   │ apply()               │  │ poll()                │
        │ heartbeat /       │   │  GlobalMutationLock   │  │  findAfter(cursor)    │
        │ requireActive     │   │  FileChangeValidator  │  │  rỗng → ChangeNotifier│
        │ (in-memory, 15s)  │   │  ConflictNameGenerator│  │  .awaitAfter(25s)     │
        └─────────┬─────────┘   └──┬──────────┬─────────┘  └──────────┬───────────┘
                  │                │          │                       │
                  ▼                ▼          ▼                       ▼
        ┌─────────────────┐  ┌──────────────┐  ┌───────────────────┐
        │  persistence layer (JdbcTemplate, SQL viết tay)          │
        │  FileStateRepository  ChangeLogRepository  OperationReceiptRepository │
        │  (file_state)         (change_log)         (operation_receipt)        │
        └───────────────────────────────┬───────────────────────────────────────┘
                                        ▼
                                    H2 file DB (schema.sql)
```

- **Schema (schema.sql)**: 3 bảng + CHECK constraint bảo vệ hình dạng dữ liệu:
  - `file_state` — trạng thái *hiện tại* của từng file (content BLOB, checksum, `file_version`, `deleted`, `modified_by/at`). Ràng buộc: file deleted thì content phải NULL.
  - `change_log` — **append-only log phiên bản toàn cục**: `global_version` IDENTITY (tăng dần), mỗi dòng 1 mutation. DELETE không mang content.
  - `operation_receipt` — biên nhận idempotency theo `operation_id` (outcome, filename đề xuất vs chấp nhận, version).
- **`SyncVerseServer.java:29-32`** — bean `Clock` (UTC) + `SessionService` được khai báo thủ công trong `main` (config class).

---

## 3. Ba luồng xử lý chính

### 3.1 Luồng LIVE — file thay đổi local → các client khác nhận

```
User sửa file ở Alice
  │
  ▼
DirectoryWatcher (WatchService, debounce 300ms, bỏ qua OVERFLOW→full rescan)
  │ fileChanged.accept(filename)                 [DirectoryWatcher.java:85-90]
  ▼
SyncCoordinator.enqueueFilename → dirty.add → drainDirty      [SyncCoordinator.java:381-409]
  │
  ▼
handleLocal: chụp snapshot → Reconciler quyết định UPLOAD_LOCAL
  │                                                     [SyncCoordinator.java:310-347]
  ▼
UploadService.submitWithResponse:
  │  - lưu PendingOperation vào state (crash an toàn)
  │  - POST /api/files/changes (baseFileVersion = version trong manifest)
  │  - cập nhật manifest theo outcome                      [UploadService.java:33-76]
  ▼
SERVER: SyncService.apply → validator → GlobalMutationLock → transaction
  │  - idempotency: receipt trùng operation_id → trả response cũ
  │  - stale (baseVersion ≠ current): DELETE → CONFLICT_REJECTED (409)
  │                                 UPDATE → conflict copy .conflict-<client>-<op>.txt
  │  - ok: append change_log + upsert file_state + insert receipt
  │  - signal ChangeNotifier                                [SyncService.java:80-99]
  ▼
Bob đang long-poll GET /api/deltas?since=cursor
  │  ChangeNotifier báo → DeltaService trả ngay (tối đa 20 revision/lần)
  ▼
SyncCoordinator.pollLoop: accept(response) → RemoteFileApplier
  │  ghi file atomic (temp + ATOMIC_MOVE) + cập nhật manifest + lưu state
  │                                                       [SyncCoordinator.java:411-445]
  ▼
Workspace Bob có file mới (còn ghi file như cũ → Bob không upload lại nhờ
  manifest cập nhật trước, watcher đã debounce)
```

### 3.2 Luồng RECONNECT / Auto-healing (offline → online)

```
Client offline (crash/mất mạng)
  │  Heartbeat fail → ConnectionManager chuyển OFFLINE, retry exponential 1s→30s
  ▼
Reconnect thành công:
  │  POST /api/reconnect { clientName, lastSeenGlobalVersion }   [ConnectionManager.java:161-169]
  ▼
Server cấp session mới + trả currentGlobalVersion (max change_log)
  ▼
SyncCoordinator.pollLoop: session ≠ reconciledSession → reconcileSession(target)
  │  [SyncCoordinator.java:411-425]
  ├─ retryPending(): nếu state còn pendingOperation cũ → gửi lại (idempotent)
  ├─ fetchThrough(target): lặp GET /api/deltas cho tới khi cursor đuổi kịp target
  │                        [SyncCoordinator.java:173-190]
  └─ reconcile(base manifest, local scan, remote revisions): [SyncCoordinator.java:192-236]
       với MỖI file → Reconciler.reconcile() quyết định:
       NO_OP / UPLOAD_LOCAL / APPLY_REMOTE / APPLY_DELETE / UPLOAD_CONFLICT
  ▼
reconciliationComplete → ConnectionManager sang ONLINE → poll bình thường
```

### 3.3 Luồng GHI FILE remote → local (RemoteFileApplier)

```
apply(state, revision)                                  [RemoteFileApplier.java:30-49]
  │
  ├─ DELETE → validate (không được mang content) → Files.deleteIfExists
  ├─ CREATE/UPDATE → validateContent (base64, ≤1MB, checksum khớp)
  │    → ghi temp file (.syncverse-*.tmp) → ATOMIC_MOVE (fallback REPLACE) [92-110]
  │
  └─ cập nhật manifest (checksum, fileVersion, deleted) → stateStore.save → trả state mới
```

---

## 4. Điểm logic quan trọng cần nắm

### 4.1 `Reconciler.reconcile()` — quyết định cho mọi file (client)

**File quan trọng nhất để hiểu hành vi sync.** So sánh 3 trạng thái (chỉ bằng *presence + SHA-256 checksum*, không bao giờ so nội dung):

| base (manifest) | local (disk) | remote (revision) | Kết luận |
|---|---|---|---|
| giống local | giống remote | — | NO_OP |
| local khác base | remote giống base | — | UPLOAD_LOCAL |
| local giống base | remote khác base | — | APPLY_REMOTE / APPLY_DELETE |
| local ≠ base **và** remote ≠ base | — | — | local không tồn tại + remote tồn tại → **APPLY_REMOTE** (khôi phục file bị xóa nhầm) |
| | | | ngược lại → **UPLOAD_CONFLICT** |

**Trường hợp đặc biệt quan trọng:** local bị xóa trong khi remote đổi mới → `APPLY_REMOTE` khôi phục file (không upload DELETE) — chính là "stale delete bị triệt tiêu" trong `LiveSyncIT`.

### 4.2 `SyncService.apply()` — luật mutation trên server

```
Duplicate (operation_id đã có receipt) ──► trả lại response cũ (idempotent)
Stale DELETE (baseVersion ≠ current) ────► CONFLICT_REJECTED → 409 STALE_DELETE
Stale CREATE/UPDATE ────────────────────► conflict copy: <name>.conflict-<client>-<op>.txt
                                          (nếu trùng tên → thêm suffix, lặp tới khi trống)
OK ─────────────────────────────────────► append change_log + upsert file_state + receipt
```

Tất cả nằm trong `GlobalMutationLock` (ReentrantLock fair) + transaction, nên **mutation được serialize toàn bộ** — đảm bảo `global_version` tăng đơn điệu và không race. Khi response có `globalVersion != null` → `ChangeNotifier.signalCommitted` đánh thức các long-poll.

### 4.3 Idempotency — vì sao không bao giờ trùng revision

Mỗi mutation client tạo `operationId` UUID (giữ nguyên qua retry). Server lưu vào `operation_receipt` **cùng transaction**. Gửi lại lần 2 → tìm thấy receipt → trả y hệt response cũ, **không** ghi thêm `change_log`. Nhờ vậy mất response giữa đường (client retry) không tạo duplicate. Client cũng lưu `pendingOperation` vào state **trước khi gửi** để crash giữa chừng vẫn gửi lại được.

### 4.4 Trạng thái client — "nơi sự thật" cho recovery

`ClientState(formatVersion, clientName, lastSeenGlobalVersion, manifest, pendingOperation)` lưu vào `<workspace>.syncverse-state.json`:
- Ghi **atomic** (temp file + rename) — không bao giờ hỏng giữa chừng
- Lưu **sau mỗi revision áp dụng** và **trước mỗi upload** — nên crash ở bất kỳ đâu cũng recovery được
- `lastSeenGlobalVersion` = cursor delta; `manifest` = fileVersion từng file; `pendingOperation` = tối đa 1 upload đang dở
- **Lỗi khi start** nếu state thuộc client name khác (bảo vệ workspace chéo)
- Không được chỉnh tay file này khi client đang chạy (`docs/RUN_AND_DEMO.md:187`)

### 4.5 Session & trạng thái kết nối (server ↔ client)

- **Server** (`SessionService`): in-memory `ConcurrentMap<UUID, ClientSession>`; expire sau `syncverse.session-expiry` (15s) nếu không heartbeat; expired → 410 `SESSION_EXPIRED` (`GlobalExceptionHandler.java:57-60`). `requireActive` là cổng kiểm tra cho mọi API.
- **Client** (`ConnectionManager`): máy trạng thái `STARTING → RECONCILING → ONLINE ⇄ OFFLINE → STOPPED`:
  - `ONLINE`/`RECONCILING` → heartbeat mỗi 4s (`syncverse.heartbeat-interval`)
  - Heartbeat fail → `OFFLINE`, retry theo `RetryPolicy.exponential(1s, 30s)`
  - `OFFLINE` → gọi reconnect → `RECONCILING` → SyncCoordinator báo `reconciliationComplete` → `ONLINE`
  - Lỗi 4xx vĩnh viễn → `STOPPED` (dừng hẳn)

### 4.6 Kiểm soát kích thước — nhiều tầng

| Tầng | Giới hạn | Kết quả |
|---|---|---|
| Client scan (`DirectoryScanner.java:16`) | file > 1 MiB | bỏ qua (không sync) |
| Client ghi remote (`RemoteFileApplier.java:83`) | content > 1 MiB | ném `InvalidRemoteRevisionException` |
| HTTP body (`HttpRequestSizeLimitFilter.java:25`) | request > 2 MiB (base64 overhead) | 413 `FILE_TOO_LARGE` |
| Server validate (`FileChangeValidator.java:17`) | decoded > 1 MiB | 413 `FILE_TOO_LARGE` |

### 4.7 An toàn đường dẫn (path traversal)

- **Server**: `FileChangeValidator.validateFilename` — chặn `/`, `\`, `..`, NUL, > 255 ký tự, phải là base name
- **Client**: `RemoteFileApplier.target` — resolve + kiểm tra `parent == workspace`, ném `InvalidRemoteRevisionException` nếu thoát workspace
- DTO luôn đi kèm checksum; server **re-verify checksum từ nội dung decoded** (`FileChangeValidator.java:55-57`) — client gửi checksum sai là bị từ chối, không tin tưởng dữ liệu đầu vào

### 4.8 Nội dung không bao giờ vào log

Server log metadata (client, operationId, outcome, version) — không log `contentBase64`/checksum. `PackagedJarsE2EIT` có assertion `assertFalse(readLog(serverLog).contains("contentBase64"))` chặn regression.

---

## 5. Bảng map nhanh "khi cần sửa gì thì đọc file nào"

| Nếu cần... | Đọc file chính | Kèm theo |
|---|---|---|
| Sửa quyết định đồng bộ (ai thắng, khi nào upload/apply) | `Reconciler.java` | `SyncCoordinator.java` |
| Sửa luật mutation trên server (conflict, idempotency) | `SyncService.java` | `FileChangeValidator.java`, `ConflictNameGenerator.java` |
| Sửa giao thức / DTO | `syncverse-common/dto/*` | `protocol/*` |
| Sửa giao tiếp HTTP client | `ServerApiClient.java` | `ServerApiException.java` |
| Sửa cách ghi/đọc state local | `AtomicClientStateStore.java` | `ClientState.java` |
| Sửa việc phát hiện thay đổi file | `DirectoryWatcher.java` | `DirectoryScanner.java` |
| Sửa long-polling server | `DeltaService.java` | `ChangeNotifier.java` |
| Sửa SQL / schema | `persistence/*Repository.java` | `schema.sql` |
| Sửa session / heartbeat | `SessionService.java` | `ConnectionManager.java` |
| Sửa config | `SyncProperties.java` | `application.properties` |
| Sửa error/status code | `GlobalExceptionHandler.java` | `StaleDeleteException` (FileChangeController) |
| Hiểu hành vi offline conflict (spec chuẩn) | `LiveSyncIT.java` | `docs/superpowers/specs/*` |

---

## 6. Ghi chú phát triển (nơi người mới hay vấp)

1. **Client KHÔNG có Spring** — không được import Spring vào `syncverse-client` (chỉ có Jackson + SLF4J trong pom).
2. **`syncverse-common` là hợp đồng chung** — sửa DTO ở đây ảnh hưởng cả 2 phía; phải sửa đồng bộ client + server.
3. **`global_version` là nguồn sự thật duy nhất cho thứ tự** — đừng dùng timestamp để so sánh thứ tự sync.
4. **Sửa `Reconciler` phải kèm test** — đây là chỗ quyết định "dữ liệu có bị mất hay không"; xem `ReconcilerTest` + các IT trong `LiveSyncIT`.
5. **IT phải chạy qua `verify`** (failsafe), không chạy qua `test` (surefire). `PackagedJarsE2EIT` cần JAR đã build sẵn.
6. **Bảo toàn tính chất: không log nội dung file** — nếu thêm log, kiểm tra assertion của `PackagedJarsE2EIT`.
