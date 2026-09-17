# Offline journal byte-chain and scanner — parent evidence

## Implemented scope

- `CommonJournalChain`: consumes an ordered pair of exact LF-terminated mirror records, validates independent recovery codec/record hash, sequence from zero and previous full-line hash INCLUDING LF; permanent stop retains the common prefix count.
- `CommonJournalScanner`: reads finite immutable snapshots in bounded chunks without line normalization/skipping. Distinguishes both EOF, invalid/unmatched/partial suffix, read error, and record-limit exhaustion; never promotes a one-sided record. Caller owns stream lifetime and actual file identity/provenance.
- Fixed `CanonicalDecoder.VerifyJournalLine`: journal cap includes LF and is checked BEFORE body allocation/copy, matching independent guardian encoder `MaxDocumentBytes - 1` body limit.

These layers do NOT validate event schemas, trusted genesis, process identity, terminal semantics, durability/ACL/handle authority, or historyComplete. BothEof on two equally truncated snapshots can be correct byte-level EOF, and MUST NOT establish complete history. No live journal/receipt/attempt or Paper is read/launched by these tests. Scanner assumes exclusive access to finite immutable standard blocking Stream snapshots from the beginning; live growing pipes/files are outside scope.

## Observed tests

- Chain compiled stub failed first common record, then implementation passed.
- Decoder cap regression: `journal-line-cap-red.log` records actual assertion failure accepting line Max+1. Fix passed (`journal-line-cap-green.log`); later coverage added exact scanner and independent writer boundaries.
- Scanner initial assertion failed (`journal-scanner-red.log`); then first complete-pair path passed. An implementation was written prematurely before that first test execution, removed, and reimplemented after the recorded RED; no claim that the earlier premature write followed TDD.
- Read-error and budget assertions failed with two concrete failures (`journal-scanner-errors-red.log`); minimal handling passed. Invalid same-stream input failed separately before guards were added.
- Supplementary green-first coverage: all 50,745 one-byte mutations rejected without escaped input exceptions; 1,095 truncation probes; 25 chain scenarios, 8 line-boundary paths, 9 scanner scenario groups. These are not all separate RED-GREEN cycles.
- Fresh full offline runner and source SHA inventory: `journal-scanner-final-verification.json`. Final record is regenerated after all code/test edits.

## Independent review and parent disposition

- `journal-chain-opus-raw.json`: PASS_FOR_OFFLINE_CHAIN; raw envelope byte-equality verified after preservation.
- `journal-scanner-opus-raw.json`: PASS_FOR_OFFLINE_SCANNER; raw envelope byte-equality verified after preservation. Includes full recovery decoder and re-encoder. Review input/source SHA bindings in `journal-scanner-review-scope.json`; implementation unchanged after review, extra tests only.
- Both raw results subtype success and `claude-opus-5` in modelUsage, with ancillary Haiku. No runtime/whole-guardian approval.
- Cap mismatch: CONFIRMED at direct decoder entry point, not a chain acceptance defect. Fixed with RED-GREEN and writer/scanner parity coverage.
- Potential malformed-input exception escape: REJECTED for current standard byte parser. Integer overflow becomes FormatException; UTF-8 sequence parser validates scalars before strict conversion. Parent read the complete relevant methods and ran mutation/invalid-UTF8/overflow probes. No blanket catch for OOM/crypto/provider failures added; these are not evidence of clean EOF.
- Missing-field indexer concern: REJECTED as current bug. Return type is `IDictionary`; absent entries return null and are rejected. Missing-field tests lock current behavior. A future return-type refactor must preserve rejection semantics.
- Eager 1 MiB buffers: bounded and accepted within offline scope; not a throughput benchmark.
- Missing writer and scanner-at-cap review evidence: CONFIRMED coverage gap in bounded input, closed with direct independent writer/reader/scanner tests after review. No implementation change.

## Remaining gates

P0 is still open: strict event/request payload schemas and exact trusted identity/receipt binding, full terminal/history classifier, serialized real dual-durable writer, Win32 pipe/token/job/ACL process ownership and interruption/drift evidence. A fresh exact fixture/bundle review and separate authorization remain required before Paper. P1-P6 are not marked complete by these infrastructure tests.

Existing `.hermes/WORKING_STATE.md` is stale; an earlier protected-checkpoint write timed out. This is a test/review report, not an alternate-path operational checkpoint. No retry of that blocked checkpoint write was made here.
