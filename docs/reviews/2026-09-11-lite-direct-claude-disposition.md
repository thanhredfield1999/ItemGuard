# Direct Claude account review — ItemGuard LITE

User explicitly approved invoking the currently configured Claude account directly. No Hermes/Codex delegation route was used and no auth/config was changed.

- First Claude CLI invocation reached 25-turn limit without a verdict: `lite-direct-claude-review.json`. It is not approval.
- Bounded stdin review completed, exit 0, is_error=false, actual model `claude-opus-5`: `lite-direct-claude-review-bounded.json`.
- Inputs: `lite-direct-review-input.txt`, hashes `lite-direct-review-input-manifest.json`; all 11 input files matched after the review.
- Reviewer wording: `PASS_FOR_OFFLINE_CANDIDATE (conditional)`, including high-severity findings. Parent disposition: **BLOCK_FOR_RUNTIME / NOT_RELEASE_READY**, not unconditional PASS.

## Parent triage

H1 is credible from direct current-source tracing: requireOpen and enqueue are separate; executor stays open until after queued connection close; a late-admitted operation can run behind connection close and recovery can open a new connection before owner lock release. SerialDatabaseExecutor uses a normal single-thread executor and has no admission serialization that disproves this race. A deterministic regression is still required; no runtime reproduction is claimed. Do not apply the reviewer's suggested blanket closed check blindly: pre-close admitted work currently must drain, so distinguish closing admission from connection-close completion.

H2 reentrant call and H3 shutdown timeout require comparison against pre-existing contracts/call paths; not accepted as newly introduced defects without that evidence. Other concerns (diagnostics, admission fairness, GUI callback behavior and missing packaging assertions) remain triage items.

Reject suggested api-version lowering as an automatic fix: 1.21.11 is deliberate; older compatibility is compile-only, and reflection/serialization runtime is unverified. Retaining Full internal classes is explicitly documented and not alone a defect.

No source changes, rebuild, server boot, fixture consumption, deployment or public upload in this review turn. Current JAR remains the previously documented candidate; keep runtime and public release blocked until shutdown/recovery race is resolved and reviewed.
