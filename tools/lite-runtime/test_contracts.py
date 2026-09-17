"""Synthetic UNIT fixtures only: verifier rejection and child cleanup, not Paper evidence."""
import hashlib, json, shutil, tempfile, unittest
from pathlib import Path
from unittest import mock
import verify
import smoke
import sys

class FastClock:
    """Drop-in for smoke.time so a driver timeout is adjudicated without waiting in real seconds."""
    def __init__(self): self.now=0.0
    def monotonic(self):
        self.now+=1.0; return self.now
    def sleep(self, seconds): self.now+=seconds

class DummyPopen:
    pid=0; returncode=0; stdin=None
    def poll(self): return None
    def wait(self, timeout=None): return 0
    def kill(self): pass

class DummyThread:
    def join(self, timeout=None): pass
    def is_alive(self): return False

def scripted_process(stagings):
    """A scripted stand-in for a child process, with the REAL wait/count/has_more logic kept.

    Only the transport is faked, so what is under test is the driver's own ordering contract:
    which lines it insists on seeing, and how many of them. `stagings` is how many times the
    scripted candidate actually answers `liteprobe craftprepare` with a staged result.
    """
    state={'craftprepare':0}
    class ScriptedProcess(smoke.Process):
        def __init__(self, args, fixture, name):
            self.lines=[]; self.sent=[]; self.p=DummyPopen(); self.thread=DummyThread()
            if name.startswith('server'):
                self.lines+= ['Done (1.234s)! For help, type "help"\n','LITE_PROBE_BOOT\n']
            else:
                self.lines+= ['{"event":"spawn","player":"LiteStaff"}\n',
                              '{"event":"spawn","player":"LiteMember"}\n']
        def send(self, value):
            self.sent.append(value)
            if value=='liteprobe craftstage':
                self.lines+= ['LITE_CASE craft-table-staged PASS\n','LITE_CASE craft-window-opened PASS\n']
            elif value=='liteprobe craftprepare':
                state['craftprepare']+=1
                if state['craftprepare']<=stagings: self.lines.append('LITE_CASE craft-result-staged PASS\n')
            elif value=='liteprobe craftassert normal':
                self.lines.append('LITE_CASE craft-normal-fail-closed PASS\n')
            elif value=='liteprobe craftassert shift':
                self.lines.append('LITE_CASE craft-shift-fail-closed PASS\n')
            elif 'craftReady' in value:
                self.lines.append('{"event":"craft-ready","player":"LiteStaff"}\n')
            elif 'craftClick' in value:
                self.lines.append('{"event":"craft-clicked","player":"LiteStaff"}\n')
    return ScriptedProcess

def scripted_bots(receipts):
    """A scripted bots child that answers only the first `receipts` toss requests.

    The REAL has/count/has_more logic is kept and only the transport is faked, so what is under
    test is the driver's own ordering contract at the custody call sites: whether a repeated toss
    waits for a NEW client receipt or is satisfied by the line an earlier iteration already wrote.
    """
    state={'toss':0}
    class ScriptedBots(smoke.Process):
        def __init__(self):
            self.lines=[];self.sent=[];self.p=DummyPopen();self.thread=DummyThread()
        def send(self, value):
            self.sent.append(value)
            if json.loads(value).get('toss'):
                state['toss']+=1
                if state['toss']<=receipts:
                    self.lines.append('{"event":"tossed","player":"LiteStaff"}\n')
    return ScriptedBots

# --- Crafting-close receipts, as the bounded loss gate will have to read them. ---
# A crafting window closed with a tracked stack in the grid is returned to the player by vanilla,
# or dropped at their feet when there is no room. Either way the item still exists, so no loss and
# in particular no CLEARED row may be written. Both cases are mandatory: the spare-inventory case
# proves the return path, the full-inventory case proves the drop path, and only the full case can
# show what happens when the return has nowhere to go. Every field below is an observation the probe
# has to read back off the server; none of them may be an assumed constant.
CRAFTCLOSE = {
    'spare': ('LITE_LOSS craftclose spare windowClosed=true closeAck=CLIENT'
              ' codeBefore=UNIT01 codeAfter=UNIT01'
              ' uuidBefore=c0ffee00-1111-4222-8333-444455556666'
              ' uuidAfter=c0ffee00-1111-4222-8333-444455556666'
              ' holder=LiteStaff holderType=PLAYER_INVENTORY entityResolved=false inSlots=true'
              ' slotsFree=27 fullAtClose=false dropObserved=false'
              ' ticksObserved=80 samples=80 rowsBefore=0 rowsAfter=0'
              ' clearedBefore=0 clearedAfter=0 staffAlive=true\n'),
    'full':  ('LITE_LOSS craftclose full windowClosed=true closeAck=CLIENT'
              ' codeBefore=UNIT02 codeAfter=UNIT02'
              ' uuidBefore=deadbee0-7777-4888-8999-aaaabbbbcccc'
              ' uuidAfter=deadbee0-7777-4888-8999-aaaabbbbcccc'
              ' holder=GROUND holderType=ITEM_ENTITY entityResolved=true inSlots=false'
              ' slotsFree=0 fullAtClose=true dropObserved=true'
              ' ticksObserved=80 samples=80 rowsBefore=0 rowsAfter=0'
              ' clearedBefore=0 clearedAfter=0 staffAlive=true\n'),
}

class PrivacyAdjudicationContract(unittest.TestCase):
    """The multi scope's privacy claim, decided from the transcripts.

    Three ways this can look true without being true, and each one is a case here: the member's
    stream leaking the actor name, the member never being answered at all, and the staff view not
    containing the name either - in which case the member's silence proves nothing. The third is the
    one an earlier attempt at this assertion got wrong: it searched the whole member stream for the
    actor's name and matched the vanilla "joined the game" broadcast.
    """

    REDACTED = ('[ItemGuard LITE] 2. Drop | another player (staff only) | location hidden',
                '[ItemGuard LITE] 3. Pickup | LiteMember | world (0, 64, 0)')
    DISCLOSED = ('[ItemGuard LITE] 2. Drop | LiteStaff | world (10, 64, 5)',
                 '[ItemGuard LITE] 3. Pickup | LiteMember | world (0, 64, 0)')

    def log(self, member_lines, staff_lines):
        # Compact separators on purpose: bots.cjs writes `"player":"LiteMember"` with no space, and
        # the adjudicator matches that exact shape. A default `json.dumps` (which inserts a space)
        # would make these tests pass against a format the harness never produces.
        rows = [{'event': 'message', 'player': 'LiteMember', 'message': line}
                for line in member_lines]
        rows += [{'event': 'message', 'player': 'LiteStaff', 'message': line}
                 for line in staff_lines]
        return '\n'.join(json.dumps(row, separators=(',', ':')) for row in rows)

    def test_redacted_member_view_with_a_staff_control_passes(self):
        facts = verify.adjudicate_privacy(self.log(
            self.REDACTED,
            ['[ItemGuard LITE] Recent history for #7F8EB4 (newest first, up to 45 entries):',
             '2. Dropped | LiteStaff | world (10, 64, 5)']))
        self.assertEqual(1, facts['member_foreign_rows'])
        self.assertTrue(facts['member_saw_redaction'])
        self.assertFalse(facts['member_kept_foreign_location'])
        self.assertFalse(facts['member_leaked_actor'])

    def test_member_view_disclosing_the_actor_rejects(self):
        with self.assertRaisesRegex(AssertionError, 'disclosed the other actor name'):
            verify.adjudicate_privacy(self.log(
                self.DISCLOSED,
                ['[ItemGuard LITE] Recent history for #7F8EB4 (newest first, up to 45 entries):',
                 '2. Dropped | LiteStaff | world (10, 64, 5)']))

    def test_a_plugin_line_in_an_unlisted_shape_is_still_inspected(self):
        """M2 (review #3): the leak check used to allowlist plugin output to the prefix or a row
        number, while `LiteCommand` sends its overview lines straight to `sendMessage` - so a
        disclosure in that shape was invisible to every assertion in this class."""
        overview = '\u00a76#7F8EB4 \u00a7f| latest: LiteStaff dropped it (10, 64, 5)'
        with self.assertRaisesRegex(AssertionError, 'disclosed the other actor name'):
            verify.adjudicate_privacy(self.log(
                [overview, *self.REDACTED],
                ['[ItemGuard LITE] Recent history for #7F8EB4:',
                 '2. Dropped | LiteStaff | world (10, 64, 5)']))

    def test_member_never_answered_rejects_rather_than_passing_vacuously(self):
        # The member's only line is the vanilla broadcast - which is exactly the line the first
        # version of this assertion matched when it searched the whole stream for the actor name.
        with self.assertRaisesRegex(AssertionError, 'never answered'):
            verify.adjudicate_privacy(self.log(
                ['LiteStaff joined the game'],
                ['[ItemGuard LITE] Recent history for #7F8EB4:',
                 '2. Dropped | LiteStaff | world (10, 64, 5)']))

    def test_staff_view_without_the_actor_rejects_so_the_member_claim_means_something(self):
        with self.assertRaisesRegex(AssertionError, 'staff view never showed the actor'):
            verify.adjudicate_privacy(self.log(
                self.REDACTED, ['[ItemGuard LITE] Recent history for #7F8EB4:']))

    def test_a_foreign_row_that_kept_its_location_rejects(self):
        with self.assertRaisesRegex(AssertionError, 'kept a foreign location'):
            verify.adjudicate_privacy(self.log(
                ('[ItemGuard LITE] Recent history for #7F8EB4:',
                 '2. Dropped | another player (staff only) | world (10, 64, 5)'),
                ('[ItemGuard LITE] Recent history for #7F8EB4:',
                 '2. Dropped | LiteStaff | world (10, 64, 5)')))

    def test_a_vanilla_broadcast_is_not_the_plugin_answer(self):
        """The line that made the first version of this assertion meaningless."""
        self.assertFalse(verify.is_plugin_answer('LiteStaff joined the game'))
        self.assertTrue(verify.is_plugin_answer('[ItemGuard LITE] ItemGuard LITE: totals'))
        self.assertTrue(verify.is_plugin_answer('2. Dropped | another player (staff only) | location hidden'))


class Contracts(unittest.TestCase):
    def baseline(self, root):
        files={}
        for name in ['paper.jar','plugins/ItemGuard-LITE.jar','plugins/LiteProbe.jar','bots.cjs']:
            p=root/name;p.parent.mkdir(exist_ok=True);p.write_bytes(b'UNIT-ONLY');files[name]=hashlib.sha256(p.read_bytes()).hexdigest()
        # The declared candidate is the hash of the candidate jar this fixture actually contains.
        (root/'stage.json').write_text(json.dumps({'files':files,'candidate_sha256':files['plugins/ItemGuard-LITE.jar']}))
        (root/'attempt.json').write_text(json.dumps({
            'stage_sha256':hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(),
            'generations':2,'scope':'separate-lite-smoke'}))
        (root/'outcome.json').write_text(json.dumps({'status':'CAPTURED','port_released':True,'cleanup':[
            {'child':name,'exit':0,'forced':False,'log_closed':True} for name in ['bots-1','server-1','bots-2','server-2']]}))
        for g in [1,2]:
            cases=['permissions','identity','history','gui-open','gui-guide-row','gui-back-button','gui-framed-border','gui-click-readonly','craft-normal-fail-closed','craft-shift-fail-closed','custody-self-drop-not-counted','history-flood-bounded','container-scene','container-kinds-distinct','container-actions-named','hopper-not-attributed','timeline-icons-heads','chest-position-is-the-chest','custody-handover-counted-once','custody-pingpong-throttled','custody-restore','duplicate-not-removed'] if g==1 else ['restart-identity','restart-history','clear-recorded-as-cleared']
            receipts='LITE_CRAFT normal event=1 ingredient=3 output=0\nLITE_CRAFT shift event=1 ingredients=3 output=0\nLITE_CUSTODY self transfers=0 holders=1\nLITE_CUSTODY transfer transfers=1 holders=2\nLITE_CUSTODY pingpong transfers=1 holders=2\n' if g==1 else ''
            (root/f'server-{g}.log').write_text('Done (\nLITE_PROBE_BOOT\nStopping server\nAll dimensions are saved\nITEMGUARD_DUPLICATE_CONFIRMED\n'+receipts+'\n'.join('LITE_CASE '+c+' PASS' for c in cases))
            rows=[{'event':event,'player':p} for event in ['spawn','end'] for p in ['LiteStaff','LiteMember']]
            rows.extend([{'event':'message','player':'LiteMember','message':'LiteStaff joined the game'},
                {'event':'message','player':'LiteMember','message':'[ItemGuard LITE] You do not have permission.'},
                {'event':'message','player':'LiteStaff','message':'[ItemGuard LITE] Recent history for #UNIT01 (newest first, up to 45 entries):'},
                {'event':'message','player':'LiteStaff','message':'DUPE ALERT!'},
                {'event':'message','player':'LiteStaff','message':'ItemGuard policy blocks crafting a result that would need a new tracked identity'},
                {'event':'message','player':'LiteStaff','message':'ItemGuard policy blocks crafting a result that would need a new tracked identity'},
                {'event':'message','player':'LiteStaff','message':'1. First tracked | LiteStaff | world (8, -60, 9) | 2026-09-12 11:51:22 (0s ago)'},
                {'event':'message','player':'LiteMember','message':'[ItemGuard LITE] Recent history for #UNIT01 (newest first, up to 45 entries):'},
                {'event':'message','player':'LiteMember','message':'1. Picked up | another player (staff only) | location hidden | 2026-09-12 11:51:22 (0s ago)'},
                {'event':'clicked','player':'LiteStaff'}])
            (root/f'bots-{g}.log').write_text('\n'.join(json.dumps(x) for x in rows))
    def test_missing_evidence_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(FileNotFoundError):verify.verify(Path(d))
    def test_missing_case_rejects_after_valid_unit_baseline(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r);self.assertEqual('PASS_CONTROLLED_SMOKE',verify.verify(r)['status'])
            p=r/'server-1.log';p.write_text(p.read_text().replace('LITE_CASE identity PASS',''))
            with self.assertRaisesRegex(AssertionError,'identity'):verify.verify(r)

    def test_missing_back_control_rejects_after_valid_unit_baseline(self):
        """The timeline exit is the labelled Back arrow; a receipt without it is not this browser."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r)
            p=r/'server-1.log';p.write_text(p.read_text().replace('LITE_CASE gui-back-button PASS',''))
            with self.assertRaisesRegex(AssertionError,'gui-back-button'):verify.verify(r)

    def test_missing_craft_receipt_rejects_after_valid_unit_baseline(self):
        """A fresh release candidate needs real normal and shift craft receipts, not old evidence."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('LITE_CASE craft-normal-fail-closed PASS\n',''))
            with self.assertRaisesRegex(AssertionError,'craft-normal-fail-closed'):
                verify.verify(r)

    def test_missing_second_craft_denial_rejects_after_valid_unit_baseline(self):
        """Both normal and shift client clicks must receive the fail-closed denial."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r)
            p=r/'bots-1.log'
            denial='ItemGuard policy blocks crafting a result that would need a new tracked identity'
            p.write_text(p.read_text().replace(denial, '', 1))
            with self.assertRaisesRegex(AssertionError, 'craft denial'):
                verify.verify(r)

    def craft_baseline(self, root):
        """The same synthetic fixture, captured under the bounded craft scope. UNIT ONLY."""
        self.baseline(root)
        (root/'attempt.json').write_text(json.dumps({
            'stage_sha256':hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(),
            'generations':1, 'scope':'craft-only-lite-smoke'}))
        outcome=json.loads((root/'outcome.json').read_text())
        outcome['cleanup']=outcome['cleanup'][:2]
        (root/'outcome.json').write_text(json.dumps(outcome))

    def test_craft_only_receipt_accepts_clean_single_generation(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.craft_baseline(r)
            self.assertEqual('PASS_CRAFT_ONLY_SMOKE', verify.verify_craft_only(r)['status'])
    def test_failed_or_forced_outcome_rejects(self):
        for key,value in [('status','FAILED'),('port_released',False)]:
            with tempfile.TemporaryDirectory() as d:
                r=Path(d);self.baseline(r);p=r/'outcome.json';x=json.loads(p.read_text());x[key]=value;p.write_text(json.dumps(x))
                with self.assertRaises(AssertionError):verify.verify(r)
    def test_artifact_change_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r);(r/'plugins/ItemGuard-LITE.jar').write_bytes(b'TAMPER')
            with self.assertRaisesRegex(AssertionError,'changed artifact'):verify.verify(r)
    def test_real_child_clean_shutdown(self):
        with tempfile.TemporaryDirectory() as d:
            child=smoke.Process([sys.executable,'-u','-c',"print('READY',flush=True);input()"],Path(d),'child')
            child.has('READY',10);receipt=child.stop('stop')
            self.assertEqual(0,receipt['exit']);self.assertFalse(receipt['forced']);self.assertTrue(receipt['log_closed'])
    def test_member_disclosure_of_other_actor_rejects(self):
        """A real run that leaked the other actor's name/coordinates must be REJECTED, not PASS."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r)
            self.assertEqual('PASS_CONTROLLED_SMOKE',verify.verify(r)['status'])
            p=r/'bots-1.log'
            leaked=p.read_text().replace('1. Picked up | another player (staff only) | location hidden | 2026-09-12 11:51:22 (0s ago)',
                                         '1. Picked up | LiteStaff | world (1337, 12, -4242) | 2026-09-12 11:51:22 (0s ago)')
            p.write_text(leaked)
            with self.assertRaisesRegex(AssertionError,'disclosed the other actor name'):verify.verify(r)

    def loss_baseline(self, root):
        """A synthetic PASSING loss fixture. UNIT ONLY — no Paper server produced this."""
        files={}
        for name in ['paper.jar','plugins/ItemGuard-LITE.jar','plugins/LiteProbe.jar','bots.cjs']:
            p=root/name;p.parent.mkdir(exist_ok=True,parents=True);p.write_bytes(b'UNIT-ONLY')
            files[name]=hashlib.sha256(p.read_bytes()).hexdigest()
        (root/'stage.json').write_text(json.dumps({'files':files,'candidate_sha256':files['plugins/ItemGuard-LITE.jar']}))
        (root/'attempt.json').write_text(json.dumps({
            'stage_sha256':hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(),
            'generations':1,'scope':'loss-only-lite-smoke'}))
        (root/'outcome.json').write_text(json.dumps({'status':'CAPTURED','port_released':True,'cleanup':[
            {'child':name,'exit':0,'forced':False,'log_closed':True} for name in ['bots-1','server-1']]}))
        cases=['loss-damaged-but-survives','loss-pickup-not-recorded',
               'loss-burn-recorded','loss-unload-not-recorded',
               'loss-cursor-not-recorded','loss-crafting-not-recorded',
               'loss-craftclose-spare-not-recorded','loss-craftclose-full-not-recorded',
               'loss-clear-recorded']
        # The cursor/crafting pair runs on a SECOND seeded identity after the burn, so their row
        # counts start at zero again; the clear is their positive control on that same identity.
        receipts=('LITE_LOSS survive alive=true healthBefore=5 evidenceWhileHurt=3 rowsBefore=0 rowsAfter=0\n'
                  'LITE_LOSS pickup held=true rowsBefore=0 rowsAfter=0\n'
                  'LITE_LOSS unload loaded=false rowsBefore=0 rowsAfter=0\n'
                  'LITE_LOSS burn gone=true rowsBefore=0 rowsAfter=1 reason=BURNED arenaCleared=true\n'
                  'LITE_LOSS safespot staffAlive=true memberAlive=true hazards=0 arenaDistance=24\n'
                  # Recovery after the unload, reported as separable facts rather than one
                  # world-wide item count: that count reads 0 for a chunk whose entities are not in
                  # memory and for a stack that is genuinely gone, which is how a harness-side
                  # reload failure was reported as a missing item.
                  'LITE_LOSS rehome chunk=0,0 blockLoaded=true entitiesLoaded=true staffDistance=0'
                  ' memberDistance=12 entityResolved=true items=1 recovered=true'
                  ' teleported=true ticketReleased=true\n'
                  'LITE_LOSS hold inSlots=true ticksHeld=80 samples=80\n'
                  'LITE_LOSS cursor onCursor=true inSlots=false ticksParked=80 rowsBefore=0 rowsAfter=0 samples=80 staffAlive=true\n'
                  'LITE_LOSS craftgrid inGrid=true inSlots=false ticksParked=80 rowsBefore=0 rowsAfter=0 samples=80 staffAlive=true\n'
                  'LITE_LOSS clear held=false lastAction=INVENTORY_MOVE rowsBefore=0 rowsAfter=1 reason=CLEARED ticksHeld=80 samples=80 staffAlive=true\n'
                  + CRAFTCLOSE['spare'] + CRAFTCLOSE['full'])
        (root/'server-1.log').write_text(
            'Done (\nLITE_PROBE_BOOT\nStopping server\nAll dimensions are saved\n'
            +receipts+'\n'.join('LITE_CASE '+c+' PASS' for c in cases))
        rows=[{'event':event,'player':p} for event in ['spawn','end'] for p in ['LiteStaff','LiteMember']]
        (root/'bots-1.log').write_text('\n'.join(json.dumps(x) for x in rows))

    def test_loss_baseline_accepts(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            self.assertEqual('PASS_LOSS_ONLY_SMOKE', verify.verify_loss_only(r)['status'])

    def test_loss_recorded_while_item_still_exists_rejects(self):
        """The exact defect. A row written while the item is still on the ground must REJECT."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace(
                'survive alive=true healthBefore=5 evidenceWhileHurt=3 rowsBefore=0 rowsAfter=0',
                'survive alive=true healthBefore=5 evidenceWhileHurt=3 rowsBefore=0 rowsAfter=1'))
            with self.assertRaisesRegex(AssertionError,'still existed'):
                verify.verify_loss_only(r)

    def test_loss_survive_case_that_never_damaged_the_item_rejects(self):
        """A survive case where health never dropped proves nothing and must not pass."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('healthBefore=5 evidenceWhileHurt=3','healthBefore=5 evidenceWhileHurt=5'))
            with self.assertRaisesRegex(AssertionError,'never actually damaged'):
                verify.verify_loss_only(r)

    def test_loss_pickup_recorded_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('pickup held=true rowsBefore=0 rowsAfter=0',
                                               'pickup held=true rowsBefore=0 rowsAfter=1'))
            with self.assertRaisesRegex(AssertionError,'picked up'):
                verify.verify_loss_only(r)

    def test_loss_unload_recorded_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('unload loaded=false rowsBefore=0 rowsAfter=0',
                                               'unload loaded=false rowsBefore=0 rowsAfter=1'))
            with self.assertRaisesRegex(AssertionError,'chunk unloaded'):
                verify.verify_loss_only(r)

    def test_loss_silent_plugin_rejects(self):
        """Without the positive control, a plugin that records NOTHING would pass every case."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('burn gone=true rowsBefore=0 rowsAfter=1 reason=BURNED',
                                               'burn gone=true rowsBefore=0 rowsAfter=0 reason=none'))
            with self.assertRaisesRegex(AssertionError,'real burn was not recorded'):
                verify.verify_loss_only(r)

    def test_loss_burn_recorded_with_wrong_reason_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('reason=BURNED','reason=CLEARED'))
            with self.assertRaisesRegex(AssertionError,'wrong reason'):
                verify.verify_loss_only(r)

    def test_loss_missing_case_marker_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('LITE_CASE loss-burn-recorded PASS',''))
            with self.assertRaisesRegex(AssertionError,'loss-burn-recorded'):
                verify.verify_loss_only(r)

    def test_loss_scope_cannot_borrow_another_scopes_fixture(self):
        """A craft/full fixture must not satisfy the loss gate just because it also ran cleanly."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r)
            with self.assertRaisesRegex(AssertionError,'wrong loss attempt scope'):
                verify.verify_loss_only(r)

    def test_loss_probe_failure_in_log_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text()+'\nLITE_PROBE_FAIL loss-burn-recorded')
            with self.assertRaisesRegex(AssertionError,'loss server error'):
                verify.verify_loss_only(r)

    def test_loss_forced_kill_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'outcome.json';x=json.loads(p.read_text())
            x['cleanup'][0]['forced']=True;p.write_text(json.dumps(x))
            with self.assertRaisesRegex(AssertionError,'unclean loss child exit'):
                verify.verify_loss_only(r)

    def test_loss_artifact_change_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            (r/'plugins/ItemGuard-LITE.jar').write_bytes(b'TAMPER')
            with self.assertRaisesRegex(AssertionError,'changed artifact'):
                verify.verify_loss_only(r)

    def test_loss_unload_case_that_never_unloaded_rejects(self):
        """A run where the chunk stayed loaded proves nothing and must not pass."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('LITE_LOSS unload loaded=false',
                                               'LITE_LOSS unload loaded=true'))
            with self.assertRaisesRegex(AssertionError,'never actually unloaded the chunk'):
                verify.verify_loss_only(r)

    # --- Inventory-side presence: cursor, crafting grid, and their positive clear control. ---
    # A tracked stack parked on the cursor or in the player's own crafting grid has left
    # Inventory.getContents() but is plainly still in the world. Recording it destroyed marks a
    # retrievable item as confirmed-gone, which is a restore/dupe path. These run on a second
    # seeded identity after the burn, so the clear is what proves the watcher was awake at all.

    def test_loss_cursor_receipt_missing_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text('\n'.join(l for l in p.read_text().splitlines()
                                   if not l.startswith('LITE_LOSS cursor ')))
            with self.assertRaisesRegex(AssertionError,'cursor receipt missing'):
                verify.verify_loss_only(r)

    def test_loss_recorded_while_item_sits_on_the_cursor_rejects(self):
        """The defect: a drag inside an inventory screen written down as a destruction."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace(
                'cursor onCursor=true inSlots=false ticksParked=80 rowsBefore=0 rowsAfter=0',
                'cursor onCursor=true inSlots=false ticksParked=80 rowsBefore=0 rowsAfter=1'))
            with self.assertRaisesRegex(AssertionError,'held on the cursor'):
                verify.verify_loss_only(r)

    def test_loss_cursor_case_that_never_parked_the_item_rejects(self):
        """A run where the client click never landed proves nothing about the cursor."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('cursor onCursor=true','cursor onCursor=false'))
            with self.assertRaisesRegex(AssertionError,'never actually parked'):
                verify.verify_loss_only(r)

    def test_loss_cursor_case_that_left_a_copy_in_the_slots_rejects(self):
        """If the stack is still in the slot array the watcher never saw a departure to misread."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('cursor onCursor=true inSlots=false',
                                               'cursor onCursor=true inSlots=true'))
            with self.assertRaisesRegex(AssertionError,'out of the slot array'):
                verify.verify_loss_only(r)

    def test_loss_cursor_case_shorter_than_the_scan_window_rejects(self):
        """ItemLossListener reports from two consecutive 20-tick snapshots. A park inside one
        interval is never compared, so a passing receipt would mean nothing was ever tested."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('cursor onCursor=true inSlots=false ticksParked=80',
                                               'cursor onCursor=true inSlots=false ticksParked=20'))
            with self.assertRaisesRegex(AssertionError,'two full removal scans'):
                verify.verify_loss_only(r)

    def test_loss_crafting_receipt_missing_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text('\n'.join(l for l in p.read_text().splitlines()
                                   if not l.startswith('LITE_LOSS craftgrid ')))
            with self.assertRaisesRegex(AssertionError,'craftgrid receipt missing'):
                verify.verify_loss_only(r)

    def test_loss_recorded_while_item_sits_in_the_crafting_grid_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace(
                'craftgrid inGrid=true inSlots=false ticksParked=80 rowsBefore=0 rowsAfter=0',
                'craftgrid inGrid=true inSlots=false ticksParked=80 rowsBefore=0 rowsAfter=1'))
            with self.assertRaisesRegex(AssertionError,'recorded for an item in the crafting grid'):
                verify.verify_loss_only(r)

    def test_loss_crafting_case_that_never_parked_the_item_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('craftgrid inGrid=true','craftgrid inGrid=false'))
            with self.assertRaisesRegex(AssertionError,'never actually parked'):
                verify.verify_loss_only(r)

    def test_loss_crafting_case_that_left_a_copy_in_the_slots_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('craftgrid inGrid=true inSlots=false',
                                               'craftgrid inGrid=true inSlots=true'))
            with self.assertRaisesRegex(AssertionError,'out of the slot array'):
                verify.verify_loss_only(r)

    def test_loss_crafting_case_shorter_than_the_scan_window_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('craftgrid inGrid=true inSlots=false ticksParked=80',
                                               'craftgrid inGrid=true inSlots=false ticksParked=20'))
            with self.assertRaisesRegex(AssertionError,'two full removal scans'):
                verify.verify_loss_only(r)

    def test_loss_clear_receipt_missing_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text('\n'.join(l for l in p.read_text().splitlines()
                                   if not l.startswith('LITE_LOSS clear ')))
            with self.assertRaisesRegex(AssertionError,'clear receipt missing'):
                verify.verify_loss_only(r)

    def test_loss_clear_control_that_recorded_nothing_rejects(self):
        """Positive control for the two presence checks. Without it, a build whose removal watcher
        never runs satisfies 'no loss was recorded' on the cursor and the grid for free."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace(
                'clear held=false lastAction=INVENTORY_MOVE rowsBefore=0 rowsAfter=1 reason=CLEARED',
                'clear held=false lastAction=INVENTORY_MOVE rowsBefore=0 rowsAfter=0 reason=none'))
            with self.assertRaisesRegex(AssertionError,'a real clear was not recorded'):
                verify.verify_loss_only(r)

    def test_loss_clear_control_with_wrong_reason_rejects(self):
        """A removal filed as BURNED would send an admin looking for a fire that never happened."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('rowsAfter=1 reason=CLEARED','rowsAfter=1 reason=BURNED'))
            with self.assertRaisesRegex(AssertionError,'wrong reason'):
                verify.verify_loss_only(r)

    def test_loss_clear_control_that_left_the_item_held_rejects(self):
        """If the stack survived the clear, the recorded row contradicts the world: a restore path."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('clear held=false','clear held=true'))
            with self.assertRaisesRegex(AssertionError,'still held'):
                verify.verify_loss_only(r)

    def test_loss_new_case_markers_are_each_required(self):
        for case in ['loss-cursor-not-recorded','loss-crafting-not-recorded','loss-clear-recorded']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    p=r/'server-1.log'
                    p.write_text(p.read_text().replace('LITE_CASE '+case+' PASS',''))
                    with self.assertRaisesRegex(AssertionError,case):
                        verify.verify_loss_only(r)

    # --- Closing a crafting window is not a destruction. ---
    # Vanilla empties the crafting grid on close: the stack goes back to the inventory, or, when
    # there is no room, is dropped at the player's feet. The stack exists either way, so a loss row
    # there confirms the destruction of an item the owner can still pick up, and a confirmed
    # destruction is what makes an item restorable. Both halves are mandatory and neither may be
    # adjudicated from a printed PASS: the numbers, the identity and the holder are read off the
    # raw receipt. The DROP event is recorded as an observation only — vanilla's close-time drop
    # does not have to raise one, so requiring it would fail a correct build.

    def craftclose(self, root, case, old, new):
        """Rewrite ONLY the craftclose line for `case`, so no other receipt is disturbed."""
        p=root/'server-1.log'
        prefix='LITE_LOSS craftclose '+case+' '
        lines=p.read_text().splitlines()
        self.assertTrue(any(l.startswith(prefix) for l in lines),'fixture lacks a '+case+' receipt')
        p.write_text('\n'.join(l.replace(old,new) if l.startswith(prefix) else l for l in lines))

    def drop_craftclose(self, root, case):
        p=root/'server-1.log'
        prefix='LITE_LOSS craftclose '+case+' '
        p.write_text('\n'.join(l for l in p.read_text().splitlines() if not l.startswith(prefix)))

    def test_loss_craftclose_receipt_missing_rejects(self):
        """Neither case may be optional: a gate that only reads one proves only one path."""
        for case in ['spare','full']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r);self.drop_craftclose(r,case)
                    with self.assertRaisesRegex(AssertionError,'craftclose '+case+' receipt missing'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_case_markers_are_each_required(self):
        for case in ['loss-craftclose-spare-not-recorded','loss-craftclose-full-not-recorded']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    p=r/'server-1.log'
                    p.write_text(p.read_text().replace('LITE_CASE '+case+' PASS',''))
                    with self.assertRaisesRegex(AssertionError,case):
                        verify.verify_loss_only(r)

    def test_loss_recorded_when_a_crafting_window_closed_rejects(self):
        """The defect itself, on both paths: a return or a close-time drop written down as a loss."""
        for case in ['spare','full']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    self.craftclose(r,case,'rowsBefore=0 rowsAfter=0','rowsBefore=0 rowsAfter=1')
                    with self.assertRaisesRegex(AssertionError,'survived the crafting close'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_that_added_a_cleared_row_rejects(self):
        """Zero NEW CLEARED rows, counted before and after, not inferred from the total row count.

        A build that writes the row under a different reason still marks the stack confirmed-gone,
        so the CLEARED tally is adjudicated on its own rather than folded into rowsAfter.
        """
        for case in ['spare','full']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    self.craftclose(r,case,'clearedBefore=0 clearedAfter=0',
                                    'clearedBefore=0 clearedAfter=1')
                    with self.assertRaisesRegex(AssertionError,'CLEARED'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_without_a_closed_window_rejects(self):
        """If the window never closed, nothing that this case is about ever happened."""
        for case in ['spare','full']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    self.craftclose(r,case,'windowClosed=true','windowClosed=false')
                    with self.assertRaisesRegex(AssertionError,'window was never actually closed'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_without_a_client_close_acknowledgement_rejects(self):
        """The close must be the real client's, acknowledged as such — not asserted by the probe.

        A probe-side closeInventory() reproduces none of the packet path the watcher has to
        survive, which is the same hazard already fenced off for the cursor case.
        """
        for case in ['spare','full']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    self.craftclose(r,case,'closeAck=CLIENT','closeAck=NONE')
                    with self.assertRaisesRegex(AssertionError,'client acknowledgement'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_that_did_not_keep_the_original_identity_rejects(self):
        """Survival means the exact original code AND item UUID, not a same-looking replacement.

        A build that deletes the tracked stack and hands back a fresh one leaves the inventory
        looking correct while the tracked identity is gone, which is indistinguishable from a
        successful close unless both fields are compared.
        """
        fresh='00000000-0000-4000-8000-000000000000'
        for case in ['spare','full']:
            # Only the AFTER field moves, so the receipt shape is untouched and the rejection can
            # come from nothing but the identity comparison.
            uuid_after='uuidAfter='+CRAFTCLOSE[case].split('uuidAfter=',1)[1].split()[0]
            for old,new,message in [('codeAfter=UNIT0','codeAfter=OTHER','code'),
                                    (uuid_after,'uuidAfter='+fresh,'UUID')]:
                with self.subTest(case=case,field=message):
                    with tempfile.TemporaryDirectory() as d:
                        r=Path(d);self.loss_baseline(r)
                        self.craftclose(r,case,old,new)
                        with self.assertRaisesRegex(AssertionError,message+'.*survive|survive.*'+message):
                            verify.verify_loss_only(r)

    def test_loss_craftclose_shorter_than_the_scan_window_rejects(self):
        """ItemLossListener compares two consecutive 20-tick snapshots, so a post-close state that
        did not last 40 ticks was never compared and a passing receipt would mean nothing.

        ticksObserved is the measured server-tick span and samples is the count of per-tick
        observations that actually saw the state; both are required, so neither a long wall-clock
        gap with no observation nor a burst inside one tick can stand in for it.
        """
        for case in ['spare','full']:
            for old,new in [('ticksObserved=80','ticksObserved=20'),('samples=80','samples=20')]:
                with self.subTest(case=case,field=old):
                    with tempfile.TemporaryDirectory() as d:
                        r=Path(d);self.loss_baseline(r)
                        self.craftclose(r,case,old,new)
                        with self.assertRaisesRegex(AssertionError,'two full removal scans'):
                            verify.verify_loss_only(r)

    def test_loss_craftclose_holder_must_be_an_observed_type(self):
        """Where the stack ended up is a reading, not a default.

        A receipt that fills the holder in from what the case expected would report a correct
        landing place for a build that lost the item, so an unobserved holder type must reject.
        """
        for case in ['spare','full']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    holder='holderType='+CRAFTCLOSE[case].split('holderType=',1)[1].split()[0]
                    self.craftclose(r,case,holder,'holderType=ASSUMED')
                    with self.assertRaisesRegex(AssertionError,'observed holder type'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_spare_case_that_was_not_spare_rejects(self):
        """A spare case run on a full inventory is the other case wearing its name."""
        for old,new in [('fullAtClose=false','fullAtClose=true'),('slotsFree=27','slotsFree=0')]:
            with self.subTest(field=old):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    self.craftclose(r,'spare',old,new)
                    with self.assertRaisesRegex(AssertionError,'spare case did not have room'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_spare_case_that_never_returned_the_stack_to_real_slots_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            self.craftclose(r,'spare','inSlots=true','inSlots=false')
            with self.assertRaisesRegex(AssertionError,'spare case never returned the identity'):
                verify.verify_loss_only(r)

    def test_loss_craftclose_full_case_that_was_not_full_at_close_rejects(self):
        """The full case has to prove the inventory was full AT CLOSE, from the measured free-slot
        count, or the drop path was never exercised and the receipt describes the spare case."""
        for old,new in [('fullAtClose=true','fullAtClose=false'),('slotsFree=0','slotsFree=9')]:
            with self.subTest(field=old):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    self.craftclose(r,'full',old,new)
                    with self.assertRaisesRegex(AssertionError,'was not full at close'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_full_case_without_an_entity_or_a_returned_identity_rejects(self):
        """A PASS marker is not evidence: the full case must end at a real physical original.

        Either the dropped Item entity carrying the staged UUID was resolved in the world, or the
        identity is back in the player's own slots. Neither one, and the stack is unaccounted for.
        """
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            self.craftclose(r,'full','holderType=ITEM_ENTITY entityResolved=true inSlots=false',
                            'holderType=NONE entityResolved=false inSlots=false')
            with self.assertRaisesRegex(AssertionError,
                                        'neither the original item entity nor a returned identity'):
                verify.verify_loss_only(r)

    def test_loss_craftclose_full_case_accepts_a_returned_identity_instead_of_an_entity(self):
        """Control for the test above. Paper may still fit the stack somewhere on close; that is a
        correct outcome and must pass on the returned identity alone, with no entity resolved."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            self.craftclose(r,'full',
                            'holder=GROUND holderType=ITEM_ENTITY entityResolved=true inSlots=false',
                            'holder=LiteStaff holderType=PLAYER_INVENTORY entityResolved=false inSlots=true')
            self.assertEqual('PASS_LOSS_ONLY_SMOKE',verify.verify_loss_only(r)['status'])

    def test_loss_craftclose_drop_event_is_observed_not_required(self):
        """dropObserved records whether a DROP was seen; it is not what success is judged on.

        Vanilla's close-time drop is not guaranteed to raise a PlayerDropItemEvent, so demanding
        one would reject a build that behaved correctly. The physical entity is the evidence.
        """
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            self.craftclose(r,'full','dropObserved=true','dropObserved=false')
            self.assertEqual('PASS_LOSS_ONLY_SMOKE',verify.verify_loss_only(r)['status'])

    def test_loss_craftclose_with_a_dead_actor_rejects_as_hazard(self):
        """A death empties the grid and the inventory, which is not crafting-close handling."""
        for case in ['spare','full']:
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    self.craftclose(r,case,'staffAlive=true','staffAlive=false')
                    with self.assertRaisesRegex(AssertionError,'crafting close'):
                        verify.verify_loss_only(r)

    def test_loss_craftclose_cannot_substitute_for_the_clear_positive_control(self):
        """Preserved constraint: without a removal that really is one, every 'nothing was recorded'
        assertion — the two new ones included — is satisfied by a watcher that never runs."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text('\n'.join(l for l in p.read_text().splitlines()
                                   if not l.startswith('LITE_LOSS clear ')))
            with self.assertRaisesRegex(AssertionError,'clear receipt missing'):
                verify.verify_loss_only(r)

    def test_craftclose_cases_are_produced_by_real_driver_steps(self):
        """Each adjudicated case must come from a probe action that exists, as the others do."""
        source=(Path(smoke.HOME)/'LiteProbe.java').read_text(encoding='utf-8')
        actions={step.split()[1] for step in smoke.SCOPES['loss']['probe_steps']}
        for case,action in [('loss-craftclose-spare-not-recorded','losscraftclosespare'),
                            ('loss-craftclose-full-not-recorded','losscraftclosefull')]:
            with self.subTest(case=case):
                self.assertIn(action,actions,'no driver step produces '+case)
                self.assertIn('action.equals("'+action+'")',source)
                self.assertIn(case,source)

    def test_the_crafting_window_is_closed_by_the_client_not_by_the_probe(self):
        """Negative control against the cheapest way to make these cases green.

        Closing the window from the probe skips the close packet path entirely, the same shortcut
        already fenced off for the cursor. The close must be a real client action with the client's
        own receipt, and neither craftclose block may close the window itself.
        """
        self.assertIn('craftwindowclose',smoke.CLIENT_STEPS,'no client-driven window close exists')
        bots=(Path(smoke.HOME)/'bots.cjs').read_text(encoding='utf-8')
        payload,event=smoke.CLIENT_STEPS['craftwindowclose']
        self.assertIn('closeWindow',bots,'the client never actually closes the window')
        for key in payload: self.assertIn('request.'+key,bots,'bots.cjs ignores '+key)
        self.assertIn(event.split(':')[1].strip('"'),bots)
        source=(Path(smoke.HOME)/'LiteProbe.java').read_text(encoding='utf-8')
        for action in ['losscraftclosespare','losscraftclosefull']:
            self.assertIn('action.equals("'+action+'")',source)
            block=source.split('action.equals("'+action+'")',1)[1].split('action.equals(',1)[0]
            self.assertNotIn('closeInventory(',block,
                             action+' closes the window itself: the close packet path is untested')

    # --- Hazards must not travel between phases. ---
    # On fixture fba434e89f6e the burn case left its lava in the world, the second identity was
    # seeded in it, and "LiteStaff tried to swim in lava" killed the staff bot 52 ticks into the
    # cursor window. The dropped inventory emptied the cursor and the run reported a cursor
    # presence failure for a contaminated arena. None of these may be read as a cursor defect.

    def test_loss_burn_arena_left_in_the_world_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('arenaCleared=true','arenaCleared=false'))
            with self.assertRaisesRegex(AssertionError,'burn arena was left in the world'):
                verify.verify_loss_only(r)

    def test_loss_safespot_receipt_missing_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text('\n'.join(l for l in p.read_text().splitlines()
                                   if not l.startswith('LITE_LOSS safespot ')))
            with self.assertRaisesRegex(AssertionError,'safespot receipt missing'):
                verify.verify_loss_only(r)

    def test_loss_second_identity_seeded_next_to_hazards_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('hazards=0','hazards=3'))
            with self.assertRaisesRegex(AssertionError,'leftover hazard blocks'):
                verify.verify_loss_only(r)

    def test_loss_second_identity_seeded_on_the_burn_arena_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('arenaDistance=24','arenaDistance=0'))
            with self.assertRaisesRegex(AssertionError,'on top of the burn arena'):
                verify.verify_loss_only(r)

    def test_loss_safespot_with_a_burning_actor_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('safespot staffAlive=true','safespot staffAlive=false'))
            with self.assertRaisesRegex(AssertionError,'not alive and unburnt'):
                verify.verify_loss_only(r)

    def test_loss_presence_case_with_a_dead_actor_rejects_as_hazard(self):
        """Exactly the fba434e89f6e signature: the cursor emptied because the actor died."""
        for receipt,message in [('cursor','cursor case'),('craftgrid','crafting case'),
                                ('clear','clear control')]:
            with self.subTest(receipt=receipt):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);self.loss_baseline(r)
                    p=r/'server-1.log'
                    p.write_text('\n'.join(
                        l.replace('staffAlive=true','staffAlive=false')
                        if l.startswith('LITE_LOSS '+receipt+' ') else l
                        for l in p.read_text().splitlines()))
                    with self.assertRaisesRegex(AssertionError,'stay alive through the '+message):
                        verify.verify_loss_only(r)

    # --- Recovery after the chunk unload. ---
    # The run on fixture c838b5b9f84e failed with "tracked-stack-not-recovered items=0", where
    # items was a world-wide getEntitiesByClass count. That number is 0 for a chunk whose entity
    # section was never brought back into memory, for one still loading, and for a stack that
    # really stopped existing. The receipt has to separate those, and none of them may be
    # adjudicated as an ItemGuard defect: this scope cannot attribute a disappearance to the
    # product, so the honest verdict for an unresolved identity is UNKNOWN.

    def test_loss_rehome_receipt_missing_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text('\n'.join(l for l in p.read_text().splitlines()
                                   if not l.startswith('LITE_LOSS rehome ')))
            with self.assertRaisesRegex(AssertionError,'rehome receipt missing'):
                verify.verify_loss_only(r)

    def test_loss_rehome_without_loaded_chunk_entities_rejects(self):
        """Entities not in memory is a harness-side reload failure, not a missing item."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('entitiesLoaded=true','entitiesLoaded=false')
                                      .replace('entityResolved=true items=1 recovered=true',
                                               'entityResolved=false items=0 recovered=false'))
            with self.assertRaisesRegex(AssertionError,'entities never reloaded'):
                verify.verify_loss_only(r)

    def test_loss_rehome_with_actors_still_away_rejects(self):
        """The actors were teleported 600 blocks out for the unload; if they never came back the
        home chunk was never held by anything and nothing about recovery was tested."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('staffDistance=0','staffDistance=600'))
            with self.assertRaisesRegex(AssertionError,'never returned to the home chunk'):
                verify.verify_loss_only(r)

    def test_loss_rehome_with_actors_sharing_the_pickup_radius_rejects(self):
        """The concrete race from fixture de95793790e4.

        Both actors were teleported to the same recovery point and the stack was then made
        collectable; item_history for FE73BO ends PICKUP LiteStaff, PICKUP LiteMember, so the
        member took it and recovery reported false for an item that still existed. Whoever stands
        there collects it, so a receipt showing both actors in reach must reject as a harness fault
        rather than be read as a missing stack.
        """
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('memberDistance=12','memberDistance=0'))
            with self.assertRaisesRegex(AssertionError,'shared the recovery pickup radius'):
                verify.verify_loss_only(r)

    def test_loss_rehome_without_actor_teleport_rejects(self):
        """The teleport results are on record so the receipt states where the actors actually went.
        A false result must reject rather than be read past. No run has been observed failing it."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('teleported=true','teleported=false'))
            with self.assertRaisesRegex(AssertionError,'were not teleported back'):
                verify.verify_loss_only(r)

    def test_loss_rehome_that_leaked_the_chunk_ticket_rejects(self):
        """The ticket is test-only scaffolding; leaving it held changes the world the later cases
        are read against, so a run that could not confirm its release must not pass."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('ticketReleased=true','ticketReleased=false'))
            with self.assertRaisesRegex(AssertionError,'ticket was not released'):
                verify.verify_loss_only(r)

    def test_loss_rehome_with_unresolved_identity_rejects_as_unknown(self):
        """Loaded entities plus an unresolved identity is NOT proof ItemGuard removed anything.

        The stack is resolved by the UUID recorded at staging, so this says only that the exact
        original identity could not be found once the chunk was back. Vanilla despawn, an eviction
        this harness does not model, and a real product fault all land here, so the receipt must be
        rejected as UNKNOWN and never reported as a defect.
        """
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('entityResolved=true items=1 recovered=true',
                                               'entityResolved=false items=0 recovered=false'))
            with self.assertRaisesRegex(AssertionError,'UNKNOWN'):
                verify.verify_loss_only(r)

    def test_loss_rehome_that_never_returned_the_stack_rejects(self):
        """Resolved and loaded, but the staff bot never got it back: the burn case downstream would
        then be staged from some other stack, so the run must stop here."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('recovered=true','recovered=false'))
            with self.assertRaisesRegex(AssertionError,'never returned the stack'):
                verify.verify_loss_only(r)

    def test_loss_presence_hold_receipt_missing_rejects(self):
        """Without the pre-departure hold, the watcher never had two snapshots to compare."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text('\n'.join(l for l in p.read_text().splitlines()
                                   if not l.startswith('LITE_LOSS hold ')))
            with self.assertRaisesRegex(AssertionError,'hold receipt missing'):
                verify.verify_loss_only(r)

    def test_loss_presence_hold_shorter_than_the_scan_window_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('hold inSlots=true ticksHeld=80 samples=80',
                                               'hold inSlots=true ticksHeld=20 samples=20'))
            with self.assertRaisesRegex(AssertionError,'before it departed'):
                verify.verify_loss_only(r)

    def test_loss_clear_control_on_an_already_explained_action_rejects(self):
        """A clear after DROP is suppressed by design, so passing on one proves nothing."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('lastAction=INVENTORY_MOVE','lastAction=DROP'))
            with self.assertRaisesRegex(AssertionError,'already-explained last action'):
                verify.verify_loss_only(r)

    def test_probe_never_parks_the_cursor_itself(self):
        """The cursor state must come from a real client click, not from the probe setting it.

        Negative control against the cheapest way to make the cursor case green: call
        Player.setItemOnCursor() from the probe, which reproduces none of the click path the
        watcher actually has to survive.
        """
        source=(Path(smoke.HOME)/'LiteProbe.java').read_text(encoding='utf-8')
        self.assertNotIn('setItemOnCursor(', source)

    def test_candidate_pin_matches_the_artifact_on_disk(self):
        """The harness pin must name the candidate that actually exists, not a stale build."""
        jar=Path(smoke.ROOT)/'target/ItemGuard-LITE-1.0.0.jar'
        if not jar.exists():
            self.skipTest('no packaged candidate on disk')
        self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(), smoke.EXPECTED)

    def test_loss_scope_is_wired_into_the_driver(self):
        """verify.py loss ROOT can only ever pass if the driver writes that exact scope."""
        self.assertIn('loss', smoke.SCOPES)
        self.assertEqual('loss-only-lite-smoke', smoke.SCOPES['loss']['scope'])
        self.assertEqual(1, smoke.SCOPES['loss']['generations'])

    def test_loss_attempt_receipt_matches_the_verifier_contract(self):
        """Written without a server: the receipt shape is what the adjudicator reads."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.loss_baseline(r);(r/'attempt.json').unlink()
            smoke.write_attempt(r,'loss')
            attempt=json.loads((r/'attempt.json').read_text())
            self.assertEqual('loss-only-lite-smoke',attempt['scope'])
            self.assertEqual(1,attempt['generations'])
            self.assertEqual(hashlib.sha256((r/'stage.json').read_bytes()).hexdigest(),
                             attempt['stage_sha256'])

    def test_loss_driver_only_uses_probe_actions_that_exist(self):
        """Negative control against a driver that sends invented console commands."""
        source=(Path(smoke.HOME)/'LiteProbe.java').read_text(encoding='utf-8')
        actions=[step.split()[1] for step in smoke.SCOPES['loss']['probe_steps']]
        self.assertTrue(actions,'loss driver sends no probe commands')
        for action in actions:
            self.assertIn('action.equals("'+action+'")', source,
                          'driver sends a probe action that does not exist: '+action)

    def test_loss_driver_covers_every_adjudicated_case(self):
        """Each case verify_loss_only() demands must be produced by an actual probe step."""
        source=(Path(smoke.HOME)/'LiteProbe.java').read_text(encoding='utf-8')
        actions={step.split()[1] for step in smoke.SCOPES['loss']['probe_steps']}
        required={'loss-damaged-but-survives':'lossdamagesurvive',
                  'loss-pickup-not-recorded':'losspickup',
                  'loss-burn-recorded':'lossburn',
                  'loss-unload-not-recorded':'lossunload',
                  'loss-cursor-not-recorded':'losscursor',
                  'loss-crafting-not-recorded':'losscraftgrid',
                  'loss-clear-recorded':'lossclear'}
        for case,action in required.items():
            self.assertIn(action,actions,'no driver step produces '+case)
            self.assertIn(case,source)

    def sweep_baseline(self, root):
        """A synthetic PASSING closed-chest sweep fixture. UNIT ONLY — no Paper server produced this."""
        files={}
        for name in ['paper.jar','plugins/ItemGuard-LITE.jar','plugins/LiteProbe.jar','bots.cjs']:
            p=root/name;p.parent.mkdir(exist_ok=True,parents=True);p.write_bytes(b'UNIT-ONLY')
            files[name]=hashlib.sha256(p.read_bytes()).hexdigest()
        (root/'stage.json').write_text(json.dumps({'files':files,'candidate_sha256':files['plugins/ItemGuard-LITE.jar']}))
        (root/'attempt.json').write_text(json.dumps({
            'stage_sha256':hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(),
            'generations':1,'scope':'sweep-only-lite-smoke'}))
        (root/'outcome.json').write_text(json.dumps({'status':'CAPTURED','port_released':True,'cleanup':[
            {'child':name,'exit':0,'forced':False,'log_closed':True} for name in ['bots-1','server-1']]}))
        # The receipt itself hardcodes confirmed=unknown locations=unknown; the plugin's own
        # ITEMGUARD_DUPLICATE_CONFIRMED line is the only thing that may carry the verdict.
        (root/'server-1.log').write_text(
            'Done (\nLITE_PROBE_BOOT\nStopping server\nAll dimensions are saved\n'
            'LITE_CASE sweep-chests-loaded PASS\nLITE_CASE sweep-identity-resolves PASS\n'
            'LITE_SWEEP closedchests enabled=true chestA=10,64,10 chestB=10,64,16 '
            'sameDoubleChest=false code=UNIT01 uuid=c0ffee00-1111-4222-8333-444455556666 '
            'sweepPasses=1 confirmed=unknown locations=unknown\n'
            'ITEMGUARD_DUPLICATE_CONFIRMED code=UNIT01 uuid=c0ffee00-1111-4222-8333-444455556666 '
            'epoch=1 locations=2 action=NONE\n')
        rows=[{'event':event,'player':p} for event in ['spawn','end'] for p in ['LiteStaff','LiteMember']]
        (root/'bots-1.log').write_text('\n'.join(json.dumps(x) for x in rows))

    def test_sweep_baseline_accepts(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.sweep_baseline(r)
            self.assertEqual('PASS_SWEEP_SMOKE', verify.verify_sweep_only(r)['status'])

    def test_sweep_driver_only_uses_probe_actions_that_exist(self):
        """Negative control against a driver that sends invented console commands."""
        source=(Path(smoke.HOME)/'LiteProbe.java').read_text(encoding='utf-8')
        actions=[step.split()[1] for step in smoke.SCOPES['sweep']['probe_steps']]
        self.assertTrue(actions,'sweep driver sends no probe commands')
        for action in actions:
            self.assertIn('action.equals("'+action+'")', source,
                          'driver sends a probe action that does not exist: '+action)

    def test_sweep_same_double_chest_rejects(self):
        """Two chests that merged into one double chest prove nothing and must REJECT."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.sweep_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace('sameDoubleChest=false','sameDoubleChest=true'))
            with self.assertRaisesRegex(AssertionError,'double chest'):
                verify.verify_sweep_only(r)

    def test_sweep_missing_plugin_confirmation_rejects(self):
        """A probe receipt alone, with no plugin ITEMGUARD_DUPLICATE_CONFIRMED line, must REJECT."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.sweep_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace(
                'ITEMGUARD_DUPLICATE_CONFIRMED code=UNIT01 uuid=c0ffee00-1111-4222-8333-444455556666 '
                'epoch=1 locations=2 action=NONE\n', ''))
            with self.assertRaisesRegex(AssertionError,'never confirmed'):
                verify.verify_sweep_only(r)

    def test_sweep_confirmation_for_a_different_code_rejects(self):
        """A plugin confirmation naming a different identity must not validate this receipt."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.sweep_baseline(r)
            p=r/'server-1.log'
            p.write_text(p.read_text().replace(
                'ITEMGUARD_DUPLICATE_CONFIRMED code=UNIT01', 'ITEMGUARD_DUPLICATE_CONFIRMED code=OTHER99'))
            with self.assertRaisesRegex(AssertionError,'never confirmed'):
                verify.verify_sweep_only(r)

    def test_full_scope_cannot_borrow_a_craft_only_fixture(self):
        """The full gate must name its own scope, exactly as the loss and craft gates already do.

        Same hazard as test_loss_scope_cannot_borrow_another_scopes_fixture(), other direction: an
        attempt captured under a bounded scope must not be adjudicated as the full smoke.
        """
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r)
            self.assertEqual('PASS_CONTROLLED_SMOKE',verify.verify(r)['status'])
            p=r/'attempt.json';x=json.loads(p.read_text())
            x['scope']='craft-only-lite-smoke';x['generations']=1
            p.write_text(json.dumps(x))
            with self.assertRaisesRegex(AssertionError,'wrong attempt scope'):
                verify.verify(r)

    def test_full_attempt_must_declare_both_generations(self):
        """The full scope is two generations. A receipt claiming one must not pass the full gate."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.baseline(r)
            p=r/'attempt.json';x=json.loads(p.read_text())
            x['generations']=1
            p.write_text(json.dumps(x))
            with self.assertRaisesRegex(AssertionError,'wrong attempt scope'):
                verify.verify(r)

    def test_declared_candidate_hash_must_match_the_hashed_fixture_jar(self):
        """No gate may hand back a candidate identity it never hashed off the fixture's own jar.

        Every verifier returns stage['candidate_sha256'] as the identity of the build that was
        proven, and the per-file manifest is checked against disk — but nothing ties that one
        declared field to plugins/ItemGuard-LITE.jar. A manifest naming a different build therefore
        produces a PASS receipt for a candidate this run never actually exercised.
        """
        stale=hashlib.sha256(b'A-DIFFERENT-CANDIDATE').hexdigest()
        # subTest so each scope is adjudicated on its own: one gate still returning a stale
        # candidate must not hide whether the other two do.
        for scope,build,gate in [('full',self.baseline,verify.verify),
                                 ('craft',self.craft_baseline,verify.verify_craft_only),
                                 ('loss',self.loss_baseline,verify.verify_loss_only)]:
            with self.subTest(scope=scope):
                with tempfile.TemporaryDirectory() as d:
                    r=Path(d);build(r)
                    p=r/'stage.json';x=json.loads(p.read_text())
                    x['candidate_sha256']=stale;p.write_text(json.dumps(x))
                    # Re-bind the attempt, so the rejection can only come from the candidate field.
                    a=r/'attempt.json';y=json.loads(a.read_text())
                    y['stage_sha256']=hashlib.sha256(p.read_bytes()).hexdigest();a.write_text(json.dumps(y))
                    with self.assertRaisesRegex(AssertionError,'candidate'):
                        gate(r)

    def test_second_craft_prepare_requires_a_newly_staged_result(self):
        """The shift half of the craft gate must fail when the second staging never happened.

        The scripted candidate answers `liteprobe craftprepare` exactly once. The normal click
        consumes that staged result; the second craftprepare produces nothing. A driver that waits
        for a NEW marker times out and the attempt is FAILED. A driver that waits with previous=0 is
        satisfied by the marker the FIRST staging already wrote, proceeds to the shift click, and
        reports CAPTURED for a run in which the shift case was never actually staged.
        """
        with tempfile.TemporaryDirectory() as d:
            r=Path(d)
            (r/'stage.json').write_text(json.dumps({'root':str(r),'port':0}))
            with mock.patch.object(smoke,'Process',scripted_process(stagings=1)), \
                 mock.patch.object(smoke,'admission',lambda fixture:{'port':0}), \
                 mock.patch.object(smoke,'available',lambda port:port), \
                 mock.patch.object(smoke,'time',FastClock()):
                with self.assertRaises(SystemExit):
                    smoke.run(r, mode='craft')
            outcome=json.loads((r/'outcome.json').read_text())
            self.assertEqual('FAILED',outcome['status'])
            self.assertIn('Timeout',outcome['error'])

    def test_craft_driver_accepts_a_candidate_that_stages_both_times(self):
        """Control for the test above: the same scripted driver must still pass when staging works."""
        with tempfile.TemporaryDirectory() as d:
            r=Path(d)
            (r/'stage.json').write_text(json.dumps({'root':str(r),'port':0}))
            with mock.patch.object(smoke,'Process',scripted_process(stagings=2)), \
                 mock.patch.object(smoke,'admission',lambda fixture:{'port':0}), \
                 mock.patch.object(smoke,'available',lambda port:port), \
                 mock.patch.object(smoke,'time',FastClock()):
                smoke.run(r, mode='craft')
            outcome=json.loads((r/'outcome.json').read_text())
            self.assertEqual('CAPTURED',outcome['status'])

    # --- Custody self-drop: the stale client receipt, and what the pull receipt actually proves. ---
    # On fixture 7133b80dd6a5 the full smoke reported LITE_PULLED LiteStaff twice and then
    # LITE_PROBE_FAIL pull-deadline on the third self-drop cycle. The same class as the craft
    # staging race already fixed at smoke.py's second `craftprepare`: `Process.has()` scans the
    # WHOLE accumulated child log and the log is never cleared (see has_more's docstring), so only
    # the FIRST '"event":"tossed"' of a run is ever really awaited. Iterations 2 and 3 of the
    # self-drop loop return on iteration 1's line and dispatch `liteprobe pull` while the bot is
    # still inside `bot.equip`/`bot.tossStack`.

    def test_stale_client_receipt_cannot_satisfy_a_later_iteration(self):
        """Characterisation of the primitive the custody loop picks the wrong half of.

        Control for the contract tests below: with a child that emitted exactly one receipt,
        `has` is satisfied a second time by the line the FIRST iteration already wrote, while
        `has_more` correctly times out. Both halves are the real driver logic; only which one the
        custody call sites use is in question.
        """
        with tempfile.TemporaryDirectory() as d:
            child=smoke.Process([sys.executable,'-u','-c',
                                 'print(\'{"event":"tossed"}\',flush=True);input()'],Path(d),'child')
            child.has('"event":"tossed"',10)
            previous=child.count('"event":"tossed"')
            self.assertEqual(1,previous)
            with mock.patch.object(smoke,'time',FastClock()):
                # The defect: a second wait is satisfied instantly by the first iteration's line.
                child.has('"event":"tossed"',20)
                with self.assertRaises(TimeoutError):
                    child.has_more('"event":"tossed"',previous,20)
            child.stop('stop')

    def test_every_repeated_toss_receipt_is_awaited_monotonically(self):
        """The parent RED for fixture 7133b80dd6a5's pull-deadline.

        The custody self-drop loop, the handover and the ping-pong loop all wait with
        `bots.has('"event":"tossed"')`. Every one of those after the very first is satisfied by a
        stale line, so `liteprobe pull` is sent before the toss it is supposed to collect. The
        craft gate already fixed exactly this by counting first and then waiting with `has_more`;
        the toss call sites were never converted. No timeout raise can cover it: the probe's own
        pull budget is 120 samples x 4 ticks (~24s), which expires INSIDE the driver's 30s wait, so
        the run always surfaces as LITE_PROBE_FAIL pull-deadline rather than a driver timeout.
        """
        source=(Path(smoke.HOME)/'smoke.py').read_text(encoding='utf-8')
        self.assertNotIn("""bots.has('"event":"tossed"'""", source,
                         'a toss receipt is awaited with has(); a repeat is satisfied by a stale line')

    def test_repeated_toss_is_not_satisfied_by_an_earlier_iterations_receipt(self):
        """Behavioural twin of the source check above, run through the call site itself.

        The source assertion can be satisfied by renaming the wait; this cannot. The scripted child
        answers exactly ONE toss, as a bot mid-`equip` does. A driver that counts first and waits
        with has_more times out on the second cycle; a driver that waits with has() returns
        instantly on iteration one's line and dispatches `liteprobe pull` for a toss that never
        happened — fixture 7133b80dd6a5's LITE_PROBE_FAIL pull-deadline.
        """
        bots=scripted_bots(receipts=1)()
        with mock.patch.object(smoke,'time',FastClock()):
            smoke.toss(bots,'LiteStaff')
            with self.assertRaises(TimeoutError):
                smoke.toss(bots,'LiteStaff')
        # The second cycle really was attempted: the timeout is about the receipt, not a skipped send.
        self.assertEqual(2,sum(1 for line in bots.sent if json.loads(line).get('toss')))

    def test_repeated_toss_accepts_a_client_that_answers_every_time(self):
        """Control for the test above: three real receipts must still drive three clean cycles."""
        bots=scripted_bots(receipts=3)()
        with mock.patch.object(smoke,'time',FastClock()):
            for _ in range(3):
                smoke.toss(bots,'LiteStaff')
        self.assertEqual(3,bots.count('"event":"tossed"'))

    def test_bot_toss_receipt_proves_the_stack_actually_left_the_inventory(self):
        """Why the third cycle deadlines instead of erroring: a toss receipt for a toss that did not happen.

        bots.cjs emits 'tossed' unconditionally after `bot.tossStack(held)`, where `held` may be
        `bot.heldItem` read from a client view that has not yet applied the pickup packets from the
        pull that resolved microseconds earlier. Dropping a slot the server already emptied moves
        nothing, no Item entity is ever spawned, and the receipt still says the toss succeeded.
        `liteprobe pull` then polls an empty world until pull-deadline. The receipt has to be
        conditional on the stack being gone from the bot's inventory, not on the call returning.
        """
        source=(Path(smoke.HOME)/'bots.cjs').read_text(encoding='utf-8')
        toss=source.split('request.toss)',1)[1].split('else if (request.cursorPick',1)[0]
        self.assertIn('tossStack', toss)
        self.assertRegex(toss, r"(?s)tossStack.*?(?:inventory\.items\(\)|heldItem).*?emit\('tossed'",
                         'tossed is emitted without re-reading the inventory: a no-op drop reports success')

    def test_pull_receipt_names_the_exact_stack_and_a_real_pickup(self):
        """Actor collision, read off the duplicate scene rather than assumed.

        `liteprobe duplicate` copies the staff's tracked stack into LiteMember slot 0 and
        `duplicatesafe` asserts BOTH survive ("duplicate-not-removed"), so from that point on two
        live stacks carry the same code. The pull action filters candidates on code alone -- no item
        UUID, and no check on who dropped it -- then picks the nearest by distance, latches `pending`
        once and never re-resolves it. `LITE_PULLED <actor>` is emitted on `!pending.isValid()`,
        which is equally true of a despawn or a merge between the two same-code stacks. Every
        custody claim in the full smoke rests on that receipt.
        """
        source=(Path(smoke.HOME)/'LiteProbe.java').read_text(encoding='utf-8')
        pull=source.split('action.equals("pull")',1)[1].split('action.equals("historyrows")',1)[0]
        self.assertIn('LITE_PULLED', pull)
        self.assertIn('getItemUuidFromItem', pull,
                      'pull matches on code alone; the duplicate scene leaves two stacks sharing it')
        self.assertRegex(pull, r'getInventory\(\)|PlayerPickupItem|EntityPickupItem',
                         'LITE_PULLED is emitted on !isValid(), which a despawn or merge satisfies')

    def test_wrong_namespace_rejects_before_attempt(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d)
            with self.assertRaisesRegex(RuntimeError,'namespace'):smoke.admission(r)
            self.assertFalse((r/'attempt.json').exists())

class FixturePruneContract(unittest.TestCase):
    """Deleting fixture roots must never remove the evidence under test.

    This code exists because a full disk killed a matrix run. That makes it tempting to be
    aggressive, which is exactly the wrong instinct: a fixture root IS the evidence, and a
    prune that takes one root too many destroys a result nobody can reproduce afterwards.
    """

    CURRENT = 'a' * 64
    OTHER = 'b' * 64

    def _root(self, base, name, sha):
        root = base / ('itemguard-lite-isolated-' + name)
        root.mkdir()
        if sha is not None:
            (root / 'stage.json').write_text(json.dumps({'candidate_sha256': sha}), encoding='utf-8')
        return root

    def _prune(self, base, current):
        # smoke.py runs a server when executed, so the function is lifted out by source and
        # compiled on its own. This still tests the shipped text - if the real function
        # changes, this changes with it - without booting anything.
        source = (Path(__file__).resolve().parent / 'smoke.py').read_text(encoding='utf-8')
        start = source.index('def prune_superseded_fixtures(')
        end = source.index('\ndef ', start + 1)
        namespace = {'BASE': base, 'json': json, 'shutil': shutil}
        exec(compile(source[start:end], 'smoke.py:prune', 'exec'), namespace)
        namespace['prune_superseded_fixtures'](current)

    def test_keeps_the_candidate_under_test_and_removes_only_others(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            keep = self._root(base, 'keepme00000a', self.CURRENT)
            drop = self._root(base, 'dropme00000b', self.OTHER)
            self._prune(base, self.CURRENT)
            self.assertTrue(keep.exists(), 'the candidate under test lost its own evidence')
            self.assertFalse(drop.exists(), 'a superseded root was not reclaimed')

    def test_a_root_without_a_readable_manifest_is_left_alone(self):
        # An unreadable manifest means we do not know what it is. Deleting it would be
        # guessing, and the cost of guessing wrong is unrecoverable evidence.
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            nomanifest = self._root(base, 'nomanifest00', None)
            broken = self._root(base, 'brokenjson00', self.OTHER)
            (broken / 'stage.json').write_text('{not json', encoding='utf-8')
            self._prune(base, self.CURRENT)
            self.assertTrue(nomanifest.exists(), 'a root with no manifest was deleted')
            self.assertTrue(broken.exists(), 'a root with an unreadable manifest was deleted')

    def test_an_empty_current_sha_deletes_nothing(self):
        # Without a candidate every root compares unequal, which would wipe the lot.
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            a = self._root(base, 'aaaaaaaaaaaa', self.CURRENT)
            b = self._root(base, 'bbbbbbbbbbbb', self.OTHER)
            self._prune(base, '')
            self.assertTrue(a.exists() and b.exists(), 'an unset candidate wiped fixtures')


if __name__=='__main__':unittest.main()

class ProbeCloseRoutingContract(unittest.TestCase):
    """The probe must not close inventories behind the lifecycle's back.

    On Spigot there is no InventoryCloseEvent.getReason(), so the crafting-close cases decide
    "this was the real client" by asking ProbeInitiatedClose whether a probe-side close is in
    flight. A raw viewer.closeInventory() bypasses that and would be adjudicated as a client
    close, which is exactly the shortcut the CLIENT gate exists to reject.

    This is pinned as source text because the failure is silent: the fixture would still pass,
    and the receipt would still say closeAck=CLIENT, while proving nothing about the packet
    path. A previous version guarded this with a flag nobody ever set.
    """

    def test_every_probe_close_is_bracketed_by_the_router(self):
        probe = (Path(__file__).parent / 'LiteProbe.java').read_text(encoding='utf-8')
        lines = probe.splitlines()
        closes = [i for i, line in enumerate(lines) if '.closeInventory()' in line]
        self.assertTrue(closes, 'expected the probe to close inventories somewhere')
        for index in closes:
            window = '\n'.join(lines[max(0, index - 2):index + 3])
            self.assertIn(
                'ProbeInitiatedClose.enter()', window,
                'a probe-side close at line %d is not marked as the probe\'s own, so on a '
                'server that cannot name the close origin it would be adjudicated as the '
                'client\'s:\n%s' % (index + 1, window)
            )
            self.assertIn(
                'ProbeInitiatedClose.exit(', window,
                'the mark must be cleared in a finally block at line %d, or a later client '
                'close reads as probe-initiated:\n%s' % (index + 1, window)
            )

    def test_lifecycle_consults_the_router_not_a_settable_flag(self):
        lifecycle = (Path(__file__).parent / 'CraftCloseLifecycle.java').read_text(encoding='utf-8')
        self.assertIn(
            'ProbeInitiatedClose.inProgress()', lifecycle,
            'the UNSPECIFIED->CLIENT path must ask the router at event time'
        )
        self.assertNotIn(
            'markProbeInitiatedClose', lifecycle,
            'the old caller-set flag was never invoked, which made the guard decorative'
        )

