"""Read-only Minecraft status and final startup checks for a manual session."""
from pathlib import Path
import hashlib,json,socket,struct,subprocess,sys,time
p=Path(sys.argv[1]);deadline=time.monotonic()+120
while time.monotonic()<deadline:
    text=(p/'console.log').read_text(encoding='utf-8',errors='replace') if (p/'console.log').exists() else ''
    if (p/'outcome.json').exists():raise SystemExit('Server already stopped')
    if 'Done (' in text:break
    time.sleep(.5)
else:raise SystemExit('Startup deadline')
assert 'Enabling ItemGuard v' in text and 'Disabling ItemGuard' not in text
assert 'ERROR' not in text and 'Exception' not in text,text
m=json.loads((p/'stage.json').read_text());live=json.loads((p/'live.json').read_text())
for n in ['paper.jar','plugins/ItemGuard-LITE.jar']:
    assert hashlib.sha256((p/n).read_bytes()).hexdigest()==m['files'][n]
def vi(n):
    b=bytearray()
    while n>127:b.append((n&127)|128);n>>=7
    b.append(n);return bytes(b)
def frame(b):return vi(len(b))+b
def exact(s,n):
    data=b''
    while len(data)<n:
        b=s.recv(n-len(data))
        if not b:raise EOFError()
        data+=b
    return data
def readvi(s):
    n=0
    for shift in range(0,35,7):
        b=exact(s,1)[0];n|=(b&127)<<shift
        if b<128:return n
    raise ValueError('VarInt too long')
with socket.create_connection(('127.0.0.1',m['port']),timeout=5) as s:
    host=b'127.0.0.1'
    s.sendall(frame(b'\x00'+vi(774)+vi(len(host))+host+struct.pack('>H',m['port'])+vi(1))+b'\x01\x00')
    length=readvi(s);assert 0<length<1048576
    assert readvi(s)==0
    size=readvi(s);assert 0<size<length
    status=json.loads(exact(s,size))
assert status['version']['protocol']==774,status
net=subprocess.run(['netstat.exe','-ano','-p','tcp'],capture_output=True,text=True,check=True).stdout
listeners=[l.strip() for l in net.splitlines() if len(l.split())>=5 and l.split()[1].endswith(':'+str(m['port'])) and l.split()[3]=='LISTENING']
assert len(listeners)==1 and listeners[0].split()[1]=='127.0.0.1:'+str(m['port']) and int(listeners[0].split()[-1])==live['pid'],listeners
result={'state':'READY_FOR_MANUAL_TEST','live':live,'status':status,'listeners':listeners,'candidate_sha256':m['files']['plugins/ItemGuard-LITE.jar'],'visual_acceptance':'PENDING','cleanup':'PENDING_SESSION_OPEN'}
print(json.dumps(result,indent=2))
