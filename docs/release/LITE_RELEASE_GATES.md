# LITE release gates

Scope: English-first, Paper 1.21.11 / Java 21. SpigotMC is the marketplace, not a vanilla Spigot
compatibility claim. No restore/teleport entrypoint; tracking, history, read-only GUI menu and
NOTIFY only. LITE writes PDC identity tags and scans inventories, so it is a non-destructive
writer, not a read-only plugin — only the menu is read-only.

This checklist implements `.hermes/plans/2026-09-14_023455-itemguard-lite-release-roadmap.md`. It
does not supersede IG-R016 and **authorizes nothing to be published**.

Current candidate for every row below:
`8c0e540ec646ffec38cd1409cb69b8c339d1d83f7ba499841bbe6866d624ce4a` (5,122,519 bytes) — the jar in
`release/spigot-upload/`, byte-identical to `release/upload/` and `target/ItemGuard-LITE-1.0.0.jar`
per `release/SHA256SUMS.txt`. The receipt field `runtime_verified` is a hardcoded constant written by
`scripts/package_lite.py` and must not be cited as runtime evidence in either direction.
Runtime evidence for this candidate, as files rather than prose: `run/runtime-gates-20260917-230108.json` (7/7 gates, 442 s, one fresh fixture per gate) and `run/version-matrix-20260917-230115.json` (9/9 versions).

**Six candidates in one day, and why that is the honest number.** Each of them is superseded by a
round of fixes that a check or a review found: `e11ada6a…` was superseded by the C1/C2/C3 work,
`a952d161…` was superseded by the first review of it, `94eb0dad…` was superseded by the fixes to that
review's findings, `28aed104…` was superseded by the fixes to the findings of the second review,
`80fc610b…` was superseded by the fixes to the third review's findings, `00b40fb1…` was superseded
by turning the craft refusal mapping into a function that can be tested rather than a condition
written twice, and `8c0e540e…` is the one this checklist is bound to. Three reviews ran against
code that had already been fixed at least once,
and each round found defects the previous one had introduced or missed: the first found 11 in the
C1/C2/C3/H1–H5 fixes (`docs/reviews/2026-09-17-review-adjudication.md`), the second found 18 in the
fixes that answered them, including three HIGH defects in the gates those fixes had just added
(`docs/reviews/2026-09-17-review2-adjudication.md`), and the third found 3 HIGH / 7 MEDIUM / 6 LOW in
this candidate's own code and in those same gates
(`docs/reviews/2026-09-17-review3-adjudication.md`). Every row below is bound to the hash above;
a rebuild voids all of them.

| Gate | Current evidence | Status / required next step |
|---|---|---|
| Java test gate | **855/855** tests, 0 failures / 0 errors / 0 skipped — `docs/reviews/lite-package-build.log`, run inside `scripts/package_lite.py`. The four new ones are the craft refusal mapping; the flaky `SqliteLossJournalTest` thread assertion was made deterministic in the same edit (it failed once under load, which is a gate failing for a reason unrelated to the code) | PASS offline; not Paper proof. A source edit voids it: rebuild with `scripts/finalize_release_candidate.py` |
| Artifact permission/default surface | `python scripts/verify_lite_artifact.py` → `VERDICT ARTIFACT_CONSISTENT_OFFLINE_ONLY` on the jar above | PASS offline-only by the verifier's own words |
| No Vietnamese the jar would print | **Source:** `check_no_hardcoded_vietnamese.py` → 0 findings over 180 files, 2 of them inside an exempt directory because LITE reaches them, 14 command files declared unreachable one by one. A literal is cleared only if the *statement* carries the language flag, or an English literal sits beside it, or its enclosing block header does. **Artifact:** `… --jar` → 0 over **364 class entries**, parsing each constant pool to the end, with every one of the 442 entries either scanned or in a named bucket (364 scanned, 73 in FULL-only directories, 5 declared bilingual). Two of the scanned classes are in exempt directories because the source pass declares LITE reaches them, so the artifact half no longer skips the file the source half went to the trouble of declaring. Of the five bilingual classes, each has to be present in the jar and still carry a Vietnamese literal, so the declaration cannot rot into a free pass | PASS offline. 14 tests, including the ASCII-only banner that shipped, the random-byte case, a sixth bilingual class that must be declared on purpose, a declared file inside an exempt directory that must be scanned, and the entry-count invariant. 22 tests in the file |
| Listing copies agree with the jar | `check_listing_copies.py` → **0** files quoting an older artifact, 6 recognised records named. Truncated hashes are read, every retired candidate is declared one line at a time so a quote of a build whose full digest survives nowhere is still drift, the exception for a past build requires a phrase that asserts something (`void` no longer matches inside `avoid`), archives are opened, a `.jar` inside an archive is hashed against the shipping jar, and a bare jar of the shipping name is hashed too instead of being taken on the word of `SHA256SUMS.txt` | PASS offline. 20 tests |
| Script suites | `python -B -m unittest discover -s scripts -p 'test_*.py'` → **67/67 OK**; `tools/lite-runtime/test_contracts.py` → **107/107 OK** | PASS offline |
| One command for the whole handover | `python scripts/finalize_release_candidate.py` — repin, rewrite the digest in the listing and the sums file, regenerate preview and handoff, then run every offline gate. It refuses to rewrite a digest the listing does not own: a second, different 64-hex value in there is a claim about another artifact, and silently making it agree is manufacturing agreement rather than reporting it | Added 2026-09-17 after the fourth drift of the same claim |
| Read-only command boundary | `LiteReleaseBoundaryTest`: wildcard permissions cannot dispatch restore/teleport or access DB/server; tab list exact | PASS unit, not Paper proof |
| Cancellation is never silent | Every refusal announces itself: 5 readiness guards, the corrupt-tag refusal (own message, not "try again"), and the pickup that is cancelled so the item can be tagged first. The throttle is per player **and per kind of refusal** - keyed by player alone, a corrupt tag clicked within five seconds of an unrelated refusal was silent, which is the exact silence the message exists to remove. Map hard-capped | PASS unit contract. `ItemListenerNoticeContractTest` counts the notices against the refusal sites |
| Untracked craft output | `tracking.cancel-untracked-craft-output` (default `true`) chooses between cancelling and allowing; the switch cannot open the tagged branches, and each of the **three** refusals has its own message. Only the untagged one names the config key, because it is the only one the key decides - the copy refusal used to fall through to that message and send an admin to change a setting `CraftOutputPolicy.decide` never reads | PASS unit. The message choice is an exhaustive switch over the action, so a sixth action cannot inherit another one's wording. The page states the block and names the key |
| `general.enabled` is honoured | `ItemGuard.onEnable` reads it between `loadConfigManagers()` and `loadRuntimeManagers()`, so the database is not opened, the sidecar lock is not taken and no startup recovery runs; the plugin then disables itself so other plugins do not see an enabled plugin with a null API. Known and not claimed otherwise: the commands declared in `plugin.yml` stay in Bukkit's `CommandMap`, so `/ig` answers with the plugin-disabled error rather than the explanation in the log (L4, review #3, inferred from Bukkit and not verified against Paper) | PASS unit + source contract |
| Terminal loss boundary | Fixture `itemguard-lite-isolated-de889630ec24` (scope `loss-only`) → `PASS_LOSS_ONLY_SMOKE` | PASS on this candidate, within the verdict's own limits |
| Controlled smoke | Fixture `itemguard-lite-isolated-836201295596` (scope `separate`, 2 generations) → `PASS_CONTROLLED_SMOKE` | PASS on this candidate |
| Duplicate sweep / second-epoch rule | Fixture `itemguard-lite-isolated-96daf846e57a` (scope `sweep-only`) → `PASS_SWEEP_SMOKE` | PASS on this candidate; the C1 rule still detects a planted pair |
| `/reload confirm` | Fixture `itemguard-lite-isolated-09a53fc5c744` → PASS | PASS on this candidate |
| Two tracked items, two readers, and the privacy boundary | Fixture `itemguard-lite-isolated-43ede009afbf` → `PASS_MULTI`, adjudicated independently by `verify.py multi` against the raw transcripts | PASS on this candidate. The scope now also asserts the redaction: the member's own timeline of an item they hold shows the previous actor as `another player (staff only)` and its location as `location hidden`, **while staff's own view of the same rows shows the name and the coordinates** — that control is what stops "the member did not see it" from being vacuous. On this candidate the whole seven-gate run passed first time (442 s). The two rejections worth
recording came earlier, while the case was being validated on the previous candidate: `ca8a5543255d`
failed the scope's own two-item seeding (`slot0=null`, intermittent in the probe), and `f2ba586f32d1`
passed the scope but was rejected by *my* first verifier, which asked for an `outcome.json` this scope
does not write |
| Foreign plugin coexistence | Fixture `itemguard-lite-isolated-e139adaccaa6` → PASS | PASS on this candidate |
| Power cut mid-write | Fixture `itemguard-lite-isolated-d8f6ddf74c8b` (2 generations) → PASS | PASS — **and it gates the records, not the item**: a killed JVM before an autosave rolls the player back with vanilla `playerdata` |
| Version matrix | **9/9 PASS on this candidate** (`run/version-matrix-20260917-230115.json`, one fresh attempt each, none killed): 1.21.4 `8KP5VI` · 1.21.5 `0EBNFC` · 1.21.6 `0HJ5IH` · 1.21.8 `BPFHQ7` · 1.21.9 `TNYLEY` · 1.21.10 `G8QJPH` · 1.21.11 `T8YIXD` · purpur:1.21.4 `A0VGDM` · purpur:1.21.11 `QOOPSS` — every entry `dupe=True locations=2 plugin_enabled=true` | PASS; `1.21.7`, `26.1.1`, `26.1.2`, `26.2` **cannot be certified by this harness** (the pinned bot libraries carry no protocol for them) and are untested, never failures. An earlier `13/13` claim is unsupported: that run booted 1.21.11 thirteen times |
| Duplicate-alert repeat throttle | When the wall-clock window is defeated - a sweep overrun, a server below 20 TPS - the audit still refuses to re-report an identity in two consecutive epochs, which halves the repeat rate. It is a backstop, not a substitute for the window: from the third epoch on it reports again. `detection-cooldown-ms` cannot be set to 0 to disable the throttle, because the value is floored to two scan cycles before it reaches the repository; the `cooldown = 0 ⇒ alert every epoch` path is reachable only from the API, and both halves are unit-tested | PASS unit (`DuplicateConfirmationEpochRuleTest`, both halves) |
| History privacy | **Exercised at runtime now**, on this candidate: fixture `itemguard-lite-isolated-43ede009afbf`, facts `member_foreign_rows=3`, `member_saw_redaction=true`, `member_leaked_actor=false`, `member_kept_foreign_location=false`, and `staff_saw_actor=true` as the positive control. The disclosure check inspects **every line the member was sent** except named vanilla broadcasts, because the previous allowlist (plugin prefix or row number) could not see the overview lines `LiteCommand` sends straight through `sendMessage` - a disclosure in that shape was invisible to every assertion. Six contract tests pin the adjudication, including the two ways it could look true while proving nothing (the member never answered; the staff view not containing the name either) and the unlisted-shape line | PASS for this scenario. Still not covered: a member querying someone else's item by ID (refused by permission, unit-tested only) and the GUI timeline's redaction |
| Tracking / custody | **Rebound to this candidate and read from the raw rows**: fixture `itemguard-lite-isolated-836201295596` (`separate`, 2 generations), `python scripts/verify_custody_rows.py <fixture>` → one tracked identity `VVDPUY`, owner `LiteMember`, 27 history rows, exactly two actors, a real `PICKUP by LiteMember` after the `SPAWN` by `LiteStaff`, then **7 genuine handovers** with the largest gap 2,196 ms — all inside the custody window, which is what makes the scope's `transfers=1` the throttled answer rather than an accident — and `last_action=CLEARED` from the `/clear` case staged last. The window is read from the fixture's own config and the source of the number is printed with it: for LITE that is `CustodyWindow.DEFAULT_MILLIS` (900,000 ms), because the LITE config carries no such key — stated rather than passed off as a configured value. Five contract tests pin the check, including the three ways it could pass while proving nothing and a fixture configured with a smaller window | PASS on this candidate. The throttling arithmetic itself stays `CustodyChain`'s and its unit tests'; what the rows prove is that the events, the actors and the timing are real. Not covered: custody in the GUI/timeline, and the Vietnamese strings for it |
| GUI navigation and English visual | Banner text re-verified by grep against this source | OPEN: the owner's client acceptance |
| Craft | Cancellation is proven, disclosed and switchable; **issuance through crafting has never been verified** | OPEN: never advertise crafting support |
| Natural-break / publication | IG-R016, successor-35 | **OPEN, never verified at any point** |
| Duplicate-alert false positives | "Moved twice, identically" and "two copies" are the same observation data; both candidate tightenings were tried and rejected with reasons | OPEN, needs a spec decision: consult the item's own transfer history |
| Item clicked in a container | An untagged trackable item clicked inside a chest is **not** tagged at that moment; the sweep tags it. The branch that would have done it was unreachable and has been removed | Documented behaviour, not a claim |
| Independent review | Three read-only adversarial reviews, each adjudicated against source before anything was changed: `docs/reviews/2026-09-17-review{,-adjudication}.md`, `…-review-of-the-post-review-changes.md` + `…-review2-adjudication.md`, and `…-review3-candidate-and-gates.md` + `…-review3-adjudication.md` (the third covers this candidate's own code and these gates; it found 3 HIGH / 7 MEDIUM / 6 LOW and named, per gate, the attack that failed) | Adjudicated; the third round's accepted findings are answered by fixes in the candidate below. Not a formal third-party audit |
| Packaging | `ItemGuard-LITE-1.0.0.jar`, sha256 `8c0e540e…`, 5,122,519 bytes; three copies, the sums file and the listing digest all repinned by `scripts/finalize_release_candidate.py` in one pass. The gate now hashes a bare jar of the shipping name rather than trusting the sums file | PASS offline-only |
| Listing | Regenerated; version count nine; `Languages Supported` read from the jar; the craft block and the storage-minecart refusal are stated | OPEN: owner sign-off |
| Owner publication | Not authorized by this checklist | OPEN |

## Offline gates, in the order they are cheap

    python scripts/check_listing_copies.py                     # every copy names the real jar
    python scripts/check_no_hardcoded_vietnamese.py             # source
    python scripts/check_no_hardcoded_vietnamese.py --jar release/spigot-upload/ItemGuard-LITE-1.0.0.jar
    python -B -m unittest discover -s scripts -p 'test_*.py'   # both gates' own tests
    python scripts/verify_lite_artifact.py
    python tools/lite-runtime/test_contracts.py
    python scripts/finalize_release_candidate.py               # all of the above, after a rebuild

## Runtime gates on this candidate

    python scripts/verify_custody_rows.py <fixture-root>       # raw rows behind the custody claims
    python scripts/run_release_runtime_gates.py                # seven servers, one at a time; each
                                                               # verdict is read from the file the
                                                               # gate writes, and `multi` is
                                                               # re-adjudicated by verify.py multi
    python scripts/version_matrix.py 1.21.4 1.21.5 1.21.6 1.21.8 1.21.9 1.21.10 1.21.11 \
        purpur:1.21.4 purpur:1.21.11

Sequenced, never parallel: two Paper servers at once is how the 2026-09-17 matrix ran this host out of
native memory and reported `plugin_enabled: False` on every version. Cleanup is verified by looking —
no `java` process may be left — rather than by trusting a harness summary.
