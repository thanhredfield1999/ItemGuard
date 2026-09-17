You are performing an ADVERSARIAL, READ-ONLY review of Minecraft plugin changes made TODAY.

REPOSITORY: E:/AI.WORK/ItemGuard  (Java 21, Paper 1.21.11, Maven)
You may ONLY read. Do not edit, create, move or delete any file. Do not run builds, tests, servers,
git or network commands. If you cannot verify something from the source, say so instead of guessing.

CONTEXT YOU NEED
- `ItemGuardLite extends ItemGuard` is the free edition being published. `ItemGuard.onEnable()`
  registers every listener for both editions; only the catalog/GUI/filterChat stack is skipped when
  `isLiteEdition()`. So almost all shared code runs on a LITE server.
- LITE is advertised as English-only. `ConfigManager.getLanguage()` resolves through
  `MessageLanguagePolicy` to `en` for LITE whatever `config.yml` says.
- These changes went in today, after an earlier adversarial review; that review's report and my
  adjudication are `docs/reviews/2026-09-17-independent-review-of-the-fixes.md` and
  `docs/reviews/2026-09-17-review-adjudication.md`. Read the adjudication first: it says what I
  accepted, what I rejected and why. Attack the changes, and attack that reasoning if it is wrong.

THE CHANGES TO ATTACK, EACH WITH ITS CLAIM

1. `ItemGuard.onEnable()` — returns early when `config.yml: general.enabled` is false, logging one
   line. Claim: "the switch now works, nothing is registered, and the plugin is inert."
   Attack: what still runs when the guard fires? Does `onDisable()` behave correctly for a plugin
   that never opened its database (`DatabaseManager` is constructed in `loadManagers()` before the
   guard, closed unconditionally in `onDisable`)? What does Bukkit do with the commands declared in
   `lite/plugin.yml` when nothing sets an executor? Can an admin still recover (`/itemguard reload`)?
   Is there any partial state that makes a later `/reload` of the server worse than a plain restart?
2. `ItemListener.tellIdentityNotReady(Player)` — a 5 s per-player notice on every refusal path, in a
   `ConcurrentHashMap` capped at 4096 with `values().removeIf(...)` pruning. Claim: "every refusal is
   now announced, throttled, bounded." Attack the throttle (can it suppress a notice the player
   needs? can it fire per-event anyway under a different player object? does `removeIf` on a
   `ConcurrentHashMap` values view behave as the code assumes?) and attack whether every refusal
   path really calls it — including paths in other listeners that cancel for the same reason.
3. `CraftOutputPolicy.decide(..., cancelUntrackedCraftOutput)` plus
   `ConfigManager.cancelsUntrackedCraftOutput()` (default `true`) and `CraftListener`. Claim: "one
   switch, only the untagged-eligible branch moves, every tagged branch stays fail-closed."
   Attack that claim, including the interaction with `TrackingWorthinessPolicy` and with a craft
   whose output is a *tracked* item that the player obtained elsewhere.
4. `ContainerListener.cooldownKey` — a new `DoubleChest` branch keyed on `getLocation()`. Claim: "a
   double chest now shares one key, so its cooldown fires."
   Attack: is `DoubleChest.getLocation()` actually stable across opens and identical for both halves?
   Is the branch order safe? What else can be a holder and still land in the identity-hash branch?
5. `AntiDupeSettings.detectionCooldown` — floored at two scan cycles, with the interval clamped
   before multiplying. Claim: "the floor now exceeds the distance between two audits, so a repeat is
   suppressed, and a nonsense interval no longer overflows to 0."
   Attack the arithmetic and the "two audits are at least one cycle apart" premise, including what
   happens when the sweep overruns a cycle.
6. `MessageManager` two new keys (`identity-not-ready`, `craft-refused`) mirrored in
   `messages_en.yml`, `messages.yml`, `messages_zh.yml`, and pinned by `MessageManagerFallbackTest`.
   Attack the pinning and any key/file mismatch, and whether either new message can be wrong for the
   situation that triggers it (a permanent refusal reads the same as a transient one).
7. `CheckCommand` printing `unknown (adopted)` instead of the adoption time when
   `last_action = 'ADOPTED'`. Attack: is `last_action` the right test, and can a row be adopted and
   then carry a different `last_action` before anyone looks?
8. TWO NEW GATES, and their own assumptions are fair game:
   - `scripts/check_listing_copies.py`: a copy may quote a different jar hash only if its filename is
     dated or one of its first 20 lines *starts* with `SUPERSEDED` / `HISTORICAL RECORD` / `RECORD`.
   - `scripts/check_no_hardcoded_vietnamese.py`: source mode allows a Vietnamese literal only in a
     file that also contains `isVietnamese()` / `vietnamese ?`; artifact mode parses each `.class`
     constant pool and allows Vietnamese only in five declared bilingual classes; files inside three
     exempt directories are skipped unless declared in `SCANNED_INSIDE_EXEMPT`, and a file inside an
     exempt directory that is referenced from outside and declared in neither list is a finding.
   Attack the class-file parser in particular (`class_strings`): which constant-pool tags does it
   handle, what does it do on an unknown tag, and can a class carry a Vietnamese string it would not
   see? Attack the language-flag rule: can a file contain the flag and still print Vietnamese on a
   LITE server? Attack the declaration lists: are the SKIPPED reasons actually true?
9. `tools/lite-runtime/reload.py` and `verify.py` were changed to expect the new English strings
   (`ItemGuard is disabled.`, and the craft-refusal text) instead of the Vietnamese ones they used to
   wait for. Attack: does each assertion still prove what it used to, or does it now pass on a weaker
   signal? Would it fail if the message disappeared entirely?

OUTPUT
Answer in Vietnamese; keep file paths, identifiers and quoted code in English.

  1. Tóm tắt: số lượng theo mức độ + một câu về độ tin cậy.
  2. CRITICAL / HIGH / MEDIUM / LOW. Mỗi mục: file:line, đoạn code quyết định, hậu quả cho người
     chơi hoặc admin, cách tái hiện (hoặc lý do không tái hiện được từ source), mức kiểm chứng
     (verified / suy luận / chưa kiểm được), cách sửa tối thiểu.
  3. Đã tấn công mà không phá được: mỗi mục 1-9, nêu đòn đã thử.
  4. Những gì bạn KHÔNG kiểm được trong phiên này.

No padding. A finding without file:line is not a finding. Do not propose features or refactors.
