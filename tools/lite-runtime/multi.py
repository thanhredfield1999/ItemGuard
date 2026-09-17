"""Two tracked items, and two players touching one item.

Every fixture so far has used exactly one tracked item and one acting player. Both limits hide
a specific class of bug:

* **One item cannot catch slot confusion.** Read the wrong slot with a single tracked item and
  you usually find nothing, so the mistake fails loudly. With two tracked items side by side,
  reading the wrong slot finds the *other* tracked item — and the bug looks like success. This
  is exactly how the clicked-slot defect survived its first fixture.

* **One player cannot catch contention.** SQLite has a single writer. Two players moving the
  same item, and two staff querying it at once, exercise the queueing around that writer.
  Nothing has ever run those paths.

Three cases, one server:

    A. seed two trackable items -> both get identities -> assert they are DIFFERENT
    B. staff drops a tracked item, member picks it up, both run /ig check concurrently
       -> one identity, consistent for both readers
    C. the privacy boundary: the member now owns the item and its history holds the staff
       member's DROP. The member's own drilldown must hide the other actor's name and
       location; staff must still see both. Until 2026-09-17 this branch had only ever been
       exercised by unit tests, because no fixture put a foreign actor into a member's
       owner-scoped window.

Run: python tools/lite-runtime/multi.py
"""
import json
import pathlib
import sys

HOME = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HOME))

import smoke  # noqa: E402
import verify  # noqa: E402


def main():
    before = set(smoke.BASE.glob('itemguard-lite-isolated-*'))
    smoke.stage()
    created = sorted(set(smoke.BASE.glob('itemguard-lite-isolated-*')) - before)
    if len(created) != 1:
        raise RuntimeError(f'expected exactly one new fixture root, saw {created}')
    fixture = created[0]

    m = smoke.admission(fixture)
    smoke.write_attempt(fixture, 'multi')
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

        # ---- case A: two tracked items must not share an identity -------------------
        server.send('liteprobe seed-two')
        server.has('LITE_SEED_TWO_DONE', 20)
        server.send('liteprobe two-items')
        server.has('LITE_TWO_ITEMS ', 40)
        line = next(l.split('LITE_TWO_ITEMS ')[1].strip()
                    for l in reversed(server.lines) if 'LITE_TWO_ITEMS ' in l)
        fields = dict(part.split('=', 1) for part in line.split())
        result['two_items'] = fields
        result['two_items_distinct'] = fields.get('distinct') == 'true'

        # ---- case B: two players, one item, concurrent reads ------------------------
        server.send('liteprobe identity')
        server.has('LITE_CASE identity PASS', 40)
        server.has('LITE_CODE ', 10)
        code = next(l.split('LITE_CODE ')[1].split()[0]
                    for l in reversed(server.lines) if 'LITE_CODE ' in l)
        result['shared_code'] = code

        # Hand the item over for real: drop it, let the other player take it.
        # toss() drives the bots; pull() drives the probe on the server. Passing the wrong
        # process sends 'liteprobe pull' into the bot's JSON stdin and it dies on the parse.
        smoke.toss(bots, 'LiteStaff', 20)
        smoke.pull(server, 'LiteMember', 30)

        # Both query at the same time - no waiting between the two sends. If the single-writer
        # queue is mishandled, this is where it shows.
        marks = len([l for l in bots.lines if 'ItemGuard LITE' in l])
        bots.send(json.dumps({'player': 'LiteStaff', 'chat': '/ig check'}))
        bots.send(json.dumps({'player': 'LiteMember', 'chat': '/ig check'}))
        bots.wait(lambda ls: len([l for l in ls if 'ItemGuard LITE' in l]) >= marks + 2, 40)

        # Harvest the codes each reader was actually shown, rather than asking whether the
        # expected code appears somewhere. `{code} if anyone_saw_it else set()` can only ever
        # hold one element, so a "no two readers disagree" check built that way can never fail.
        import re as _re
        tail = bots.lines[-120:]
        seen = {'LiteStaff': set(), 'LiteMember': set()}
        for line in tail:
            for actor in seen:
                if f'"player":"{actor}"' in line:
                    seen[actor].update(_re.findall(r'\b[A-Z0-9]{6}\b', line))
        # Six-character uppercase tokens that are not identity codes (chat furniture, the
        # plugin banner) would be noise, so only compare against codes this run minted.
        minted = {code, result['two_items']['slot0'], result['two_items']['slot1']}
        staff_codes = seen['LiteStaff'] & minted
        member_codes = seen['LiteMember'] & minted
        result['staff_codes'] = sorted(staff_codes)
        result['member_codes'] = sorted(member_codes)
        result['staff_saw_code'] = code in staff_codes
        result['member_saw_code'] = code in member_codes

        # The real claim: whoever can see the item must see THE SAME identity for it. A reader
        # shown a different code for one physical object is the failure worth catching.
        readers_with_codes = [s for s in (staff_codes, member_codes) if s]
        result['one_identity_for_both'] = (
            len(readers_with_codes) > 0
            and all(s == readers_with_codes[0] for s in readers_with_codes))

        # After the handover the member holds the item and the staff account holds nothing, so
        # the staff reply SHOULD be "no tracking ID" - that is correct behaviour, not a miss.
        # Asserted explicitly: a run where staff silently saw nothing at all (a broken command,
        # a permission regression) would otherwise look identical to this expected outcome.
        result['staff_told_no_id'] = any(
            '"player":"LiteStaff"' in l and 'no tracking ID' in l for l in tail)
        result['holder_sees_id_non_holder_does_not'] = (
            result['member_saw_code'] and not result['staff_saw_code']
            and result['staff_told_no_id'])

        server.send('liteprobe history')
        server.has('LITE_CASE history PASS', 40)
        result['history_consistent'] = True

        # ---- case C: the privacy boundary, exercised for real ----------------------
        # Attribute replies by the bot the plugin was answering, and only look at the lines from
        # this moment on: a whole-stream search for "LiteStaff" matches the vanilla
        # "joined the game" broadcast, which is how an earlier version of this assertion passed
        # without testing anything.
        # Attribution and shapes come from verify.py, which owns this adjudication and is exercised
        # by the contract tests: each log line is `{"player":"LiteMember","message":"..."}`, so a
        # search for "LiteStaff" over the raw line matches the envelope of every line addressed to
        # staff, and the timeline rows carry no plugin prefix. Both mistakes made the first version
        # of this check either vacuous or blind.
        def ask(actor, command, want_lines):
            mark = len(bots.lines)
            bots.send(json.dumps({'player': actor, 'chat': command}))
            bots.wait(lambda ls: len(verify.bot_messages('\n'.join(ls[mark:]))) >= want_lines, 40)

        ask('LiteMember', f'/ig history #{code}', 1)
        ask('LiteStaff', f'/ig history #{code}', 1)

        facts = verify.privacy_facts('\n'.join(bots.lines))
        result.update(facts)
        result['privacy_ok'] = (
            facts['staff_saw_actor'] and facts['member_foreign_rows'] > 0
            and not facts['member_leaked_actor'] and not facts['member_kept_foreign_location'])

        ok = (result['two_items_distinct'] and result['one_identity_for_both']
              and result['holder_sees_id_non_holder_does_not'] and result['privacy_ok'])
        result['status'] = 'PASS_MULTI' if ok else 'FAILED_MULTI'
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
        smoke.save(fixture / 'multi.json', result)
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
