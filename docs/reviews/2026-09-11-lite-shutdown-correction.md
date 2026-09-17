# LITE shutdown/recovery correction — verified offline

## Exact result

- Candidate: `target/ItemGuard-LITE-1.0.0-test.jar`
- SHA-256: `d86135e750b31e9271ab08256c8717e42d26c84f999ef15a2535f5dc769a0de8`
- Full fresh gate: `JAVA_HOME='C:/Program Files/Java/jdk-21' python scripts/package_lite.py` -> clean verify **403 tests, 0 failures/errors/skips**, package entry comparison PASS. No Failsafe plugin/IT execution is configured in the inspected pom; this count is explicitly Surefire tests, not runtime or guardian tool tests.
- All 226 packaged application class bytes equal fresh target/classes. Review source manifests (16 inputs + 6 follow-up inputs, overlapping allowed) match canonical source after review. git diff --check exit 0.

## Confirmed and fixed

H1: `requireOpen()` and task enqueue were separate. Concurrent close could enqueue physical close first; late work then caused rollback recovery to reopen a connection after shutdown, while close released ownership and reported success.

`SqliteShutdownAdmissionTest` intercepts only ExecutorService admission; operations, SQLite connections, serial worker and owner close remain real. A latch-controlled schedule reproduces the original leak for execute/call/callAsync: all three RED with `shutdown must not leave a recovered connection open`. `admissionLock` now makes check+enqueue atomic against setting closed+enqueueing final close. Blocking future waits stay outside admissionLock. Accepted work drains and can recover before the final close. All three GREEN; existing drain/reopen and interrupted-write regression tests also PASS. Logs: `lite-shutdown-red.log`, `lite-shutdown-green.log`.

M1: LITE synchronous and asynchronous query failures now retain exception causes in local logs. RED diagnostic regression and final full GREEN. Log `lite-diagnostics-red.log`.

Additional actual wiring defect: LITE declared itemguard.notify, but shared notification loop checked only itemguard.bypass. New two-edition test was RED for LITE and GREEN for Full. LITE now checks itemguard.notify; Full keeps its prior node. Final two cases GREEN. Log `lite-notify-recipient-red.log`.

## Independent review

Claude account invoked directly, actual model claude-opus-5, no Hermes delegation or auth edits. Both invocations exit 0 and is_error=false:

- `lite-shutdown-rereview.json`: PASS_FOR_OFFLINE_CANDIDATE for H1/M1 correction.
- `lite-notify-rereview.json`: PASS_FOR_OFFLINE_CANDIDATE for notification delta and missing config/action callees.

These are bounded static verdicts, not permission to boot/deploy or entire-product certification. Reviewed first-pass claims were not blindly implemented:

- Path normalization concern rejected: SqliteProcessLock canonicalizes parent before locking.
- Eager WorldGuard concern rejected for LITE: constructor checks integrationEnabled before resolving adapter; LITE hard-disables it.
- Destructive-action bypass/default-zero concerns rejected for current source: sole action consumer is InventoryScanTask -> finalizer -> policy with destructive operations disallowed; settings have explicit nonzero history/cooldown defaults.
- Suggested api-version lowering rejected: pin is deliberate, compatibility beyond it remains unverified.
- Reentrant synchronous DB calls remain unsupported/pre-existing; cited FindItemService predicate executes outside store operation. Timeout retains ownership deliberately and returns failure; not a successful shutdown claim.
- Failsafe concern not applicable to current pom (no configured Failsafe); no claim that Surefire counts guardian/runtime suites.

The follow-up review's statement that reverting the ternary fails both edition cases is inaccurate: the observed RED had LITE fail and Full pass, as intended. Notification permission is declared in lite/plugin.yml and packaged descriptor (parent verified). No runtime facts are inferred from reviewer prose.

## Remaining

Fresh approved fixture and existing authority/admission gates, actual Paper startup, player-facing ID/history/GUI/permissions, crafting and item transitions, duplicate-warning journey, clean stop/restart, scale/concurrency and client visual acceptance. Global single-query admission fairness and cosmetic text/GUI limitations remain documented hardening, not secretly completed. No server started, fixture reused, production modified, commit, push or SpigotMC upload.
