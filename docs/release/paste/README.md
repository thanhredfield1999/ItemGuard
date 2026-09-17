# Đăng ItemGuard LITE lên SpigotMC — làm theo đúng thứ tự

Trang: https://www.spigotmc.org/resources/add

Thư mục cần mở song song một cửa sổ Explorer:
    E:\AI.WORK\ItemGuard\release\spigot-upload

**Mọi câu chữ để dán KHÔNG nằm trong file này.** Nằm trong `_PASTE-THIS-TO-CLAUDE.md`
(cùng thư mục) — file đó do máy sinh ra từ `description.bbcode.txt`, tự kiểm hash của nó với
jar, và tự đọc ô ngôn ngữ từ trong jar. File này chỉ còn các bước bấm, nên nó không thể lệch.

Cách nhanh nhất: dán nguyên `_PASTE-THIS-TO-CLAUDE.md` cho trợ lý AI kèm câu "điền form này
giúp tôi, dừng trước khi bấm Submit". Nếu tự điền thì đối chiếu từng ô theo file đó.

---------------------------------------------------------------------------
BƯỚC 0 — Chọn loại resource (màn hình đầu, trước khi hiện form)

    Resource Type      ->  Plugin
    Plugin Type        ->  Spigot  (không phải Bungee, không phải Sponge)

Nếu nó hỏi "Is this a premium resource?" -> chọn KHÔNG / Free. Bấm Continue / Next.

---------------------------------------------------------------------------
BƯỚC 1 — Resource Title

Mở `docs\release\paste\01-resource-name.txt` — file này do
`scripts\build_spigot_handoff.py` ghi ra, cùng chuỗi với ô Title trong
`_PASTE-THIS-TO-CLAUDE.md`. Dán nguyên.

Lưu ý: dấu gạch dài "—" phải giữ nguyên, đừng thay bằng "-".

---------------------------------------------------------------------------
BƯỚC 2 — Tag line

Lấy ở mục "Field: Tagline" trong `_PASTE-THIS-TO-CLAUDE.md`. Dán nguyên văn.

---------------------------------------------------------------------------
BƯỚC 3 — Icon

Ô "Resource Icon" -> Choose file / Drop files here
Chọn:   E:\AI.WORK\ItemGuard\release\spigot-upload\icon-512.png

Spigot tự thu về 96x96 để hiện ở danh sách. Logo đã thiết kế cho cỡ đó.

---------------------------------------------------------------------------
BƯỚC 4 — Description  (ô lớn nhất, có thanh công cụ BBCode)

QUAN TRỌNG: phải dán ở chế độ BBCode, không phải chế độ soạn thảo trực quan.

  1. Nhìn góc trên bên phải ô Description, tìm nút chuyển chế độ
     (biểu tượng [] hoặc chữ "BB Code").
  2. Bấm vào đó -> ô chuyển thành ô văn bản trơn.
  3. Mở file:  E:\AI.WORK\ItemGuard\release\spigot-upload\description.bbcode.txt
     Ctrl+A, Ctrl+C, rồi dán vào ô. (Bản y hệt nằm trong
     `_PASTE-THIS-TO-CLAUDE.md`, mục "Field: Description".)

Nếu quên bước chuyển chế độ, các thẻ [B] [LIST] [SPOILER] sẽ hiện ra
thành chữ thay vì được định dạng. Dán xong nhìn lướt: phải thấy chữ
đậm và danh sách, không thấy dấu ngoặc vuông.

---------------------------------------------------------------------------
BƯỚC 5 — Ảnh trong bài

Ảnh KHÔNG upload ở ô Description. Spigot có khu riêng.

  1. Kéo xuống dưới form, tìm mục "Screenshots" hoặc "Images"
     (có nút "Upload Screenshots" / vùng kéo thả).
  2. Mở Explorer tại E:\AI.WORK\ItemGuard\release\spigot-upload
  3. Chọn ĐỒNG THỜI 6 file (click file đầu, giữ Shift, click file cuối):

        screenshot-1-check.png
        screenshot-2-history.png
        screenshot-3-timeline.png
        screenshot-4-gui.png
        screenshot-5-duplicate-alert.png
        screenshot-6-stats.png

  4. Kéo cả 6 vào vùng upload, hoặc bấm Choose files rồi chọn cả 6.
  5. Đợi đủ 6 thumbnail hiện ra mới làm tiếp. Upload thiếu là chuyện
     hay xảy ra khi mạng chậm — đếm lại cho đủ 6.
  6. Thứ tự hiển thị = thứ tự tên file, nên ảnh 1 (/ig check) sẽ nằm
     đầu. Đó là ảnh muốn người ta thấy trước. Nếu Spigot cho kéo sắp
     xếp thì giữ nguyên 1->6.

  KHÔNG upload icon-512.png vào đây. Icon đã làm ở bước 3.

  Sáu ảnh này chụp trên bản cũ (c776a020…), không phải bản ship bên dưới.
  Ngày 17/09 đã grep lại
  từng dòng chữ trong ảnh với source hiện tại — không dòng nào đổi, nên
  vẫn dùng được. Chi tiết: docs\release\SCREENSHOT_STATUS.md

---------------------------------------------------------------------------
BƯỚC 6 — Version

    Version Number  ->  1.0.0

Đúng chuỗi đó, không thêm chữ v, không thêm "-lite".

---------------------------------------------------------------------------
BƯỚC 7 — File JAR

Mục "Resource File" -> chọn "Upload file" (không phải External URL).
Chọn:   E:\AI.WORK\ItemGuard\release\spigot-upload\ItemGuard-LITE-1.0.0.jar

Sau khi upload xong, đối chiếu dung lượng Spigot hiện với số ở mục
"File upload" trong `_PASTE-THIS-TO-CLAUDE.md` (số đó do máy đọc từ
chính file jar, không phải chép tay).

Lệch số -> upload hỏng hoặc chọn nhầm file -> upload lại.

---------------------------------------------------------------------------
BƯỚC 8 — Các ô còn lại

Xem mục "Field:" tương ứng trong `_PASTE-THIS-TO-CLAUDE.md` và làm đúng
theo đó. Hai ô hay làm sai:

    Tested Minecraft Versions  ->  CHỈ tick 1.21.
        KHÔNG tick 26.1 / 26.2. Bản jar này chưa từng chạy trên dòng 26.x,
        tick vào là tự nhận hỗ trợ mình không có. (Bản hướng dẫn cũ ghi
        "tick tất cả từ 1.21 trở lên" — sai, đã bỏ.)
    Languages Supported        ->  English.
        Không điền "Tiếng Việt": jar LITE chỉ có messages_en.yml.

Nếu có ô "External Support/Documentation URL":
    https://discord.gg/EnSNSNVV5G

---------------------------------------------------------------------------
BƯỚC 9 — Licence

Nếu form có ô "Licence" hoặc "Terms":
Mở:  docs\release\paste\04-licence-field.txt  -> dán toàn bộ.

Nếu chỉ có ô chọn sẵn (dropdown) và không cho dán chữ:
chọn "Custom" / "All Rights Reserved".

---------------------------------------------------------------------------
BƯỚC 10 — Kiểm trước khi bấm Submit

Soát đúng 6 điều, mất 1 phút:

    [ ] Tiêu đề khớp 01-resource-name.txt, còn dấu "—"
    [ ] Description hiện chữ ĐẬM và danh sách, KHÔNG thấy [B] hay [LIST]
    [ ] Spoiler bấm mở ra được
    [ ] Đủ 6 ảnh, đếm bằng mắt
    [ ] Icon hiện ở ô icon (không nằm lẫn trong 6 ảnh)
    [ ] JAR đúng số bytes ở mục "File upload" của _PASTE-THIS-TO-CLAUDE.md

Rồi bấm  Submit / Save.

---------------------------------------------------------------------------
SAU KHI ĐĂNG

Spigot duyệt thủ công, thường vài giờ tới 1-2 ngày. Trong lúc chờ:

  - Bài nằm ở trạng thái pending, chưa ai thấy.
  - Nếu bị từ chối, Spigot nhắn lý do qua tin nhắn riêng -> gửi tôi đọc.

Khi bài live, việc tiếp theo KHÔNG phải là thêm tính năng:
dữ liệu Spigot cho thấy thứ quyết định là nhịp cập nhật đều, và bản
1.0.1 nên là thứ người dùng thật yêu cầu, không phải thứ mình đoán.

---------------------------------------------------------------------------
MỘT ĐIỂM LỆCH NHỎ, BIẾT TRƯỚC CHO KHỎI BẤT NGỜ

Tiêu đề trang là "ItemGuard LITE — Item Identity & Duplicate Detection",
còn dòng chữ lớn mở đầu phần mô tả là "ItemGuard LITE — Item Logger &
History Tracker". Không sai gì về tính năng, chỉ là hai câu chữ khác
nhau. Muốn thống nhất thì sửa dòng đầu của `description.bbcode.txt` rồi
chạy lại `python scripts/build_spigot_handoff.py` và
`python scripts/build_listing_preview.py` — đừng sửa tay bản đã sinh.
