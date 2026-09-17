# LITE controlled smoke — PASS_CONTROLLED_SMOKE

## Authority and scope

User's latest instruction after the explicit separate-smoke question: “phân tích xem ok chưa đc thì triển”. Treated as conditional authorization to assess and execute that bounded LITE-only protocol. Earlier clarification output did not prove a selected answer; this later instruction is the execution basis. This does not authorize production or replace successor-35 guardian authority.

## Exact target

- Fixture: `E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-ed9e109ccc9a`
- Candidate: `target/ItemGuard-LITE-1.0.0-test.jar`
- SHA256: `d86135e750b31e9271ab08256c8717e42d26c84f999ef15a2535f5dc769a0de8`
- Observed Paper `1.21.11-131-ver/1.21.11@6d5b910`; Java `21.0.4+8-LTS-274`.
- Loopback port 63411. One immutable attempt, two generations with one clean restart. Fixture is now consumed; no replay authorized.
- Earlier preparation `itemguard-lite-isolated-06ab587efa49` was not attempted; it is not the evidence target.

## Execution and independent verification

`python -m unittest discover -s tools/lite-runtime -p test_contracts.py -v`: 6/6 PASS (UNIT harness/parser/real-child cleanup contracts, not gameplay).

`python tools/lite-runtime/smoke.py run E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-ed9e109ccc9a`: exit 0, CAPTURED.

`python tools/lite-runtime/verify.py E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-isolated-ed9e109ccc9a`: exit 0, PASS_CONTROLLED_SMOKE. The same verifier rejected this fixture before execution because attempt.json was absent. Final verifier reads raw logs and exact artifact hashes, not merely runner status.

Native server evidence: server-1.log lines 82–93 record permission, identity, history, GUI-open/read-only click and retained duplicate items. Duplicate confirmation records code CQ3RLR, two locations, action NOTIFY. Bot messages separately establish member denial, staff history and staff alert. server-2.log lines 64–65 establish unchanged item code/UUID and readable history after restart. This is seeded disposable inventory evidence, not natural acquisition/crafting evidence.

## Cleanup and durable read-back

All four owned children exited 0, without forced kill, log readers closed. Independent tasklist read-back found no PID 8504, 49552, 37928 or 17716. Fresh TCP connect to 127.0.0.1:63411 returned 10061. Both server logs end with All dimensions are saved. Read-only SQLite PRAGMA integrity_check on the exact fixture DB returned `ok`.

## Remaining boundaries

NOT_RELEASE_READY. No real-client visual acceptance, Vietnamese presentation acceptance, crafting/natural-break, scale/concurrency/crash/AV/host-guardian proof, or other Paper-version runtime support. No production, public upload, commit or push. Product candidate unchanged by this smoke. Previous compile audits and 403-test product baseline retain their original scope; no new full product build was claimed here.
