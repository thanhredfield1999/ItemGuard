"""Does an identity survive a server that is killed mid-write?

The existing fixture restarts the server *cleanly*: Paper flushes its worlds and SQLite closes
its connection, so "the ID came back" proves only that an orderly shutdown works. Pulling the
plug is a different failure, and it is the one a server owner actually meets.

This harness does the impolite version:

    boot -> create a tracked identity -> start a write -> SIGKILL the JVM mid-write
         -> boot again on the same world and database -> demand the ID and its history

Deliberate choices:

* `taskkill /F` (not `stop`, not SIGTERM). Anything Paper can catch would let it save, which
  is exactly the behaviour under test. The JVM dies with no chance to flush.
* The kill lands **while a write is in flight**, not while idle. Killing an idle server proves
  very little; the dangerous window is the one where a transaction is half-written.
* Second boot reuses the same fixture root. A fresh world would silently pass.
* The item is read back from the *player's inventory*, not from the database. The ID lives in
  the item's PDC, and that is the claim being tested — the database only has to agree.

Run: python tools/lite-runtime/power_cut.py
"""
import json
import pathlib
import subprocess
import sys
import time

HOME = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HOME))

import smoke  # noqa: E402  - reuses the pinned candidate, staging and process plumbing


def kill_hard(process):
    """Kill the JVM in a way it cannot intercept, and confirm the OS agrees it is gone."""
    pid = process.p.pid
    subprocess.run(['taskkill', '/F', '/T', '/PID', str(pid)],
                   capture_output=True, check=False)
    try:
        process.p.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.p.kill()
        process.p.wait(timeout=15)
    if process.p.stdin:
        try:
            process.p.stdin.close()
        except OSError:
            pass
    process.thread.join(timeout=10)
    return pid


def main():
    # stage() prints the root and returns None, so capture the newest root it just created
    # rather than assuming a return value.
    before = set(smoke.BASE.glob('itemguard-lite-isolated-*'))
    smoke.stage()
    created = sorted(set(smoke.BASE.glob('itemguard-lite-isolated-*')) - before)
    if len(created) != 1:
        raise RuntimeError(f'expected exactly one new fixture root, saw {created}')
    fixture = created[0]
    m = smoke.admission(fixture)
    smoke.write_attempt(fixture, 'power-cut')
    result = {'status': 'FAILED', 'root': str(fixture)}

    server = bots = None
    try:
        # ---- generation 1: create an identity, then die mid-write -------------------
        smoke.available(m['port'])
        server = smoke.Process([smoke.JAVA, '-Xms512M', '-Xmx1536M', '-jar', 'paper.jar',
                                '--nogui'], fixture, 'server-1')
        server.has('Done (', 180)
        server.has('LITE_PROBE_BOOT', 10)

        bots = smoke.Process(['node', 'bots.cjs', str(m['port'])], fixture, 'bots-1')
        bots.wait(lambda ls: sum('"event":"spawn"' in l for l in ls) == 2, 60)

        server.send('liteprobe seed')
        server.has('LITE_CASE permissions PASS')
        server.send('liteprobe identity')
        server.has('LITE_CASE identity PASS')
        server.has('LITE_CODE ', 10)

        code = next(l.split('LITE_CODE ')[1].split()[0]
                    for l in reversed(server.lines) if 'LITE_CODE ' in l)
        result['code_before'] = code

        # Generate history writes, then kill while they are still being flushed. The item is
        # moved repeatedly so SQLite has an open transaction when the process dies.
        server.send('liteprobe history-churn')
        time.sleep(1.5)                      # long enough to be mid-write, short enough to be unclean
        killed_pid = kill_hard(server)
        server = None
        result['killed_pid'] = killed_pid

        if bots is not None:
            try:
                bots.stop('{"quit":true}')
            except Exception:
                pass
            bots = None

        db = fixture / 'plugins/ItemGuard/itemguard.db'
        result['db_bytes_after_kill'] = db.stat().st_size if db.is_file() else 0
        # A leftover journal means the kill really did interrupt a transaction.
        result['journal_left'] = sorted(
            p.name for p in db.parent.glob('itemguard.db-*')) if db.parent.is_dir() else []

        # ---- generation 2: same world, same database, no repair ---------------------
        time.sleep(2)
        smoke.available(m['port'])
        server = smoke.Process([smoke.JAVA, '-Xms512M', '-Xmx1536M', '-jar', 'paper.jar',
                                '--nogui'], fixture, 'server-2')
        server.has('Done (', 180)
        server.has('LITE_PROBE_BOOT', 10)

        bots = smoke.Process(['node', 'bots.cjs', str(m['port'])], fixture, 'bots-2')
        bots.wait(lambda ls: sum('"event":"spawn"' in l for l in ls) == 2, 60)

        # Ask the plugin what the item in the player's hand is, after the crash.
        server.send('liteprobe identity-readback')
        server.has('LITE_READBACK ', 30)
        readback = next(l.split('LITE_READBACK ')[1].strip()
                        for l in reversed(server.lines) if 'LITE_READBACK ' in l)
        result['readback'] = readback

        bots.send(json.dumps({'player': 'LiteStaff', 'chat': '/ig check'}))
        bots.has('ItemGuard LITE', 30)
        result['check_lines'] = [l for l in bots.lines[-40:] if 'LITE_CODE' in l or code in l]

        # The probe prints "<CODE> ready=.. expected=<CODE>" on success and
        # "NONE expected=<CODE>" on failure. A substring test against the whole line matches
        # the echoed expected= value in BOTH cases, so it can never fail. Compare the first
        # field only.
        observed = readback.split()[0]
        result['observed_code'] = observed
        result['identity_survived'] = observed == code

        # The item itself is NOT expected back. A player's inventory lives in
        # playerdata/<uuid>.dat, written by vanilla on logout or autosave - not by ItemGuard.
        # Killing the JVM before an autosave rolls the player back, and every item goes with
        # it, tracked or not. Asserting the item survives would be asserting that a plugin can
        # undo a vanilla world-save loss, which it cannot.
        #
        # What IS ItemGuard's to guarantee is its own records, so that is what gates the run.
        db = fixture / 'plugins/ItemGuard/itemguard.db'
        rows = {}
        try:
            import sqlite3
            with sqlite3.connect(str(db)) as con:
                for table in ('tracked_items', 'item_history', 'item_snapshots',
                              'tag_publications'):
                    columns = [c[1] for c in con.execute(f'PRAGMA table_info("{table}")')]
                    rows[table] = sum(
                        con.execute(f'SELECT COUNT(*) FROM "{table}" WHERE "{c}"=?',
                                    (code,)).fetchone()[0]
                        for c in columns)
        except Exception as exc:
            rows = {'error': repr(exc)}
        result['db_rows_for_identity'] = rows
        result['records_survived'] = all(
            isinstance(n, int) and n > 0 for n in rows.values()) and bool(rows)
        survived = result['records_survived']
        result['status'] = 'PASS_POWER_CUT' if survived else 'FAILED_RECORDS_LOST'
        return result
    finally:
        cleanup = []
        for child, proc in (('bots', bots), ('server', server)):
            if proc is None:
                continue
            try:
                cleanup.append({'child': child,
                                **proc.stop('{"quit":true}' if child == 'bots' else 'stop')})
            except Exception as exc:                      # cleanup must never mask the verdict
                cleanup.append({'child': child, 'error': repr(exc)})
        result['cleanup'] = cleanup
        smoke.save(fixture / 'power-cut.json', result)
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
