# TV m3u8

Ứng dụng Android phát danh sách IPTV M3U/M3U8 bằng ExoPlayer (Media3).

## Tính năng

- Mở màn hình danh sách kênh ngay khi khởi động; không tự phát kênh.
- Chọn kênh để phát toàn màn hình. Chạm video hoặc nhấn OK/Enter để mở danh sách kênh ở cạnh phải; nhấn Trở về để dừng phát và quay về lưới chính.
- Hiển thị tên kênh và logo `tvg-logo` trong `#EXTINF`.
- Chỉ lưu một URL playlist HTTP/HTTPS. Nhập URL mới sẽ thay playlist cũ và tải ngay.
- Nút cập nhật tải lại playlist đã lưu; nếu tải lỗi, danh sách kênh cũ vẫn được giữ để tiếp tục xem.
- Nút và chữ lớn, tương phản cao, phù hợp thao tác đơn giản.
- Hỗ trợ launcher và điều khiển TV, điện thoại, máy tính bảng; giao diện thích ứng kích thước màn hình và không khóa hướng xoay.
- Nút CH+/CH− trên remote đổi kênh; khi danh sách đang mở, danh sách giữ nguyên và đánh dấu kênh đang phát.
- Icon launcher TV m3u8 là WebP trong `app/src/main/res/mipmap-nodpi/m3utv_icon.webp` để giảm dung lượng; nền đỏ cam kiểu công nghệ, góc ngoài trong suốt.
- Banner ngang dành cho Android TV nằm tại `app/src/main/res/drawable-nodpi/tv_m3u8_banner.webp`.
- Màn hình chính có đồng hồ, ngày dương, ngày âm và thời tiết theo vị trí chọn. Danh sách vị trí lấy từ `app/src/main/assets/locations.json`; nhấn thẻ thời tiết để chọn xã/phường hoặc trung tâm tỉnh/thành.
- Nút bánh răng mở phần cài đặt để nhập hoặc đổi URL M3U, chọn vị trí thời tiết và chu kỳ tự cập nhật. Giao diện dùng tông xanh quân đội.
- Thời tiết hiện tại lấy theo tọa độ từ [Open-Meteo Forecast API](https://open-meteo.com/en/docs), làm mới mỗi 30 phút. Ngày âm được tính cục bộ theo giờ Việt Nam.

## Build APK

Mở thư mục này bằng Android Studio (JDK 17), đợi Gradle tải các thư viện, rồi chọn **Build > Build APK(s)**. APK debug sẽ được tạo ở `app/build/outputs/apk/debug/app-debug.apk`.

Hoặc dùng Gradle 8.9:

```powershell
gradle assembleDebug
```

Nếu cài bằng sideload, Android có thể yêu cầu cho phép cài ứng dụng từ nguồn này.

## Định dạng playlist

Playlist cần theo định dạng M3U có các dòng `#EXTINF` rồi đến URL stream. Logo được đọc từ thuộc tính `tvg-logo`. Với playlist cục bộ, nên dùng URL đầy đủ cho từng luồng; các đường dẫn tương đối qua SAF không được Android cho phép truy cập tự do.
