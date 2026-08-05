# SyncVerse — Hướng dẫn build, chạy và demo

Tài liệu này dùng PowerShell trên Windows và giả định terminal đang đứng tại thư mục gốc project:

```text
C:\Workspace\VCCORP\syncverse
```

## 1. Yêu cầu môi trường

Kiểm tra Java và Maven:

```powershell
java -version
mvn -version
```

Project compile về Java 17. Có thể build và chạy bằng JDK 17 hoặc mới hơn.

## 2. Build hai executable JAR

Luôn chạy clean build sau khi đổi branch hoặc pull code:

```powershell
mvn clean verify
```

Khi build thành công, hai file chạy độc lập là:

```text
syncverse-server\target\server.jar
syncverse-client\target\client.jar
```

Kiểm tra kích thước:

```powershell
Get-Item .\syncverse-server\target\server.jar, .\syncverse-client\target\client.jar |
    Select-Object FullName, Length
```

Kích thước tham khảo:

- `server.jar`: khoảng 23–24 MB.
- `client.jar`: khoảng 3–4 MB.

Nếu file chỉ khoảng 2 KB và báo `no main manifest attribute`, bạn đang dùng artifact cũ hoặc chưa chạy `mvn clean verify` trên code đã merge.

## 3. Chạy server và hai client

Cần ba terminal PowerShell, đều đứng tại thư mục gốc project.

### Terminal 1 — Server

```powershell
$env:SYNCVERSE_DATA_DIR = "$PWD\runtime-data"
java -jar .\syncverse-server\target\server.jar AlphaServer
```

Server mặc định lắng nghe tại `http://localhost:8080`. Dữ liệu H2 được lưu trong `runtime-data`, nên restart server không làm mất lịch sử sync.

### Terminal 2 — Alice

```powershell
$env:SYNCVERSE_SERVER_URL = "http://localhost:8080"
java -jar .\syncverse-client\target\client.jar Alice_Node .\workspace_alice
```

### Terminal 3 — Bob

```powershell
$env:SYNCVERSE_SERVER_URL = "http://localhost:8080"
java -jar .\syncverse-client\target\client.jar Bob_Node .\workspace_bob
```

Client tự tạo workspace nếu chưa tồn tại. Giữ cả ba process chạy trong lúc demo.

## 4. Demo create, update và delete

Mở terminal PowerShell thứ tư tại thư mục gốc project.

### Create từ Alice

```powershell
Set-Content .\workspace_alice\hello.txt "Hello from Alice"
```

Quan sát phía Bob:

```powershell
Get-Content .\workspace_bob\hello.txt
```

Kết quả mong đợi:

```text
Hello from Alice
```

### Update từ Alice

```powershell
Set-Content .\workspace_alice\hello.txt "Alice updated this file"
Get-Content .\workspace_bob\hello.txt
```

Kết quả phía Bob sẽ đổi thành:

```text
Alice updated this file
```

### Delete từ Alice

```powershell
Remove-Item .\workspace_alice\hello.txt
Test-Path .\workspace_bob\hello.txt
```

Sau khi sync hoàn tất, `Test-Path` trả về `False`.

Sync sử dụng watcher và long-poll nên thường hội tụ dưới một giây. Nếu đọc quá nhanh, chạy lại lệnh quan sát sau một lát.

## 5. Demo offline conflict không mất dữ liệu

Đầu tiên, khi Alice và Bob đều online, tạo file nền:

```powershell
Set-Content .\workspace_alice\shared.txt "Base version"
Get-Content .\workspace_bob\shared.txt
```

Sau khi Bob đã nhận `Base version`:

1. Nhấn `Ctrl+C` ở terminal Bob để Bob offline.
2. Alice sửa file và để Alice tiếp tục online:

```powershell
Set-Content .\workspace_alice\shared.txt "Alice online version"
```

3. Trong lúc Bob vẫn tắt, sửa bản local của Bob:

```powershell
Set-Content .\workspace_bob\shared.txt "Bob offline version"
```

4. Khởi động lại Bob:

```powershell
$env:SYNCVERSE_SERVER_URL = "http://localhost:8080"
java -jar .\syncverse-client\target\client.jar Bob_Node .\workspace_bob
```

5. Quan sát workspace Bob:

```powershell
Get-ChildItem .\workspace_bob
Get-Content .\workspace_bob\shared.txt
Get-Content .\workspace_bob\shared.conflict-Bob_Node-*.txt
```

Kết quả mong đợi:

- `shared.txt` chứa `Alice online version` — canonical version trên server.
- File `shared.conflict-Bob_Node-<operation>.txt` chứa `Bob offline version`.

Như vậy cả hai nội dung đều được giữ lại thay vì silent last-write-wins.

## 6. Quan sát log và trạng thái

Server log mỗi mutation với các trường như client, operation ID, filename, outcome và version; không log nội dung file.

Các outcome thường gặp:

- `APPLIED`: mutation được áp dụng vào canonical file.
- `CONFLICT_COPY_CREATED`: server giữ mutation trong conflict copy.
- `CONFLICT_REJECTED`: stale delete bị từ chối để bảo vệ canonical mới hơn.

Client lưu cursor, manifest và pending operation ở file sibling của workspace:

```text
workspace_alice.syncverse-state.json
workspace_bob.syncverse-state.json
```

Không chỉnh tay các file state trong lúc client đang chạy.

## 7. Dừng và reset demo

Nhấn `Ctrl+C` tại các terminal client và server trước khi reset.

Lệnh sau xóa toàn bộ dữ liệu demo và không thể khôi phục:

```powershell
Remove-Item -Recurse -Force .\runtime-data, .\workspace_alice, .\workspace_bob
Remove-Item -Force .\workspace_alice.syncverse-state.json, .\workspace_bob.syncverse-state.json `
    -ErrorAction SilentlyContinue
```

Sau đó chạy lại ba process theo mục 3.

## 8. Troubleshooting

### `no main manifest attribute`

Nguyên nhân: JAR được build từ code cũ hoặc build trước khi cấu hình Boot/Shade được merge.

Khắc phục:

```powershell
git branch --show-current
mvn clean verify
Get-Item .\syncverse-server\target\server.jar, .\syncverse-client\target\client.jar |
    Select-Object FullName, Length
```

### Port 8080 đang được dùng

Chọn port khác cho server:

```powershell
$env:SERVER_PORT = "8090"
$env:SYNCVERSE_DATA_DIR = "$PWD\runtime-data"
java -jar .\syncverse-server\target\server.jar AlphaServer
```

Hai client phải dùng cùng URL:

```powershell
$env:SYNCVERSE_SERVER_URL = "http://localhost:8090"
```

### Client chưa sync

Kiểm tra lần lượt:

```powershell
Test-NetConnection localhost -Port 8080
Get-Process java
Get-ChildItem .\workspace_alice
Get-ChildItem .\workspace_bob
```

Đồng thời xem log server/client để tìm `SESSION_EXPIRED`, lỗi kết nối hoặc file vượt giới hạn 1 MiB.

### Giới hạn của assignment

- Chỉ sync regular file ngay tại root workspace.
- Không sync thư mục con hoặc symbolic link.
- Kích thước decoded tối đa là 1 MiB mỗi file.
- Không có authentication/TLS vì đây là assignment chạy demo local.
