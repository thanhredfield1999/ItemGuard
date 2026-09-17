# Stackable identity fail-closed — independent review

Ngày: 2026-08-26

## Verdict

- Pre-runtime exact `cc/claude-opus-4-8` gặp HTTP 429; approved fallback `ag/claude-opus-4-6-thinking` trả `PASS`, blockers none.
- Reviewer phát hiện partial code-XOR-UUID có thể lọt hopper; finding được chuyển thành production RED wiring test và sửa bằng corruption-aware `hasCodeOrUuid` guards.
- Focused sau correction: `16/16` PASS.
- Full clean Java 21: `60` suites / `212/212` PASS.
- Final reviewer: exact `cc/claude-opus-4-8`.
- Final verdict: `PASS`.
- Required fixes: none.
- Production change required: `NO` — nghĩa là không cần sửa source thêm; production server vẫn chưa deploy/restart/verify.

## Các seam được review

1. `ItemIdentityEligibilityPolicy` bắt buộc `maxStackSize == 1 && amount == 1`; config true và force-material không bypass.
2. Item/entity readiness và physical reconciliation dùng cùng policy.
3. Player/container observations bỏ qua unsupported legacy stackables.
4. Drop/click/drag/use, craft và hopper xét complete hoặc partial identity trước new-tag eligibility; unsupported/corrupt identity bị cancel.
5. Pickup COMPLETE identity dùng entity readiness và bị cancel nếu stackable.
6. Canonical/read-only APIs vẫn có thể hiển thị legacy row nhưng không có operational mutation nếu không qua readiness.
7. Không auto-strip, migrate, delete hoặc rebind legacy canonical data.

## Counterexamples đã bác bỏ

- GREEN reuse old row không làm oracle tuần hoàn: old snapshot là adversarial input; proof là fixed candidate từ chối vận hành nó và chặn fresh first-tag.
- Cleanup không mask DB delta: final seal chạy trước cleanup; observations append-only vẫn `0`; exact legacy row bằng baseline.
- Aggregate canceling delta không phù hợp evidence: exact row-level publication ID/payload SHA/canonical UUID đều giữ nguyên và counts append-only không tăng.
- Config key reserved không mở lại unsafe feature vì policy ignore `track-stackable=true` khi item không hỗ trợ identity.

## Findings không blocking

- `low`: legacy tagged stackable rơi qua player death có thể thành ground entity không pickup được. Đây là availability/UX issue, không tạo identity hoặc canonical write mới.
- `info`: `ItemStack#getMaxStackSize()` phản ánh per-item max-stack component. Item thực sự bị cấu hình max 1 có thể eligible; chưa có evidence vanilla vẫn merge item báo max 1. Đây là documented boundary ngoài APPLE MVP.
- `info`: read-only lookup APIs vẫn trả legacy identity; không có operational effect nếu không qua readiness.

## Residual risks

- Custom/per-item max-stack override và Paper custom items.
- Merge giữa các stack nguồn khác nhau.
- Multi-player/multi-writer.
- Runtime hopper, death-drop/pickup và scale.
- Production deployment/restart/observation.

## Raw review artifacts

- `C:\Users\thanh\AppData\Local\Temp\itemguard_stackable_merge_fixed_review_result.txt`.
- `C:\Users\thanh\AppData\Local\Temp\itemguard_stackable_merge_final_review_result.txt`.
- Sealed copies: `E:\AI.WORK\itemguard-paper-smoke\evidence\stackable-merge-fixed-9284f975\review`.

Runtime report: `docs/runtime/2026-08-26-controlled-stackable-identity-fail-closed.md`.
