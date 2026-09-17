# Incident: stackable identity bị nhân qua split

Ngày: 2026-08-26  
Trạng thái: `VERIFIED controlled` cho defect và bản sửa; production `NOT DEPLOYED / NOT VERIFIED`.

## Triệu chứng

Trên controlled Paper 1.21.11, khi clone cố ý bật `tracking.track-stackable=true`, ItemGuard candidate cũ cấp một durable identity cho `APPLE x2`:

- code `WOZF78`;
- item UUID `7904f9e8-267a-4502-a6bc-e2369a02bf9e`;
- publication `PUBLISHED`;
- snapshot `186` bytes, SHA-256 `6ddf6c0c664c37d46bdd00363a8dc64f78d9022ea08e5ef6c339513b37d5b03a`.

Real inventory right-click split tạo hai physical `APPLE x1` ở slots `9/10`, cả hai giữ cùng exact code, item UUID và PDC payload. Một completed observation epoch ghi hai locations cho cùng identity. Hai bản sao còn nguyên qua clean restart.

Đây là confirmed identity-duplication defect của option stackable cũ, không phải chỉ là hành vi merge/split tự nhiên của Minecraft: ItemGuard đã cấp và publish UUID-per-item identity cho stack trước khi vanilla copy metadata sang hai physical items.

## Root cause

Identity UUID-per-item được lưu trực tiếp trong `ItemStack` PDC. Vanilla split copy metadata/PDC của stack nguồn sang stack đích. Candidate cũ cho phép `maxStackSize > 1` được first-tag khi config hoặc force-material bật, còn readiness/reconciliation/observation chỉ kiểm code+UUID mà không khóa physical cardinality.

Vì chưa có ownership model cho split/merge, một identity trên stack amount lớn hơn một không thể đại diện duy nhất cho một physical item.

## Bản sửa tối thiểu

- Thêm `ItemIdentityEligibilityPolicy` chỉ cho phép identity khi `ItemStack#getMaxStackSize() == 1` và `amount == 1`.
- Config `track-stackable` được giữ làm compatibility key nhưng `true` không thể bypass policy.
- Force-track material cũng không thể bypass.
- Áp policy tại first publication, item/entity readiness, physical reconciliation, player/container observations.
- Existing complete hoặc partial identity được kiểm trước new-tag eligibility trên drop/click/drag/use, craft và hopper; item không ready bị cancel fail-closed.
- Không migrate, strip, repair hoặc xóa legacy PDC/DB rows tự động.
- Không thay schema và không triển khai split/merge ownership model lớn.

## Regression tests

- `ItemIdentityEligibilityPolicyTest`: stackable config/force-material không bypass; malformed amount bị từ chối; non-stackable amount một được phép.
- `StackableIdentityWiringContractTest`: khóa mọi identity lifecycle seam và partial code-XOR-UUID mutation guards.
- Focused Java 21: `16/16` PASS.
- Full clean Java 21: `60` suites, `212/212` PASS, zero failures/errors.
- `git diff --check` PASS.

## Controlled verification của bản sửa

Candidate local: `89ff7410dc937b29857938a06afac229070a04f5e524bc28d135be20fa28656c`.

Run `9284f975-9ce1-4ece-9436-7473fca03c33` trên controlled clone, vẫn cố ý đặt `track-stackable=true`:

1. Fresh `APPLE x2`: `shouldTrack=false`, tag request bị từ chối, không có PDC.
2. Real right-click split tạo hai `APPLE x1` untagged.
3. Exact legacy `WOZF78/7904f9e8-...` được restore từ old DB snapshot; real click packet đã gửi nhưng client và authoritative server giữ slot `11 x2`, cursor empty, `isIdentityReady=false`.
4. Clean restart giữ hai fresh x1 untagged và legacy x2 bị khóa.
5. Exact canonical/snapshot/publication legacy không đổi; DB zero delta cho tracked/snapshot/publication/history/observation/finding/claim, integrity `ok`, PREPARED `0`, active claims `0`, triggers `0`.
6. Cleanup xóa đúng ba fixture items; post-cleanup DB vẫn zero delta.

## Review

- Pre-runtime fallback `ag/claude-opus-4-6-thinking`: `PASS`, blockers none; exact Opus 4.8 trước đó bị HTTP 429.
- Final exact `cc/claude-opus-4-8`: `VERDICT: PASS`, required fixes none, production change required `NO` (không cần sửa thêm).
- Finding low: legacy tagged stackable có thể rơi khi player chết rồi không pickup được; availability/UX, không phải dupe.
- Boundary info: custom per-item max-stack override, merge-between-different-stacks, multi-player, runtime hopper/death và scale chưa verified.

## Runtime status

- Source local đã sửa và controlled clone đã verify.
- Production server/JAR/config chưa deploy, restart hoặc verify.
- Project vẫn `NOT RELEASE READY`; anti-dupe destructive action và issuance vẫn disabled.

## Evidence

- RED: `E:\AI.WORK\itemguard-paper-smoke\evidence\stackable-merge-red-5c2873b4`.
- GREEN: `E:\AI.WORK\itemguard-paper-smoke\evidence\stackable-merge-fixed-9284f975`.
- Final evidence: `E:\AI.WORK\itemguard-paper-smoke\stackable-merge-fixed-final-evidence.json`.
- Runtime report: `docs/runtime/2026-08-26-controlled-stackable-identity-fail-closed.md`.
- Review: `docs/reviews/2026-08-26-stackable-identity-fail-closed-review.md`.
- Combined archive: `E:\AI.WORK\backups\itemguard-stackable-merge-fixed-9284f975-20260826-134440.tar.gz`.
- Archive SHA-256: `5762d7c6df486709520bcb7992fc52d3f1892228ec4827cc9aff86d0e1676e24`.
