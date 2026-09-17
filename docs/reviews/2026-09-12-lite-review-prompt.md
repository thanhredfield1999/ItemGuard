You are an independent reviewer. Scope: ItemGuard LITE offline candidate only. READ-ONLY.

Hard rules:
- Do NOT edit any file, run git write commands, build, package, start a server, or call network tools.
- You may read files and run read-only shell inspection (unzip -p, sha256sum, grep) inside E:/AI.WORK/ItemGuard.
- Do NOT claim runtime, client, gameplay, release or production verification. There is none.
- Report only defects you can point to with file path + line or exact JAR entry evidence.

Exact inputs (frozen hashes in docs/reviews/2026-09-12-lite-input-manifest.json):
- LITE JAR: target/ItemGuard-LITE-1.0.0-test.jar sha256 88da1c3f00d99a54ae64a50cb62d82c272957e96d6cc18d51e1daba3655d0c55
- Full JAR: target/ItemGuard-1.0.0.jar sha256 4082d66a67a5ac1272526f59eb585b7c869b2b2247923ddb72feb6c118c78875
- Source: src/main/java/com/itemguard/lite/, src/main/resources/lite/
- Packager: scripts/package_lite.py ; Verifier: scripts/verify_lite_artifact.py
- Docs: docs/LITE_TESTING.md

Review these questions specifically:
1. Command surface: does the LITE descriptor + ItemGuardLite.registerCommands() expose ONLY read-only investigation commands? Can any Full command (finditem, matdo, igcheck/ighistory/igsearch/igstats, ig aliases) still be reached at runtime given plugin.yml declares only `itemguard` with alias `ig`?
2. Destructive safety: can LITE delete, confiscate, move, grant, or mutate any item? Check LiteCommand click/drag handling, ConfigManager.getAntiDupeAction, AntiDupeActionPolicy, ObservationEpochFinalizer, and any listener registered by the shared ItemGuard.onEnable that LITE inherits.
3. Permission boundary: can a non-staff player read another player's item history via `/ig history <id>` owner-drilldown path in LiteCommand? Trace ownerScoped/ownDrilldown/required logic for an exploit.
4. Inherited Full behaviour: ItemGuardLite extends ItemGuard, so listeners/tasks from ItemGuard.onEnable still run. Identify any inherited behaviour that writes, mutates, or scans in a way the LITE docs do not disclose.
5. Packaging claims: does scripts/package_lite.py + scripts/verify_lite_artifact.py actually prove what they claim? Find gaps (e.g. entries not compared, substitutions too permissive, a claim provable only at runtime).
6. Doc truthfulness: does docs/LITE_TESTING.md overclaim support, safety or verification relative to the evidence? Note the current doc still lists an older SHA.

Output format, plain text, no JSON schema:
VERDICT: one of PASS_FOR_OFFLINE_CANDIDATE | BLOCK
BLOCKERS: numbered list, each with file:line or JAR entry, why it is a defect, and the smallest correct fix. Empty if none.
NON_BLOCKING_NOTES: short list.
RUNTIME_CLAIM: must state that no runtime/client/release evidence was produced by this review.
