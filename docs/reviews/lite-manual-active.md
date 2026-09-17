# Closed LITE manual session

State: SESSION_CLOSED, visual acceptance PENDING. The server stopped at its configured deadline; no restart was performed.
User requested a small-RAM server to enter and test. New fixture, not replay of consumed smoke.

- Fixture: E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-manual-05201e247811
- Hermes controller handle: proc_d19391f63388
- Controller PID: 13348; Java PID: 55580 (recheck before later action).
- Address: 127.0.0.1:62559; Paper 1.21.11, protocol 774.
- Heap: Xms256M/Xmx1024M; ActiveProcessorCount=2; view/simulation 3; flat peaceful world; creative mode; no bots/helper; default LITE language/config.
- One-hour automatic graceful stop deadline: 2026-09-11 15:54:01 +07. Console records graceful stop at 15:54:02, ItemGuard disable and database close, followed by all dimensions saved at 15:54:03.
- Only localhost, offline mode, RCON/query off. No OP granted; waiting for user character name before permission changes.
- Candidate SHA: d86135e750b31e9271ab08256c8717e42d26c84f999ef15a2535f5dc769a0de8.
- Readiness: independent manual_status.py checks exact jars, completed startup, no error/exception, Minecraft protocol status and listener PID. Zero players online at check.
- Actual console `ig stats` responded, but rendered `com.itemguard.data.PluginStats@7672d74e`, not useful stats. Confirmed presentation defect, not fixed in this live artifact. No claim that every command is accepted.

Manual test: creative inventory -> take non-stackable sword -> hold it -> /ig check -> /ig history -> /ig gui. History may require an observed action first. Capture lore/GUI and confirm language/readability. Staff search requires identified player permissions. Do not alter/rebuild the live JAR.

Post-close reconciliation: controller PID 13348 and Java PID 55580 are absent; loopback port 62559 is closed. No automatic restart/retry occurred and no unrelated process was stopped. The old guardian scope and production remain untouched.
