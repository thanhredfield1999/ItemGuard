You are performing an ADVERSARIAL, READ-ONLY code review of a Minecraft plugin repository.

REPOSITORY: E:/AI.WORK/ItemGuard  (Java 21, Paper 1.21.11, Maven, ~200 main source files)
You may ONLY read. Do not edit, create, move or delete any file. Do not run builds, tests, servers,
git commands or network calls. If a tool would change state, do not use it — say in the report that
you could not verify something rather than changing anything.

WHY THIS REVIEW EXISTS
An earlier independent audit (2026-09-16) of this repo found three Critical and five High defects.
They have all been fixed since. Those fixed files have NEVER been independently reviewed — the audit
reviewed the code that was wrong, not the code that replaced it. That is the gap this review closes.

WHAT TO REVIEW, IN THIS ORDER

1. THE FIXES. For each claim below, decide whether the fix is CORRECT, COMPLETE, and whether the
   same defect class survives elsewhere in the repo. Attack them; do not confirm them by reading
   the commit-style summary. Names are approximate — find the real symbols yourself.

   C1. An epoch spans real time: the player inventory scan completes in one tick while the container
       sweep spreads over up to ~60s and writes into the SAME epoch, so an item carried and then
       stored looked like a duplicate at two locations. Claimed fix: a CONFIRMED duplicate now also
       requires the identity at two or more locations in the PREVIOUS epoch.
       Also check: the "previous epoch" state itself — what creates an epoch, what completes one,
       what happens after a restart, after `/reload`, and when a scan is interrupted.
   C2. Readiness lives in an in-memory cache, emptied by restart; the only refill paths were the
       player-inventory and container scans, both of which skip holders whose physical slot cannot
       be resolved (ender chest, storage minecart, virtual inventories). Result: every click on such
       an item was cancelled silently and forever. Claimed fix: reconcile on contact through
       `isIdentityReady` using a special `TagPhysicalSourceKey.unresolvedHolder(...)` key whose
       namespace no publication can occupy, so the call can confirm an existing identity and can
       neither complete nor mint one. Attack that claim: can the key collide? Can the call mint or
       complete a PREPARED publication? Can it block the main thread? Can it be reached for an item
       whose canonical row is gone? What about a container holder type that is neither `Container`
       nor `Player`?
   C3. A tagged item whose canonical row was gone could not be picked up, so the ground item
       despawned. Claimed fix: ADOPT — but only when the journal has never held that code or uuid at
       all; adopted rows record `last_action = 'ADOPTED'` plus an ADOPTED history row. Attack the
       boundary: what exactly does "the journal has never held it" mean in SQL, and is there any
       ordering or concurrency in which a genuine dupe is adopted, or a legitimate item is still
       refused forever? What does adoption do to stats, findings, custody chains and `/ig check`
       output a player sees?
   H4. `anti-dupe.detection-cooldown-ms` shipped as 5s against a 30s scan cycle, so it never
       suppressed a repeat. Claimed fix: the window is floored at one scan cycle and the shipped
       default moved to 300000, with a test that loads the real config.yml.
   H1. A pending chat filter had no expiry. Claimed fix: 30s TTL and the player is told the window.
   H3. The container cooldown map was keyed on `holder.toString()`, so for anything but a block
       container the key changed per snapshot, the cooldown never fired and the map grew forever.
       Claimed fix: stable keys, map pruned and capped at 4096.
   H5. `MessageManager.DEFAULT_MESSAGES` was Vietnamese, compiled into the class, used as the
       fallback for keys a message file does not define, and shipped inside an English-only jar.
       Claimed fix: the fallback is the English text, pinned key-for-key to messages_en.yml by a
       test; a language policy makes the edition decide; LITE is English always.
       Attack H5 from the LISTING side as well: any string a buyer sees (plugin.yml description,
       startup banner, command output, GUI, config comments) that is Vietnamese, or that claims a
       feature the jar does not implement.

2. THE SAME CLASS OF DEFECT ELSEWHERE. The project's own history says its worst bugs are
   "the code does exactly what it says, and what it says is wrong for the player", found by playing
   rather than by reading. Look for more:
   - state that lives only in memory but decides whether a player action is allowed or cancelled;
   - any place a cancellation happens with NO feedback to the player;
   - comparisons or caches keyed on something unstable (`toString()` of an object, a mutable map,
     an identity hash);
   - a number that is only meaningful relative to another number (a cooldown vs a scan interval, a
     TTL vs a cycle) with no guard tying them together;
   - a claim in a config comment, permission default, plugin.yml, or user-facing message that the
     code does not honour.

3. LITE vs FULL BOUNDARY. LITE is the free edition that is being published: tracking, history,
   read-only GUI and NOTIFY only, with no restore, no teleport, no issuance, no deletion. Verify
   that boundary against the actual entry point and the command/permission surface of the LITE jar
   (`src/main/resources/lite/plugin.yml`, `src/main/resources/lite/config.yml`, the LITE classes).
   Any path by which a LITE server can move, create, delete or hand back an item is Critical.

GROUND RULES FOR YOUR FINDINGS
- Every finding must cite file:line and quote the decisive lines. A finding without a location is
  not a finding.
- State the consequence for a player or an admin, not just the code smell.
- Say whether you could actually verify it from source alone, inferred it, or could not check —
  and what evidence would settle it. Do not present inference as measurement.
- If a claimed fix holds up under attack, say so explicitly and say which attack failed. A review
  that finds nothing must be as specific as one that finds something.
- Do not propose features. Do not propose refactors. Only defects, plus the minimum correction.
- Severity: CRITICAL (data loss, item loss, permanent player-visible breakage, security, or a false
  claim to buyers), HIGH (wrong alert or wrong denial in a normal admin/player flow), MEDIUM, LOW.

REPORT FORMAT (answer in Vietnamese, keep file paths and code identifiers verbatim in English)
  1. Tóm tắt: số lượng theo mức độ, và một câu về mức độ tin cậy của bạn.
  2. CRITICAL / HIGH / MEDIUM / LOW — mỗi mục: file:line, đoạn code quyết định, hậu quả, cách
     tái hiện hoặc lý do không tái hiện được, mức độ đã kiểm chứng (verified / inferred / chưa),
     và cách sửa tối thiểu.
  3. Đã tấn công mà không phá được: liệt kê từng fix C1/C2/C3/H1/H3/H4/H5 và đòn tấn công đã thử.
  4. Những gì bạn KHÔNG kiểm được trong phiên này, và vì sao.

Be precise and be brief where you can. Do not pad the report.
