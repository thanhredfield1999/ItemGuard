# Two-player exact-identity anti-dupe — independent review

Ngày: 2026-08-26

## Verdict

- Pre-runtime reviewer: exact `cc/claude-opus-4-8`.
- Pre-runtime verdict: `PASS`, blockers none, oracle fixes none.
- Final reviewer: exact `cc/claude-opus-4-8`.
- Final verdict: `PASS`.
- Required fixes: none.
- Production change: `NO`.

Production server vẫn chưa deploy/restart/verify; `NO` chỉ nghĩa là source không cần sửa thêm để chấp nhận narrow controlled gate.

## Reviewer xác nhận

1. Fresh baseline → atomic capture có đúng delta: `+1` canonical/snapshot/PUBLISHED/publication, `+1` attributable `SPAWN`, `+1` finding/stat; PREPARED/ABORTED/claim/trigger không đổi.
2. Winning epoch `1787733435319` có đúng hai completed `PLAYER` observations, cùng exact code/UUID/slot 12, hai holder UUID khác nhau; finding dùng chính epoch đó, không dùng later rows.
3. Exclusion `e9d0414b...` hợp lệ: watcher quá strict về history, product thực hiện đúng first-publication audit.
4. First restart exclusion hợp lệ: readiness RAM cache trống, reconciliation async chưa hoàn tất; exact same-identity retry sau chờ production scans PASS và không che product regression.
5. Cooldown giữ finding/stat không tăng qua later epochs/restarts.
6. Cleanup removed=2, save cả hai playerdata, later empty epoch đưa observations về zero; durable audit rows còn nguyên.
7. Không có production source fix bắt buộc cho gate hẹp này.

## Advisory đã xử lý

- Reviewer lưu ý `distinct_locations` thực tế là count physical observation keys, không phải distinct holder count. Runtime docs đã ghi boundary này; journey vẫn có hai holder UUID khác nhau nên evidence đúng.
- Reviewer yêu cầu reproducible JAR entry comparison. Final evidence bundle đã thêm `jar-entry-comparison.json`: runtime/rebuilt có `332/332` non-manifest entries, zero byte differences. Manifest artifact SHA `d0b22960…a81d`.

## Residual limits

- Multi-server/multi-writer.
- Offline player và disconnect timing.
- Double chest/external storage.
- Destructive action, scale và production.
- `distinct_locations` không nên được diễn giải thành distinct holder count ngoài exact observation-key scope.

Raw review artifacts nằm trong final archive `E:\AI.WORK\backups\two-player-af9af793-20260826-154443.tar.gz`, SHA-256 `d041a434…08b9`.

Runtime report: `docs/runtime/2026-08-26-controlled-two-player-exact-identity-anti-dupe.md`.
