# Đối chiếu review độc lập #3 (2026-09-17)

Bản review: `docs/reviews/2026-09-17-review3-candidate-and-gates.md` (37,922 bytes, `claude -p --model
opus --restricted --allowedTools "Read Grep Glob"`, read-only, brief ở
`docs/reviews/2026-09-17-review-brief-candidate-and-gates.md`). Kết quả: **3 HIGH · 7 MEDIUM · 6 LOW ·
0 CRITICAL**.

Nguyên tắc đối chiếu, giống hai lượt trước: **không tin báo cáo, đọc source trước khi sửa**. Mỗi mục
dưới đây ghi rõ tôi đã kiểm được gì bằng chính mắt mình. Không có mục nào bị bác vì "review nhầm" — lượt
này mọi phát hiện tôi kiểm được đều đúng.

---

## HIGH

### H-1 · Cổng artifact bỏ qua đúng file mà cổng source khai LITE với tới — ĐÚNG, đã sửa

Tôi đọc `scan_jar` và xác nhận: `EXEMPT_DIRS` được áp tuyệt đối, và cả 5 `BILINGUAL_CLASSES` (trong đó
có `LiteCommand.class`) bị `continue` không điều kiện. Nghĩa là nửa source khai `commands/ItemCodeInput.java`
là `SCANNED` vì `LiteCommand` gọi nó, còn nửa artifact lại bỏ qua chính file đó. Con số trong bảng gate
("0 trên 361 class entries") được trình bày như nửa chứng cứ độc lập, thực tế hẹp hơn lời khai.

Đã sửa:
* `declared_lite_reachable()` — class nằm trong thư mục exempt chỉ được bỏ qua khi file `.java` tương ứng
  **không** có trong `SCANNED_INSIDE_EXEMPT`; lớp inner khớp qua tên lớp ngoài (`Foo$Bar.class` ← `Foo.java`).
* Mỗi `BILINGUAL_CLASSES` nay phải **có mặt trong jar** và **vẫn mang literal tiếng Việt** — một khai báo
  đã cũ (đổi tên, gỡ hết tiếng Việt) là finding, thay vì được miễn trừ miễn phí.
* `scan_jar` trả thêm `skipped` và `main` in ra ba con số; có test bất biến **tổng entry = scanned + exempt
  + bilingual**, nên một skip im lặng không thể trở thành một `checked` nhỏ hơn.
* 4 test mới, gồm một jar giả có `commands/ItemCodeInput.class` mang tiếng Việt và một hàng xóm exempt —
  đòi file đã khai phải bị quét, hàng xóm vẫn không.

Số sau sửa (candidate cuối): **365 scanned · 73 exempt · 5 bilingual = 443 entry**.

Điều còn lại, đã ghi rõ trong bảng: **bytecode không cho biết nhánh nào chạy**, nên nhóm bilingual vẫn
không được adjudicate ở nửa artifact. Cái mới là: việc miễn trừ nay có điều kiện và có thể hết hạn.

### H-2 · Luật hash rút gọn chỉ hoạt động khi digest đầy đủ tình cờ còn trong phạm vi quét — ĐÚNG, đã sửa

Kiểm chứng bằng grep: digest đầy đủ của `c776a020…` không tồn tại ở đâu trong cây, nên `c776a020` không
là tiền tố của bất kỳ chuỗi nào trong `known` ⇒ dòng `SCREENSHOT_STATUS.md:12` ("Captured on … The build
actually running") được quét và **không** bị báo. Tài liệu sống gọi tên một build không ship, cổng in `0`.

Đã sửa:
* `RETIRED_PREFIXES` — mỗi candidate đã bị thay khai một dòng, kèm lý do (`a952d161`, `94eb0dad`,
  `28aed104`, `65f3e1d1`, `e11ada6a`, `34d7fab2`, `c776a020`). Token trùng tiền tố đã khai là drift,
  bất kể digest đầy đủ có sống sót ở đâu hay không; luật `known` giữ nguyên cho phần chưa khai.
* Hai tài liệu sống được **sửa cho nói thẳng ra** điều chúng vốn ám chỉ: `SCREENSHOT_STATUS.md` ghi
  "Captured on (superseded) … not shipped and must not be uploaded", `paste/README.md` ghi "chụp trên
  bản cũ …, không phải bản ship bên dưới". Không phải thêm chữ để cổng im — thêm chữ vì chỗ đó vốn
  phải nói vậy với người đọc.
* 2 test mới: trích dẫn rút gọn của build đã nghỉ là drift; cùng lời đó khi nói rõ nó đã cũ thì không.

### H-3 · Thông báo từ chối craft chỉ admin đi sửa một khóa vô tác dụng — ĐÚNG, đã sửa

Kiểm chứng: `decide()` trả `CANCEL_TAGGED_READY_COPY` khi `hasIdentity && identityReady`, và `return`
trước khi đọc `cancelUntrackedCraftOutput`; `CraftListener` chỉ tách đúng một action, nên trường hợp này
rơi vào `craft-refused`, câu này vừa nói sai bản chất ("would need a new tracked identity") vừa chỉ admin
đặt `tracking.cancel-untracked-craft-output: false` — khóa mà đường đó không bao giờ đọc.

Đã sửa, mạnh hơn đề xuất của review: tách **ba** khóa (`craft-refused`, `craft-refused-tag-pending`,
`craft-refused-tagged`), và mapping chuyển thành hàm thuần `CraftOutputPolicy.messageKeyFor(Action)` bằng
`switch` **liệt kê đủ 5 hằng** — thêm action thứ sáu sẽ không biên dịch được cho tới khi được gán nghĩa,
thay vì lặng lẽ thừa hưởng câu của action khác.

Review đề nghị "thêm một test ánh xạ cả ba action"; tôi làm đúng thế nhưng bằng một hàm test được thật
(chứ không phải test soi chuỗi source): `CraftRefusalMessageContractTest` phủ 4 tính chất — ba refusal ba
câu khác nhau; **chỉ** refusal mà config quyết định mới được nhắc tên khóa; hai câu còn lại không được
nhắc khóa đó và không được nói kết quả thiếu identity; và cả ba khóa phải tồn tại trong **cả bốn** nguồn
message (nên một lỗi gõ khóa không thể lọt qua `MessageManagerFallbackTest`).

Ghi lại trung thực: bản đầu tôi viết mapping ngay trong `CraftListener`. Tôi đã **dừng chuỗi runtime đang
chạy** trên candidate lúc đó (5/7 gate đã PASS) để làm lại đúng cách, vì một mapping chỉ được canh bằng
mắt không xứng với một hàng gate ghi "PASS unit".

---

## MEDIUM

* **M-1 · Throttle dùng chung cho cả ba loại từ chối** — ĐÚNG. `identityNoticeAt` khóa theo `UUID` trần,
  nên một tag hỏng bấm trong 5 giây sau một từ chối khác bị nuốt im lặng. Đã sửa: khóa `<uuid>|<key>`,
  map đổi sang `Map<String, Long>`, comment giải thích vì sao. (Lỗi biên dịch đầu tiên của tôi — quên đổi
  kiểu map — bị chính build bắt, ghi lại vì build đỏ là bằng chứng trung thực, không phải điều để giấu.)
* **M-2 · Cổng privacy dùng allowlist** — ĐÚNG và đây là lỗi tôi tự tạo ở lát cắt trước. `is_plugin_answer`
  (tiền tố hoặc số thứ tự) là allowlist, mà `LiteCommand:158` gửi dòng overview thẳng qua `sendMessage`,
  không mang cả hai dạng đó ⇒ mọi khẳng định "member không thấy tên" chỉ đúng với tập dòng cổng tự chọn.
  Đã sửa: `plugin_lines` chuyển thành **denylist** (bỏ đúng broadcast vanilla được nêu tên); `is_plugin_answer`
  giữ vai trò "plugin có lên tiếng không" và được ghi rõ là allowlist không được dùng cho phép kiểm rò rỉ.
  Thêm test contract cho dòng mang tên actor ở dạng không tiền tố/không số. Chạy lại trên fixture thật:
  `member_lines_inspected=7` — trong lượt này không đổi kết luận, việc sửa là đóng lỗ tiềm ẩn, và tôi nói
  đúng như vậy chứ không nhận là đã bắt được thêm lỗi.
* **M-3 · Hàng gate hứa `0` = không throttle** — ĐÚNG: `detectionCooldown` floor lên hai chu kỳ nên `0`
  trong config không bao giờ tới repository. Không đổi hành vi sản phẩm; sửa lời hàng gate thành đúng sự
  thật (config bị floor, đường `= 0` chỉ tới được từ API) và ghi vào `config.yml` rằng giá trị này không
  đặt được 0.
* **M-4 · Luật statement quá dễ tha** — ĐÚNG cả ba phần (literal phụ bất kỳ, `vietnamese` trong comment,
  `statements()` cắt cả trong string literal). Đã sửa cả ba: cắt statement có ý thức về string literal,
  bỏ comment trước khi soi cờ, và literal "bên cạnh" phải trông như văn xuôi (loại mã màu `§c`/`&c`, khóa
  config dạng `a.b`/`a-b`, dấu câu). Bản siết đầu tiên của tôi **phạt oan** `text("Enabled", "Đã bật")`
  — phát hiện ngay khi chạy cổng trên cây thật, sửa lại luật và ghi vào docstring điều còn không chứng
  minh được (literal bạn đồng hành có thể là chuỗi văn xuôi bất kỳ trong cùng statement).
* **M-5 · `HISTORIC` khớp `void` trong `avoid`** — ĐÚNG. Đã thay bằng các cụm khẳng định có chủ ngữ
  (`superseded`, `void/voided`, `no longer`, `rebind…`, `previously shipped`, `earlier build`,
  `historic record`) cộng hai cụm tiếng Việt mà tài liệu này thật sự dùng. 2 test mới khoá hai câu
  phản ví dụ của review.
* **M-6 · Điều khoản epoch bị nói quá** — ĐÚNG: nó chia đôi nhịp báo chứ không thay cửa sổ. Sửa lời hàng
  gate thành "backstop, không thay thế cửa sổ; từ epoch thứ ba lại báo".
* **M-7 · `config.yml` nói một chu kỳ, code floor hai** — ĐÚNG. Sửa comment thành hai chu kỳ kèm lý do
  (khoảng cách thật giữa hai audit không bằng chu kỳ cấu hình).

## LOW

* **L-1** lý do trong `AntiDupeSettings` ngược chiều với chính setting — sửa lại lời.
* **L-2** `CustodyWindow` biện luận bằng một con số đã chết ("defaults to five seconds") — viết lại lý do
  thật (cooldown là giá trị operator chỉnh được, bị floor, và phụ thuộc TPS, nên không thể dùng làm khoảng
  thời gian cho luật custody).
* **L-3** cửa sổ custody hardcode trong script + phép kiểm vacuous ở quy mô harness — sửa: đọc từ
  `config.yml` của **chính fixture**, in kèm **nguồn** của con số, và docstring nói thẳng đây là tiền đề
  mọi lượt chạy đều thoả. Khi làm việc này tôi phát hiện câu in cũ của mình sai: LITE **không** có khóa
  `tracking.custody-window-ms` trong config, nên con số thật là `CustodyWindow.DEFAULT_MILLIS` — và bây giờ
  nó in đúng như thế. Thêm test: một fixture cấu hình cửa sổ nhỏ phải làm phép kiểm đỏ.
* **L-4** sau `disablePlugin`, command trong `plugin.yml` vẫn nằm trong `CommandMap` nên `/ig` trả lỗi đỏ
  thay vì câu giải thích. **Chấp nhận, không sửa hành vi**: đây là suy luận từ Bukkit (review tự đánh dấu
  vậy), không có nguồn Paper trong cây để chốt, và hậu quả là một thông báo khó chịu chứ không mất dữ liệu.
  Đã ghi vào hàng gate `general.enabled` đúng mức độ tin cậy đó.
* **L-5** `hasCodeOrUuid` đọc lại PDC ở nhánh đã biết trước kết quả — bỏ điều kiện thừa, kèm comment.
* **L-6** chỉ `.jar` bên trong archive được băm; một `.jar` trần cùng tên cạnh listing thì không — đúng là
  chỗ dựa duy nhất cho `release/upload/` là `SHA256SUMS.txt`, tức một file tự khai. Đã sửa: mọi `.jar` trần
  **cùng tên với jar ship** đều được băm và so; jar khác tên (bản FULL) không bị đụng. 2 test mới.

---

## Việc tôi tự tìm ra trong lúc đối chiếu

1. **Runner của tôi nhận tên gate lạ**: `run_release_runtime_gates.py multii` in `0/0 gates PASS` và
   **exit 0** — người gọi đọc exit code tưởng thành công; tên gõ sai cạnh tên đúng thì gate đó biến mất
   khỏi tổng. Đã sửa thành lỗi cứng (exit 2, in danh sách tên hợp lệ) + 5 contract test.
   (Review #3 xác nhận lỗ này đã đóng ở lượt chạy của nó.)
2. **`finalize_release_candidate.py` sửa hash lạ thành hash của mình**: nó thay *mọi* chuỗi 64-hex trong
   description bằng digest hiện tại. Nếu description trích hash của một artifact khác, nó biến lời khai
   sai thành lời khai đúng thay vì báo. Đã chặn: còn digest nào không phải của jar này thì dừng với exit 2.
3. **Lỗi biên dịch của tôi** (map `Map<UUID, Long>` sau khi đổi khóa sang String) — build đỏ, sửa, chạy lại.

## Không phá được (theo review) — và giá trị của phần này

Review dành hẳn một mục để nêu, theo từng cổng, **đòn tấn công đã thử mà không phá được**: parser constant
pool đối chiếu từng tag JVMS và `UnparsedClass` không nuốt được gì; `referenced_from_outside` không dựng
được tham chiếu lọt; `record_marker` từ chối cả bốn cách nguỵ trang; verdict file thiếu/hỏng đều fail-closed;
`newest_fixture` đòi đúng một fixture nên "gate không chạy" là REJECTED; `plugin_lines`/`bot_messages` đọc
đúng trường `message` (lỗi "assert trên envelope" đã thật sự được sửa); `verify_multi` kiểm cổng bằng
`connect_ex` thật và `stage.json` khai bừa bị bắt. Phần này quan trọng ngang phần phát hiện: nó là bằng
chứng rằng các cổng đã bị tấn công, chứ không phải chỉ được chạy.

## Điều review không kiểm được, tôi đã kiểm

Review nói rõ nó không đọc được các fixture (nằm ngoài repo) nên mọi hàng gate trích fixture là lời khai
không kiểm lại được từ chỗ nó đứng. Tôi đã chạy `verify.py multi`, `verify_custody_rows.py` trên các fixture
thật và dán số liệu vào bảng — đó là phần chứng cứ mà lượt review này không thể có.
