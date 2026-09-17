# Same-identity double-chest anti-dupe gate

Ngày: 2026-08-26

## Trạng thái

`VERIFIED CONTROLLED PAPER` cho same exact identity nằm tại hai physical halves của một real double chest. Đây là verification closure của residual anti-dupe scope, không phải defect production mới.

## Mục tiêu

Chứng minh resolver physical-half vừa sửa không chỉ tạo hai distinct observations cho hai identities, mà production finalizer còn detect đúng khi hai keys mang cùng exact item UUID/code.

## Fixture contract

- Production first-publish đúng một fresh non-stackable item qua real InventoryOpen path.
- Probe clone exact serialized bytes sang half còn lại chỉ sau PUBLISHED/readiness.
- Probe không gọi scanner/finalizer.
- Publication delta `+1`, không `+2`.
- One completed epoch phải có đúng two physical observation keys và one finding.
- Restart phải có positive cooldown epoch; cleanup exact two items/two blocks.

## Evidence

Run `97353438-bfd4-43d3-bbe3-daf894757d39` tạo finding ID4, epoch `1787741743307`, exact two `CONTAINER` holders/local3, `CONFIRMED/NOTIFY`, distinct_locations2. Restart epoch `1787741854276` có same observations, finding ID4/stat unchanged. Cleanup và DB integrity PASS.

## Review history

Initial exact Opus 4.8 `BLOCK` fixture. Corrected fixture review `PASS` qua approved Opus 4.6 fallback. Final review được ghi riêng sau administrative seal.

## Runtime status

Production source không cần fix thêm trong gate này. Production vẫn `NOT DEPLOYED / NOT VERIFIED`; project `NOT RELEASE READY`.
