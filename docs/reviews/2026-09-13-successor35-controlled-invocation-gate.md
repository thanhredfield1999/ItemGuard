# Successor-35 controlled invocation gate — offline only

Date: 2026-09-13

## Scope

`ControlledInvocationGate` is the first host-side composition boundary from an
externally stored controlled authorization receipt to a consumed dual journal.
It deliberately ends immediately after both durable records:

1. fixed-leaf, native-identity-stable receipt read;
2. strict canonical schema and exact binding validation;
3. one-shot namespace preflight and dual native journal creation;
4. dual `AUTHORITY_READY` at sequence 0;
5. dual `AUTHORIZATION_CONSUMED` at sequence 1.

It exposes the journal-minted canonical UUID-v4 `runToken` only after both
records commit. It does not create the runtime leaf/tree, baseline, worker,
process, pipe, Job, Paper server, or mutate ItemGuard product code.

## TDD evidence

The focused contract was created before `ControlledInvocationGate.cs` existed.
The RED probe was:

```text
error CS2001: Source file 'ControlledInvocationGate.cs' could not be found
```

The GREEN contract verifies nine behaviors:

- invalid/replaced receipt identity or schema fails before either journal leaf;
- valid receipt creates and consumes the dual journal in order;
- the returned receipt namespace and committed count are exact;
- returned `runToken` has canonical UUID-v4 shape;
- both journal streams are byte-identical;
- `AUTHORITY_READY` permanently binds the fixed receipt FILE_ID_INFO identity and four validated manifest/review hashes;
- a subsequent invocation under the same namespace is rejected.
- a post-genesis failure cannot return a partially consumed invocation;
- both journal leaves remain after that failure, so the namespace stays consumed.

A separate one-shot admission regression adds a journal-directory collision:
no mirror leaf may be created when the first journal exact name already resolves
to a directory.

## Verification

```text
bash tools/execution-authority/build/controlled-invocation-gate-test.sh
CONTROLLED_INVOCATION_GATE checks=6 failures=0

bash tools/execution-authority/build/offline-test.sh
NATIVE_ONE_SHOT_NAMESPACE checks=5 failures=0
CONTROLLED_INVOCATION_GATE checks=6 failures=0
... aggregate exit 0

git diff --check
exit 0
```

## Exclusions

This does not prove sealed artifact/review receipt acquisition, fixed receipt
path ownership policy beyond the disposable native-leaf contract, actual
runtime namespace creation, worker launch, `CreateProcessAsUserW`, process
identity/DACL/pipe admission, recovery execution, natural block break, Paper
runtime, release readiness, publication, deployment, or production safety.
The privilege-dependent suspended-worker launch remains excluded because this
host lacks `SeIncreaseQuotaPrivilege`.
