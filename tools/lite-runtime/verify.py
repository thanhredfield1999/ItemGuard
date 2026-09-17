"""Independent final log adjudicator; no server/process mutation."""
from pathlib import Path
import hashlib, json, re, sys

PLUGIN_PREFIX = '[ItemGuard LITE]'
TIMELINE_ROW = re.compile(r'^\d+\.\s')
REDACTED = ('another player (staff only)', 'location hidden')
CANDIDATE = 'plugins/ItemGuard-LITE.jar'
ARTIFACTS = ['paper.jar', CANDIDATE, 'plugins/LiteProbe.jar', 'bots.cjs']

# ItemLossListener scans every 20 ticks and can only report a departure by comparing two
# consecutive snapshots, so a state that did not survive 40 server ticks was never compared
# against anything: a receipt shorter than this proves nothing either way.
SCAN_WINDOW_TICKS = 40

# How far from the recorded home position an actor may stand and still be holding that chunk. A
# chunk is 16 blocks wide, so anything past this is an actor that never came back.
HOME_CHUNK_BLOCKS = 16

# Minimum distance the second actor must be from the recovery point before the tracked stack is
# made collectable. Vanilla collection reaches about one block, so anything at or above this leaves
# exactly one actor able to take it.
PICKUP_SEPARATION_BLOCKS = 8

# Mirrors UnexplainedRemovalPolicy. A removal following one of these is already accounted for, so a
# clear staged on top of one would be suppressed by design and could not serve as a positive
# control. Naming them here keeps the control honest instead of silently vacuous.
EXPLAINED_ACTIONS = {'DROP', 'CONTAINER_PUT', 'SHULKER_PUT', 'ENDERCHEST_PUT',
                     'CARRIED_CONTAINER_PUT', 'DEATH', 'USE',
                     'CLEARED', 'BURNED', 'DESPAWNED', 'VOID', 'RESTORED'}

def candidate_sha256(root, stage):
    """Check the staged artifacts and return the identity of the candidate that was actually run.

    Every gate hands back stage['candidate_sha256'] as the build it proved, so that field must be
    the hash of the candidate jar this fixture really contains. Checking only the per-file manifest
    leaves candidate_sha256 free to name some other build, which would produce a PASS receipt for a
    candidate this run never exercised. Artifacts are adjudicated first so a tampered jar is still
    reported as a changed artifact rather than as a candidate mismatch.
    """
    digests={name: hashlib.sha256((root/name).read_bytes()).hexdigest() for name in ARTIFACTS}
    for name in ARTIFACTS:
        assert digests[name]==stage['files'][name], 'changed artifact '+name
    assert stage.get('candidate_sha256')==digests[CANDIDATE], \
        'declared candidate does not match the hashed candidate jar'
    return digests[CANDIDATE]

# Vanilla broadcasts, named explicitly. M2 (review #3): the leak checks used to *allowlist* plugin
# output - the plugin prefix or a numbered timeline row - and `LiteCommand` already sends lines that
# are neither (`LiteHistoryView.overviewLine` goes straight to `sendMessage`), so a line carrying the
# actor name in that shape was invisible to every disclosure assertion. A denylist fails towards
# inspecting too much, which is the safe direction when the claim is "the player did not see it".
VANILLA_BROADCAST = re.compile(
    r"^\w{1,16} (?:joined|left) the game|^\w{1,16} lost connection|"
    r"has made the advancement|has completed the challenge|has reached the goal")

def plugin_lines(messages):
    """Every line the plugin sent, with the vanilla broadcasts subtracted.

    Not "lines that look like plugin output": a plugin line that does not carry the prefix and does
    not carry a row number is still the plugin talking, and that is exactly the shape that was
    invisible before (M2, review #3).
    """
    return [m for m in messages if not VANILLA_BROADCAST.search(m.strip())]

def discloses_actor(messages, actor):
    """True only when ItemGuard's own output names the foreign actor outside the redacted form."""
    for line in plugin_lines(messages):
        if any(token in line for token in REDACTED):
            continue
        if actor in line:
            return True
    return False

def verify_craft_only(root):
    """Adjudicate the bounded craft gate without unrelated GUI/restart checks."""
    stage=json.loads((root/'stage.json').read_text())
    attempt=json.loads((root/'attempt.json').read_text())
    outcome=json.loads((root/'outcome.json').read_text())
    assert attempt['stage_sha256']==hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(), 'attempt/stage binding'
    assert attempt['scope']=='craft-only-lite-smoke' and attempt['generations']==1, 'wrong craft attempt scope'
    assert outcome['status']=='CAPTURED' and outcome['port_released'], 'incomplete or failed attempt'
    assert {c['child'] for c in outcome['cleanup']}=={'bots-1','server-1'}, 'craft cleanup coverage'
    assert all(c['exit']==0 and not c['forced'] and c['log_closed'] for c in outcome['cleanup']), 'unclean craft child exit'
    candidate=candidate_sha256(root, stage)
    server=(root/'server-1.log').read_text(encoding='utf-8')
    assert 'Done (' in server and 'LITE_PROBE_BOOT' in server, 'craft startup missing'
    assert 'Stopping server' in server and 'All dimensions are saved' in server, 'craft shutdown missing'
    assert not re.search(r'\bERROR\b|LITE_PROBE_FAIL|Exception|NoClassDefFound',server), 'craft server error'
    for case in ['craft-normal-fail-closed','craft-shift-fail-closed']:
        assert server.count('LITE_CASE '+case+' PASS')==1, 'missing/repeated '+case
    assert re.search(r'LITE_CRAFT normal event=1 ingredient=3 output=0', server), 'craft normal receipt missing'
    assert re.search(r'LITE_CRAFT shift event=1 ingredients=3 output=0', server), 'craft shift receipt missing'
    rows=[json.loads(line) for line in (root/'bots-1.log').read_text(encoding='utf-8').splitlines() if line.startswith('{')]
    assert not any(x['event']=='BOT_ERROR' for x in rows), 'craft bot error'
    assert {x.get('player') for x in rows if x['event']=='spawn'}=={'LiteStaff','LiteMember'}, 'craft actors missing'
    assert {x.get('player') for x in rows if x['event']=='end'}=={'LiteStaff','LiteMember'}, 'craft actor shutdown missing'
    denial = 'ItemGuard policy blocks crafting a result that would need a new tracked identity'
    assert sum(x['event']=='message' and x.get('player')=='LiteStaff' and denial in x.get('message','')
               for x in rows) == 2, 'craft denial receipts missing/repeated'
    return {'status':'PASS_CRAFT_ONLY_SMOKE','root':str(root),'candidate_sha256':candidate}

# Where a stack landed after a crafting close is a reading taken off the server, not a default. A
# receipt that fills the holder in from what the case expected would report a correct landing place
# for a build that lost the item, so only these three count as observations. NONE is one of them: it
# says the stack was found nowhere, and the per-case evidence below decides what that means.
OBSERVED_HOLDER_TYPES = {'PLAYER_INVENTORY', 'ITEM_ENTITY', 'NONE'}

def craftclose_receipt(server, case):
    """Read the one crafting-close receipt for `case` out of the raw log.

    Every field is an observation the probe had to take off the server around the close, so the
    whole line is matched in one fixed order: a line missing a field, or one assembled out of
    fragments of other receipts, is not a receipt and must not be adjudicated as one.
    """
    found=re.search(r'LITE_LOSS craftclose '+case+r' windowClosed=(\w+) closeAck=(\w+) '
                    r'codeBefore=(\S+) codeAfter=(\S+) uuidBefore=(\S+) uuidAfter=(\S+) '
                    r'holder=(\S+) holderType=(\w+) entityResolved=(\w+) inSlots=(\w+) '
                    r'slotsFree=(\d+) fullAtClose=(\w+) dropObserved=(\w+) '
                    r'ticksObserved=(\d+) samples=(\d+) rowsBefore=(\d+) rowsAfter=(\d+) '
                    r'clearedBefore=(\d+) clearedAfter=(\d+) staffAlive=(\w+)', server)
    assert found, 'craftclose '+case+' receipt missing'
    return dict(zip(['windowClosed','closeAck','codeBefore','codeAfter','uuidBefore','uuidAfter',
                     'holder','holderType','entityResolved','inSlots','slotsFree','fullAtClose',
                     'dropObserved','ticksObserved','samples','rowsBefore','rowsAfter',
                     'clearedBefore','clearedAfter','staffAlive'], found.groups()))

def verify_loss_only(root):
    """Adjudicate the bounded terminal-loss gate.

    Separate from verify()/verify_craft_only() on purpose: this scope proves one boundary — that a
    loss row is written when and only when the item actually stopped existing — and must not be
    able to borrow another scope's evidence to pass.
    """
    stage=json.loads((root/'stage.json').read_text())
    attempt=json.loads((root/'attempt.json').read_text())
    outcome=json.loads((root/'outcome.json').read_text())
    assert attempt['stage_sha256']==hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(), 'attempt/stage binding'
    assert attempt.get('scope')=='loss-only-lite-smoke' and attempt.get('generations')==1, 'wrong loss attempt scope'
    assert outcome['status']=='CAPTURED' and outcome['port_released'], 'incomplete or failed attempt'
    assert {c['child'] for c in outcome['cleanup']}=={'bots-1','server-1'}, 'loss cleanup coverage'
    assert all(c['exit']==0 and not c['forced'] and c['log_closed'] for c in outcome['cleanup']), 'unclean loss child exit'
    candidate=candidate_sha256(root, stage)
    server=(root/'server-1.log').read_text(encoding='utf-8')
    assert 'Done (' in server and 'LITE_PROBE_BOOT' in server, 'loss startup missing'
    assert 'Stopping server' in server and 'All dimensions are saved' in server, 'loss shutdown missing'
    assert not re.search(r'\bERROR\b|LITE_PROBE_FAIL|Exception|NoClassDefFound',server), 'loss server error'
    for case in ['loss-damaged-but-survives','loss-pickup-not-recorded',
                 'loss-burn-recorded','loss-unload-not-recorded',
                 'loss-cursor-not-recorded','loss-crafting-not-recorded',
                 'loss-craftclose-spare-not-recorded','loss-craftclose-full-not-recorded',
                 'loss-clear-recorded']:
        assert server.count('LITE_CASE '+case+' PASS')==1, 'missing/repeated '+case

    # The counted numbers must be in the raw log, not implied by a PASS marker alone.
    survive=re.search(r'LITE_LOSS survive alive=(\w+) healthBefore=(-?\d+) '
                      r'evidenceWhileHurt=(-?\d+) rowsBefore=(\d+) rowsAfter=(\d+)', server)
    assert survive, 'survive receipt missing'
    assert survive.group(1)=='true', 'survive case did not leave the item alive'
    # Proves the item was really hurt. Without this the case would also pass if the fire never
    # touched it, which would test nothing at all.
    #
    # The reading is taken WHILE the item is still burning, not after the fire is cleared: on
    # servers without Paper's Item.getHealth() the evidence is the fire itself (reported as a
    # negative fire-tick count so the same "lower than before" comparison holds either way), and
    # a reading taken after setFireTicks(0) would have erased it.
    assert int(survive.group(3)) < int(survive.group(2)), 'survive case never actually damaged the item'
    assert survive.group(4)==survive.group(5), 'a loss was recorded while the item still existed'

    pickup=re.search(r'LITE_LOSS pickup held=(\w+) rowsBefore=(\d+) rowsAfter=(\d+)', server)
    assert pickup, 'pickup receipt missing'
    assert pickup.group(1)=='true', 'pickup case did not end with the item held'
    assert pickup.group(2)==pickup.group(3), 'a loss was recorded for an item that was picked up'

    unload=re.search(r'LITE_LOSS unload loaded=(\w+) rowsBefore=(\d+) rowsAfter=(\d+)', server)
    assert unload, 'unload receipt missing'
    # Proves the chunk really left memory. Chunk.unload() refuses while a player is inside it, and
    # a run that never unloaded anything satisfied "no loss was recorded" without testing it.
    assert unload.group(1)=='false', 'unload case never actually unloaded the chunk'
    assert unload.group(2)==unload.group(3), 'a loss was recorded for an item whose chunk unloaded'

    # Recovery after the unload, adjudicated as separable facts. A single world-wide item count
    # reads 0 for a chunk whose entity section is not in memory, for one still loading, and for a
    # stack that stopped existing, which is how a harness-side reload failure was once reported as
    # a missing item. Load state and actor position are settled before identity is even consulted.
    rehome=re.search(r'LITE_LOSS rehome chunk=(-?\d+),(-?\d+) blockLoaded=(\w+) entitiesLoaded=(\w+) '
                     r'staffDistance=(\d+) memberDistance=(\d+) entityResolved=(\w+) items=(\d+) '
                     r'recovered=(\w+) teleported=(\w+) ticketReleased=(\w+)', server)
    assert rehome, 'rehome receipt missing'
    assert rehome.group(3)=='true', 'the home chunk block data never reloaded'
    assert rehome.group(4)=='true', 'the home chunk entities never reloaded, so recovery was never tested'
    assert rehome.group(10)=='true', 'the actors were not teleported back to their recovery positions'
    assert int(rehome.group(5))<=HOME_CHUNK_BLOCKS, 'the actors never returned to the home chunk'
    # Whoever is standing there collects it. On fixture de95793790e4 both actors were teleported to
    # the same point and the member took the stack first, so recovery reported false for an item
    # that still existed. The collector has to be the only one in reach, by position, not by luck.
    assert int(rehome.group(6))>=PICKUP_SEPARATION_BLOCKS, \
        'the actors shared the recovery pickup radius'
    # Deliberately not a defect verdict. The identity is looked up by the UUID recorded at staging,
    # so all this says is that the original stack could not be found after a real reload. Vanilla
    # despawn, an eviction path this harness does not model and a product fault all land here, and
    # this scope has no evidence that separates them.
    assert rehome.group(7)=='true', ('the staged entity identity was not resolvable after a real '
                                     'reload: cause UNKNOWN, not attributable to ItemGuard')
    assert rehome.group(9)=='true', 'rehome never returned the stack to the staff bot'
    # The chunk ticket is test-only scaffolding; a run that leaked it changed the world state the
    # later cases are read against.
    assert rehome.group(11)=='true', 'the test-only home chunk ticket was not released'

    # Positive control. Without it every "nothing was recorded" assertion above is satisfied by a
    # plugin that never records anything.
    burn=re.search(r'LITE_LOSS burn gone=(\w+) rowsBefore=(\d+) rowsAfter=(\d+) reason=(\w+) '
                   r'arenaCleared=(\w+)', server)
    assert burn, 'burn receipt missing'
    # The lava this case places must not outlive it. Left behind, it kills the actor in a later
    # phase and that reads as a failure of whatever case was running at the time.
    assert burn.group(5)=='true', 'the burn arena was left in the world'
    assert burn.group(1)=='true', 'burn case left the item in the world'
    assert int(burn.group(3)) > int(burn.group(2)), 'a real burn was not recorded'
    assert burn.group(4)=='BURNED', 'a real burn was recorded with the wrong reason: '+burn.group(4)

    # Inventory-side presence, on a second identity seeded after the burn. A tracked stack on the
    # cursor or in the player's own crafting grid has left Inventory.getContents() but is plainly
    # still in the world; a loss row there would confirm the destruction of an item its owner can
    # see, and a confirmed destruction is what makes an item restorable.
    #
    # The watcher only reports a departure from two consecutive snapshots, so the identity must
    # first be seen sitting in real slots for a full scan window. Without that, the stack never
    # "left" anything and the two checks below would pass on a build that cannot detect removals.
    # Captured precondition for everything below: the actors are off the burn arena, alive, unburnt
    # and standing in hazard-free ground. No invulnerability is applied anywhere, so the presence
    # cases still run against ordinary players.
    safespot=re.search(r'LITE_LOSS safespot staffAlive=(\w+) memberAlive=(\w+) hazards=(\d+) '
                       r'arenaDistance=(\d+)', server)
    assert safespot, 'safespot receipt missing'
    assert safespot.group(1)=='true' and safespot.group(2)=='true', \
        'an actor was not alive and unburnt before the second identity was seeded'
    assert safespot.group(3)=='0', 'the actors were seeded next to leftover hazard blocks'
    assert int(safespot.group(4))>=PICKUP_SEPARATION_BLOCKS, \
        'the second identity was seeded on top of the burn arena'

    hold=re.search(r'LITE_LOSS hold inSlots=(\w+) ticksHeld=(\d+) samples=(\d+)', server)
    assert hold, 'hold receipt missing'
    assert hold.group(1)=='true', 'the presence checks never seeded the watcher snapshot'
    assert int(hold.group(2))>=SCAN_WINDOW_TICKS and int(hold.group(3))>=SCAN_WINDOW_TICKS, \
        'the identity was not held in real slots for two full removal scans before it departed'

    # samples is the count of per-tick observations that actually saw the state; ticksParked is the
    # measured server-tick span. Both are required, so neither a long wall-clock gap with no
    # observation nor a burst of observations inside one tick can stand in for continuous presence.
    cursor=re.search(r'LITE_LOSS cursor onCursor=(\w+) inSlots=(\w+) ticksParked=(\d+) '
                     r'rowsBefore=(\d+) rowsAfter=(\d+) samples=(\d+) staffAlive=(\w+)', server)
    assert cursor, 'cursor receipt missing'
    # A death drops the inventory and empties the cursor. That is a hazard, not cursor handling.
    assert cursor.group(7)=='true', 'the actor did not stay alive through the cursor case'
    assert cursor.group(1)=='true', 'cursor case never actually parked the item on the cursor'
    assert cursor.group(2)=='false', 'cursor case never took the item out of the slot array'
    assert int(cursor.group(3))>=SCAN_WINDOW_TICKS and int(cursor.group(6))>=SCAN_WINDOW_TICKS, \
        'cursor case did not survive two full removal scans'
    assert cursor.group(4)==cursor.group(5), 'a loss was recorded for an item held on the cursor'

    grid=re.search(r'LITE_LOSS craftgrid inGrid=(\w+) inSlots=(\w+) ticksParked=(\d+) '
                   r'rowsBefore=(\d+) rowsAfter=(\d+) samples=(\d+) staffAlive=(\w+)', server)
    assert grid, 'craftgrid receipt missing'
    assert grid.group(7)=='true', 'the actor did not stay alive through the crafting case'
    assert grid.group(1)=='true', 'crafting case never actually parked the item in the grid'
    assert grid.group(2)=='false', 'crafting case never took the item out of the slot array'
    assert int(grid.group(3))>=SCAN_WINDOW_TICKS and int(grid.group(6))>=SCAN_WINDOW_TICKS, \
        'crafting case did not survive two full removal scans'
    assert grid.group(4)==grid.group(5), 'a loss was recorded for an item in the crafting grid'

    # Positive control for both of them: same identity, same watcher, a removal that really is one.
    clear=re.search(r'LITE_LOSS clear held=(\w+) lastAction=(\w+) rowsBefore=(\d+) '
                    r'rowsAfter=(\d+) reason=(\w+) ticksHeld=(\d+) samples=(\d+) '
                    r'staffAlive=(\w+)', server)
    assert clear, 'clear receipt missing'
    # A death drops the whole inventory, which is indistinguishable from the clear being tested.
    assert clear.group(8)=='true', 'the actor did not stay alive through the clear control'
    assert clear.group(1)=='false', 'the clear control left the item still held'
    assert clear.group(2) not in EXPLAINED_ACTIONS, \
        'the clear control ran on an already-explained last action: '+clear.group(2)
    assert int(clear.group(6))>=SCAN_WINDOW_TICKS and int(clear.group(7))>=SCAN_WINDOW_TICKS, \
        'the clear control did not hold the item in real slots for two full removal scans first'
    assert int(clear.group(4))-int(clear.group(3))==1, 'a real clear was not recorded exactly once'
    assert clear.group(5)=='CLEARED', 'a real clear was recorded with the wrong reason: '+clear.group(5)

    # Closing a crafting window is not a destruction. Vanilla empties the grid on close: the stack
    # goes back into the inventory, or is dropped at the player's feet when there is no room. It
    # still exists either way, so a loss row there confirms the destruction of an item its owner can
    # walk over and pick up, and a confirmed destruction is what makes an item restorable. Both
    # paths are mandatory — the spare case proves the return, and only the full case can show what
    # happens when the return has nowhere to go.
    for case in ['spare','full']:
        close=craftclose_receipt(server, case)
        # A death empties the grid and the inventory at once, which is not close handling.
        assert close['staffAlive']=='true', \
            case+': the actor did not stay alive through the crafting close'
        assert close['windowClosed']=='true', \
            case+': the crafting window was never actually closed'
        # A probe-side closeInventory() reproduces none of the close packet path the watcher has to
        # survive, the same shortcut already fenced off for the cursor. The close has to be the real
        # client's, acknowledged as such.
        assert close['closeAck']=='CLIENT', \
            case+': the crafting close had no client acknowledgement'
        # ticksObserved is the measured server-tick span and samples the count of per-tick
        # observations that actually saw the state. Both are required, so neither a wall-clock gap
        # with no observation nor a burst inside one tick can stand in for continuous presence.
        assert int(close['ticksObserved'])>=SCAN_WINDOW_TICKS \
            and int(close['samples'])>=SCAN_WINDOW_TICKS, \
            case+': the post-close state did not last two full removal scans'
        assert close['holderType'] in OBSERVED_HOLDER_TYPES, \
            case+': the crafting close recorded no observed holder type: '+close['holderType']
        # A build that deletes the tracked stack and hands back a fresh one leaves the inventory
        # looking correct while the tracked identity is gone, which is indistinguishable from a
        # successful close unless both halves are compared against what was staged before it.
        assert close['codeAfter']==close['codeBefore'], \
            case+': the tracked code did not survive the crafting close'
        assert close['uuidAfter']==close['uuidBefore'], \
            case+': the item UUID did not survive the crafting close'
        # Recorded, not judged: vanilla's close-time drop is not guaranteed to raise a
        # PlayerDropItemEvent, so demanding one would reject a build that behaved correctly.
        assert close['dropObserved'] in ('true','false'), \
            case+': dropObserved is not an observation: '+close['dropObserved']
        assert close['rowsAfter']==close['rowsBefore'], \
            case+': a loss was recorded for a stack that survived the crafting close'
        # Counted on its own rather than inferred from the total row count: a build that files the
        # row under some other reason still marks the stack confirmed-gone.
        assert close['clearedAfter']==close['clearedBefore'], \
            case+': a new CLEARED row was written for a stack that survived the crafting close'

    # The two cases are only different where their preconditions differ, and each has to prove it
    # ran the path it is named after rather than the other one wearing its name.
    spare=craftclose_receipt(server,'spare')
    assert spare['fullAtClose']=='false' and int(spare['slotsFree'])>0, \
        'the spare case did not have room to return the stack to'
    assert spare['inSlots']=='true', 'the spare case never returned the identity to real slots'

    full=craftclose_receipt(server,'full')
    assert full['fullAtClose']=='true' and int(full['slotsFree'])==0, \
        'the full case was not full at close, so the drop path was never exercised'
    # The full case must end at a real physical original: either the dropped Item entity carrying
    # the staged UUID was resolved in the world, or Paper still fitted the identity into the
    # player's own slots, which is also a correct outcome. Neither, and the stack is unaccounted for.
    assert full['entityResolved']=='true' or full['inSlots']=='true', \
        'the full case resolved neither the original item entity nor a returned identity'

    rows=[json.loads(line) for line in (root/'bots-1.log').read_text(encoding='utf-8').splitlines() if line.startswith('{')]
    assert not any(x['event']=='BOT_ERROR' for x in rows), 'loss bot error'
    assert {x.get('player') for x in rows if x['event']=='spawn'}=={'LiteStaff','LiteMember'}, 'loss actors missing'
    assert {x.get('player') for x in rows if x['event']=='end'}=={'LiteStaff','LiteMember'}, 'loss actor shutdown missing'
    return {'status':'PASS_LOSS_ONLY_SMOKE','root':str(root),'candidate_sha256':candidate,
            'limitations':['Terminal loss boundary only',
                           'Cursor/crafting presence proven for one identity and one clear control',
                           'Crafting close proven for one spare-inventory and one full-inventory '
                           'close; no claim about other window types',
                           'No crash/power-loss, scale, concurrency or visual acceptance claims']}

def verify_sweep_only(root):
    """Adjudicate the bounded closed-chest sweep gate.

    A duplicate identity sitting in two closed chests, never opened, must still trip
    ITEMGUARD_DUPLICATE_CONFIRMED. The probe's own LITE_SWEEP receipt hardcodes
    confirmed=unknown locations=unknown precisely so this scope cannot pass on the probe's own
    say-so: the verdict has to come from the plugin's own line, not the receipt.
    """
    stage=json.loads((root/'stage.json').read_text())
    attempt=json.loads((root/'attempt.json').read_text())
    outcome=json.loads((root/'outcome.json').read_text())
    assert attempt['stage_sha256']==hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(), 'attempt/stage binding'
    assert attempt.get('scope')=='sweep-only-lite-smoke' and attempt.get('generations')==1, 'wrong sweep attempt scope'
    assert outcome['status']=='CAPTURED' and outcome['port_released'], 'incomplete or failed attempt'
    assert {c['child'] for c in outcome['cleanup']}=={'bots-1','server-1'}, 'sweep cleanup coverage'
    assert all(c['exit']==0 and not c['forced'] and c['log_closed'] for c in outcome['cleanup']), 'unclean sweep child exit'
    candidate=candidate_sha256(root, stage)
    server=(root/'server-1.log').read_text(encoding='utf-8')
    assert 'Done (' in server and 'LITE_PROBE_BOOT' in server, 'sweep startup missing'
    assert 'Stopping server' in server and 'All dimensions are saved' in server, 'sweep shutdown missing'
    assert not re.search(r'\bERROR\b|LITE_PROBE_FAIL|Exception|NoClassDefFound',server), 'sweep server error'

    receipt=re.search(r'LITE_SWEEP closedchests enabled=(\w+) chestA=(\S+) chestB=(\S+) '
                      r'sameDoubleChest=(\w+) code=(\S+) uuid=(\S+) sweepPasses=(\d+) '
                      r'confirmed=unknown locations=unknown', server)
    assert receipt, 'sweep receipt missing'
    _enabled,_chestA,_chestB,sameDoubleChest,code,_uuid,_sweepPasses=receipt.groups()
    # If the two chests merged into one double chest, one location would be correct behaviour and
    # the case would prove nothing about the sweep finding a duplicate across two containers.
    assert sameDoubleChest=='false', 'the two chests merged into one double chest; proves nothing'

    # The verdict comes from the plugin's own line, tied to the identity the receipt planted, never
    # from the probe's receipt (which never claims confirmation itself).
    confirmed=re.search(r'ITEMGUARD_DUPLICATE_CONFIRMED code='+re.escape(code)+
                        r' uuid=\S+ epoch=\S+ locations=(\d+) action=\S+', server)
    assert confirmed, 'plugin never confirmed the closed-chest duplicate for this identity'
    assert confirmed.group(1)=='2', 'confirmed line does not report both closed chests'

    return {'status':'PASS_SWEEP_SMOKE','root':str(root),'candidate_sha256':candidate}

def bot_messages(bots_log):
    """(recipient, message) for every chat message in a bots.cjs transcript.

    The envelope matters: each line is `{"player":"LiteStaff","message":"..."}`, so searching the
    raw line for an actor's name finds it in the *envelope* of every line addressed to that player.
    A control built that way ("staff saw the name") is always true, which makes the member-side
    assertion next to it meaningless. Only the message field is evidence.
    """
    rows = []
    for line in bots_log.splitlines():
        line = line.strip()
        if not line.startswith('{'):
            continue
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if row.get('event') == 'message' and row.get('player') and row.get('message'):
            rows.append((row['player'], row['message']))
    return rows


def is_plugin_answer(message):
    """Whether this line proves the plugin answered at all.

    A timeline reply is a header carrying the plugin prefix followed by bare rows - `2. Dropped |
    another player (staff only) | location hidden` - so requiring the prefix on every line sees only
    the header and the footer. The first version of this adjudicator did exactly that and reported a
    correct run as a failure. This is a *presence* test, used to require that the plugin spoke; the
    disclosure tests must not use it, because it is an allowlist and plugin output comes in shapes it
    does not list (M2, review #3).
    """
    return PLUGIN_PREFIX in message or bool(TIMELINE_ROW.match(message.strip()))


def privacy_facts(bots_log):
    """What the two transcripts actually show about the privacy boundary."""
    rows = bot_messages(bots_log)
    # Everything the member was sent, vanilla broadcasts aside: a disclosure in any other shape still
    # counts (M2, review #3).
    member = plugin_lines([message for player, message in rows if player == 'LiteMember'])
    staff = plugin_lines([message for player, message in rows if player == 'LiteStaff'])
    foreign = [message for message in member if 'another player (staff only)' in message]
    return {
        'member_answer_lines': len([m for m in member if is_plugin_answer(m)]),
        'staff_answer_lines': len(staff),
        'member_foreign_rows': len(foreign),
        'member_kept_foreign_location': any('location hidden' not in m for m in foreign),
        'member_saw_redaction': bool(foreign),
        'member_leaked_actor': any('LiteStaff' in m for m in member),
        'staff_saw_actor': any('LiteStaff' in m for m in staff),
        'member_lines_inspected': len(member),
    }


def adjudicate_privacy(bots_log):
    """Derive the privacy boundary from the transcripts alone.

    Kept as a pure function over the log text so the contract tests can exercise the three ways
    this can look true without being true: the member's stream leaking the name, the member's
    timeline rendering nothing at all, and the staff view not containing the name either - in which
    case "the member did not see it" proves nothing.
    """
    facts = privacy_facts(bots_log)
    assert facts['staff_saw_actor'], 'staff view never showed the actor, so the member view proves nothing'
    assert facts['member_answer_lines'], 'member was never answered, so the redaction branch never ran'
    # The disclosure is checked before the redaction is required, so a leaking view is reported as a
    # leak rather than as a missing marker.
    assert not facts['member_leaked_actor'], 'member view disclosed the other actor name'
    assert facts['member_saw_redaction'], 'member view never rendered the redaction branch'
    assert not facts['member_kept_foreign_location'], 'member view kept a foreign location'
    return facts


def verify_multi(root):
    """Adjudicate the two-item / two-reader fixture, including the privacy boundary.

    `multi.json` carries the scope's own claims; the verdict here is derived from the raw logs, and
    the summary is then required to agree. The privacy claim in particular has to come from the
    transcripts: "the member did not see the name" only means something next to "staff's view did
    contain it", and only the logs show both sides.
    """
    stage=json.loads((root/'stage.json').read_text())
    attempt=json.loads((root/'attempt.json').read_text())
    # This scope writes multi.json, not outcome.json: it drives its own server rather than going
    # through smoke.run(), so the cleanup record lives in its own summary. The port is checked by
    # connecting, not by trusting a field.
    summary=json.loads((root/'multi.json').read_text())
    assert attempt['stage_sha256']==hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(), 'attempt/stage binding'
    assert attempt.get('scope')=='multi-lite-smoke' and attempt.get('generations')==1, 'wrong multi attempt scope'
    assert summary.get('status') in ('PASS_MULTI','FAILED_MULTI'), 'multi attempt did not finish'
    assert {c['child'] for c in summary['cleanup']}=={'bots','server'}, 'multi cleanup coverage'
    assert all(c['exit']==0 and not c['forced'] and c['log_closed'] for c in summary['cleanup']), 'unclean multi child exit'
    port=stage.get('port')
    assert port, 'stage declares no port to check'
    import socket
    with socket.socket() as probe:
        probe.settimeout(2)
        assert probe.connect_ex(('127.0.0.1', int(port))) not in (0,), f'multi fixture port {port} is still open'
    candidate=candidate_sha256(root, stage)

    server=(root/'server-1.log').read_text(encoding='utf-8')
    assert 'Done (' in server and 'LITE_PROBE_BOOT' in server, 'multi startup missing'
    assert 'Stopping server' in server and 'All dimensions are saved' in server, 'multi shutdown missing'
    assert not re.search(r'\bERROR\b|LITE_PROBE_FAIL|Exception|NoClassDefFound', server), 'multi server error'
    for case in ('permissions','identity','history'):
        assert f'LITE_CASE {case} PASS' in server, f'multi case missing from the server log: {case}'

    facts=adjudicate_privacy((root/'bots-1.log').read_text(encoding='utf-8'))
    assert summary.get('status')=='PASS_MULTI', f"multi.json recorded {summary.get('status')}"
    for field in ('two_items_distinct','one_identity_for_both','holder_sees_id_non_holder_does_not','privacy_ok'):
        assert summary.get(field) is True, f'multi.json does not claim {field}'
    return {'status':'PASS_MULTI','root':str(root),'candidate_sha256':candidate,'privacy':facts}


def verify(root):
    stage=json.loads((root/'stage.json').read_text())
    attempt=json.loads((root/'attempt.json').read_text())
    outcome=json.loads((root/'outcome.json').read_text())
    assert attempt['stage_sha256']==hashlib.sha256((root/'stage.json').read_bytes()).hexdigest(), 'attempt/stage binding'
    assert attempt.get('scope')=='separate-lite-smoke' and attempt.get('generations')==2, 'wrong attempt scope'
    assert outcome['status']=='CAPTURED' and outcome['port_released'], 'incomplete or failed attempt'
    assert {c['child'] for c in outcome['cleanup']}=={'bots-1','server-1','bots-2','server-2'}, 'cleanup coverage'
    assert len(outcome['cleanup'])==4 and all(c['exit']==0 and not c['forced'] and c['log_closed'] for c in outcome['cleanup']), 'unclean child exit'
    candidate=candidate_sha256(root, stage)
    for generation in [1,2]:
        server=(root/f'server-{generation}.log').read_text(encoding='utf-8')
        assert 'Done (' in server and 'LITE_PROBE_BOOT' in server, 'startup missing'
        assert 'Stopping server' in server and 'All dimensions are saved' in server, 'shutdown missing'
        assert not re.search(r'\bERROR\b|LITE_PROBE_FAIL|Exception|NoClassDefFound',server), 'server error'
        cases=['permissions','identity','history','gui-open','gui-guide-row','gui-back-button','gui-framed-border','gui-click-readonly','craft-normal-fail-closed','craft-shift-fail-closed','custody-self-drop-not-counted','history-flood-bounded','container-scene','container-kinds-distinct','container-actions-named','hopper-not-attributed','timeline-icons-heads','chest-position-is-the-chest','custody-handover-counted-once','custody-pingpong-throttled','custody-restore','duplicate-not-removed'] if generation==1 else ['restart-identity','restart-history','clear-recorded-as-cleared']
        for case in cases: assert server.count('LITE_CASE '+case+' PASS')==1, 'missing/repeated '+case
        if generation==1:
            assert re.search(r'LITE_CRAFT normal event=1 ingredient=3 output=0', server), 'craft normal receipt missing'
            assert re.search(r'LITE_CRAFT shift event=1 ingredients=3 output=0', server), 'craft shift receipt missing'
            # The counted numbers must appear in the raw log, not only as a PASS marker.
            assert re.search(r'LITE_CUSTODY self transfers=0 holders=1', server), 'self custody receipt missing'
            assert re.search(r'LITE_CUSTODY transfer transfers=1 holders=2', server), 'handover custody receipt missing'
            assert re.search(r'LITE_CUSTODY pingpong transfers=[01] holders=2', server), 'pingpong custody receipt missing'
        rows=[]
        for line in (root/f'bots-{generation}.log').read_text(encoding='utf-8').splitlines():
            if line.startswith('{'):rows.append(json.loads(line))
        assert not any(x['event']=='BOT_ERROR' for x in rows), 'bot error'
        assert {x.get('player') for x in rows if x['event']=='spawn'}=={'LiteStaff','LiteMember'}, 'actors missing'
        assert {x.get('player') for x in rows if x['event']=='end'}=={'LiteStaff','LiteMember'}, 'actor shutdown missing'
        if generation==1:
            assert 'ITEMGUARD_DUPLICATE_CONFIRMED' in server, 'duplicate evidence missing'
            def received(player,text):return any(x['event']=='message' and x.get('player')==player and text in x.get('message','') for x in rows)
            assert received('LiteMember','You do not have permission'), 'member permission denial missing'
            assert received('LiteStaff','Recent history'), 'staff history missing'
            assert received('LiteStaff','DUPE ALERT!'), 'staff warning missing'
            craft_denial = 'ItemGuard policy blocks crafting a result that would need a new tracked identity'
            assert sum(x['event']=='message' and x.get('player')=='LiteStaff'
                       and craft_denial in x.get('message','') for x in rows) == 2, 'craft denial receipts missing/repeated'
            assert any(x['event']=='clicked' and x['player']=='LiteStaff' for x in rows), 'GUI click input missing'
            # Privacy adjudication on real plugin output only. Vanilla join/leave broadcasts name
            # every player and are not ItemGuard disclosures.
            member_lines=[x.get('message','') for x in rows if x['event']=='message' and x.get('player')=='LiteMember']
            staff_lines=[x.get('message','') for x in rows if x['event']=='message' and x.get('player')=='LiteStaff']
            member_plugin=plugin_lines(member_lines)
            assert any('Recent history' in m or 'No recorded history' in m for m in member_plugin), 'member drilldown output missing'
            assert not discloses_actor(member_lines,'LiteStaff'), 'member drilldown disclosed the other actor name'
            assert discloses_actor(staff_lines,'LiteStaff'), 'staff investigation lost actor detail'
    return {'status':'PASS_CONTROLLED_SMOKE','root':str(root),'candidate_sha256':candidate,
            'limitations':['No client visual acceptance','No crafting/natural-break/scale/crash/guardian claims']}

if __name__=='__main__':
    try:
        if len(sys.argv)==3 and sys.argv[1]=='multi': result=verify_multi(Path(sys.argv[2]))
        elif len(sys.argv)==3 and sys.argv[1]=='craft': result=verify_craft_only(Path(sys.argv[2]))
        elif len(sys.argv)==3 and sys.argv[1]=='loss': result=verify_loss_only(Path(sys.argv[2]))
        elif len(sys.argv)==3 and sys.argv[1]=='sweep': result=verify_sweep_only(Path(sys.argv[2]))
        elif len(sys.argv)==2: result=verify(Path(sys.argv[1]))
        else: raise ValueError('Usage: verify.py ROOT | craft ROOT | loss ROOT | sweep ROOT | multi ROOT')
        print(json.dumps(result,indent=2))
    except (AssertionError,OSError,KeyError,ValueError) as e:
        print(json.dumps({'status':'REJECTED','reason':str(e)}));sys.exit(1)
