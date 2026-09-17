# P1a — generator service wiring / collision closure (offline)

Scope: bounded existing P1a contract, user requested continuing roadmap. No new schema, identity rewrite, runtime fixture, release, or production action. Catalog and P0/C2–C4/P2–P6 remain separate.

## Observed defects and corrections

- ItemTrackingService used its own SHA/modulo code generation even though PublicItemCodeGenerator was tested. Replace only generateCode delegate; production constructor supplies SecureRandom, package-private constructor injects generator for deterministic tests.
- First patch introduced the inert constructor/field seam (no generation behavior change), then behavioral RED observed service consuming zero injected draws instead of six. GREEN delegates to tested generator. Initial test setup NPE was corrected, NOT counted as behavioral RED.
- Old CodeGenerationTest asserted a hardcoded nine-character string; now exercises actual generator. Config documentation matches six uppercase ASCII alphanumeric characters. Existing code/UUID values are not rewritten. Codes are public labels, NOT secrets and NOT a uniqueness proof.
- AsyncTagPublicationCoordinator publish-submission failure path did not release source lock if target.publishFailed threw. RED reproduced leaked lock; finally releases it. Other completion/preparation paths already use finally.

## Existing persistence contract (not redesigned)

- SqliteConnectionOwner serializes one cooperating owner. reserve compares both code and UUID with canonical and journal, including ABORTED identities, inside the existing transaction. Only typed collision is retried, max three attempts.
- Proposal factory rebuilds full tagged clone/snapshot/UUID on main dispatcher; revalidates same source/digest. Physical write follows successful reservation only.
- Same source/digest PREPARED reservation returns old identity and snapshot, even if new proposed code differs. No random-generation uniqueness assertion.
- Publish outage after physical write retains PREPARED; exact receipt reconciliation can publish once. Aborted stale-source publication leaves no canonical/snapshot grant. No new reclaim/issuance path.

## Verification boundaries

Focused 23 tests and full Java21 Maven clean test package 360 tests passed before independent review. See p1a-*.log.

Seven ItemTrackingPublicationIntegrationTest cases call public requestEntityTag with real service/coordinator/JDBC on fresh temp databases: collision, exhaustion, two concurrent sources, publish failure/reopen/reconcile, stale source abort, generic reserve failure, old PREPARED identity reuse after reopen.

Mockito 5.14.2 is TEST scope only; mocks unavoidable Bukkit bootstrap/scheduler/entities/PDC and static ItemStack deserialize boundary. Serialization deliberately uses test payloads, not Paper NBT. Reopen is real JDBC close/open, not Paper restart or OS crash. Player/container physical authority and Paper serialization remain runtime gates, not newly verified by these tests.

Review exact snapshot and tests for defects; do not expand an offline PASS to Paper/runtime authorization. Old consumed fixtures/receipts remain consumed. No production mutation.
