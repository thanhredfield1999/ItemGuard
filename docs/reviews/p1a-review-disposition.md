# P1a independent review disposition

Initial review `p1a-review.json`: claude-opus-5 PASS_FOR_OFFLINE_CANDIDATE for input e224f4fa11ecb76b2c1fb223accc8eb3e5b4a666c1680aa175e0869c60bea46d. Parent verified 360 tests/build independently; reviewer did NOT run tests.

- B1: CONFIRMED dynamic test-agent warning; configure explicit Mockito javaagent in Surefire. Manifest of installed Mockito 5.14.2 declares PremainAttach, focused tests pass without dynamic-attach warning. Claim that JDK>=24 always fails not independently established; target remains JDK21.
- B2: added green-first coverage production one-arg constructor and SecureRandom type, generated 6-character format.
- B3: added deferred abort-completion lock release coverage for SOURCE_CHANGED and PHYSICAL_WRITE_FAILED. Removal on DB thread is safe ConcurrentHashMap key-set operation only, no Bukkit access; no additional dispatcher dependency introduced.
- B4: CONFIRMED negative lore-position other than -1 throws before proposal. Behavioral RED (p1a-lore-red.log) then minimal `position < 0` append fallback; test preserves original lore and physical source. No unrelated visual redesign.
- B5: REJECTED premise that source generation changed 9 to 6. Source already generated 6, only hardcoded test/config documentation claimed nine. ItemCodeInput accepts both six and legacy hyphenated strings; test covers both. FindItemCommandParser delegates to it. CheckCommand reads exact identity from hand; command permission gates in SearchCommand/CheckCommand/FindItemCommand/MatDoCommand unchanged. Runtime remains open.
- B6: CONFIRMED public labels not secrets. Command permission + eligibility/reclaim gate remain separate; no authorization based on possession of a code added.
- B7: fixture explicit newRequestAfterTerminal replaces fake failure-list entry.
- C1: REJECTED two-PREPARED-row claim. Schema idx_tag_publication_source_lock UNIQUE(source_key) WHERE state='PREPARED' prevents second row and transaction rolls back. New clock-regression JDBC coverage leaves original PREPARED, replacement absent. Better diagnostics optional; no speculative persistence patch.
- C2: REJECTED slot mismatch in current resolver. resolveLoaded(location,localSlot) calls resolveSide(...,localSlot), record stores that SAME localSlot; no raw-to-local remap in this method (only resolve(inventory,rawSlot) remaps). Exact coordinates/loaded guards unchanged. Supplied resolver excerpt in followup.
- C3: CONFIRMED residual exact-receipt fail-closed coverage gap; already P1 runtime/support matrix. Do not loosen source/snapshot receipt to manufacture completion or reclaim eligibility.
- C4: retention capacity OPEN P2. Both code/UUID unique indexes and prepared source index exist. ABORTED identity permanence intentional; no deletion/reuse added.
- C5: ready-identity cache growth OPEN P2 lifecycle/performance. No unsafe eviction design invented here.
- C6: legacy non-uppercase/raw code normalization inconsistency remains OPEN compatibility gate; this patch emits same uppercase alphabet/length as before and does not rewrite existing identities.
- C7: unrelated nits/diagnostic improvements deferred; no broad refactor.

No flag enabling anti-dupe automatic action, reclaim issuance, WorldGuard or runtime approval. No Paper/production changes.
