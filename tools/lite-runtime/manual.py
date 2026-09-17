"""One owned manual server per project; close on EOF, stop request or bounded deadline."""
import argparse,hashlib,json,os,queue,re,shutil,socket,subprocess,sys,threading,time,uuid
from pathlib import Path
BASE=Path('E:/AI.WORK/30_KET_QUA_THU_NGHIEM')
SOURCE=Path('E:/AI.WORK/ItemGuard/target/ItemGuard-LITE-1.0.0.jar')
CACHE=BASE/'itemguard-lite-isolated-ed9e109ccc9a'
JAVA=Path('C:/Program Files/Java/jdk-21/bin/java.exe')
EXPECTED='8c0e540ec646ffec38cd1409cb69b8c339d1d83f7ba499841bbe6866d624ce4a'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,x):p.write_text(json.dumps(x,indent=2),encoding='utf-8')
def free(port=0):
    with socket.socket() as s:s.bind(('127.0.0.1',port));return s.getsockname()[1]

class SessionLock:
    """OS lock remains held until cleanup finishes; stale files do not hold a lock."""
    def __init__(self, path):
        self.path = path
    def __enter__(self):
        self.file = self.path.open('a+b')
        if self.file.seek(0, 2) == 0:
            self.file.write(b'0'); self.file.flush()
        self.file.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            self.file.close()
            raise RuntimeError('ItemGuard manual server already running; stop its owned session first') from exc
        return self
    def __exit__(self, *args):
        self.file.close()

def owned_root(p):
    if (p.is_symlink() or (hasattr(p, 'is_junction') and p.is_junction())
            or p.resolve().parent != BASE.resolve()
            or not re.fullmatch(r'itemguard-lite-manual-[a-f0-9]{12}', p.name)):
        raise RuntimeError('outside manual session namespace')
    return p

def pid_alive(pid):
    if not isinstance(pid, int) or pid <= 0:
        return False
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return ctypes.get_last_error() != 87  # Access denied is not proof of exit.
        try:
            code = wintypes.DWORD()
            return not kernel.GetExitCodeProcess(handle, ctypes.byref(code)) or code.value == 259
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True

def port_released(port):
    try:
        free(port)
        return True
    except OSError:
        return False

def reject_unfinished_sessions():
    # Read only this launcher's bounded namespace. Legacy/orphan sessions have no OS lock.
    for root in BASE.glob('itemguard-lite-manual-*'):
        live = root / 'live.json'
        if not live.exists() or (root / 'outcome.json').exists():
            continue
        owned_root(root)
        record = json.loads(live.read_text(encoding='utf-8'))
        if pid_alive(record['pid']) or not port_released(record['port']):
            raise RuntimeError(f'unfinished session: {root}; inspect/stop it before launching another')

def java_command():
    return [str(JAVA), '-Xms256M', '-Xmx1024M', '-XX:ActiveProcessorCount=2',
            '-jar', 'paper.jar', '--nogui']

def stage(operator=None):
    assert sha(SOURCE)==EXPECTED
    assert 'eula=true' in (CACHE/'eula.txt').read_text()
    p=BASE/('itemguard-lite-manual-'+uuid.uuid4().hex[:12]);p.mkdir();(p/'plugins').mkdir()
    shutil.copy2(SOURCE,p/'plugins/ItemGuard-LITE.jar')
    shutil.copy2(CACHE/'paper.jar',p/'paper.jar');shutil.copy2(CACHE/'eula.txt',p/'eula.txt')
    for n in ['libraries','versions','cache']:
        if (CACHE/n).exists():shutil.copytree(CACHE/n,p/n)
    # Optional operator, recorded in the manifest so the fixture's permission state is evidence,
    # not an undocumented side effect. Paper only reads ops.json at startup.
    # Offline UUIDs are MD5 of "OfflinePlayer:<name>" with version 3 and RFC 4122 variant bits,
    # which is NOT what uuid.uuid3 produces, so the digest is adjusted explicitly.
    if operator:
        name=operator
        digest=bytearray(hashlib.md5(('OfflinePlayer:'+name).encode('utf-8')).digest())
        digest[6]=(digest[6]&0x0f)|0x30
        digest[8]=(digest[8]&0x3f)|0x80
        offline=uuid.UUID(bytes=bytes(digest))
        save(p/'ops.json',[{'uuid':str(offline),'name':name,'level':4,'bypassesPlayerLimit':False}])
    port=free()
    generator=json.dumps({'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:dirt','height':2},{'block':'minecraft:grass_block','height':1}],'biome':'minecraft:plains','features':False,'lakes':False},separators=(',',':'))
    (p/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nmax-players=2\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\ngamemode=creative\ndifficulty=peaceful\nlevel-type=minecraft:flat\ngenerator-settings={generator}\ngenerate-structures=false\nallow-flight=true\nmotd=ItemGuard LITE - Manual Local Test\n',encoding='utf-8')
    save(p/'stage.json',{'root':str(p),'port':port,'controller':sha(Path(__file__)),'java':sha(JAVA),'files':{f.relative_to(p).as_posix():sha(f) for f in p.rglob('*') if f.is_file()}})
    print(p)
def run(p, minutes=30, detached=False):
    owned_root(p)
    if not 0 < minutes <= 120:
        raise ValueError('session duration must be positive and at most 120 minutes')
    with SessionLock(BASE / '.itemguard-lite-manual.lock'):
        reject_unfinished_sessions()
        return run_owned(p, minutes, detached)

def run_owned(p, minutes, detached):
    m=json.loads((p/'stage.json').read_text());assert m['root']==str(p)
    assert m['controller']==sha(Path(__file__)) and m['java']==sha(JAVA)
    for n,h in m['files'].items():
        f=p/n;assert f.resolve().is_relative_to(p.resolve()) and sha(f)==h,n
    assert not (p/'outcome.json').exists()
    if (p/'stop.request').exists():
        raise RuntimeError('session already has a stop request; stage a new session')
    free(m['port'])
    with (p/'attempt.json').open('x') as f:json.dump({'stage':sha(p/'stage.json'),'purpose':'manual','started':time.time()},f)
    log=(p/'console.log').open('w',encoding='utf-8');child=None;forced=False
    reason='error'
    try:
        child=subprocess.Popen(java_command(),cwd=p,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
        deadline=time.monotonic()+minutes*60
        save(p/'live.json',{'pid':child.pid,'controller_pid':os.getpid(),'root':str(p),'port':m['port'],'deadline_epoch':time.time()+minutes*60,'max_heap_mb':1024,'detached':detached,'close_on_stdin_eof':not detached})
        print('MANUAL_LIVE '+str(p),flush=True)
        commands=queue.SimpleQueue();stdin_closed=threading.Event()
        def console():
            try:
                for line in sys.stdin:
                    if child.poll() is not None:return
                    commands.put(line.rstrip('\r\n'))
            finally:
                stdin_closed.set()
        if not detached:
            threading.Thread(target=console,daemon=True).start()
        inbox=p/'console.in'
        while True:
            if child.poll() is not None:
                reason='child_exited';break
            if (p/'stop.request').exists():
                reason='stop_request';break
            if stdin_closed.is_set() and not detached:
                reason='stdin_closed';break
            if time.monotonic()>=deadline:
                reason='deadline';break
            if inbox.exists():
                for line in inbox.read_text(encoding='utf-8').splitlines():
                    commands.put(line)
                inbox.write_text('',encoding='utf-8')
            while not commands.empty():
                line=commands.get().strip()
                if line:
                    child.stdin.write(line+'\n');child.stdin.flush()
            time.sleep(0.1)
    except KeyboardInterrupt:
        reason='interrupted'
        raise
    finally:
        if child is not None:
            if child.poll() is None:
                try:child.stdin.write('stop\n');child.stdin.flush()
                except (OSError,ValueError):pass
                try:child.wait(timeout=60)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=15);forced=True
            try:child.stdin.close()
            except (OSError,ValueError):pass
        log.close()
        result={'pid':None if child is None else child.pid,'exit':None if child is None else child.returncode,'forced':forced,'finished':time.time(),'stop_reason':reason,'port_released':port_released(m['port'])}
        save(p/'outcome.json',result)
    return result

def stop(p, timeout=80):
    """Ask the owned controller to stop; never kill a PID taken from a stale receipt."""
    owned_root(p)
    live=json.loads((p/'live.json').read_text(encoding='utf-8'))
    if Path(live['root']).resolve()!=p.resolve():
        raise RuntimeError('live receipt root mismatch')
    (p/'stop.request').write_text('manual.py stop: task complete\n',encoding='utf-8')
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        outcome=p/'outcome.json'
        if outcome.exists() and not pid_alive(live['pid']) and port_released(live['port']):
            try:
                result=json.loads(outcome.read_text(encoding='utf-8'))
            except json.JSONDecodeError:
                time.sleep(0.1);continue
            if result['pid']!=live['pid']:
                raise RuntimeError('outcome PID mismatch')
            result['port_released']=True
            return result
        time.sleep(0.1)
    raise RuntimeError(f'cleanup not verified after {timeout}s; inspect owned controller/log at {p}')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    modes=parser.add_subparsers(dest='mode',required=True)
    stage_parser=modes.add_parser('stage');stage_parser.add_argument('--op')
    run_parser=modes.add_parser('run');run_parser.add_argument('root',type=Path)
    run_parser.add_argument('--minutes',type=int)
    run_parser.add_argument('--detach',action='store_true',help='explicit bounded human test; use stop ROOT when done')
    stop_parser=modes.add_parser('stop');stop_parser.add_argument('root',type=Path)
    args=parser.parse_args()
    if args.mode=='stage':stage(args.op)
    elif args.mode=='stop':
        result=stop(args.root);print(json.dumps(result,indent=2))
        if result.get('forced') or result.get('exit')!=0:raise SystemExit(1)
    else:
        if args.detach and args.minutes is None:parser.error('--detach requires an explicit --minutes 1..120')
        if args.minutes is not None and not 1<=args.minutes<=120:parser.error('--minutes must be 1..120')
        result=run(args.root,minutes=args.minutes if args.minutes is not None else 30,detached=args.detach)
        if result['forced'] or result['exit']!=0 or not result['port_released']:raise SystemExit(1)

if __name__=='__main__':
    main()
