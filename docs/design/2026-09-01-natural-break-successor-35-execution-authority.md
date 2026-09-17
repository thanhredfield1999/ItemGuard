# Successor 35: external execution authority cho host restart và AV integrity loss

Ngày: 2026-09-01

## Trạng thái

`USER-APPROVED FOR OFFLINE DESIGN/TDD / BLOCKED AFTER 2026-09-08 INDEPENDENT DESIGN REVIEW`

Review Opus 5 ngày 2026-09-08 đã trả BLOCK trên design SHA-256
`7db9404cf0bf6736e3d42a87db16c65b0e3d6d72daeda1820a42b4fa944429aa`.
Corrections dưới đây chưa có fresh review PASS. Disposition:
`../reviews/2026-09-08-successor35-findings-disposition.md`.

Re-review 2026-09-08: `PASS_FOR_OFFLINE_TDD` cho revised design trước các
clarifications tiếp theo; không sealed-artifact/runtime approval. Required
interleavings được thêm dưới đây, phải kiểm chứng bằng offline regressions.

Tài liệu này khóa thiết kế kỹ thuật trước TDD. Chưa có `review-bundle-attempt-35`, chưa có receipt mới, chưa có authorization mới và chưa chạy Paper.

User đã chọn phương án guardian đầy đủ sau ba vòng design review BLOCK. Quyết
định này chỉ authorize tiếp tục thiết kế và TDD offline; không authorize tạo
runtime namespace, chạy Paper, deploy, release hoặc production.

Định danh phải tách rõ:

- successor bundle: `review-bundle-attempt-35`;
- controlled runtime namespace dự kiến: `attempt-15`;
- không dùng nhầm `attempt-35` làm runtime namespace;
- `attempt-13`, `attempt-14` và receipt 34 đã consumed, không tái sử dụng.

## Scope

Thiết kế phải bảo đảm fail-closed khi:

- parent/child process crash;
- host shutdown/restart hoặc mất điện;
- AV quarantine/xóa/thay bundle payload trước hoặc trong run;
- journal bị thiếu, truncate, partial write hoặc hai mirror lệch nhau;
- same-user child cố mở lại journal hoặc duplicate handle trong lúc run.

Thiết kế không nhằm:

- tăng availability bằng cách bypass/whitelist/disable AV;
- restore file quarantine;
- chống kernel/AV filter độc hại hoặc administrator đã compromise host;
- chứng minh malicious same-user post-reboot không thể rewrite đồng thời cả hai mirror và review authority;
- tự cấp Paper, product, release hoặc production PASS.

## Evidence dẫn tới thiết kế

### OBSERVED — Attempt 14

- Bundle 34 mất 45 manifest payload, gồm `pipeline.py`, quanh host restart/AV quarantine boundary.
- Không có terminal execution journal đủ để kết luận Paper đã hoặc chưa chạy.
- Exact accepted classification là `PAPER_EXECUTION_NOT_ESTABLISHED_BUNDLE_INTEGRITY_LOSS`.
- Handle/ACL protection trong cùng process không sống qua host restart.

### OBSERVED — source staging 34

- `pipeline.py`, `baseline.py` và `restore.py` predecessor import
`preflight_snapshot.py` từ chính bundle; successor không dùng baseline/restore
Python làm runtime authority.
- `pipeline.py` dùng `runpy.run_path`/subprocess cho `baseline.py`, `trigger.py`, `phase-runner.py`, `restore.py`.
- `phase-runner.py` đọc trực tiếp ít nhất 14 payload bundle và tự `Popen` Paper.
- `write_exclusive` hiện dùng `open('xb')`, flush và `os.fsync`; không có external journal.
- `runtime-activation.json` và `runtime-verdict.json` không tạo monotonic intent/observed history.

### VERIFIED_ON_THIS_HOST — Windows durability spike

Host/volume probe trên E: cho kết quả:

- filesystem: NTFS;
- volume serial: `1634470768`;
- `CreateFileW(FILE_APPEND_DATA, CREATE_NEW, FILE_FLAG_WRITE_THROUGH)` + `WriteFile` + `FlushFileBuffers`: PASS;
- `MoveFileExW(MOVEFILE_WRITE_THROUGH)`: PASS;
- directory handle `GENERIC_READ | FILE_FLAG_BACKUP_SEMANTICS` + `FlushFileBuffers`: FAIL, Windows code `5`;
- directory handle `GENERIC_WRITE | FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_WRITE_THROUGH` + `FlushFileBuffers`: PASS.

Microsoft documents that `FlushFileBuffers` requires `GENERIC_WRITE`, and `FILE_FLAG_WRITE_THROUGH` causes NTFS metadata changes resulting from the request to be flushed. Therefore successor must not use the reviewer's incorrect `GENERIC_READ` directory-flush primitive.

Sources:

- https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers
- https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew
- https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-movefileexw

## Architecture

### 1. `ExecutionGuardian.exe` — external execution authority

Một C#/.NET Framework executable độc lập là entrypoint duy nhất của invocation được authorize.

Trách nhiệm:

1. tự hash executable đang chạy và xác minh independent manifest/review binding;
2. xác minh hai byte-identical guardian copies tại C: và E:;
3. xác minh bundle 35, review receipt và namespace `attempt-15` trước mutation;
4. tự fingerprint original read-only, tạo private runtime clone và baseline; worker
   không nhận path/handle/ACL tới original hoặc evidence authority;
5. tạo hai journal mirror trên hai volume;
6. tạo một Windows Job Object riêng cho từng worker với
   `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_ACTIVE_PROCESS`,
   `ActiveProcessLimit=1`, không bật `BREAKAWAY_OK` hoặc
   `SILENT_BREAKAWAY_OK`;
7. là code duy nhất được gọi Win32 process creation: launch mỗi worker bằng
   `CreateProcessAsUserW` với privilege-restricted, Low Integrity primary token
   và process/thread security
   descriptors áp ngay tại create; process được tạo
   `CREATE_SUSPENDED`, assign vào job trước instruction đầu tiên, bảo vệ process
   DACL, xác minh `IsProcessInJob`, rồi mới `ResumeThread`;
8. nhận strict protocol request từ worker, kiểm state machine và chỉ ACK sau khi
   cả hai journal mirror đã durable;
9. tự derive exact command từ allowlisted role manifest và tự query process
   identity; không chạy argv/executable/PID do child tự chọn;
10. proxy stdin/stdout/stderr/wait/terminate cho Paper, JDI monitor, bot và mọi
   load-bearing worker; worker không giữ child process handle;
11. theo dõi bundle/runtime integrity; drift làm đóng toàn bộ worker jobs và
    chặn tiếp tục;
12. ghi terminal authority record khi có thể, nhưng không suy success từ việc
    thiếu record.

Guardian không import Python hoặc symbol từ executable bundle. Baseline clone,
candidate/probe placement, original re-fingerprint và safe post-state seal là
guardian operations; predecessor `baseline.py`/`restore.py` không còn là runtime
authority. Mỗi process OS
trong invocation — pipeline, trigger, phase controller, `javac`, Paper, JDI
monitor, Mineflayer bot và verifier — phải là direct child do guardian tạo và
nằm trong worker job riêng. `ActiveProcessLimit=1` làm mọi worker-side
`subprocess.Popen`/`ProcessBuilder` child attempt fail hoặc bị terminate; vì vậy
không còn đường Paper spawn hợp lệ từ Python. Nếu guardian chết, last handle của
tất cả worker jobs đóng và mọi worker bị terminate. Nếu host restart, mọi process
chết nhưng các record đã ACK phải còn trên journal mirrors.

Theo Win32, child mặc định tham gia job nhưng có thể break away nếu job bật một
trong hai breakaway limits. Successor phải chứng minh exact job flags không chứa
hai bit đó. Scratch spike trên host này đã xác nhận `ActiveProcessLimit=1` chặn
grandchild và `KILL_ON_JOB_CLOSE` terminate worker. Đây là feasibility evidence,
không thay RED/GREEN contract của sealed implementation.

#### Worker containment boundary

`ActiveProcessLimit=1` không tự là sandbox. Mọi worker phải chạy bằng primary
token do guardian tạo từ chính primary token qua
`CreateRestrictedToken(DISABLE_MAX_PRIVILEGE)` rồi đặt exact
`TokenIntegrityLevel=S-1-16-4096` (Low Integrity). Guardian xác minh primary token
type, `IsTokenRestricted==0` (vì thiết kế cấm restricting SID), exact remaining
privilege set, Low MIC label và không fallback sang unrestricted/medium token.
`PROC_THREAD_ATTRIBUTE_CHILD_PROCESS_POLICY =
PROCESS_CREATION_CHILD_PROCESS_RESTRICTED` được áp thêm; đây là defense-in-depth,
không được claim như boundary độc lập.

`WinRestrictedCodeSid (S-1-5-12)` restricting-SID design đã bị exact-host probe
`REJECTED`: `CreateRestrictedToken` thành công nhưng exact benign Windows child
thoát `0xC0000142`; thêm `WRITE_RESTRICTED`, process/thread DACL hoặc desktop
selection không sửa được. Successor không dùng restricting SID và không diễn giải
`0xC0000142` như product/runtime evidence. Exact-host replacement probe xác nhận
privilege-restricted + Low MIC child exit `0`, đọc code và ghi low-labeled scratch.

Guardian chuẩn bị ACL/MIC tối thiểu trước worker launch:

- bundle/toolchain exact files: read/execute cho user SID, không mutation;
- private runtime clone/log roots: explicit Low Integrity mandatory label và
  quyền mutation bounded cho user SID. Dynamic role logs không nằm ở đây;
- dynamic role-log roots do guardian sở hữu: Medium Integrity, DACL read-only cho
  user SID từ lúc create, guardian giữ exact write handles không inherit/share
  write/delete. Low workers chỉ phát bytes qua stdout/stderr pipes và không nhận
  path/handle role log. Mỗi stdout/stderr log leaf được relative-created với final
  DACL/MIC qua held parent capability như journal primitive, bind path, volume
  fields, FileId, owner, ACL/MIC hash vào `<ROLE>_PROCESS_CREATED_SUSPENDED`;
- original, evidence root, journal, guardian và recovery authority: giữ Medium
  Integrity hoặc cao hơn, không có handle được inherit; worker không nhận path
  original/evidence trong argv/environment/protocol;
- guardian và mọi process guardian tạo deny owner-sensitive process rights gồm
  `PROCESS_CREATE_PROCESS`, duplicate-handle, VM operation/write, create-thread,
  terminate, suspend, set-information, `WRITE_DAC`, `WRITE_OWNER`.
  Thread security descriptor deny `THREAD_TERMINATE`, `THREAD_SUSPEND_RESUME`,
  `THREAD_SET_CONTEXT`, `THREAD_SET_INFORMATION`, `WRITE_DAC`, `WRITE_OWNER`;
  guardian-held process/thread handles là capabilities duy nhất có full control.

Mỗi controlled worker process nhận protected DACL ngay trong
`CreateProcessAsUserW` process/thread SECURITY_ATTRIBUTES. Exact-host fixture đã
chứng minh một Low worker chạy/đọc/ghi đúng low root nhưng không thể `OpenProcess`
worker khác cho 11 quyền nhạy cảm (`PROCESS_TERMINATE`, create-thread,
VM-operation/write, duplicate-handle, create-process, set-quota/information,
suspend-resume, `WRITE_DAC`, `WRITE_OWNER`): tất cả trả Windows `5`; guardian-held
full handle vẫn terminate fixture được. Environment/PATH/cwd được guardian dựng
từ sealed role manifest; broad user environment, credential và unlisted
inheritable handle đều bị loại.

Guardian executable lúc entry chưa thể nhận process DACL do chính nó tạo. Nó
phải self-protect process DACL trước khi mở/giữ bundle, baseline, journal, token,
job hoặc child authority; sau đó verify denial bằng fresh `OpenProcess` theo
own PID từ chính guardian (không pseudo-handle, không spawn probe child) và chỉ
mới chuyển `BOOTSTRAP -> AUTHORITY_READY`. Không worker child tồn tại trước gate
này. Pre-existing malicious same-user sibling/injected parent là ngoài threat
model; không được claim chống lại nó. Failure token/MIC/DACL/bootstrap gate
consume không namespace nào và block trước authority acquisition/worker đầu tiên.

Low MIC không tự chặn mọi IPC/proxy tới unrelated same-user service. Assurance
cho worker code còn dựa trên exact-byte bundle, sealed no-spawn/dynamic-escape
policy, explicit handle list và no arbitrary endpoint. Arbitrary worker-code
injection hoặc pre-existing cooperative external proxy nằm ngoài scope; nếu scope
này cần mở rộng thì phải chuyển sang AppContainer/network-broker design mới và
fresh user approval/review.

Compatibility thực của Java/Node dưới privilege-restricted Low token +
active-process limit
chưa được chứng minh. Offline fixture phải chạy exact Java/Node benign roles và
attempt child spawn; controlled Paper về sau có thể fail closed nhưng không được
suy product defect. `JOB_OBJECT_MSG_ACTIVE_PROCESS_LIMIT`/`NEW_PROCESS` chỉ là
notification bổ sung vì Microsoft không đảm bảo delivery. Load-bearing proof là
kernel limits, restricted token, process DACL, handle-list và accounting query;
guardian poll `TotalProcesses/ActiveProcesses`, giữ exact process handles và
block `ROLE_CHILD_PROCESS_ACTIVITY` nếu accounting từng vượt expected one.

Sealed launch flags cho console roles được pin:
`CREATE_SUSPENDED|CREATE_UNICODE_ENVIRONMENT|EXTENDED_STARTUPINFO_PRESENT|
CREATE_NO_WINDOW`; không NEW_CONSOLE, DETACHED_PROCESS hoặc BREAKAWAY.
Mọi stdio vẫn qua explicit pipes. Microsoft Process Creation Flags nói
CREATE_NO_WINDOW không đặt console handle; không chấp nhận reviewer claim rằng
flag này bắt buộc cấp console. Exact Java/Node restricted-worker compatibility
và job accounting vẫn là required offline tests, chưa VERIFIED.
Nguồn: https://learn.microsoft.com/en-us/windows/win32/procthread/process-creation-flags

Thread-time equality chỉ là corroboration cho suspended non-execution, không
tự là proof. Proof cần no release-intent/no ResumeThread attempted, enforced
create-suspended + sole-resume authority + thread DACL denial và confirmed
termination. Bất kỳ release intent hoặc uncertainty giữ NOT_ESTABLISHED.

Source Win32:

- https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-createrestrictedtoken
- https://learn.microsoft.com/en-us/windows/win32/secauthz/mandatory-integrity-control
- https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject
- https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-setinformationjobobject
- https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_limit_information
- https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects

### 2. `review-bundle-attempt-35` — executable payload

Bundle giữ product candidate, probe, Python/Java/JS tooling và offline contracts.

Thay đổi bắt buộc:

- `pipeline.py` và `phase-runner.py` dùng strict request/ACK protocol với guardian;
- mọi mutation load-bearing phải có INTENT được dual-durable ACK trước action;
- worker không được spawn process; worker chỉ gửi allowlisted role request, còn
  guardian dual-durably commit SPAWN_INTENT rồi tự `CreateProcessAsUserW`;
- sealed role manifest định nghĩa typed parameter schema cho từng role. Mỗi field
  có fixed argv position và đúng một total validator: closed enum, bounded unsigned
  integer, canonical lowercase UUID, lowercase hex-64 hoặc member của fixed set do
  guardian sở hữu. Unknown/extra/missing/duplicate/mistyped field bị NAK;
- worker không bao giờ gửi path, executable, argv, cwd, environment hoặc process
  flag. Guardian tự tạo và giữ mọi scratch/toolchain/runtime root, map logical
  handle enum sang path đã mở/identity-checked, rồi tự expand exact argv/cwd/env;
  `<ROLE>_SPAWN_INTENT` chứa typed parameter vector và exact expanded argv/cwd/env
  trước process creation. Không có string interpolation ngoài manifest expander;
- guardian luôn truyền non-null exact full path trong `lpApplicationName`, không
  shell/PATH lookup. Sealed expander dùng một pinned Windows argv quoting algorithm
  cho `lpCommandLine`; role manifest khai target parser (`MSVC_CRT`, `JAVA`, hoặc
  exact fixture-proven parser) và cấm arbitrary raw command-line fragment. Unicode
  environment block được dựng từ fixed allowlist, sort case-insensitive, double-NUL
  terminate và dùng `CREATE_UNICODE_ENVIRONMENT`; không kế thừa broad environment
  hoặc credentials. Python/Java/Node round-trip fixtures dưới root có space/quote
  phải nhận byte-for-byte exact logical argv và exact allowlisted environment;
- ngay sau `CreateProcessAsUserW` success, guardian mint `processKey` và ghi
  `<ROLE>_PROCESS_CREATED_SUSPENDED` từ returned handle/PID/creation FILETIME,
  image identity và primary-thread baseline kernel/user FILETIME, trước mọi
  post-create gate; event chỉ chứng minh process tồn tại và vẫn suspended, không
  chứng minh job/DACL/pipe admission hoặc role code đã chạy. Guardian chỉ ghi
  `<ROLE>_PROCESS_ADMISSION_READY` sau assign-job/DACL/pipe/identity checks PASS;
  chỉ event ready mới cho phép release/ACK spawn;
- guardian dual-durably commit `<ROLE>_EXECUTION_RELEASE_INTENT` ngay trước
  `ResumeThread`. Chỉ khi `ResumeThread` trả exact previous suspend count `1`,
  guardian mới dual-durably ghi `<ROLE>_EXECUTION_RELEASE_OBSERVED`. Return khác
  hoặc API failure làm terminate khi còn suspended nếu có thể, đóng jobs và không
  bịa release observed;
- Guardian gate mọi post-release protocol dispatch và activity-event enqueue
  cho tới khi release-observed dual commit thành công. Drain threads vẫn drain
  không chờ, chỉ giữ factual counters. Commit fail đóng jobs, không dispatch.
- release-observed chỉ chứng minh guardian đã cho phép scheduling, không chứng minh
  process thread thật sự chạy. Guardian ghi đúng một
  `<ROLE>_EXECUTION_ACTIVITY_OBSERVED` chỉ sau factual source: primary
  `GetThreadTimes` kernel/user value tăng strict so với suspended baseline; hoặc
  guardian đọc ít nhất một stdout/stderr byte từ child-only pipe; hoặc nhận một
  strict post-release role-protocol request trên inherited capability. Payload bind
  source enum và exact observed delta/byte count/request hash. Process exit, port
  absence hoặc `ResumeThread` success riêng lẻ không đủ;
- nếu pre-resume gate fail sau create, guardian dual-durably ghi
  `<ROLE>_SUSPENDED_TERMINATION_INTENT`, gọi terminate qua held process handle,
  wait signaled, verify primary thread times không tăng so với baseline, rồi mới
  ghi `<ROLE>_SUSPENDED_TERMINATION_OBSERVED`. Sau đó drain EOF, seal zero/nonzero
  factual output và ghi exit. Nếu thread time tăng, termination/identity check fail
  hoặc mirror commit fail, không claim suspended non-execution;
- normal logs không được lẫn với control protocol;
- pipeline/phase child không nhận journal append handle, child process handle
  hoặc quyền ghi trực tiếp vào journal authority;
- bundle drift không được recovery bằng cách copy/restore quarantine; run bị block.

Không cố chạy toàn bộ Python từ RAM. `phase-runner.py` phụ thuộc nhiều payload Java/JS/Python/JAR trên disk, nên in-memory một vài `.py` không đóng class TOCTOU. Thiết kế thay availability claim bằng evidence-safe intent/observed protocol và guardian drift termination.

### 3. `RecoveryVerifier.exe` — independent read-only recovery authority

Một C#/.NET Framework executable khác, source/build/manifest riêng, không reference/import code từ guardian hoặc bundle.

Trách nhiệm:

- xác minh byte identity của hai recovery copies;
- đọc và kiểm cả hai journal mirror;
- kiểm canonical encoding, monotonic sequence, previous hash, record hash và longest common exact prefix;
- phân loại missing/partial/damaged/diverged/valid history;
- kiểm bundle/attempt tree read-only, original fingerprint, controlled ports và process absence;
- ghi receipt mới chỉ vào external recovery-output namespace nằm ngoài cả hai
  sealed verifier trees và ngoài `attempt-15`;
- không sửa `attempt-15`, không append/rewrite journal, không seal/restore runtime, không chạy Paper.

Nếu logical SQLite verification không thể thực hiện độc lập, verifier phải báo `DATABASE_LOGICAL_STATE_NOT_VERIFIED`; không gọi byte hash là logical PASS.

Recovery manifest embed hai root tuyệt đối đã canonical/identity-check trên E:/ và
C:/ trong member `enforcementReceiptRoots`, cùng hai exact relative path, byte length
và SHA-256 byte-identical của mỗi một trong bốn enforcement receipts trong member
duy nhất `enforcementReceipts`. Mỗi receipt có strict-schema/fixed key set, reject
unknown/duplicate key, và bind cùng exact triple:
`bundleManifestSha256`, `executionAuthorityManifestSha256`,
`recoveryManifestContentSha256`. Field cuối được tính bằng exact hàm:
parse final shipped recovery manifest bằng duplicate-key-rejecting parser, xóa
toàn bộ member `enforcementReceipts`, serialize object còn lại thành canonical
UTF-8 JSON với keys lexicographic, separators đúng `,`/`:`, không whitespace,
không LF, rồi SHA-256 exact bytes. Verifier tự tái tạo pre-image này từ final
manifest; draft file hoặc post-insertion manifest hash không hợp lệ. Recovery chỉ đọc tám exact path (bốn receipt × hai root) từ manifest, reject
reparse/extra/duplicate/stale file, yêu cầu hai copy mỗi receipt byte-identical,
kiểm length/hash/triple và không scan theo filename. Missing/mismatch đặt
`enforcementGate=ABSENT`; summary chỉ dùng
`PAPER_INTENT_ABSENT_ENFORCEMENT_UNPROVEN` khi reducer cũng xác định
`paperProcess=ABSENT`. Với process khác ABSENT, gate vắng giữ product `NOT_ISSUED`
nhưng không thay canonical process-state token; attacker-planted receipt bị ignore.

### Artifact-build DAG — không circular hash

Exact thứ tự duy nhất được phép:

1. seal final bundle manifest;
2. deterministic-build guardian source/exe cùng role/source-policy manifests, rồi
   seal final execution-authority manifest. Manifest này không chứa review receipt,
   authorization receipt hoặc recovery-manifest hash;
3. deterministic-build recovery source/exe và dựng recovery-manifest pre-image
   đã chứa hai fixed `enforcementReceiptRoots`, nhưng chưa có member
   `enforcementReceipts`;
4. chạy offline RED/GREEN gates trên exact bundle + execution authority + recovery
   pre-image, tạo bốn enforcement receipt bytes bind triple đã định nghĩa và write
   mỗi byte identity vào cả hai sealed receipt root;
5. thêm đúng member `enforcementReceipts` chứa four pair-of-path/length/hash
   entries vào
   pre-image để tạo final recovery manifest; verifier reconstruct pre-image bằng
   deletion rule, không dùng draft file;
6. seal final recovery trees/manifests; rerun receipt-binding gate read-only;
7. independent review nhận và bind SHA-256 của exact three final manifests. JSON-only
   review receipt nằm ngoài cả ba sealed trees, bind exact review output/provenance
   và `authorizedControlledNamespace=attempt-15`;
8. evidence owner read-only verify toàn DAG/hash/test receipt. Không artifact nào
   được rebuild hoặc sửa sau review; thay một byte làm review non-authoritative.

Không manifest nào hash một receipt vốn lại hash chính final manifest đó. Guardian
runtime nhận external review-receipt path từ fixed invocation contract, tự verify
receipt bytes/triple/provenance rồi journal `reviewReceiptSha256`; receipt không
được copy vào hoặc mutate sealed guardian/bundle/recovery tree.

### One-shot authorization consumption

Controlled authorization receipt là strict canonical JSON-only, external và
immutable. Exact field set bind `receiptType=CONTROLLED_PAPER_ONE_SHOT`,
`authorizedControlledNamespace=attempt-15`, SHA-256 của exact three final
manifests, exact independent review-receipt SHA-256, user authorization timestamp
và scope booleans explicit: `production=false`, `release=false`, `retry=false`.
Guardian reject unknown/duplicate field, stale hash, path replacement/reparse,
wrong namespace hoặc receipt không phải exact fixed path. Receipt không tự là
runtime/Paper PASS.

One-shot consume marker là namespace journal leaves, không phải mutable flag trong
receipt:

1. trước authority acquisition, guardian require cả E:/C: exact journal leaves và
   `attempt-15` runtime leaf absent. Một leaf/runtime collision tồn tại từ trước
   invocation block namespace `attempt-15` vĩnh viễn nhưng không phải bằng chứng
   authorization đã được consume: không retry/clean cùng namespace, chỉ một fresh
   namespace kèm fresh explicit user approval mới có thể được xét;
2. sau verify receipt, guardian create E: journal leaf rồi C: leaf theo immutable
   order. Sự tồn tại của **bất kỳ** exact leaf sau first successful create đã
   consume `attempt-15`, kể cả second create/flush/genesis fail hoặc host crash;
3. guardian ghi common `seq=0 AUTHORITY_READY` bind exact receipt identity/hashes,
   rồi `seq=1 AUTHORIZATION_CONSUMED`; chỉ sau both records dual-flush mới được tạo
   private runtime tree, baseline hoặc worker;
4. invocation sau luôn block nếu một/both journal leaves hoặc runtime leaf tồn tại;
   không cleanup, overwrite, rename hoặc resume partial namespace. Recovery phân
   loại partial genesis/authorization nhưng không cấp retry;
5. failure trước first journal create không mutate original/runtime. Với namespace
   còn thực sự fresh, một authorization invocation chưa tạo leaf có thể chỉ được
   thử lại theo fresh explicit user decision, không tự-loop/retry trong guardian.
   Collision pre-existing không phải trạng thái fresh và không thuộc ngoại lệ này.

Offline tests không tạo receipt/path thật; dùng isolated scratch names và prove
first-leaf crash, one-sided mirror, seq0-only, seq1-only và runtime-leaf collision
đều block second invocation.

## Authority paths

Dự kiến:

- guardian artifact E: `.../execution-authority-attempt-35/`;
- guardian redundant copy C: `%LOCALAPPDATA%/ItemGuardEvidence/execution-authority-attempt-35/`;
- bundle E: `.../review-bundle-attempt-35/`;
- primary journal E: `.../attempt-15-authority.execution.jsonl`;
- mirror journal C: `%LOCALAPPDATA%/ItemGuardEvidence/attempt-15-authority.execution.jsonl`;
- recovery artifact E: `.../recovery-verifier-attempt-35/`;
- recovery redundant copy C: `%LOCALAPPDATA%/ItemGuardEvidence/recovery-verifier-attempt-35/`;
- external recovery receipts E:
  `.../recovery-receipts-attempt-35/<journal-digest>-<sequence>-<invocation-id>.json`;
- external recovery receipts C:
  `%LOCALAPPDATA%/ItemGuardEvidence/recovery-receipts-attempt-35/<journal-digest>-<sequence>-<invocation-id>.json`;
- sealed enforcement receipts E:
  `.../successor-35-offline-receipts/{sole-spawn,active-process-limit,no-child-spawn,classification-hard-gate}.json`;
- sealed enforcement receipts C:
  `%LOCALAPPDATA%/ItemGuardEvidence/successor-35-offline-receipts/{sole-spawn,active-process-limit,no-child-spawn,classification-hard-gate}.json`;
- external controlled authorization receipt E:
  `.../successor-35-controlled-authorization-attempt-15.json`;
- runtime tree chỉ ở `.../attempt-15/` sau authorized invocation.

Mọi sealed artifact path phải reject symlink/reparse, replacement, unexpected
file, duplicate manifest path và volume mismatch trước mutation. Journal parent là
pre-existing broad authority root: sibling entries không thuộc journal authority
và không được scan/diễn giải. Chỉ exact journal leaf collision, reparse,
replacement hoặc identity mismatch có ý nghĩa; pre-create collision block
namespace, không tự xóa hoặc retry cùng namespace, nhưng không giả authorization
đã được consume.

Hai sealed recovery trees có exact declared file list chỉ gồm executable,
manifest và declared payload; không tree nào chứa output directory. Mỗi verifier
invocation tạo cryptographically random lowercase UUID `invocation-id`, dùng
`CREATE_NEW` cho cùng exact receipt bytes ở E:/C:, dual flush trước success và
không đọc prior output receipt như authority. Một-sided output không sửa verdict
đã tính nhưng invocation trả `RECOVERY_RECEIPT_MIRROR_DIVERGED`; không overwrite,
delete hoặc reuse path. Self-identity/tree hash phải bất biến qua repeated runs.

Chỉ guardian copy tại E: là launch target. Copy C: là independently opened
byte-identity witness/fallback input cho recovery review, không phải alternate
entrypoint. Invocation từ path khác E: bị reject trước journal creation.

## Dual journal contract

### Creation/protection

Mỗi mirror:

- exact parent directory đã tồn tại, được verify plain/non-reparse trên đúng NTFS
  volume. Guardian mở một non-inheritable parent capability bằng `NtCreateFile`
  `FILE_OPEN` với exact DesiredAccess
  `FILE_LIST_DIRECTORY|FILE_TRAVERSE|FILE_ADD_FILE|FILE_READ_ATTRIBUTES|
  FILE_WRITE_ATTRIBUTES|READ_CONTROL|SYNCHRONIZE`, ShareAccess
  `FILE_SHARE_READ`, và CreateOptions
  `FILE_DIRECTORY_FILE|FILE_WRITE_THROUGH|FILE_SYNCHRONOUS_IO_NONALERT`;
- exact journal leaf phải absent. Guardian tạo leaf tương đối qua
  `OBJECT_ATTRIBUTES.RootDirectory = parentHandle` bằng đúng một `NtCreateFile`
  `FILE_CREATE` với DesiredAccess
  `FILE_APPEND_DATA|FILE_READ_ATTRIBUTES|READ_CONTROL|SYNCHRONIZE`,
  ShareAccess `FILE_SHARE_READ`, CreateOptions
  `FILE_NON_DIRECTORY_FILE|FILE_WRITE_THROUGH|FILE_SYNCHRONOUS_IO_NONALERT`, và
  `OBJECT_ATTRIBUTES.SecurityDescriptor` chứa final protected DACL và Medium
  mandatory label ngay tại create; không có directory leaf hoặc post-create
  `SetSecurityInfo` tightening;
- final DACL deny `OWNER RIGHTS` `WRITE_DAC|WRITE_OWNER`, chỉ grant current user
  read. MIC là `S:(ML;;NW;;;ME)`. Returned create handle vẫn giữ append capability;
  mọi later same-user open xin append/write/regrant bị Windows từ chối; Low worker
  còn bị no-write-up;
- journal append handle và parent directory handle non-inheritable; journal handle
  không share write/delete. Parent `FILE_DELETE_CHILD` không thể xóa/replace journal
  khi held file handle không share delete;
- mỗi record: exact `WriteFile`, `FlushFileBuffers`;
- sau relative file create, guardian `FlushFileBuffers` exact held parent handle để
  durable directory entry; sau mỗi record chỉ `WriteFile` + file
  `FlushFileBuffers` vì không có directory metadata transition. Không mở lại cùng
  parent để flush. Exact-host probe xác nhận held handle phải có write access và
  một second write-open không được giả định sẽ qua share gate;
- guardian giữ append handles đã mở;
- worker process DACL được áp trong `CreateProcessAsUserW`
  SECURITY_ATTRIBUTES; guardian process DACL được self-protect + verify ở
  bootstrap trước authority acquisition. Cả hai deny ít nhất create-process,
  duplicate-handle, VM write/operation, create-thread, terminate, suspend,
  set-information, `WRITE_DAC`, `WRITE_OWNER`.

Nếu NTFS volume thiếu `FS_PERSISTENT_ACLS`, final ACL/MIC không canonical,
creation không phải exactly `NtCreateFile(FILE_CREATE)` với
`IoStatusBlock.Information=FILE_CREATED`, file ID đổi, hoặc bất kỳ create/open trả
shape khác, namespace bị block trước worker launch. Chỉ first successful
`FILE_CREATED` leaf mới chứng minh authorization consumption.

`AUTHORITY_READY` payload bind cả hai mirror bằng hai field không được normalize:
DWORD `volumeInformationSerial` từ `GetVolumeInformationW` và uint64
`fileIdInfoVolumeSerial` cùng `FILE_ID_INFO.FileId` từ
`GetFileInformationByHandleEx(FileIdInfo)` trên cả parent/file handle, owner SID,
canonical DACL hash và mandatory-label hash. Hai API có width/semantics khác nhau;
không được so hoặc thay thế chúng cho nhau.
Recovery reopens bằng
`FILE_FLAG_OPEN_REPARSE_POINT`, kiểm same identities/ACL/MIC trước parse. Một
Medium same-user process có thể xin `DELETE` qua parent `FILE_DELETE_CHILD` sau
guardian exit (exact-host probe: during run code `32`, after close DELETE open
PASS). Đây không phải durability/availability guarantee: missing/replaced file,
file-ID/ACL/MIC mismatch của exact journal leaf bắt buộc
`PAPER_EXECUTION_NOT_ESTABLISHED`. Full coordinated post-run rewrite của cả hai
mirrors và public review authority vẫn là declared out-of-scope non-goal; không
được dùng hash chain để claim ngược lại.

Creation order là E: rồi C:. Nếu E: create PASS nhưng C: create/flush/protection
FAIL, namespace bị consume; guardian không launch worker, không xóa/reuse orphan
mirror và không thử lại. Recovery phân loại `JOURNAL_MIRROR_DIVERGED_AT_GENESIS`.
Không có cleanup authority nào được phép biến một partial genesis thành namespace
fresh.

Exact-host probes đã bác thiết kế intermediate dùng directory finalization:
directory được tạo với OWNER RIGHTS deny thì cả `SetKernelObjectSecurity` và
`SetSecurityInfo` qua held handle đều trả Windows `5`. Successor không dùng
primitive đó. Direct final-at-create file probe đã chứng minh append+file flush
PASS, Low child exit `0`, và append/generic-write/delete/`WRITE_DAC`/`WRITE_OWNER`
reopen cùng unlink từ Low child đều bị code `5`; Medium peer append/write/regrant
bị code `5` cả trong/sau run, DELETE bị code `32` khi held và được parent cho phép
sau close; scratch cleanup qua parent PASS. Win32 binding,
SDDL/constants và return-handle width phải là sealed source + RED/GREEN tests;
không fallback sang managed `FileStream` nếu binding fail.
Probe tiếp theo đã xác nhận exact relative-create path trên E:/NTFS: parent
`NtCreateFile(FILE_OPEN)` với access mask ở trên PASS, child
`NtCreateFile(FILE_CREATE, RootDirectory=parentHandle)` trả
`IoStatusBlock.Information=FILE_CREATED`, append/file flush/parent flush đều PASS,
và scratch cleanup PASS. Contract test phải đọc lại effective SD và FileIdInfo;
không suy chúng chỉ từ create status.

### Mirror commit rule

- Cùng exact record bytes được ghi vào E: và C:.
- ACK chỉ được gửi sau khi cả hai write + flush PASS.
- Không claim atomic transaction xuyên volume.
- Nếu mirror 1 commit nhưng mirror 2 fail, guardian không ACK, đóng job và ghi failure event
  `JOURNAL_MIRROR_DIVERGED` nếu mirror còn ghi được. Đây không phải recovery summary token:
  recovery giữ `journalAuthority=VALID` nhưng `historyComplete=false` rồi dùng total-summary
  derivation; không có token riêng cho mid-run divergence.

### Canonical record

Mỗi line là strict canonical UTF-8 JSON, LF terminated. Record body có:

- `schemaVersion`;
- `seq`;
- `event`;
- `namespace` = `attempt-15`;
- `runToken`: canonical lowercase UUID v4, được guardian sinh bằng CSPRNG sau khi
  cả hai leaf đã `FILE_CREATED` nhưng trước `seq=0`, rồi bind immutable với namespace,
  authorization receipt và ba manifest SHA-256; mọi record sau phải dùng đúng value;
- `guardianPid` và `guardianStartFileTime`;
- `hostBootIdentity` (observational binding, không phải secret);
- `bundleManifestSha256`;
- `executionAuthorityManifestSha256`;
- `reviewReceiptSha256`;
- `previousRecordSha256`;
- `payload`;
- `recordSha256` tính trên canonical body không chứa chính field này.

Parser reject duplicate key, unknown field, non-canonical encoding, sequence gap, hash mismatch và trailing partial line.

Normative codec v1 áp dụng cho journal, control frames và recovery-manifest
pre-image: UTF-8 không BOM, reject invalid UTF-8 và unpaired surrogate, không
Unicode normalization. Mọi object sort keys theo ordinal Unicode scalar value;
duplicate key bị reject trước sort. String escape chỉ quote/backslash bằng
`\"`/`\\`, U+0000..001F bằng lowercase `\u00xx`; ký tự còn lại raw UTF-8,
không escape slash hoặc non-ASCII. Array giữ thứ tự. Chỉ boolean, string,
object, array và integer trong [0,18446744073709551615]; integer decimal ngắn
nhất, không dấu/leading zero/exponent/fraction. Null không thuộc codec v1.
Schema từng message/event có quyền giới hạn type/range hẹp hơn. Không whitespace
ngoài string. Journal thêm đúng một LF; frames/manifests không thêm LF.
`recordSha256` hash canonical object sau khi xóa đúng top-level member
`recordSha256`, không placeholder và không LF. Verifier parse strict, dựng lại
canonical bytes rồi yêu cầu exact equality với input trước hash; không dùng
default JSON serializer hoặc fixed-length suffix slicing. Guardian/recovery
implement độc lập, cùng frozen vectors, không shared assembly.

Codec implementation constraints: root phải là object; empty object/array hợp lệ
ở đúng vị trí schema. Key order là lexicographic unsigned bytes của decoded key
được encode strict UTF-8, không phải `String.CompareOrdinal` UTF-16. Prefix ngắn
sort trước. Decoder chỉ chấp nhận escape quote, backslash và lowercase
`\u00xx` cho control U+0000..001F; reject mọi escape khác. Raw control bị reject.
UTF-8 decode dùng exception fallback (`new UTF8Encoding(false, true)`) hoặc
strict byte parser: reject overlong, surrogate-range encoding, scalar >10FFFF,
truncated sequence và leading BOM; U+FEFF bên trong string là dữ liệu hợp lệ.
Integer parse trực tiếp digits sang UInt64 với checked overflow, không qua float.
Document cap 1 MiB, tối đa 32 nested object/array levels (root=1), tối đa 4096
members/elements mỗi container; frames vẫn cap nhỏ hơn 64 KiB. Kiểm bound trước
allocation/recursion. Hash fields lowercase hex-64; journal thiếu recordSha256
thì reject, không im lặng hash body. Normative frozen vectors được tạo bằng TDD
trước implementation, phải seal path/length/SHA trước interoperability gate;
chưa có vector artifact không tự là defect của pure encoding contract.

Recovery trước hết tính longest common exact, canonical, hash-valid prefix của hai
mirrors. State-machine classifier chỉ dùng prefix đó và không bao giờ hạ một fact
đã được guardian chứng minh trong prefix vì record sau thiếu/corrupt. Nếu hai
mirrors không có common trusted prefix chứa fact thì fact không established.

Record `seq=0` dùng `previousRecordSha256` là đúng 64 ký tự `0`; mọi record sau
bind SHA-256 của exact complete prior line bytes gồm LF.

### Security claim chính xác

Hash chain + dual mirror phát hiện corruption, truncation, reorder và divergence trong threat model vận hành. Nó không phải chữ ký/HMAC và không chống được adversary có thể rewrite đồng thời journal, manifests và review authority.

Reviewer đề xuất dùng public delegation ID làm chain seed để “chống rewrite” bị `REJECTED`: public value không tạo unforgeability. Delegation ID/review receipt hash chỉ bind provenance. Runtime anti-mutation dựa vào existing-handle capability, DACL, process protection và mirror separation; malicious admin/kernel nằm ngoài scope.

## Protocol/state machine

Guardian là writer duy nhất. Mọi append đi qua một serialized writer queue;
allocate seq, prior-line hash, write/flush E: rồi write/flush C: thuộc cùng một
serialized operation. Mirror failure làm writer terminal-failed, không nhận
append tiếp hoặc ACK success. Chỉ protocol-speaking worker gửi request và block
đến ACK/NAK; silent roles không có control channel.

### Capability transport

- Sealed role manifest bắt buộc khai `controlProtocolPolicy=SPEAKER|SILENT`.
  SILENT roles không có control pipes, request reader hoặc ACK deadline. SPEAKER
  roles nhận hai anonymous pipes: request worker→guardian và response
  guardian→worker. Role không khai policy bị reject trước spawn.
- Guardian giữ request-read/response-write ends non-inheritable. Exact
  `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` là union của ba child stdio ends
  (`stdin-read`, `stdout-write`, `stderr-write`) và, chỉ với SPEAKER, hai control
  ends (`request-write`, `response-read`). Mỗi listed handle inheritable;
  `bInheritHandles=TRUE`, `STARTF_USESTDHANDLES` và
  `EXTENDED_STARTUPINFO_PRESENT` bắt buộc. STARTUPINFO std handles phải khớp
  exact union; missing/extra handle block trước resume. Không broad inheritance.
  `stdinPolicy=none` vẫn cấp stdin-read, guardian đóng stdin-write để tạo EOF.
  Tất cả worker descendants bị cấm bởi `ActiveProcessLimit=1`.
- Control frames không dùng stdin/stdout/stderr. Normal process output chỉ được
  guardian drain vào bounded role logs; không có push/data pipe thứ hai.
- Frame là `uint32-le length` + exact canonical UTF-8 JSON bytes, tối đa 64 KiB.
  Schema cố định gồm `schemaVersion`, monotonic `requestId`, allowlisted
  `requestType`, `role`, `payload`. Unknown/duplicate key, length 0/oversize,
  partial body, trailing bytes hoặc out-of-order request bị reject.
- Guardian mint một `processKey` canonical lowercase UUID v4 ngay sau mọi successful
  `CreateProcessAsUserW` và trước post-create gate; `<ROLE>_PROCESS_CREATED_SUSPENDED`
  cùng mọi lifecycle/stdio event sau
  bắt buộc mang exact key trong payload, bound immutable với role/PID/creation
  FILETIME. Guardian-initiated `PIPELINE` cũng nhận key theo rule này dù không có
  requester/control ACK.
- ACK/NAK frame bind exact `requestId` và exact SHA-256 của request bytes. Với
  `ROLE_SPAWN` do SPEAKER request, guardian chỉ ACK sau durable
  `<ROLE>_PROCESS_ADMISSION_READY` và ACK duy nhất này còn chứa `processKey` đã
  mint/bind; mọi ACK khác không chứa key. Nếu create/check/event commit không hoàn
  tất trong role deadline (tối đa 30 giây), guardian NAK/đóng jobs thay vì ACK intent.
  Worker không action nếu chưa nhận ACK matching cả hai giá trị.
- Guardian đọc đủ request frame trước ACK, worker write đầy đủ request rồi đọc
  response; mỗi worker tối đa một outstanding request. Reads/writes loop trên
  partial transfer với deadline; không dựa pipe buffer chứa được toàn frame.
  Test frame 64 KiB trên buffer nhỏ phải complete, không timeout/deadlock.
- Mỗi request có deadline monotonic cụ thể trong role manifest; deadline tối đa
  30 giây cho journal ACK. Timeout, unexpected EOF, broken pipe, malformed frame, duplicate
  request hoặc unsolicited bytes làm guardian NAK khi có thể, ghi
  `PROTOCOL_REJECTED` nếu journal còn usable và đóng mọi worker jobs.
- SILENT role exit không thể gây control EOF failure vì không có control channel.
  SPEAKER clean close cần terminal-close protocol được đặc tả và test riêng trước
  implementation transport: sau khi hoàn tất mọi request trước, SPEAKER gửi
  `CONTROL_CLOSE` như request cuối. Guardian validate không pending operation,
  journal `<ROLE>_CONTROL_CLOSE_ACCEPTED`, rồi ACK matching request hash và ID.
  Worker chỉ sau ACK mới đóng request-write và exit trong deadline manifest.
  Sau accepted-close guardian không nhận thêm frame; unexpected bytes, partial
  frame, duplicate close hoặc exit deadline expiry fail-closed. EOF sau close
  accepted không tự là exit; vẫn phải wait/drain/seal/exit như lifecycle chung.
  EOF trước close accepted fail-closed dù process exit 0; không suy verdict từ
  scheduling order giữa EOF và wait notification. ACK write failure vẫn failure,
  không biến close accepted thành proof worker nhận ACK.
- Pipe handle là runtime capability, không phải cryptographic identity. Threat
  model cùng-user được giữ bằng explicit handle inheritance + guardian process
  deny `PROCESS_DUP_HANDLE`; named pipe/TCP/file polling không được dùng làm
  control channel.

### Interactive stdio proxy

Guardian giữ toàn bộ controller-side OS pipe handles của process role. Worker
không bao giờ nhận raw stdin/stdout/stderr handle của Paper, JDI monitor, bot hoặc
verifier. Ngay sau `CreateProcessAsUserW` thành công, guardian đóng copies của
child-side ends trong guardian (`stdout-write`, `stderr-write`, `stdin-read`), chỉ
giữ `stdout-read`, `stderr-read` và—khi policy cho phép—`stdin-write`. Nếu
`stdinPolicy=none`, guardian đóng `stdin-write` ngay để child nhận EOF; không giữ
một writer vô chủ làm child hoặc drain thread treo.

- Spawn manifest khai báo riêng cho từng role: `stdinPolicy`, `stdoutPolicy`,
  `stderrPolicy`, max frame, max total bytes, idle deadline và allowed controller
  role. Role không khai báo policy bị deny mọi stdio request.
- Controller gửi `ROLE_STDIN_WRITE` trên control pipe với target `processKey`,
  là UUID v4 canonical đã nhận trong matching `ROLE_SPAWN` ACK và còn bound với
  current allowed controller/role/PID/creation FILETIME; bịa, stale hoặc cross-role
  key bị NAK. Payload là object closed-schema `{operation:"PAPER_GRACEFUL_STOP"}`;
  worker không gửi raw bytes, hash hay `runToken`. Guardian tự expand operation
  allowlisted cho state hiện tại, tính byte count/SHA-256 rồi journal chúng. Với
  Paper graceful stop, template duy nhất là exact UTF-8
  `say ITEMGUARD_NATURAL_BREAK_STOP_BOUNDARY <runToken>\nstop\n`.
- Guardian dual-durably commit `<ROLE>_STDIN_WRITE_INTENT` chứa byte count/hash,
  rồi `WriteFile` vào private role-stdin handle; successful exact write được ghi
  `<ROLE>_STDIN_WRITE_OBSERVED` trước ACK. Partial write, timeout hoặc broken pipe
  đóng tất cả jobs. Tối đa một graceful-stop payload cho mỗi Paper role instance;
  unsolicited/rate-exceeded payload là `PROTOCOL_REJECTED`.
- Với role `PAPER`, exact event names là `PAPER_STDIN_WRITE_INTENT`,
  `PAPER_STDIN_WRITE_OBSERVED` và `PAPER_OUTPUT_SEALED`; recovery không normalize
  chúng từ prose hoặc generic aliases.
- Guardian drain stdout/stderr liên tục trên dedicated internal threads để OS pipe
  không backpressure process. Exact bytes được ghi vào bounded role log do guardian
  sở hữu; limit mặc định 64 MiB/stream, overflow đóng jobs. Drain threads không giữ
  journal/control locks và không chờ controller.
- Controller đọc output chỉ bằng allowlisted paginated
  `ROLE_OUTPUT_READ(processKey, stream, cursor, maxBytes<=32KiB)` với cùng key
  validation trên existing
  response control pipe. Response chứa base64 exact bytes, `nextCursor`, `eof` và
  SHA-256 của exact prefix `[0,nextCursor)`; canonical encoded frame vẫn phải <=64
  KiB. Cursor phải bằng prior `nextCursor`, không âm/skip/rewind; guardian phục vụ
  từ immutable captured prefix dưới read lock ngắn. Request này không journal và
  không thay đổi role/process lifecycle. Controller poll theo bounded role interval;
  chậm đọc không ngăn guardian drain, không đóng healthy role và không tạo output
  delivery deadline cạnh tranh với journal ACK.
- Stdout/stderr không journal từng chunk. Guardian chỉ seal output sau cả ba điều:
  process wait handle signaled, guardian đã đóng `stdin-write`, và cả stdout/stderr
  read ends trả broken-pipe EOF sau khi drain hết buffered bytes. Sau đó guardian
  flush role logs, ghi đúng một `<ROLE>_OUTPUT_SEALED` chứa exact byte count và
  SHA-256, exact log identity tuple và final file length riêng từng stream, rồi mới
  ghi `<ROLE>_EXIT_OBSERVED` với exit code.
  Nếu bất kỳ stream có byte count >0 hoặc final primary thread time tăng, matching
  `<ROLE>_EXECUTION_ACTIVITY_OBSERVED` phải đã dual-durable trước output seal;
  nonzero seal/positive thread delta không có prior activity event làm suffix
  invalid. Suspended-termination branch yêu cầu zero bytes và zero thread delta;
  nếu không, không emit suspended-termination-observed.
  Deadline EOF/flush là role-specific; timeout đóng jobs nhưng không được bịa
  OUTPUT_SEALED hoặc clean EXIT_OBSERVED.
- Controller nhận marker/log bằng cursor-pull frames và yêu cầu stdin qua control
  frames; semantics `STOP_BOUNDARY_SENTINEL` và graceful Paper shutdown của
  predecessor được giữ mà controller không sở hữu process/stdio capability.

### Sole-spawn enforcement

- Worker chỉ được request một `role` enum đã khóa trong sealed role manifest;
  không được truyền arbitrary executable, argv, cwd, environment hay flags.
- Guardian tự derive executable/argv/cwd/env whitelist và exact expected hashes,
  dual-durably ghi `<ROLE>_SPAWN_INTENT`, rồi mới `CreateProcessAsUserW`. Ngay sau
  suspended-create guardian ghi `<ROLE>_PROCESS_CREATED_SUSPENDED`; identity/job/
  DACL/pipe checks pass mới ghi `<ROLE>_PROCESS_ADMISSION_READY`, rồi guardian phải ghi release intent trước
  resume và release observed sau successful resume như contract trên.
- Mỗi worker job có active-process limit 1; bất kỳ worker-side child creation nào
  bị Windows chặn/terminate. Guardian kiểm `TotalProcesses`/`ActiveProcesses` và
  completion-port process notifications; unexpected process event là
  `PROTOCOL_REJECTED` + close jobs.
- Required role lifecycle tuples gồm ít nhất `PIPELINE`, `TRIGGER`, `PHASE_CONTROLLER`,
  `JAVAC`, `PAPER`, `JDI_MONITOR`, `MINEFLAYER_BOT`, `TELEMETRY_VERIFIER`,
  `BRANCH_VERIFIER`, `PUBLICATION_VERIFIER`, `DB_SEAL`, `NBT_VERIFIER`. Mọi
  guardian-spawned role bắt đầu bằng `SPAWN_INTENT`,
  `PROCESS_CREATED_SUSPENDED`, rồi đi đúng một nhánh:
  (a) release: `PROCESS_ADMISSION_READY`, `EXECUTION_RELEASE_INTENT`,
  `EXECUTION_RELEASE_OBSERVED`, optional factual `EXECUTION_ACTIVITY_OBSERVED`,
  `OUTPUT_SEALED`, `EXIT_OBSERVED`; hoặc
  (b) pre-release abort: `SUSPENDED_TERMINATION_INTENT`,
  `SUSPENDED_TERMINATION_OBSERVED`, `OUTPUT_SEALED`, `EXIT_OBSERVED`. Zero-byte
  stream vẫn seal với byte count 0 và SHA-256 của empty bytes.
- Sealed `runtime-source-policy.json` chia exact bundle-manifest paths thành hai
  disjoint sets, mỗi entry bind relative path, byte length và SHA-256:
  `firstPartyParsedSources` và `opaqueHashPinnedPayloads`. Union phải bằng toàn bộ
  executable/source payload mà role manifests có thể load; overlap, omission,
  extra path hoặc hash mismatch fail closed. First-party Python/Java/JS source
  phải qua full static no-child/dynamic-escape policy. Third-party `node_modules`,
  Paper/plugin/toolchain JAR/class/exe và generated schema payload là opaque,
  không được giả là AST-analyzed; assurance sole-spawn của chúng chỉ đến từ
  restricted-token, process-DACL, child-policy và Job Object kernel receipts.
- `no-child-spawn` receipt strict-schema embed exact policy SHA-256, sorted
  first-party coverage paths/hashes và sorted opaque exclusions paths/hashes.
  Recovery recompute policy/coverage against sealed bundle manifest; absent,
  stale hoặc partial coverage không mở classification gate. Static contract là
  defense-in-depth, không thay restricted token/kernel enforcement; parser không
  hiểu một first-party file/call site thì fail closed.

Forbidden Python surface gồm import/call/attribute hoặc alias của `subprocess`,
`os.system`, `os.popen`, `os.exec*`, `os.spawn*`, `os.posix_spawn*`,
`multiprocessing` process/pool/manager, `asyncio.create_subprocess_*`,
`asyncio.subprocess`, `pty.spawn`, `webbrowser`, `ctypes`/`cffi`/`pywin32` process
or shell APIs, COM/WMI shell launch, và dynamic code/import (`eval`, `exec`,
`compile`, `__import__`, `importlib`) trên runtime workers. Forbidden Java surface
gồm `ProcessBuilder`, `Runtime.exec`, `Desktop.open/edit/print/mail/browse`, JNI,
JNA, FFM/native linker, `System.load*`, scripting engine, reflection/method handle
đến process/native APIs. Forbidden Node surface gồm `child_process` mọi sync/async
API, `cluster`, `worker_threads`, native addon/FFI, COM/WMI/PowerShell/WSH launch,
dynamic `eval`/`Function`/`vm`/computed import/require. Shell/batch/PowerShell/VBS
runtime source bị cấm hoàn toàn. Unknown extension hoặc generated runtime source
không có sealed parser/hash policy bị reject. Only guardian source được chứa
allowlisted `CreateProcessAsUserW`; recovery source không được chứa process launch.

Minimum events:

1. `AUTHORITY_READY`;
2. `AUTHORIZATION_CONSUMED`;
3. `BUNDLE_VERIFIED`;
4. `PIPELINE_SPAWN_INTENT`, `PIPELINE_PROCESS_CREATED_SUSPENDED`,
   `PIPELINE_PROCESS_ADMISSION_READY`, `PIPELINE_EXECUTION_RELEASE_INTENT`,
   `PIPELINE_EXECUTION_RELEASE_OBSERVED`;
5. `BASELINE_BEGIN`, `BASELINE_COMMITTED`;
6. `TRIGGER_SPAWN_INTENT`, `TRIGGER_PROCESS_CREATED_SUSPENDED`,
   `TRIGGER_PROCESS_ADMISSION_READY`, `TRIGGER_EXECUTION_RELEASE_INTENT`,
   `TRIGGER_EXECUTION_RELEASE_OBSERVED`;
7. `TRIGGER_MUTATION_INTENT`, `TRIGGER_MUTATION_OBSERVED`;
8. `TRIGGER_OUTPUT_SEALED`, `TRIGGER_EXIT_OBSERVED`;
9. cho mỗi phase: `PHASE_CONTROLLER_SPAWN_INTENT`,
   `PHASE_CONTROLLER_PROCESS_CREATED_SUSPENDED`,
   `PHASE_CONTROLLER_PROCESS_ADMISSION_READY`, `PHASE_CONTROLLER_EXECUTION_RELEASE_INTENT`,
   `PHASE_CONTROLLER_EXECUTION_RELEASE_OBSERVED`, `PHASE_BEGIN`;
10. mọi successful create dùng `<ROLE>_SPAWN_INTENT`,
    `<ROLE>_PROCESS_CREATED_SUSPENDED`; chỉ admission/release branch thêm đúng một
    `<ROLE>_PROCESS_ADMISSION_READY`, rồi `<ROLE>_EXECUTION_RELEASE_INTENT`,
    `<ROLE>_EXECUTION_RELEASE_OBSERVED`, `<ROLE>_OUTPUT_SEALED`,
    `<ROLE>_EXIT_OBSERVED`. Gate-fail branch dùng suspended-termination pair thay
    admission/release. Rule áp dụng cho `JAVAC`, `PAPER`,
    `JDI_MONITOR`, `MINEFLAYER_BOT` và từng verifier role thật sự dùng;
    `EXECUTION_ACTIVITY_OBSERVED` là optional chỉ khi chưa có factual activity;
    nếu output/thread/protocol source factual xuất hiện thì event là mandatory
    trước `OUTPUT_SEALED`. Seal operation lấy factual counters sau drain EOF và
  final thread-times; nếu activity chưa được journal thì serialized writer
  commit activity trước seal, không để drain thread tự append. Failure ở activity
  commit cấm seal/exit. Nếu activity đã có, không emit duplicate hoặc đòi adjacency.
  Pre-release abort dùng suspended-termination pair thay
    toàn bộ release/activity events;
11. `PHASE_COMPLETED`, `PHASE_CONTROLLER_OUTPUT_SEALED`,
    `PHASE_CONTROLLER_EXIT_OBSERVED`;
12. `RESTORE_BEGIN`, `RESTORE_COMPLETED`;
13. `FINAL_SEAL_BEGIN`, `FINAL_SEAL_COMPLETED`;
14. `PIPELINE_OUTPUT_SEALED`, `PIPELINE_EXIT_OBSERVED`;
15. terminal guardian-authored `RUN_COMPLETED` hoặc `RUN_FAILED`.

Mọi `PROCESS_CREATED_SUSPENDED` bắt buộc có payload `processKey` theo minting rule
ở protocol; mọi matching lifecycle/stdio payload sau phải giữ chính key đó. Release
branch bắt buộc có exactly one `PROCESS_ADMISSION_READY` sau created-suspended và
trước release-intent; suspended-termination branch cấm ready/release. Reducer
validate ordering/cardinality này nhưng ready không tự nâng trục process/code/output/exit.
Mọi `PROCESS_CREATED_SUSPENDED` bắt buộc có at most one matching release-intent,
release-observed, output-seal và exit cho cùng `processKey`, PID và creation
FILETIME. `EXECUTION_RELEASE_OBSERVED` bắt buộc có exact prior matching
`EXECUTION_RELEASE_INTENT`; release-observed không có intent, duplicate hoặc xuất
hiện trước created-suspended làm suffix invalid. Missing exit được recovery phân loại
`<ROLE>_EXIT_NOT_ESTABLISHED`; không được collapse thành clean exit hoặc
`FAIL_BEFORE_PAPER`. Operational event (`TRIGGER_MUTATION_*`, `PHASE_*`, stdio)
không thay thế process lifecycle event.

Mọi `<ROLE>_EXIT_OBSERVED` bắt buộc có exactly one preceding matching
`<ROLE>_OUTPUT_SEALED` cho cùng `processKey`, PID và creation FILETIME. Exit thiếu
seal, seal sau exit hoặc duplicate seal/exit là invalid lifecycle suffix và product
`NOT_ISSUED`; classifier vẫn giữ mọi process-execution fact đã có trong longest
common valid prefix. Recovery không dùng current role-log bytes để hợp thức hóa
hoặc bác bỏ một contemporaneous journal transition. Nếu current log còn tồn tại,
verifier kiểm exact identity/length/hash từ seal event; missing/replaced/mismatch
emit `<ROLE>_LOG_ARTIFACT_NOT_ESTABLISHED` và product `NOT_ISSUED`, nhưng không
downgrade `paperProcess`, `paperCodeExecution`, `paperOutput` hoặc `paperExit`. Product verifier cần
log content thì phải fail closed khi artifact không established. Không được chọn
nhánh thuận lợi hơn từ các event còn lại.

Failure events có thể gồm:

- `BUNDLE_INTEGRITY_LOST`;
- `RUNTIME_STATIC_INTEGRITY_LOST`;
- `PROTOCOL_REJECTED`;
- `PROCESS_IDENTITY_REJECTED`;
- `ROLE_CHILD_PROCESS_ACTIVITY` với exact `role`, process identity và observation
  source chỉ khi guardian có factual notification/accounting evidence;
- `JOURNAL_MIRROR_DIVERGED`;
- `CONTROLLED_PORT_OPEN`.

Rules:

- Guardian ACK INTENT chỉ sau dual durable commit.
- Pipeline/phase không action trước matching ACK.
- `PAPER_PROCESS_CREATED_SUSPENDED` chỉ do guardian ghi từ returned process handle
  ngay sau successful create, với `GetProcessTimes`, `QueryFullProcessImageNameW`
  và primary thread vẫn suspended. `PAPER_PROCESS_ADMISSION_READY` chỉ được ghi
  sau `IsProcessInJob`, process/thread DACL và pipe/job checks PASS; release bị cấm
  trước ready. Gate fail phải đi suspended-termination branch với cùng processKey.
- `PAPER_EXECUTION_RELEASE_OBSERVED` chỉ do guardian ghi sau exact
  `ResumeThread(primaryThreadHandle)==1`. Nếu dual commit của observed event thất
  bại sau resume, common prefix giữ release intent và classifier không được suy
  non-execution hoặc established execution.
- Guardian đóng job khi protocol/drift/mirror gate fail.

## Recovery classifications

- Verifier luôn xuất bốn trục strict enum trước summary:
  `paperProcess = ABSENT|SPAWN_INTENT_ONLY|CREATED_SUSPENDED|
  SUSPENDED_TERMINATION_INTENT_UNCERTAIN|TERMINATED_SUSPENDED|
  RELEASE_INTENT_UNCERTAIN|RELEASED|EXITED`,
  `paperCodeExecution = NOT_APPLICABLE|NON_EXECUTION_ESTABLISHED|
  NOT_ESTABLISHED|ESTABLISHED`,
  `paperOutput = NOT_APPLICABLE|NOT_ESTABLISHED|ESTABLISHED`,
  `paperExit = NOT_APPLICABLE|NOT_ESTABLISHED|ESTABLISHED`. Chỉ common valid
  prefix được nâng một trục; event chỉ có ở một mirror không nâng fact.
- Exact transition reducer, theo thứ tự event trong common valid prefix:
  - no spawn -> `ABSENT/NOT_APPLICABLE/NOT_APPLICABLE/NOT_APPLICABLE`;
  - `PAPER_SPAWN_INTENT` ->
    `SPAWN_INTENT_ONLY/NOT_APPLICABLE/NOT_APPLICABLE/NOT_APPLICABLE`;
  - `PAPER_PROCESS_CREATED_SUSPENDED` ->
    `CREATED_SUSPENDED/NOT_ESTABLISHED/NOT_ESTABLISHED/NOT_ESTABLISHED`;
  - `PAPER_SUSPENDED_TERMINATION_INTENT` -> process
    `SUSPENDED_TERMINATION_INTENT_UNCERTAIN`; ba state fields còn lại không đổi;
  - valid `PAPER_SUSPENDED_TERMINATION_OBSERVED` -> process
    `TERMINATED_SUSPENDED`, code `NON_EXECUTION_ESTABLISHED`; output/exit chưa đổi;
  - `PAPER_EXECUTION_RELEASE_INTENT` -> process `RELEASE_INTENT_UNCERTAIN`;
  - `PAPER_EXECUTION_RELEASE_OBSERVED` -> process `RELEASED`; code chưa đổi;
  - factual `PAPER_EXECUTION_ACTIVITY_OBSERVED` -> code `ESTABLISHED`;
  - valid `PAPER_OUTPUT_SEALED` -> output `ESTABLISHED`;
  - valid `PAPER_EXIT_OBSERVED` -> process `EXITED`, exit `ESTABLISHED`.

  Suspend-termination và release branches loại trừ nhau. Output seal yêu cầu exact
  prior branch fact và exit yêu cầu output established. Activity không được xuất
  hiện trên suspended-termination branch. Event vi phạm order/cardinality/branch
  làm suffix invalid; reducer dừng trước offending event, không apply một phần.
  Không event nào được hạ trục đã established.

  `NOT_APPLICABLE` chỉ dùng khi chưa có Paper process object
  (`ABSENT|SPAWN_INTENT_ONLY`). Từ `CREATED_SUSPENDED` trở đi output/exit là
  `NOT_ESTABLISHED` cho tới matching event. Chỉ exact
  `SUSPENDED_TERMINATION_OBSERVED` nâng code thành
  `NON_EXECUTION_ESTABLISHED`; chỉ exact activity event nâng code thành
  `ESTABLISHED`.

- Exact summary derivation:
  - nếu `paperProcess=ABSENT` và enforcement gate thiếu, summary là
    `PAPER_INTENT_ABSENT_ENFORCEMENT_UNPROVEN`;
  - nếu `paperProcess=ABSENT`, all four enforcement gates valid nhưng
    `historyComplete=false`, summary là `PAPER_INTENT_ABSENT_HISTORY_INCOMPLETE`;
  - chỉ `paperProcess=ABSENT`, all four enforcement gates valid và
    `historyComplete=true` mới được `FAIL_BEFORE_PAPER`;
  - mọi state khác dùng duy nhất canonical token
    `PAPER_STATE_<paperProcess>__CODE_<paperCodeExecution>__OUTPUT_<paperOutput>__EXIT_<paperExit>`
    với enum spelling y nguyên ở trên. Không alias, prose normalization hoặc special
    summary khác.

  `historyComplete` là history metadata riêng, không thay bốn process axes.
  True chỉ khi hai mirrors byte-identical toàn bộ, có valid common genesis,
  không partial/invalid suffix và exactly one cuối cùng guardian terminal
  `RUN_COMPLETED|RUN_FAILED` bind `finalSeq` bằng seq của chính terminal record.
  Terminal event cấm mọi append sau nó; absent-Paper terminal chỉ hợp lệ sau
  guardian đã đóng intake, chặn mọi spawn và settle mọi in-flight spawn request.
  Không có terminal, một-sided suffix hoặc suffix bị truncate => false. Không
  được suy history completeness từ receipt build-time hoặc ports hiện tại.

  Vì summary là pure function của bốn trục cộng enforcement/history metadata,
  mọi valid prefix—including khoảng
  suspended-termination intent/observed, release intent/observed, activity,
  output-seal và exit—có đúng một expected token. Code/output/exit established vẫn
  không tự là product PASS; product luôn `NOT_ISSUED` nếu thiếu product receipts
  hoặc có drift/failure.

- Non-Paper roles dùng cùng reducer với prefix `<ROLE>` và canonical summary
  `<ROLE>_STATE_<process>__CODE_<code>__OUTPUT_<output>__EXIT_<exit>`; không emit
  `FAIL_BEFORE_PAPER`.
- missing/damaged/gap/diverged suffix: truncate analysis tại longest common valid
  prefix và chạy reducer/summary function trên sau authority precheck dưới đây;
- bundle/runtime drift tại bất kỳ thời điểm nào: product/release `NOT_ISSUED`.
- factual `ROLE_CHILD_PROCESS_ACTIVITY`: close all jobs và product `NOT_ISSUED`;
  nếu worker `CreateProcess` fail nhưng guardian không có factual OS evidence,
  recovery không được invent subreason; classification dựa trên actual lifecycle
  và missing product receipts, vẫn `NOT_ISSUED`.

Absence không tự chứng minh non-execution nếu journal authority không valid.

## AV handling

- Không whitelist, disable, pause hoặc bypass Bitdefender.
- Không restore quarantine bundle 34.
- Full manifest verify trước guardian launch, trước pipeline launch và trước mỗi ACK cho disk-based load-bearing transition.
- Guardian dùng `ReadDirectoryChangesW` và metadata poll tối đa mỗi 250 ms; full
  manifest hash ngay trước mỗi load-bearing spawn/transition và sau mỗi worker
  exit. Watch notification/mismatch đóng jobs. Không claim phát hiện một
  delete/replace/restore transient hoàn tất giữa hai observations; window này là
  residual TOCTOU và cấm nâng product verdict.
- Drift làm dual-journal event nếu còn ghi được, đóng job và block.
- Không claim handle sharing chặn minifilter; Attempt 14 đã falsify claim đó.
- Không claim thiết kế loại bỏ mọi check/open TOCTOU; intent/observed classification là fail-closed response khi window xảy ra.

### Authority precheck and total summary

Trước reducer bốn trục, recovery xác định strict
`journalAuthority=VALID|NO_COMMON_GENESIS|MIRROR_DIVERGED_AT_GENESIS|LEAF_IDENTITY_MISMATCH`.
Nó là metadata tiền-kiểm, không phải trục process và không được suy từ ports hay
receipt build-time. Summary total theo thứ tự duy nhất:

1. `NO_COMMON_GENESIS` hoặc `LEAF_IDENTITY_MISMATCH` ->
   `PAPER_EXECUTION_NOT_ESTABLISHED`.
2. `MIRROR_DIVERGED_AT_GENESIS` -> `JOURNAL_MIRROR_DIVERGED_AT_GENESIS`.
3. Chỉ `VALID` mới chạy reducer/history summary tại 920–946: nếu
   `paperProcess=ABSENT` và `enforcementGate=ABSENT` ->
   `PAPER_INTENT_ABSENT_ENFORCEMENT_UNPROVEN`; nếu gate valid nhưng history incomplete
   -> `PAPER_INTENT_ABSENT_HISTORY_INCOMPLETE`; nếu cả hai valid -> `FAIL_BEFORE_PAPER`.
   Mọi process state khác dùng canonical `PAPER_STATE_...`, kể cả khi enforcement
   gate absent; product vẫn `NOT_ISSUED`.

Không có valid input nào được trả hơn một summary token. FileId/ACL/MIC mismatch
là `LEAF_IDENTITY_MISMATCH`; E-create/C-create failure là
`MIRROR_DIVERGED_AT_GENESIS`.

## TDD slices — offline only

1. `dual_journal_writer`: RED duplicate/missing/partial/mirror-fail; GREEN canonical append + dual flush + no ACK on one-sided commit.
   RED race fixture chứng minh post-hoc DACL có cửa sổ peer lấy write handle; GREEN
   dùng exact relative `NtCreateFile` contract, không post-create tightening. Matrix
   expected status: pre-create open -> Win32 2/3; pre-create planted leaf làm
   guardian `FILE_CREATE` fail/block namespace-without-authorization-consumption;
   post-create Medium/Low append/write/regrant
   -> 5; Low delete/unlink -> 5; Medium delete -> 32 khi held và có thể PASS sau
   guardian close. Recovery reject replacement bằng bound FileId/ACL/MIC/genesis.
   Held append/file flush/parent flush phải PASS. Test kiểm đúng pointer-width
   invalid handle, exact `IoStatusBlock.Information=FILE_CREATED`, effective SD,
   `FileIdInfo` và no second write-open của held parent.
2. `journal_genesis_flush`: RED mở lại held parent để write-flush trả sharing code
   32; GREEN relative child create rồi flush bằng chính held parent handle, không
   second open. Abrupt-kill fixture sau genesis phải giữ exact journal entry/seq=0;
   power-cut thật vẫn ngoài claim.
3. `journal_classifier`: RED missing/truncated/gap/hash/divergence, exit-before-seal,
   missing/duplicate output seal, admission-ready before created-suspended,
   release-intent without prior admission-ready, duplicate admission-ready,
   admission-ready on suspended-termination branch,
   created-suspended treated as execution, release-observed treated as activity,
   nonzero output without activity event và suspended-termination with nonzero
   thread/output. GREEN exact four-axis state
   machine classification and monotonic common-prefix facts.
4. `job_kill_on_close`: RED harmless worker creates grandchild/survives guardian
   death; GREEN `ACTIVE_PROCESS=1`, no breakaway, suspended assign-before-resume
   và `KILL_ON_JOB_CLOSE` chặn grandchild/terminate worker.
   GREEN còn phải chứng minh no-restricting-SID token (`IsTokenRestricted==0`) cùng
   exact privilege/MIC shape, process
   DACL denial, no unrelated same-user proxy handle, và child-policy attribute;
   failure bất kỳ gate nào block trước resume.
5. `capability_transport`: RED named/shared endpoint accepts peer, inherited
   descendant handle, truncated/oversize/reordered frame và stalled mirror;
   GREEN explicit anonymous-pipe handle list, strict frame, matching ACK and
   deadline closes jobs.
6. `role_parameter_schema`: RED per role cho unknown/extra/missing field, arbitrary
   argv/path, non-canonical UUID/hex, traversal, out-of-range integer và logical
   root không thuộc guardian-owned set, raw quote/backslash fragment, PATH lookup
   và undeclared environment. GREEN exact typed vector expand thành manifest
   argv/cwd/env byte-identical, Python/Java/Node fixtures round-trip dưới paths có
   space/quote, và exact expansion được journal trước spawn.
7. `sole_spawn`: RED rogue Python attempts fixture child before ACK; GREEN worker
   job blocks child, only guardian creates allowlisted role after durable intent.
   Thêm Java/Node benign exact-toolchain compatibility fixtures và child-attempt
   fixtures. Nếu completion/accounting factual signal có mặt, guardian journal
   `ROLE_CHILD_PROCESS_ACTIVITY`; nếu signal vắng, test chỉ assert child absent,
   role outcome factual và product `NOT_ISSUED`, không đòi invented subreason.
8. `interactive_stdio_proxy`: RED guardian giữ child-side write end làm EOF treo,
   Paper stop payload lệch một byte/được gửi lần hai, output burst lớn hơn pipe
   buffer, controller ACK trước admission-ready hoặc khi create/gate fail, delayed
   cursor polling, invalid skip/rewind cursor, oversized pull, raw stdin bytes/hash/
   runToken hoặc unknown operation, và role-log overflow.
   GREEN chứng minh guardian-initiated PIPELINE và SPEAKER-spawned role đều journal
   processKey immutable ngay sau create, gate-fail đi suspended termination, ACK chỉ
   sau admission-ready, forged/stale/cross-role processKey NAK, và every role có
   ordered SPAWN_INTENT/PROCESS_CREATED_SUSPENDED/PROCESS_ADMISSION_READY/
   EXECUTION_RELEASE_INTENT/EXECUTION_RELEASE_OBSERVED/OUTPUT_SEALED/EXIT_OBSERVED
   trên release branch; guardian tự tái dựng exact stop template từ journal-bound
   UUID-v4 runToken sau accepted enum operation tối đa một lần, INTENT trước
   write/OBSERVED trước ACK, dedicated drains capture every byte, cursor-pull trả
   đúng prefix hash mà không block drains, chỉ overflow log đóng jobs.
9. `load_bearing_roles`: RED missing JDI/bot/verifier lifecycle hoặc trigger
   output seal; GREEN exact branch-complete lifecycle per used role.
10. `process_identity`: RED PID/path/job/start-FILETIME mismatch, created-suspended
    incorrectly classified as execution, `ResumeThread` return 0/2/-1, và crash
    after release-intent before release-observed. GREEN guardian-derived identity,
    exact resume return 1, factual thread/output/protocol activity, and classifier
    distinguishes suspended, released, uncertain and established code execution
    without downgrade. Separate terminate-while-suspended fixture proves exact
    wait + unchanged thread times + zero output before non-execution claim.
11. `bundle_drift_watchdog`: RED scratch payload delete/replace goes unnoticed;
   GREEN watch/poll drift event + fixture job termination within declared bound.
12. `recovery_isolation`: RED verifier references bundle code, writes attempt tree
   hoặc writes inside sealed verifier copy; GREEN standalone read-only verifier
   chạy hai lần, dual external receipts distinct, và hashes/file sets của cả hai
   sealed verifier copies bất biến sau mỗi lần.
13. `enforcement_receipt_binding`: RED planted fifth receipt, stale bundle triple,
   post-insertion manifest hash, missing/duplicate receipt và partial static
   coverage; GREEN verifier recompute exact manifest-with-member-removed pre-image,
   validate four byte-identical E:/C: receipt pairs at fixed manifest-bound roots,
   paths/lengths/hashes/triples và exact source-policy coverage.
14. `classification_hard_gate`: RED no-intent journal without enforcement receipt
    yields `FAIL_BEFORE_PAPER`; GREEN yields
    `PAPER_INTENT_ABSENT_ENFORCEMENT_UNPROVEN`.
15. `crash_matrix`: abrupt worker/guardian exit, partial genesis/record, one
   mirror unavailable, crash at every boundary created-suspended → admission-ready
   → release-intent → ResumeThread → release-observed → activity-observed →
   output-seal → exit, và tại mỗi suspended-termination intent/observed boundary.
    Every prefix must map to exactly one four-axis classification; no execution or
    non-execution fact may be invented or downgraded.
16. `deterministic_build`: pin exact compiler SHA/version and use
    `/deterministic+ /optimize+ /target:exe /pathmap:<scratch>=/_/`; compile twice
    in different roots, exact exe hash equality, execute E: authority + C:/E:
    verifier copies according to their path policy.
17. existing bundle contracts + sealed source-policy no-child-spawn contracts;
    exact manifest partition/count/hash, per-language RED fixtures cho từng
    forbidden first-party family và dynamic alias/import, opaque exclusions được
    receipt khai đúng nhưng không giả AST coverage, unknown parser/extension và
    policy omission fail closed; không Paper/server Java invocation.
18. `restricted_worker_boundary`: RED unrestricted/Medium worker mở original,
    evidence hoặc sensitive handle trên controlled same-user fixture process;
    GREEN privilege-restricted Low token bị deny mutation/11 process rights, vẫn
    read/execute bundle và mutate only explicit Low private runtime. Fixture khóa
    rejected restricting-SID `0xC0000142` để ngăn regression về design đã falsify.
19. `one_shot_authorization`: RED authorization receipt unknown/duplicate field,
    stale manifest/review hash, wrong namespace, replacement/reparse/fixed-path
    violation, pre-existing runtime leaf, one-sided journal leaf, seq0-only, seq1-only
    và second invocation; GREEN validates exact receipt before first create, creates
    E: then C:, mints runToken only after both `FILE_CREATED`, dual-flushes seq0/seq1,
    and blocks every later invocation for the namespace without cleanup/retry.

Mỗi slice phải có observed RED vì đúng missing behavior trước GREEN. Scratch roots chỉ ở `%LOCALAPPDATA%/Temp` hoặc dedicated `E:/AI.WORK/scratch-*`, được cleanup. Không chạm attempt 13/14, bundle 34, quarantine metadata hoặc original Paper server.

## Review/evidence gates

Trước bất kỳ controlled runtime authorization:

1. seal exact guardian source/exe/manifest và dual-copy hashes;
2. seal exact bundle 35 manifest/tree;
3. seal exact recovery source/exe/manifest và dual-copy hashes;
4. lưu RED/GREEN receipts và Windows durability contract;
5. independent Opus 4.7 xhigh adversarial review exact artifacts;
6. exact JSON-only review receipt bind cả ba manifests và `authorizedControlledNamespace=attempt-15`;
7. independent evidence-owner verify tests/hashes/receipts;
8. user authorization riêng mới được chạy Paper.

Một independent reviewer có thể review cả ba exact artifacts; authority separation là path/process/handle/code separation, không được giả vờ rằng ba delegation ID tự tạo cryptographic independence. Implementer không tự cấp review PASS.

## Preliminary Opus findings disposition

- B1 circular authority: `CONFIRMED`; sửa bằng external guardian + independent recovery.
- B2 journal absent: `CONFIRMED`; sửa bằng dual durable single-writer journal.
- B3 durability gap: `CONFIRMED_WITH_CORRECTION`; directory flush cần `GENERIC_WRITE`, không phải `GENERIC_READ`.
- B4 AV TOCTOU: `CONFIRMED`; phản hồi là verify/ACK/watchdog/job termination, không claim in-memory vài script giải quyết toàn bundle.
- B5 recovery in bundle: `CONFIRMED`; recovery verifier tách compiled artifact, read-only.
- B6 public chain seed chống rewrite: `REJECTED`; public delegation ID chỉ provenance binding.
- B7 same Python/AV domain: `CONFIRMED`; guardian/recovery chuyển sang C# và có byte-identical copies trên C:/E:, nhưng residual AV/kernel risk vẫn fail-closed.

## Non-claims

- Chưa implement successor 35.
- Chưa có sealed artifact/review PASS/authorization.
- Chưa chạy Paper hoặc server Java.
- Chưa chứng minh host power-loss durability bằng power cut thật; hiện mới có docs + exact-host Win32 scratch evidence.
- Chưa chứng minh Bitdefender sẽ không quarantine C# artifacts.
- Không có ItemGuard runtime/product/release/production PASS.
