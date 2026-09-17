"""Does an identity survive `/reload`, and does the plugin come back clean?

`/reload` is common on live servers and is a classic source of leaks: Bukkit disables and
re-enables the plugin inside a running JVM, so anything not torn down in `onDisable` survives
into the new instance. Static state, uncancelled scheduler tasks, and double-registered
listeners all show up here and nowhere else — a full restart hides every one of them, because
the JVM dies.

The reload path has never been exercised. `onDisable` looks thorough by inspection, but
inspection is what missed the clicked-slot defect.

    boot -> create an identity -> /reload confirm
         -> assert the identity is still readable
         -> assert the listener fires ONCE, not twice

The second assertion is the one that matters. A double-registered listener still works: the
item gets tagged, the ID is correct, everything looks fine. It just does every piece of work
twice, forever, and the only visible symptom is duplicated history rows.

Run: python tools/lite-runtime/reload.py
"""
import json
import pathlib
import sys

HOME = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HOME))

import smoke  # noqa: E402


def main():
    before = set(smoke.BASE.glob('itemguard-lite-isolated-*'))
    smoke.stage()
    created = sorted(set(smoke.BASE.glob('itemguard-lite-isolated-*')) - before)
    if len(created) != 1:
        raise RuntimeError(f'expected exactly one new fixture root, saw {created}')
    fixture = created[0]

    m = smoke.admission(fixture)
    smoke.write_attempt(fixture, 'reload')
    result = {'status': 'FAILED', 'root': str(fixture)}

    server = bots = None
    try:
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

        # Baseline the handler registry BEFORE reloading. An absolute count cannot be judged:
        # a class with two @EventHandler methods for one event legitimately registers twice.
        # What proves a leak is the count CHANGING across a reload.
        reg0 = server.count('LITE_LISTENER_COUNT ')
        server.send('liteprobe listener-count')
        server.has_more('LITE_LISTENER_COUNT ', reg0, 30)
        counts_before = next(l.split('LITE_LISTENER_COUNT ')[1].strip()
                             for l in reversed(server.lines) if 'LITE_LISTENER_COUNT ' in l)
        result['listener_counts_before'] = counts_before

        rows_before = server.count('LITE_HISTORY_ROWS ')
        server.send('liteprobe history-rows')
        server.has_more('LITE_HISTORY_ROWS ', rows_before, 30)
        count_before = int(next(l.split('LITE_HISTORY_ROWS ')[1].split()[0]
                                for l in reversed(server.lines) if 'LITE_HISTORY_ROWS ' in l))
        result['history_rows_before'] = count_before

        # ---- the reload itself -------------------------------------------------------
        disables = server.count('ItemGuard is disabled.')
        # Brigadier routes a bare `reload` to vanilla's datapack reload, which rejects the
        # `confirm` argument ("reload confirm<--[HERE]") and never touches plugins. The Bukkit
        # command that actually cycles plugins has to be named explicitly.
        boots = server.count('LITE_PROBE_BOOT')
        server.send('bukkit:reload confirm')
        server.has_more('ItemGuard is disabled.', disables, 90)
        # has() scans the whole accumulated log, so it would match the FIRST boot line and
        # return instantly without ever waiting for the plugin to come back. Count and require
        # a new one.
        server.has_more('LITE_PROBE_BOOT', boots, 90)
        result['reloaded'] = True

        # Any exception thrown while re-enabling means the plugin came back broken even if it
        # reports for duty.
        result['errors_during_reload'] = [
            l.strip()[:160] for l in server.lines[-200:]
            if 'ItemGuard' in l and ('ERROR' in l or 'Exception' in l)]

        # ---- 1. the identity is still readable after reload --------------------------
        server.send('liteprobe identity-readback')
        server.has('LITE_READBACK ', 40)
        readback = next(l.split('LITE_READBACK ')[1].strip()
                        for l in reversed(server.lines) if 'LITE_READBACK ' in l)
        observed = readback.split()[0]
        result['code_after'] = observed
        result['identity_survived'] = observed == code

        # ---- 2. work happens once, not twice -----------------------------------------
        # A listener registered twice still produces correct-looking results; the only tell is
        # that every action is recorded twice. Move the item once and count the new rows.
        # Measuring duplicate listeners needs an action that actually fires a Bukkit event.
        # Two approaches were tried and both failed for reasons that are NOT plugin defects:
        #   - a bot drop: after bukkit:reload the bot keeps its item but its drops no longer
        #     spawn an entity, so nothing happens at all;
        #   - probe setItem(): a direct inventory write fires no event, so no listener runs and
        #     no history row is written. LITE_SINGLE_MOVE_DONE from=0 to=9 followed by an
        #     unchanged row count is the plugin behaving correctly, not a miss.
        # Rather than assert something this fixture cannot observe, the duplicate-listener
        # question is answered from the handler registry instead.
        registrations = server.count('LITE_LISTENER_COUNT ')
        server.send('liteprobe listener-count')
        server.has_more('LITE_LISTENER_COUNT ', registrations, 30)
        counts = next(l.split('LITE_LISTENER_COUNT ')[1].strip()
                      for l in reversed(server.lines) if 'LITE_LISTENER_COUNT ' in l)
        result['listener_counts_after'] = counts

        # Bukkit reports how many handlers each event has registered. Exactly one registration
        # per ItemGuard listener is the claim; two means onDisable failed to unregister and the
        # plugin is now doing every piece of work twice - invisible in normal use because the
        # results still look correct.
        def parse(text):
            out = {}
            for pair in text.split(';'):
                if pair:
                    name, value = pair.split('=')
                    out[name] = int(value)
            return out

        before_map, after_map = parse(counts_before), parse(counts)
        grew = {name: (before_map.get(name, 0), value)
                for name, value in after_map.items() if value > before_map.get(name, 0)}
        result['handlers_that_grew'] = grew
        # onDisable must unregister the old instance's handlers. If any class ends up with more
        # registrations than it had before, the old ones survived and every event now runs
        # twice - with correct-looking results, which is why nothing else would reveal it.
        result['no_duplicate_listener'] = not grew

        ok = (result['identity_survived'] and result['no_duplicate_listener']
              and not result['errors_during_reload'])
        result['status'] = 'PASS_RELOAD' if ok else 'FAILED_RELOAD'
        return result
    finally:
        cleanup = []
        for child, proc in (('bots', bots), ('server', server)):
            if proc is None:
                continue
            try:
                cleanup.append({'child': child,
                                **proc.stop('{"quit":true}' if child == 'bots' else 'stop')})
            except Exception as exc:
                cleanup.append({'child': child, 'error': repr(exc)})
        result['cleanup'] = cleanup
        smoke.save(fixture / 'reload.json', result)
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
