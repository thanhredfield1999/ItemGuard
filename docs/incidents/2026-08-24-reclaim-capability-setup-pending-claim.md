# Incident: capability setup có thể để reclaim claim kẹt PENDING

Ngày: 2026-08-24
Trạng thái: `VERIFIED unit`; successor denial `VERIFIED controlled`; production `NOT VERIFIED`.

## Triệu chứng nguồn

Sau khi `ReclaimPreparationService` reserve claim, `MatDoCommand#runCapabilityPhase`
dựng external probes trước khi gọi `ReclaimCapabilityGate#evaluate`. Nếu plugin metadata,
optional class hoặc classloader ném `RuntimeException`/`LinkageError`, exception có thể thoát
khỏi main-thread callback trước `claims.deny(...)`. Claim đã reserve có thể giữ `PENDING`.

## Root cause

Fail-closed handling chỉ bao quanh từng `probe.probe(target)` và chỉ bắt
`RuntimeException`. Nó không bao quanh bước dựng factory/list probe, cũng không bắt
`LinkageError` từ optional dependency.

## Fix tối thiểu

- Thêm `ReclaimCapabilityEvaluator` bao quanh cả probe supplier và gate evaluation.
- `RuntimeException`/`LinkageError` ở setup thành `DENIED_ERROR` với evidence
  `CAPABILITY_SETUP`.
- `ReclaimCapabilityGate` cũng bắt `RuntimeException | LinkageError` cho từng probe.
- `MatDoCommand` xử lý decision này qua đường `persistDenial` hiện hữu; issuance vẫn đóng.

## Regression

- `linkageFailureInsideProbeFailsClosedInsteadOfEscaping`.
- `linkageFailureWhileBuildingProbesFailsClosed`.
- Focused reclaim suite: 13/13 GREEN.
- Full `mvnw.cmd clean test package --no-transfer-progress`: 161/161 GREEN.

## Controlled Paper

- Artifact SHA-256 `3a388adc43bb43fe930c44ecbcaeb17c8eb35ca3dcbbaaf1bb57a0a19de50d71`.
- BotChecker run `357aa5ce-5e2b-4404-83eb-c9338afe5363` PASS.
- Claim `44c88251-605c-4f70-9566-9383f89de171` chuyển `DENIED` với blocker
  `PLAYER_VAULTS`; DB có 8 reclaim claims, tất cả `DENIED`.
- Controlled journey không inject `LinkageError`; vì vậy exact linkage branch chỉ được
  unit verified, không được gọi runtime verified.

## Rủi ro còn lại

Nếu chính DB denial write thất bại, claim durable vẫn có thể còn `PENDING`; command báo
không ghi được và không cấp item. Startup recovery hiện deny stale pending claim, nhưng
crash/error injection cho cửa sổ này vẫn chưa controlled-runtime verified.