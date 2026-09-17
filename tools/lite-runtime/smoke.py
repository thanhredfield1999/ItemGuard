"""One approved isolated LITE attempt, with exactly one clean restart."""
from pathlib import Path
import hashlib, json, os, shutil, socket, subprocess, sys, threading, time, uuid, zipfile
ROOT = Path(__file__).resolve().parents[2]
BASE = Path('E:/AI.WORK/30_KET_QUA_THU_NGHIEM')
JAVA = 'C:/Program Files/Java/jdk-21/bin/java.exe'
EXPECTED = '8c0e540ec646ffec38cd1409cb69b8c339d1d83f7ba499841bbe6866d624ce4a'
HOME = Path(__file__).resolve().parent

# Terminal-loss driver. Each step is a real console command handled by LiteProbe.java and the
# marker is the probe's own receipt, so nothing here can pass without the server actually
# producing it.
#
# Staging is a BOT toss, not a probe-side inventory edit: a plugin-driven removal has no DROP on
# record, so ItemGuard's inventory sweep classifies it as unexplained and writes a CLEARED loss row
# for an item lying on the ground. That staging noise previously landed inside the survive window.
#
# Order is forced by the preconditions: the pickup returns the stack to the staff bot, so it must be
# thrown again before the remaining ground cases, and the burn destroys the entity for good, so it
# has to be last.
#
# Client-driven steps. The payload is a real Mineflayer action and the marker is the client's own
# receipt, so the driver can never advance on a state the probe merely asserted into existence.
# Everything that moves a tracked stack between the slots, the cursor and the crafting grid goes
# through here: the probe observes, it never places.
CLIENT_STEPS = {
    'toss':            ({'toss': True},            '"event":"tossed"'),
    'cursorpick':      ({'cursorPick': True},      '"event":"cursor-picked"'),
    'craftgridplace':  ({'craftGridPlace': True},  '"event":"craft-grid-placed"'),
    'craftgridreturn': ({'craftGridReturn': True}, '"event":"craft-grid-returned"'),
    'craftwindowclose':({'craftWindowClose': True},'"event":"craft-window-closed"'),
}

LOSS_STEPS = [
    ('toss',                         None,                                         20),
    ('liteprobe lossdrop',           'LITE_CASE loss-drop-staged PASS',            30),
    ('liteprobe lossdamagesurvive',  'LITE_CASE loss-damaged-but-survives PASS',   90),
    ('liteprobe losspickup',         'LITE_CASE loss-pickup-not-recorded PASS',    90),
    ('toss',                         None,                                         20),
    ('liteprobe lossdrop',           'LITE_CASE loss-drop-staged PASS',            30),
    ('liteprobe lossunload',         'LITE_CASE loss-unload-not-recorded PASS',    90),
    # The unload teleported both actors away and evicted the entity's chunk, so the burn needs its
    # own freshly thrown stack at the actors' new position rather than the evicted one.
    ('liteprobe lossrehome',         'LITE_CASE loss-rehome PASS',                 60),
    ('toss',                         None,                                         20),
    ('liteprobe lossdrop',           'LITE_CASE loss-drop-staged PASS',            30),
    ('liteprobe lossburn',           'LITE_CASE loss-burn-recorded PASS',         180),
    # Inventory-side presence. The burn consumed the first identity for good, so this half seeds a
    # second one and leaves the four cases above exactly as they were. A tracked stack on the
    # cursor or in the player's own crafting grid has left the slot array but is plainly still in
    # the world; the removal watcher must not call either of them a loss.
    #
    # The hold step is a precondition, not a claim: the watcher reports a departure only by
    # comparing consecutive snapshots, so the identity has to sit in real slots across a full scan
    # window before it moves, or nothing has departed and the two cases prove nothing.
    # The burn arena is torn down by the burn case itself; this then moves the actors clear of it
    # and captures that they are alive, unburnt and standing in hazard-free ground. On fixture
    # fba434e89f6e the lava outlived the case, the second identity was seeded in it, and the staff
    # bot died mid-cursor-window — a hazard carried across phases, read as a cursor failure.
    ('liteprobe losssafespot',       'LITE_LOSS safespot ',                        60),
    ('liteprobe seed',               'LITE_CASE permissions PASS',                 30),
    ('liteprobe identity',           'LITE_CASE identity PASS',                    60),
    ('liteprobe losshold',           'LITE_LOSS hold ',                            60),
    ('cursorpick',                   None,                                         30),
    ('liteprobe losscursor',         'LITE_CASE loss-cursor-not-recorded PASS',   120),
    ('craftgridplace',               None,                                         30),
    ('liteprobe losscraftgrid',      'LITE_CASE loss-crafting-not-recorded PASS', 120),
    # The client takes the stack back out of the grid and into a real inventory slot, which is where
    # every case below needs it: the crafting-close cases stage it from there, and the clear control
    # has to delete it out of a real slot rather than out of the grid the case above just proved is
    # suppressed.
    ('craftgridreturn',              None,                                         30),
    # Closing a crafting window is not a destruction. Vanilla empties the grid on close: the stack
    # goes back into the player's own slots, or is dropped at their feet when there is no room. It
    # exists either way, so a loss row there confirms the destruction of an item its owner can still
    # pick up. Both paths run on this same identity, and the client performs every move: the probe
    # arms an observer, the CLIENT closes its own screen, and the probe then reads what happened.
    #
    # The arm is a separate step because the close has to happen while it is armed — the capacity at
    # close and the identity that was in the grid stop being readable once vanilla has handed it
    # back — and because this probe never closes a window itself.
    ('cursorpick',                   None,                                         30),
    ('craftgridplace',               None,                                         30),
    ('liteprobe losscraftclosearm spare', 'LITE_CLOSE_ARMED spare',                30),
    ('craftwindowclose',             None,                                         30),
    ('liteprobe losscraftclosespare',
     'LITE_CASE loss-craftclose-spare-not-recorded PASS',                         120),
    # The spare close returned the stack to real slots, so the full case stages it again and only
    # then fills the inventory. Filler is written into EMPTY slots only, so nothing tracked is
    # destroyed to make the return path run out of room.
    ('cursorpick',                   None,                                         30),
    ('craftgridplace',               None,                                         30),
    ('liteprobe losscraftclosearm full',  'LITE_CLOSE_ARMED full',                 30),
    ('craftwindowclose',             None,                                         30),
    ('liteprobe losscraftclosefull',
     'LITE_CASE loss-craftclose-full-not-recorded PASS',                          120),
    # The full case left the ORIGINAL stack on the ground. It is given back by taking the filler out
    # and letting vanilla collect that same entity, never by handing over a replacement: the clear
    # control below has to delete the identity every case above was written about.
    ('liteprobe losscraftcloserecover',
     'LITE_CASE loss-craftclose-recovered PASS',                                   90),
    # Positive control for every "nothing was recorded" case above, on the same identity and the
    # same watcher: a removal that really is one. Without it, "nothing was recorded" is also what a
    # build whose watcher never runs would produce.
    ('liteprobe lossclear',          'LITE_CASE loss-clear-recorded PASS',        180),
]

# Entirely server-side: neither chest is ever opened, so no CLIENT_STEPS entry is needed here.
# sweepplacechests places both chests, tags the real item and forges the duplicate into chest B;
# sweepconfirm only records where they are and that the identity still resolves in both — it never
# claims the detection itself, so this scope's own waits stay generous rather than tight.
SWEEP_STEPS = [
    ('liteprobe sweepplacechests', 'LITE_CASE sweep-chests-loaded PASS',    60),
    ('liteprobe sweepconfirm',     'LITE_CASE sweep-identity-resolves PASS',30),
]

SCOPES = {
    'full':  {'scope': 'separate-lite-smoke',    'generations': 2},
    'craft': {'scope': 'craft-only-lite-smoke',  'generations': 1},
    'loss':  {'scope': 'loss-only-lite-smoke',   'generations': 1,
              'probe_steps': [command for command, _, _ in LOSS_STEPS
                              if command.startswith('liteprobe ')]},
    'sweep': {'scope': 'sweep-only-lite-smoke',  'generations': 1,
              'probe_steps': [command for command, _, _ in SWEEP_STEPS
                              if command.startswith('liteprobe ')]},
    # /reload inside a running JVM. A full restart hides listener leaks and stale static
    # state because the JVM dies; only reload keeps them alive into the new instance.
    'reload': {'scope': 'reload-lite-smoke', 'generations': 1,
               'probe_steps': ['liteprobe seed', 'liteprobe identity', 'liteprobe history-rows',
                               'liteprobe single-move', 'liteprobe listener-count',
                               'liteprobe identity-readback']},
    # Two tracked items and two players. Neither can be exercised with the single-item,
    # single-actor shape every other scope uses.
    'multi': {'scope': 'multi-lite-smoke', 'generations': 1,
              'probe_steps': ['liteprobe seed', 'liteprobe seed-two', 'liteprobe two-items',
                              'liteprobe identity', 'liteprobe history']},
    # Two plugins on one server: BastionForgeLite is installed alongside ItemGuard and edits
    # the same tracked item. One generation; the claim is about coexistence, not restart.
    'two-plugin': {'scope': 'two-plugin-lite-smoke', 'generations': 1,
                   'probe_steps': ['liteprobe seed', 'liteprobe identity', 'liteprobe digest',
                                   'liteprobe forge-socket', 'liteprobe identity-readback']},
    # Durability against an unclean stop. Two generations like 'full', but the first one is
    # killed with taskkill /F mid-write instead of being asked to stop, so nothing flushes.
    'power-cut': {'scope': 'power-cut-lite-smoke', 'generations': 2,
                  'probe_steps': ['liteprobe seed', 'liteprobe identity',
                                  'liteprobe history-churn', 'liteprobe identity-readback']},
}

def write_attempt(fixture, key):
    """Bind the attempt to this exact stage manifest and scope, refusing to overwrite one."""
    spec = SCOPES[key]
    with (Path(fixture)/'attempt.json').open('x') as f:
        json.dump({'stage_sha256': sha(Path(fixture)/'stage.json'),
                   'generations': spec['generations'], 'scope': spec['scope']}, f)

def toss(bots, actor, seconds=20):
    """Throw the tracked stack and wait for THIS toss's receipt, never an earlier one.

    `Process.has()` scans the whole accumulated log and that log is never cleared, so only the
    FIRST '"event":"tossed"' of a run is ever really awaited: every repeat returns on iteration
    one's line and `liteprobe pull` is dispatched while the bot is still mid-toss. That is what
    deadlined the third self-drop cycle on fixture 7133b80dd6a5, and it is the same defect the
    craft gate fixed at its second `craftprepare` — counted first, then awaited with has_more.
    """
    event='"event":"tossed"'
    previous=bots.count(event)
    bots.send(json.dumps({'player':actor,'toss':True}))
    bots.has_more(event,previous,seconds)

def pull(server, actor, seconds=30):
    """Collect the stack with the named actor, waiting for that actor's NEW pull receipt."""
    marker='LITE_PULLED '+actor
    before=server.count(marker)
    server.send('liteprobe pull '+actor)
    server.has_more(marker,before,seconds)

def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def available(port):
    with socket.socket() as sock: sock.bind(('127.0.0.1', port)); return sock.getsockname()[1]
def save(path, obj): Path(path).write_text(json.dumps(obj, indent=2), encoding='utf-8')

def prune_superseded_fixtures(current_sha):
    """Delete fixture roots belonging to a candidate that is no longer being tested.

    Every stage() leaves a full server behind - worlds, libraries, an unpacked jar - and a
    version matrix stages thirteen of them in one run. Left alone this filled a 224 GB disk
    and killed a matrix mid-run with ENOSPC, which cost more time than the runs themselves.

    Only roots whose stage.json names a DIFFERENT candidate are removed. Evidence for the
    candidate under test is never touched, and neither is a root whose stage.json cannot be
    read - an unreadable manifest is a reason to look, not to delete. A root still held open
    by a running server simply fails to delete and is skipped; it will be collected by a
    later run once that server is gone.
    """
    if not current_sha:
        # Without a candidate to compare against, everything would look superseded.
        return
    for root in sorted(BASE.glob('itemguard-lite-isolated-*')):
        manifest = root / 'stage.json'
        if not manifest.is_file():
            continue
        try:
            staged = json.loads(manifest.read_text(encoding='utf-8')).get('candidate_sha256', '')
        except Exception:
            continue
        if not staged or staged == current_sha:
            continue
        try:
            shutil.rmtree(root)
        except OSError:
            # Almost always a live server holding a world file open. Not ours to force.
            pass


def stage():
    product = ROOT / 'target/ItemGuard-LITE-1.0.0.jar'
    if sha(product) != EXPECTED: raise RuntimeError('Candidate mismatch')
    build = HOME / 'build'; build.mkdir(exist_ok=True)
    cp = (HOME/'classpath.txt').read_text().strip() + os.pathsep + str(product)
    # Both sources are named explicitly rather than left to implicit compilation: the probe's
    # crafting-close lifecycle is a separate class and a jar missing it is a ClassNotFoundError on
    # the first armed case, half an hour into a run.
    subprocess.run(['C:/Program Files/Java/jdk-21/bin/javac.exe','--release','21','-cp',cp,'-d',str(build),str(HOME/'LiteProbe.java'),str(HOME/'CraftCloseLifecycle.java'),str(HOME/'ProbeInitiatedClose.java')],check=True)
    helper = build/'LiteProbe.jar'
    with zipfile.ZipFile(helper,'w') as z:
        for f in (build/'smoke').rglob('*.class'): z.write(f,f.relative_to(build).as_posix())
        z.writestr('plugin.yml',"name: LiteProbe\nversion: '1'\nmain: smoke.LiteProbe\napi-version: '1.21.4'\ndepend: [ItemGuard]\ncommands:\n  liteprobe:\n    description: Isolated console-only probe\n")
    prune_superseded_fixtures(EXPECTED)
    fixture = BASE / ('itemguard-lite-isolated-' + uuid.uuid4().hex[:12]); fixture.mkdir()
    (fixture/'plugins').mkdir()
    # Only immutable runtime/dependency artifacts from cache, never worlds/plugin data/receipts.
    old = Path('E:/AI.WORK/itemguard-paper-smoke')
    # Version-matrix runs point this at another Paper build. The jar must be in place BEFORE
    # the admission manifest is hashed - swapping it afterwards is exactly what the artifact
    # gate exists to catch, and that gate must not be weakened to make a test pass.
    paper_override = os.environ.get('ITEMGUARD_PAPER_JAR')
    if paper_override:
        source_paper = Path(paper_override)
        if not source_paper.is_file():
            raise RuntimeError('ITEMGUARD_PAPER_JAR does not exist: ' + paper_override)
    else:
        source_paper = old/'paper.jar'
    shutil.copy2(source_paper,fixture/'paper.jar')
    for directory in ['libraries','versions','cache']:
        # A different Paper build brings its own libraries and unpacked server jar; reusing the
        # 1.21.11 cache would either be ignored or actively wrong.
        if paper_override and directory in ('versions','cache'): continue
        if (old/directory).exists(): shutil.copytree(old/directory,fixture/directory)
    shutil.copy2(product,fixture/'plugins/ItemGuard-LITE.jar')
    shutil.copy2(helper,fixture/'plugins/LiteProbe.jar')
    shutil.copy2(HOME/'bots.cjs',fixture/'bots.cjs')
    port = available(0)
    (fixture/'eula.txt').write_text('eula=true\n')
    props = f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
enable-rcon=false
enable-query=false
max-players=2
view-distance=3
simulation-distance=3
spawn-protection=0
gamemode=survival
difficulty=peaceful
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:dirt","height":2}},{{"block":"minecraft:grass_block","height":1}}],"biome":"minecraft:plains","features":false,"lakes":false}}
generate-structures=false
sync-chunk-writes=true
allow-flight=true
'''
    (fixture/'server.properties').write_text(props,encoding='utf-8')
    files = [p for p in fixture.rglob('*') if p.is_file()]
    manifest = {'root':str(fixture),'port':port,'candidate_sha256':EXPECTED,
                'controller_sha256':sha(__file__),'files':{p.relative_to(fixture).as_posix():sha(p) for p in files}}
    save(fixture/'stage.json',manifest)
    print(str(fixture))

def admission(fixture):
    if fixture.parent.resolve()!=BASE.resolve() or not fixture.name.startswith('itemguard-lite-isolated-'):
        raise RuntimeError('Wrong fixture namespace')
    m=json.loads((fixture/'stage.json').read_text())
    if m['root']!=str(fixture) or m['controller_sha256']!=sha(__file__) or m['candidate_sha256']!=EXPECTED:
        raise RuntimeError('Admission identity mismatch')
    for n,h in m['files'].items():
        p=fixture/n
        if not p.resolve().is_relative_to(fixture.resolve()) or sha(p)!=h: raise RuntimeError('Artifact mismatch: '+n)
    if (fixture/'attempt.json').exists() or (fixture/'outcome.json').exists(): raise RuntimeError('Consumed fixture')
    available(m['port'])
    return m

class Process:
    def __init__(self, args, fixture, name):
        self.lines=[]; self.file=(fixture/(name+'.log')).open('w',encoding='utf-8')
        self.p=subprocess.Popen(args,cwd=fixture,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
        self.thread=threading.Thread(target=self.read,daemon=True);self.thread.start()
    def read(self):
        try:
            for line in self.p.stdout:
                self.lines.append(line);self.file.write(line);self.file.flush()
        finally:self.p.stdout.close();self.file.close()
    def send(self, value):
        self.p.stdin.write(value+'\n');self.p.stdin.flush()
    def wait(self, predicate, seconds=40):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if any('LITE_PROBE_FAIL' in l or 'BOT_ERROR' in l for l in self.lines): raise RuntimeError('Probe/bot failure; inspect log')
            if predicate(self.lines):return
            if self.p.poll() is not None:raise RuntimeError('Child exited before expected evidence')
            time.sleep(.1)
        raise TimeoutError('Expected evidence timeout')
    def has(self, value, seconds=40):self.wait(lambda ls:any(value in l for l in ls),seconds)
    def count(self, value):return sum(1 for l in self.lines if value in l)
    def has_more(self, value, previous, seconds=40):
        """Wait for one NEW occurrence, without clearing the log that the verifier reads."""
        self.wait(lambda ls:sum(1 for l in ls if value in l) > previous, seconds)
    def stop(self, value):
        forced=False
        if self.p.poll() is None:
            try:self.send(value)
            except (BrokenPipeError,OSError):pass
            try:self.p.wait(timeout=60)
            except subprocess.TimeoutExpired:self.p.kill();self.p.wait(timeout=15);forced=True
        if self.p.stdin:self.p.stdin.close()
        self.thread.join(timeout=10)
        return {'pid':self.p.pid,'exit':self.p.returncode,'forced':forced,'log_closed':not self.thread.is_alive()}

def run(fixture, mode='full'):
    m=admission(fixture)
    craft_only = mode=='craft'
    loss_only = mode=='loss'
    sweep_only = mode=='sweep'
    generations = [1] if mode in ('craft','loss','sweep') else [1, 2]
    write_attempt(fixture, mode)
    result={'status':'FAILED','cleanup':[]};server=None;bots=None
    try:
        for generation in generations:
            available(m['port'])
            server=Process([JAVA,'-Xms512M','-Xmx1536M','-jar','paper.jar','--nogui'],fixture,f'server-{generation}')
            server.has('Done (',180);server.has('LITE_PROBE_BOOT',10)
            bots=Process(['node','bots.cjs',str(m['port'])],fixture,f'bots-{generation}')
            bots.wait(lambda ls:sum('"event":"spawn"' in l for l in ls)==2,60)
            if loss_only:
                # Terminal-loss scope only. Seed + identity are preconditions, not claims: the
                # loss cases read the tracked item's UUID out of the probe config and count rows
                # in SQLite, so the identity must exist before any loss action is meaningful.
                server.send('liteprobe seed');server.has('LITE_CASE permissions PASS')
                bots.send(json.dumps({'player':'LiteStaff','chat':'/ig check'}));bots.has('ItemGuard LITE')
                server.send('liteprobe identity');server.has('LITE_CASE identity PASS')
                server.has('LITE_CODE ',10)
                for command, marker, seconds in LOSS_STEPS:
                    if command in CLIENT_STEPS:
                        # A real packet from the client: a PlayerDropItemEvent for the toss, a real
                        # window click for the cursor and grid moves. The driver waits for the
                        # client's own receipt, never for a probe assertion about client state.
                        payload,event=CLIENT_STEPS[command]
                        previous=bots.count(event)
                        bots.send(json.dumps({'player':'LiteStaff',**payload}))
                        bots.has_more(event,previous,seconds)
                        continue
                    before=server.count(marker)
                    server.send(command)
                    server.has_more(marker, before, seconds)
                result['cleanup'].append({'child':f'bots-{generation}',**bots.stop('{"quit":true}')});bots=None
                result['cleanup'].append({'child':f'server-{generation}',**server.stop('stop')});server=None
                if any(c['exit']!=0 or c['forced'] or not c['log_closed'] for c in result['cleanup']):raise RuntimeError('Unclean child shutdown')
                available(m['port'])
                continue
            if sweep_only:
                # Closed-chest duplicate detection. No CLIENT_STEPS: nothing is ever opened.
                for command, marker, seconds in SWEEP_STEPS:
                    before=server.count(marker)
                    server.send(command)
                    server.has_more(marker, before, seconds)
                # The identity now sits untouched in two closed chests. Only the plugin's own
                # passive sweep can find it, so the server has to stay up long enough for a real
                # pass to run before it goes down and the log becomes fixed evidence.
                server.has('ITEMGUARD_DUPLICATE_CONFIRMED', 120)
                result['cleanup'].append({'child':f'bots-{generation}',**bots.stop('{"quit":true}')});bots=None
                result['cleanup'].append({'child':f'server-{generation}',**server.stop('stop')});server=None
                if any(c['exit']!=0 or c['forced'] or not c['log_closed'] for c in result['cleanup']):raise RuntimeError('Unclean child shutdown')
                available(m['port'])
                continue
            if generation==1:
                # Craft gate: the bot performs real output-slot clicks; LiteProbe only stages a
                # vanilla diamond-sword matrix and observes the event/result. This proves normal
                # and shift clicks stay fail-closed for this exact candidate.
                server.send('liteprobe craftstage');server.has('LITE_CASE craft-table-staged PASS')
                server.has('LITE_CASE craft-window-opened PASS')
                bots.send(json.dumps({'player':'LiteStaff','craftReady':True}));bots.has('"event":"craft-ready"')
                server.send('liteprobe craftprepare');server.has('LITE_CASE craft-result-staged PASS')
                bots.send(json.dumps({'player':'LiteStaff','craftClick':'normal'}));bots.has('"event":"craft-clicked"')
                server.send('liteprobe craftassert normal');server.has('LITE_CASE craft-normal-fail-closed PASS')
                # The normal click consumed the first staged result, so the shift half needs a NEW
                # staging marker. Counting from zero would be satisfied by the first one.
                staged=server.count('LITE_CASE craft-result-staged PASS')
                server.send('liteprobe craftprepare');server.has_more('LITE_CASE craft-result-staged PASS',staged)
                bots.send(json.dumps({'player':'LiteStaff','craftClick':'shift'}));bots.has_more('"event":"craft-clicked"',1)
                server.send('liteprobe craftassert shift');server.has('LITE_CASE craft-shift-fail-closed PASS')
                if craft_only:
                    result['cleanup'].append({'child':f'bots-{generation}',**bots.stop('{"quit":true}')});bots=None
                    result['cleanup'].append({'child':f'server-{generation}',**server.stop('stop')});server=None
                    if any(c['exit']!=0 or c['forced'] or not c['log_closed'] for c in result['cleanup']):raise RuntimeError('Unclean child shutdown')
                    available(m['port'])
                    continue
                server.send('liteprobe seed');server.has('LITE_CASE permissions PASS')
                # Query real state only after scheduled publication has had server ticks.
                bots.send(json.dumps({'player':'LiteStaff','chat':'/ig check'}));bots.has('ItemGuard LITE')
                server.send('liteprobe identity');server.has('LITE_CASE identity PASS')
                line=next(l for l in server.lines if 'LITE_CODE ' in l); code=line.split('LITE_CODE ',1)[1].strip()
                server.send('liteprobe history');server.has('LITE_CASE history PASS')
                bots.send(json.dumps({'player':'LiteMember','chat':'/ig search #'+code}));bots.has('You do not have permission')
                bots.send(json.dumps({'player':'LiteStaff','chat':'/ig search #'+code}));bots.has('Recent history')
                bots.send(json.dumps({'player':'LiteStaff','guiOpen':True}));bots.has('"event":"gui-open"')
                bots.send(json.dumps({'player':'LiteStaff','click':True}));bots.has('"event":"clicked"')
                server.send('liteprobe gui');server.has('LITE_CASE gui-click-readonly PASS')
                server.send('liteprobe duplicate');server.has('ITEMGUARD_DUPLICATE_CONFIRMED',60)
                # Persist actual warning text for independent adjudication.
                bots.wait(lambda ls:any('"event":"message"' in l and 'LiteStaff' in l and 'DUPE ALERT!' in l for l in ls),20)
                server.send('liteprobe duplicatesafe');server.has('LITE_CASE duplicate-not-removed PASS')
                # Privacy: the member now holds a copy, so an owner-scoped drilldown can surface rows
                # recorded by the other actor. Capture the member's own output for adjudication.
                bots.send(json.dumps({'player':'LiteMember','chat':'/ig history #'+code}))
                bots.wait(lambda ls:any('"event":"message"' in l and 'LiteMember' in l
                                        and ('Recent history' in l or 'No recorded history' in l) for l in ls),30)
                # Custody: the staff bot throws its own item and takes it back. Same holder, so the
                # count must stay at zero no matter how many times it happens.
                for _ in range(3):
                    toss(bots,'LiteStaff')
                    pull(server,'LiteStaff')
                server.send('liteprobe custodyself');server.has('LITE_CASE custody-self-drop-not-counted PASS',30)
                # Storage proof: those cycles must not have left a row each on disk. Checking only the
                # rendered figure is exactly how this defect survived two earlier fixes.
                server.send('liteprobe historyrows');server.has('LITE_CASE history-flood-bounded PASS',30)
                # Containers: chest vs ender chest vs carried shulker must be distinguishable, and a
                # hopper must never be attributed to a player. Reported by Thanh as all INVENTORY_MOVE.
                server.send('liteprobe containerscene');server.has('LITE_CASE container-scene PASS',30)
                server.send('liteprobe containeractions')
                server.has('LITE_CASE container-kinds-distinct PASS',30)
                server.send('liteprobe containerrows');server.has('LITE_CASE container-actions-named PASS',30)
                server.send('liteprobe hopperquiet');server.has('LITE_CASE hopper-not-attributed PASS',30)
                # The chest position must be the chest, not where the player stood.
                server.send('liteprobe containerposition')
                server.has('LITE_CASE chest-position-is-the-chest PASS',30)
                # Icons: a carried row must render a head with a real owner, not one flat icon.
                server.send('liteprobe timelineicons');server.has('LITE_CASE timeline-icons-heads PASS',40)
                # Custody: a genuine handover. The staff bot throws it, the member bot takes it.
                toss(bots,'LiteStaff')
                pull(server,'LiteMember')
                server.send('liteprobe custodytransfer');server.has('LITE_CASE custody-handover-counted-once PASS',30)
                # Custody: the same pair swapping repeatedly must not farm the count.
                for _ in range(3):
                    toss(bots,'LiteMember')
                    pull(server,'LiteStaff')
                    toss(bots,'LiteStaff')
                    pull(server,'LiteMember')
                server.send('liteprobe custodypingpong');server.has('LITE_CASE custody-pingpong-throttled PASS',30)
                server.send('liteprobe restoreholder');server.has('LITE_CASE custody-restore PASS',30)
            else:
                server.send('liteprobe restart');server.has('LITE_CASE restart-history PASS')
                # /clear last of all: it empties the hand, and generation 2 has already replayed
                # history from the database by this point.
                server.send('liteprobe clearloss')
                server.has('LITE_CASE clear-recorded-as-cleared PASS',75)
            result['cleanup'].append({'child':f'bots-{generation}',**bots.stop('{"quit":true}')});bots=None
            result['cleanup'].append({'child':f'server-{generation}',**server.stop('stop')});server=None
            if any(c['exit']!=0 or c['forced'] or not c['log_closed'] for c in result['cleanup']):raise RuntimeError('Unclean child shutdown')
            available(m['port'])
        result['status']='CAPTURED'
    except BaseException as e:result['error']=repr(e)
    finally:
        if bots:result['cleanup'].append({'child':'bots-failure',**bots.stop('{"quit":true}')})
        if server:result['cleanup'].append({'child':'server-failure',**server.stop('stop')})
        try:available(m['port']);result['port_released']=True
        except OSError:result['port_released']=False
        save(fixture/'outcome.json',result)
    print(json.dumps(result,indent=2))
    if result['status']!='CAPTURED':sys.exit(1)

if __name__=='__main__':
    if sys.argv[1:]==['stage']:stage()
    elif len(sys.argv)==3 and sys.argv[1]=='run':run(Path(sys.argv[2]))
    elif len(sys.argv)==3 and sys.argv[1]=='craft':run(Path(sys.argv[2]), mode='craft')
    elif len(sys.argv)==3 and sys.argv[1]=='loss':run(Path(sys.argv[2]), mode='loss')
    elif len(sys.argv)==3 and sys.argv[1]=='sweep':run(Path(sys.argv[2]), mode='sweep')
    else:raise SystemExit('Usage: smoke.py stage | run EXACT_ROOT | craft EXACT_ROOT | loss EXACT_ROOT | sweep EXACT_ROOT')
