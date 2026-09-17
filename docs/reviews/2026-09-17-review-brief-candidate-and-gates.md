You are performing an ADVERSARIAL, READ-ONLY review of a Minecraft plugin repository.

REPOSITORY: E:/AI.WORK/ItemGuard  (Java 21, Paper 1.21.11, Maven; Python harness under scripts/ and tools/lite-runtime/)
You may ONLY read. Do not edit, create, move or delete any file. Do not run builds, tests, servers, git
or network commands. If something cannot be decided from the source, say so instead of guessing.

CONTEXT, AND WHY THIS REVIEW EXISTS
Two adversarial reviews already ran today against earlier rounds of this code; both found real
defects, and the second found three HIGH ones inside gates the first round of fixes had just added.
Their reports and my adjudications are:
  docs/reviews/2026-09-17-independent-review-of-the-fixes.md   + 2026-09-17-review-adjudication.md
  docs/reviews/2026-09-17-review-of-the-post-review-changes.md + 2026-09-17-review2-adjudication.md
Read the two adjudications first: they say what I accepted, what I rejected and why. This review
covers what has changed SINCE then, which has never been reviewed by anyone.

THE PROJECT'S RECURRING DEFECT CLASS, because it is what to hunt for
  * a check that cannot fail: it passes for a reason unrelated to the thing it claims to establish
    (a setting that is inert at its own floor; a hash comparison that compares nothing; a scan that
    stops early and reports zero; a gate whose driver reads the exit code while the gate writes its
    verdict to a file and exits 0 either way);
  * a claim in a document, comment, log line or listing that the code does not honour;
  * an assertion built on the wrong field, so it matches the envelope of a log line rather than the
    content it is supposed to inspect;
  * a measurement that is true only for the shape of data the harness happens to produce.

WHAT TO REVIEW, IN THIS ORDER

1. THE NEW GATES, AND WHETHER EACH ONE CAN FAIL. This is the priority. For every check below, state
   what it actually proves and construct the input that would make it pass while being false:
   - `scripts/check_no_hardcoded_vietnamese.py`: the statement-level language-flag rule (including
     the "English literal beside the Vietnamese one" case), the class-constant-pool parser and
     `UnparsedClass`, the `SCANNED_INSIDE_EXEMPT` / `SKIPPED_INSIDE_EXEMPT` declarations,
     `referenced_from_outside` (wildcard imports, bare names) and the undeclared-reference check.
   - `scripts/check_listing_copies.py`: the record rules, the truncated-hash rule and its `known`
     set, the `HISTORIC` line exception, the archive handling and `stale_jars_inside`.
   - `scripts/run_release_runtime_gates.py`: the verdict reading, the verifier wiring, what a gate
     reports when its verdict file is missing or malformed, and whether the runner can report PASS
     for a gate that did not run.
   - `tools/lite-runtime/verify.py`: `bot_messages`, `is_plugin_answer`, `privacy_facts`,
     `adjudicate_privacy`, `verify_multi` — and whether the privacy assertions can be satisfied by a
     plugin that discloses the actor name.
   - `scripts/verify_custody_rows.py` and the tests beside each of these files.

2. THE PRODUCT CHANGES SINCE THE LAST REVIEW. Each of these was written today; attack the claim in
   its comment:
   - `ItemGuard.onEnable`: the `general.enabled` guard moved between `loadConfigManagers()` and
     `loadRuntimeManagers()`, followed by `getServer().getPluginManager().disablePlugin(this)`.
     What still runs? What does a plugin manager think afterwards? Is `onDisable` safe from here?
   - `ItemListener`: `tellIdentityNotReady` / `tellCorruptTag` / `tellBeingTagged` / `notifyOnce`
     (per-player throttle, map cap with an oldest-half prune). Can a refusal still be silent? Can
     the prune drop an entry it just wrote? Is the map bounded under a burst?
   - `CraftListener` + `CraftOutputPolicy`: two refusal messages chosen by action, and the
     `cancel-untracked-craft-output` switch. Attack the action-to-message mapping and the claim that
     the switch cannot open the tagged branches.
   - `ContainerListener.cooldownKey`: the `DoubleChest` branch.
   - `AntiDupeSettings.detectionCooldown`: the interval clamp before multiplying, the two-cycle
     floor, and whether the comment still describes what the code does.
   - `ItemSqliteRepository`: the epoch clause inside the duplicate-suppression `NOT EXISTS`, gated on
     a non-zero window, plus `adoptIdentity` writing `created_at = 0` and `CheckCommand` printing
     `unknown (adopted)` when it is 0. Attack the SQL (what does it suppress that it should not, and
     what does it fail to suppress?) and attack the marker's durability.
   - `MessageManager` + the four message files: the new keys and the fallback pinning.
   - `commands/ItemCodeInput.java`: the English exception text.

3. THE CLAIMS IN `docs/release/LITE_RELEASE_GATES.md` AGAINST THE CODE. For each row that says PASS,
   say whether the stated evidence could have been produced by a run that was in fact broken. Name
   the row, the claim, and the gap. Do not check the rows marked OPEN for completeness of the product.

GROUND RULES
- Every finding needs file:line and the decisive lines quoted. No location, no finding.
- State the consequence for a player, an admin, or a buyer — not just the code smell.
- Say whether you verified it from source, inferred it, or could not check, and what would settle it.
- If a claim holds up under attack, say so explicitly and name the attack that failed. A review that
  finds nothing must be as specific as one that finds something.
- Severity: CRITICAL (item/data loss, security, permanent breakage, or a false claim to buyers),
  HIGH (a wrong pass/fail in a release gate, or wrong behaviour in a normal flow), MEDIUM, LOW.
- No features, no refactors. Minimum corrections only.

REPORT (Vietnamese; keep paths, identifiers and quoted code in English)
  1. Tóm tắt: số lượng theo mức độ + một câu về độ tin cậy.
  2. CRITICAL / HIGH / MEDIUM / LOW — mỗi mục: file:line, đoạn code, hậu quả, cách tái hiện hoặc lý
     do không tái hiện được, mức kiểm chứng, cách sửa tối thiểu.
  3. Đã tấn công mà không phá được: theo từng mục ở trên.
  4. Những gì bạn KHÔNG kiểm được trong phiên này.
