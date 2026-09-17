# Successor-35 role-command run-token validation — offline only

Date: 2026-09-13

## Scope

`RoleCommandSchema.Expand` is the typed, allowlisted `PIPELINE` command expansion seam. It accepts the current journal-bound run token as a typed field and must reject a UUID that has merely canonical text formatting but is not UUID-v4.

This is a bounded contract correction. It does not launch a process, create an `ExecutionGuardian`, authorize a worker, create a runtime namespace, run Paper, or change ItemGuard product code.

## Observed root cause

The schema named its requirement `canonical UUID v4 form`, but used only `Guid.TryParseExact(token, "D")` plus a canonical string round-trip. That accepts correctly formatted UUIDs from versions other than v4 and variants other than RFC-4122.

The authority admission and recovery prefix already require the v4 version nibble and RFC-4122 variant. The role-command seam therefore had a weaker predicate and could have expanded an argv token that no valid `NativeDualJournalAdmission` receipt could mint.

## TDD receipt

Two focused test cases were added first:

- a canonical UUID text with version nibble `1`;
- a UUID with v4 version but non-RFC-4122 variant nibble `7`.

`bash tools/execution-authority/build/role-command-schema-test.sh` was RED with:

```text
FAIL System.Exception: non-v4 run token cannot enter the allowlisted role argv
```

The minimal correction requires canonical `D` text, version nibble `4`, and variant nibble in `8`, `9`, `a`, or `b`, matching the admission receipt predicate.

## Verification

- Focused: `bash tools/execution-authority/build/role-command-schema-test.sh`
  - `ROLE_COMMAND_SCHEMA checks=4 failures=0`
- Aggregate: `bash tools/execution-authority/build/offline-test.sh` exited 0.
- `git diff --check` exited 0.

## Limit

This is command-vector schema validation only. It does not prove that an argv is launched, that a worker token is restricted at process creation, that a child is assigned to a Job before resume, or that any natural block break/publication path works.
