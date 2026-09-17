# Manual test server lifecycle — 2026-09-13

Every test task owns and closes the server it opens, including failure/cancellation.
Do not open a server while merely preparing instructions or waiting for Thanh to test later.
Keep at most one ItemGuard manual server running. Finish and verify cleanup before starting
another project fixture. Existing smoke.py already uses try/finally for server and bot cleanup.

## Commands

Run from the ItemGuard source folder:

```powershell
python tools/lite-runtime/manual.py stage --op Thanh
python tools/lite-runtime/manual.py run E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-manual-<actual-id>
python tools/lite-runtime/manual.py stop E:/AI.WORK/30_KET_QUA_THU_NGHIEM/itemguard-lite-manual-<actual-id>
```

Use the exact path printed by stage; never invent or reuse a consumed attempt. The artifact
hash pin and one-attempt guard remain in place. Old staged manifests intentionally fail the
controller hash check after a launcher edit; do not rewrite those manifests to bypass it.

Normal run stays attached: stdin EOF, Ctrl+C, a stop.request, a child exit or a deadline ends
the session. Default deadline is 30 minutes. It sends `stop`, waits up to 60 seconds to save,
and kills only its direct child after that timeout, reporting `forced: true` as a failure.
The session-wide OS lock remains held through cleanup, so two different fixture directories
cannot open two manual servers at once. An unfinished legacy session also blocks a new run.

Only when Thanh requests a live manual test, explicitly use `run ROOT --detach --minutes 30`
(an integer from 1 to 120). This mode uses console.in and stop.request and has no stdin owner;
it must be closed using `stop ROOT` as soon as the test ends. Background launchers must use
`Start-Process -WindowStyle Hidden`. Do not extend a deadline or open a replacement just to wait
for a human. Reuse the existing live session if it still fits the requested test.

The task must put `stop ROOT` in its own finally/cleanup step, then verify outcome.json, the
owned Java/controller PIDs, port closure and Paper's saved-world log. A launcher's stdin is
not the same thing as a Codex/Hermes task ending: a detached task requires explicit cleanup.
`stop ROOT` waits for the child's exit and released port and returns the controller receipt;
it never kills a PID from a file. Forced/nonzero outcomes are not clean shutdown evidence.

An OS hard kill of the controller bypasses Python finally. The launch guard detects an orphan
with a live receipt and refuses to stack another server; inspect that exact process before
recovery. This is not a host-crash or production guardian. Do not use `taskkill /IM java.exe`.

## Validation and observed cleanup

2026-09-13: seven old six-hour manual sessions were closed through their existing stop.request
interface. All seven Java processes and controllers exited, all seven ports closed, all seven
Paper logs confirmed worlds saved, and all seven receipts were exit 0 / forced false.

Run launcher regression checks without a Paper server:

```powershell
python -m unittest discover -s tools/lite-runtime -p 'test_*.py' -v
```

test_manual_lifecycle.py uses real Python child processes and loopback sockets, not Minecraft.
These tests establish launcher behavior, not plugin correctness or visual acceptance.
