"""Two plugins, one item: does a BastionForge edit disturb an ItemGuard identity?

Everything so far has been indirect — BastionForge's source was read, and the persistent data
container was modelled as the key/value map it is. Neither has ever had both jars running on
one server, which is what Thanh actually asked about.

This fixture installs BastionForgeLite alongside ItemGuard LITE and drives a real item through
both:

    give a tracked sword -> ItemGuard assigns an identity + digest
    apply a BastionForge-shaped edit (editMeta, lore + attributes + its own PDC key)
    -> re-read the identity and the digest

Two separate questions, and they have different right answers:

* **Identity must not change.** It is the same physical sword; a second identity would make
  one item look like two, which is the exact failure the plugin exists to prevent.
* **Digest is expected to change**, because the digest hashes the whole item and the item
  genuinely did change. What matters is that the *identity* survives to tie the two snapshots
  together — otherwise duplicate detection on a socketed item compares nothing.

The edit is applied through the probe rather than through BastionForge's GUI: socketing needs
menu clicks a bot cannot reliably drive. The probe reproduces the exact write pattern read
from BukkitLiteItemPort.java:139 (editMeta in place, set one namespaced key, rewrite lore and
attribute modifiers). That makes this a test of the *interaction*, with BastionForge present
and loaded, not a test of BastionForge's menu.

Run: python tools/lite-runtime/two_plugin.py
"""
import json
import pathlib
import shutil
import sys

HOME = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HOME))

import smoke  # noqa: E402

FORGE_JAR = pathlib.Path('E:/AI.WORK/ForceItem/bastion-lite/build/libs/bastion-lite-0.1.0.jar')


def main():
    if not FORGE_JAR.is_file():
        raise SystemExit(f'BastionForge jar not found: {FORGE_JAR}')

    before = set(smoke.BASE.glob('itemguard-lite-isolated-*'))
    smoke.stage()
    created = sorted(set(smoke.BASE.glob('itemguard-lite-isolated-*')) - before)
    if len(created) != 1:
        raise RuntimeError(f'expected exactly one new fixture root, saw {created}')
    fixture = created[0]

    # The whole point: the other plugin is really installed and really loads.
    shutil.copy2(FORGE_JAR, fixture / 'plugins' / FORGE_JAR.name)

    m = smoke.admission(fixture)
    smoke.write_attempt(fixture, 'two-plugin')
    result = {'status': 'FAILED', 'root': str(fixture), 'forge_jar': FORGE_JAR.name}

    server = bots = None
    try:
        smoke.available(m['port'])
        server = smoke.Process([smoke.JAVA, '-Xms512M', '-Xmx1536M', '-jar', 'paper.jar',
                                '--nogui'], fixture, 'server-1')
        server.has('Done (', 180)
        server.has('LITE_PROBE_BOOT', 10)

        # Both plugins must be enabled. If BastionForge failed to load, every later assertion
        # would pass for the wrong reason: nothing would be editing the item at all.
        forge_enabled = any('BastionForgeLite' in l and 'Enabling' in l for l in server.lines)
        result['forge_enabled'] = forge_enabled
        if not forge_enabled:
            result['status'] = 'FAILED_FORGE_NOT_LOADED'
            return result

        bots = smoke.Process(['node', 'bots.cjs', str(m['port'])], fixture, 'bots-1')
        bots.wait(lambda ls: sum('"event":"spawn"' in l for l in ls) == 2, 60)

        server.send('liteprobe seed')
        server.has('LITE_CASE permissions PASS')
        server.send('liteprobe identity')
        server.has('LITE_CASE identity PASS')
        server.has('LITE_CODE ', 10)
        code_before = next(l.split('LITE_CODE ')[1].split()[0]
                           for l in reversed(server.lines) if 'LITE_CODE ' in l)
        result['code_before'] = code_before

        server.send('liteprobe digest')
        server.has('LITE_DIGEST ', 20)
        digest_before = next(l.split('LITE_DIGEST ')[1].split()[0]
                             for l in reversed(server.lines) if 'LITE_DIGEST ' in l)
        result['digest_before'] = digest_before

        # Socket a gem the way BastionForge does it.
        server.send('liteprobe forge-socket')
        server.has('LITE_SOCKET_APPLIED', 20)

        server.send('liteprobe identity-readback')
        server.has('LITE_READBACK ', 30)
        readback = next(l.split('LITE_READBACK ')[1].strip()
                        for l in reversed(server.lines) if 'LITE_READBACK ' in l)
        code_after = readback.split()[0]
        result['code_after'] = code_after

        server.send('liteprobe digest')
        server.has_more('LITE_DIGEST ', 1, 20)
        digest_after = next(l.split('LITE_DIGEST ')[1].split()[0]
                            for l in reversed(server.lines) if 'LITE_DIGEST ' in l)
        result['digest_after'] = digest_after

        bots.send(json.dumps({'player': 'LiteStaff', 'chat': '/ig check'}))
        bots.has('ItemGuard LITE', 30)
        result['check_saw_code'] = any(code_before in l for l in bots.lines[-60:])

        result['identity_survived'] = code_after == code_before
        result['digest_changed'] = digest_after != digest_before
        result['status'] = ('PASS_TWO_PLUGIN' if result['identity_survived']
                            else 'FAILED_IDENTITY_CHANGED')
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
        smoke.save(fixture / 'two-plugin.json', result)
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
