# catalog-direct

## Design stance
Vào thẳng danh sách item, tối ưu việc tra cứu lặp lại.

## Key choices
- Chest 54 slots / 9×6, navigation cố định.
- Bảng bên cạnh là tooltip/lore mô phỏng, không phải web-admin sản phẩm.
- Icon vector nội bộ, system font; không tải asset ngoài.
- Filter, pagination, detail/history/holders/storage, empty/error/close tương tác.
- Tất cả dữ liệu là MINH HỌA, không kết nối DB/server.

## Trade-offs
Nhanh cho staff thường dùng; thanh bộ lọc dày hơn màn chọn việc.

## Best for
Staff điều tra item trong game. Không có grant/delete, không thay Paper visual acceptance.
