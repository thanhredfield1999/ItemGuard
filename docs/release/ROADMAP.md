# ItemGuard roadmap

Updated 2026-09-15. Supersedes earlier roadmap notes in this folder.

The ordering here follows one rule: **nothing gets built for FULL until LITE has told us
somebody wants it.** Before launch there is no evidence about what server owners will ask for,
and building on a guess is how the FULL edition ended up with `RestoreGate`, Discord alerts and
a teleport permission that no code ever called.

---

## Now — LITE 1.0.0 to SpigotMC

Everything is built and verified. What remains is not engineering.

| Step | Owner | State |
|---|---|---|
| Approve the listing text | Thanh | pending |
| Upload to SpigotMC | Thanh | pending — the agent does not publish |

Evidence bound to the shipping candidate: 790 Java tests, 13-version matrix, Spigot sweep,
Paper loss + full, artifact check, and a tick-time benchmark. See `CURRENT_STATE.md`.

---

## Next — two weeks of listening, no new features

The temptation after launch is to keep building. Resist it: this is the only window where
real usage data is cheap to collect and impossible to fake.

**Watch for:**
- Download count against review count. Downloads measure curiosity; reviews measure use.
- Which limitation people complain about first. The listing states four (no stacked items, no
  ender chests, reports only, history never pruned) — whichever draws the first real complaint
  is the highest-value thing to fix, and it is probably not the one we would have guessed.
- Any bug report involving items appearing untracked. LITE 1.0.0 closed the `/give` window,
  but creative-mode pulls and third-party plugin grants are untested paths.
- Whether anyone asks for restore. That question, unprompted, is the signal that FULL has a
  market. Nobody asking is also data.

**Ship during this window:** bug fixes and honesty corrections to the listing only.

---

## Then — FULL, in the order the evidence supports

`docs/release/LITE_VS_FULL.md` records the current state: FULL today is not sellable. Four
advertised capabilities have no working code path. Building more on top of that would repeat
the mistake.

### 1. Make restore real (the reason FULL exists)

`/ig restore` and `/ig transfer`, reissuing an item with its original identity intact so the
restored item stays traceable and cannot become a quiet duplicate. Every admin action recorded
against the staff member who performed it.

This is the differentiator against the actual incumbent: CoreProtect logs actions by location
and time, and cannot say whether two swords existing right now are the same sword. Detecting a
duplicate is worth little if the player who lost something still cannot be helped.

**Blocked on nothing except LITE evidence.** Design contract already written:
`docs/design/2026-09-15-full-restore-contract.md`.

### 2. Issuance monitoring

Catch an operator funnelling items out, whether that is staff abusing access or a member who
obtained op. Design: `docs/design/2026-09-15-premium-issuance-monitoring.md`.

Deliberately second, not first: it needs the in-hand tagging that LITE 1.0.0 just shipped, and
it needs a real abuse log to tune the thresholds. Guessing the rate window without one produces
either false alarms during legitimate events or a detector that never fires.

### 3. Remove the gates that are pure configuration

WorldGuard regions, Discord alerts, longer history retention. These are cheap — they are
already written and switched off in `ConfigManager`. They are listed last because they are not
why anyone would pay.

**Do not ship the paid edition until 1 and 2 work.** A paid plugin whose headline feature is
"the free one, with settings unlocked" earns refunds.

---

## Pricing, when it is time

Measured from the SpigotMC premium listings on 2026-09-15
(`docs/research/2026-09-15-spigot-market-data.md`): prices cluster at **~$9** and **~$20**,
with nothing in between and nothing above $20 in the top ten by downloads.

Restore plus issuance monitoring belongs in the $20 band. Config unlocks alone do not.

---

## Known open items, carried forward

- **IG-R016** — natural block-break issuance never verified.
- **Crafting issuance** — never verified end to end.
- **Folia** — 27 scheduler call sites across 13 files, plus a cross-region read in
  `InventoryScanTask`. Two to three days plus a new fixture. Only worth it if someone asks.
- **Stacked items** — the single largest capability gap. `ItemIdentityEligibilityPolicy`
  requires `maxStackSize == 1`, so ItemGuard is blind to duplicated materials. Solving this is
  a different product, not a feature; do not start it without real demand.
- **Snapshot digests** are comparable only within one server platform.
