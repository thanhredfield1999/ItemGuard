# P1a generator/service collision — evidence

Status: VERIFIED_OFFLINE_CANDIDATE. Opus5 initial + bounded followup PASS_FOR_OFFLINE_CANDIDATE; parent exact source/test/JAR verifier PASS. NO_PAPER / NOT_RELEASE_READY.

## Changed

- `ItemTrackingService.generateCode` delegates to existing `PublicItemCodeGenerator`; default constructor uses SecureRandom. Package-private injection seam for deterministic service tests.
- Real generator used in CodeGenerationTest; config comment six uppercase alphanumeric characters, existing identities preserved.
- `AsyncTagPublicationCoordinator` releases in-flight source in finally even if publish-failure callback throws.
- Mockito 5.14.2 TEST-scope only. No schema change in P1a (schema remains C1 version 8).
- Review correction: negative lore-position appends rather than throwing before proposal; Surefire explicit Mockito javaagent removes dynamic attach dependency/warning.

## Observed RED / GREEN

- `p1a-generator-red.log`: test setup NPE only, NOT behavioral RED.
- `p1a-generator-red-r2.log`: expected six generator calls, actual zero. `p1a-generator-green.log`: 15 tests pass.
- `p1a-callback-red.log`: callback throws after publish submission failure, next request incorrectly rejected by leaked lock. `p1a-integration-coverage.log`: 12 tests pass including corrected callback path.
- `p1a-focused-final.log`: 23 tests, zero failures/errors/skips. Seven public service+JDBC integration cases (collision, exhaustion, two concurrent sources, publish outage and reopen reconcile, stale source, generic reserve error, old PREPARED reuse after reopen). These are green-first coverage except the two separately anchored defects above.
- `p1a-lore-red.log` behavioral RED invalid negative lore-position, then `p1a-review-corrections-green.log` PASS. Additional coverage checks production SecureRandom constructor, delayed abort lock release, PREPARED unique-source rollback and legacy command code format. Review dispositions: `p1a-review-disposition.md` (360 belongs INITIAL snapshot; 364 is final post-corrections).

## Exact build

Command: `python C:/Users/thanh/AppData/Local/Temp/itemguard-catalog-maven.py clean test package`
Log: `docs/reviews/p1a-full-maven-r2.log` — Java 21.0.4, BUILD SUCCESS, 364 tests / 0 failures / 0 errors / 0 skipped. Prior p1a-full-maven.log/360 is superseded.
Parent verifier: `C:/Users/thanh/AppData/Local/Temp/itemguard-p1a-verify.py` — exact reviewed file hashes and 216 packaged ItemGuard class bytes match target/classes, Mockito/ByteBuddy/Objenesis absent from shaded JAR, SQLite JDBC+Windows x86_64 native+service entry present.
JAR SHA-256: `b04dafda4e9525d70c0c20df0daf2fd228c5588363b58f5e442573ea97749644`.
Manifest: `docs/reviews/p1a-final-manifest.json`.
Initial input SHA-256: `e224f4fa11ecb76b2c1fb223accc8eb3e5b4a666c1680aa175e0869c60bea46d`. Final bounded followup: `c85d6c437d9f8692e00c90a1abdccdc0f5a90af1f0b18f48d617c48adb64046d`. Exact checker overlays followup files on initial manifest; no unchanged file silently dropped.
`git diff --check` PASS; codegraph sync complete.

Warnings retained honestly: preexisting deprecated PlayerQuitEvent constructor, SLF4J no provider/shading notices; explicit Mockito instrumentation no longer dynamically attaches. Build/test failure count zero; not a warning-free build claim.

## P0 regression only

Command: `bash tools/execution-authority/build/offline-test.sh`, exit 0; log `p1a-p0-offline-regression.log`.
All four script groups pass. Includes 128 history combinations, 242 prefix/event cases, 22 axis vectors, 76 control cases, 53 frame bounds, 9 composition, 50,745 chain byte mutations, 25 chain cases, 8 line bounds, 1,095 truncations, 9 scanner cases; codec suite detail in raw log. No count aggregated across overlapping suites.
No Paper, ACL/token/job/runtime namespace invocation; outputs only existing compiler Temp harness.

## Non-claims / residual gates

- Bukkit world/entity/PDC/scheduler and Paper serialization are test doubles. Real service/coordinator/repository execute, JDBC temp databases are real. Test payloads are NOT Minecraft NBT; DB reopen is NOT Paper restart/OS crash.
- Player/container physical authority, natural break, hotbar/armor/death/relocation/craft interoperability need exact-runtime gates. No previous runtime receipt applies to this new JAR.
- C1 general contains-search scale remains OPEN; C2 provenance/category, C3 HavenBags/custody, C4 visual acceptance, P0 real host guardian and P2–P6 remain OPEN.
- No commit/push/Paper/deploy/restart/production changes. Consumed fixtures/receipts not reused. `.hermes/WORKING_STATE.md` untouched.
