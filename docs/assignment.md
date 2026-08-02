# Assignment: SyncVerse — File Synchronization System

Viết một ứng dụng client – server để đồng bộ dữ liệu giữa các client với nhau dựa vào http restful.

1. Bạn cần phát triển một công cụ đồng bộ dữ liệu nội bộ siêu nhẹ tên là SyncVerse. Công
cụ này giúp đồng bộ các file cấu hình và tài liệu làm việc giữa nhiều máy trạm trong môi trường
doanh nghiệp mà không cần mở bất kỳ WebServer/Port nào ở phía Client (để tránh rủi ro bảo mật
và phiền hà về cấu hình NAT/Firewall).

2. Nhiệm vụ của bạn: Xây dựng hệ thống gồm Central Server (RESTful API) và các Client
Daemon CLI tự động phát hiện và đồng bộ file theo thời gian thực.

3. Ràng buộc kỹ thuật (Technical Constraints)
- Protocol: Sử dụng HTTP RESTful (kết hợp Polling / Long-Polling) cho giao tiếp Client-Server.
- Zero Client Webserver: Client tuyệt đối KHÔNG mở port hay chứa webserver.
- Flat Directory: Folder đồng bộ là thư mục phẳng (không chứa thư mục con).
- File Size Cap: Kích thước mỗi file ≤ 1MB.
- Executable Build: Đóng gói ra 2 file JAR độc lập: server.jar và client.jar.

4. Protocol & Message Architecture

Toàn bộ luồng đồng bộ sẽ dựa trên 5 loại Message chuẩn hóa:

| Message Type | Luồng truyền | Mô tả & Mục đích |
|---|---|---|
| HELLO | Client → Server | Đăng ký Client ID và khởi tạo phiên làm việc mới |
| HEARTBEAT | Client → Server | Tín hiệu sống (Ping) gửi định kỳ để Server duy trì session |
| FILE_CHANGE | Client → Server | Thông báo khi có file trong thư mục bị Tạo mới / Sửa / Xóa |
| RECONNECT | Client → Server | Báo hiệu kết nối lại sau một khoảng thời gian bị Offline |
| DELTA_REQUEST | Client ↔ Server | Yêu cầu Server trả về danh sách thay đổi (Delta) mà Client đã bỏ lỡ |

5. Vibe-Coding Roadmap (Phân chia Milestone)

### Milestone 1: Handshake & Heartbeat
- Server: Cung cấp các REST API cho phép đăng ký client (`/api/register`) và nhận heartbeat (`/api/heartbeat`).
- Client: Đọc tham số dòng lệnh, gửi HELLO lấy Client ID và khởi chạy Background Thread duy trì HEARTBEAT mỗi 3–5 giây.

### Milestone 2: Live Monitor & Push Update
- Client: Dùng `java.nio.file.WatchService` để lắng nghe sự thay đổi file local. Khi phát hiện biến động → tạo payload FILE_CHANGE gửi lên Server.
- Server: Tiếp nhận payload, lưu trữ trạng thái phiên bản mới nhất (Version/Timestamp) và nội dung file.

### Milestone 3: Auto-Healing & Catch-up Sync (Trọng tâm)
- Khi một Client bị ngắt kết nối (crash, ngắt mạng) rồi kết nối lại:
  1. Client gửi RECONNECT kèm thông tin version/timestamp cuối cùng nó ghi nhận.
  2. Client phát DELTA_REQUEST để xin Server các file bị lệch phiên bản.
  3. Server tính toán diff và trả về bản cập nhật → Client tự động kéo về và cập nhật thư mục local cho khớp hoàn toàn.

6. Chuẩn khởi chạy dòng lệnh (Execution Spec)

**Chạy Server:**
```bash
java -jar server.jar AlphaServer
```

**Chạy các Client song song (Mỗi Client quản lý một thư mục riêng):**
```bash
# Terminal 1 - Client Alice
java -jar client.jar Alice_Node ./workspace_alice

# Terminal 2 - Client Bob
java -jar client.jar Bob_Node ./workspace_bob

# Terminal 3 - Client Charlie
java -jar client.jar Charlie_Node ./workspace_charlie
```

---

**LƯU Ý:** Project của các bài tập upload lên git thì phải để public để review code
