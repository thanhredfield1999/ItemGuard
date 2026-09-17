# BetterAntiDupe — competitor research

Read 2026-09-15 from the public repo, README and user guide. **Source was not cloned and no
code was read.** Their licence (ESMP Source-Available, §2) forbids using their source in
another project, including for study that feeds a competing product. This document is written
from public documentation only, and nothing here may be turned into an implementation task by
copying them — only by deciding independently that an idea is right.

Repo: `github.com/ESMP-FUN/BetterAntiDupe` · 4.3.0, 136 commits, 24 tags, 2 stars.
Actively developed — latest release the day before this audit.

---

## What they built

Two layers, and the split is the interesting part:

**Layer 1 — block the machine.** Rail/carpet/TNT/gravity dupers, ghost-chest desync, restart
dupes. Piston can't move TNT, sand can't fall through a portal, every open window closes at
shutdown. The extra item never exists.

**Layer 2 — a balance ledger.** They record every tracked item a player mines, crafts, trades,
picks up or withdraws, and periodically count what the player actually holds. Holding more
than the record explains is the alert. Entries are hash-linked to the previous one, so editing
the database by hand breaks the chain and `/adp ledger verify` names the spot.

---

## The fundamental design difference

This is the whole comparison, and it is not a matter of quality:

| | BetterAntiDupe | ItemGuard |
|---|---|---|
| Unit of truth | **quantity per player** | **identity per item** |
| Question answered | "does this player hold more than they earned?" | "is this exact sword in two places?" |
| Stackables | covered — it's just arithmetic | **invisible** |
| Non-stackables | covered | covered, with per-item provenance |
| Vanilla stacking | untouched | untouched (only max-stack-1 items get IDs) |

ItemGuard's `ItemIdentityEligibilityPolicy.supportsIdentity` requires
`maxStackSize == 1 && amount == 1`. Diamonds, netherite ingots, blocks, ender pearls — the
things dupers actually farm for profit — **cannot be tracked at all**, by design. An ID
written into a stackable would be destroyed the moment it merges with another stack.

So they catch the dupe that inflates the economy. We catch the dupe that puts a named,
enchanted, irreplaceable item in two hands at once — and we can then show its whole history.

**These are different products that share a category name.** Neither is a weaker version of
the other.

---

## Where they are genuinely ahead

Worth stating plainly rather than defending:

1. **Prevention, not just detection.** We have zero blocking. Every classic duper works fine
   with ItemGuard installed; we only report afterwards. They stop several outright.
2. **Stackables.** The economically significant case. We are blind to it and always will be
   under an identity model.
3. **Tamper-evident storage.** Hash-linked entries mean an admin editing SQLite gets caught.
   Our audit trail is append-only by convention only — a point already noted as a limitation
   in the FULL restore contract.
4. **Folia support.** We have not tested Folia at all.
5. **Redis for multi-server networks.** We are single-server SQLite.
6. **Enforcement.** They can remove items. We are locked to NOTIFY (deliberately, but it is
   still less).
7. **Documentation.** A full GitBook, a "when an alert comes in" runbook, in-game testing
   guide. Ours is one Spigot page.

## Where we are ahead

1. **Provenance.** They tell you a balance is wrong. We tell you this specific sword has been
   in these six hands with timestamps. For a scam ticket, that is the artefact that settles it.
2. **Closed-container sweep by identity.** Their model has no reason to look inside a locked
   chest; ours found the duplicate there (evidence `code=29DEAE locations=2`).
3. **False-positive floor.** Two records of one ID in two places is a fact. A balance
   reconciliation must guess about shops, kits, `/give`, crates — their own docs describe
   forgiving negative balances, which is an accepted blind spot they then patch with a
   day-long memory heuristic.
4. **Creative/spectator.** They record nothing in those modes. We still track identity.
5. **Licence.** They are source-available and non-commercial; we can be sold.

---

## Their Spigot listing (resource 135719, v4.3.0)

Read 2026-09-15. Two reviews, both 5/5, 15 updates, author replies within hours. Small but
real traction, and the author is visibly responsive — that shows on a resource page.

**They are free with no paid tier at all.** Their page says it outright: *"No licence key.
Nothing locked behind a premium version."* Source on GitHub, PRs welcome.

Things their page does that ours should copy — structure, not words:

- **"Will it work on my server?" is the first section**, before any feature. Server software,
  MC version, Java, external dependencies. A server owner's first question answered first.
- **Every feature is a `[SPOILER]`** with a three-line summary inside. The page skims in
  twenty seconds and expands where you care. We already fold the long tables; they fold
  *everything*, and it reads better than our flat feature list.
- **Each spoiler ends with a docs link.** They can be terse on the page because depth lives
  elsewhere. We have no docs site, so our page has to carry everything — a real disadvantage.
- **They name the blind spots on the sales page** (creative/spectator not recorded, other
  plugins' items not seen). Same instinct we have.
- **A closing line asking for a review** if the plugin saved you a cleanup.

Claims on their page beyond the README: ender chests monitored, alerts to
Discord/Telegram/Slack/webhook, `[History]` and `[Stash]` buttons with click-to-teleport,
six built-in languages, PDC hidden from client-side inspection mods, and removal that only
ever takes the extras.

## What this means for the product

**Do not reposition ItemGuard as a general anti-dupe.** The listing currently says "duplicate
detection", and against a plugin that reconciles balances across all stackables, that claim
invites a comparison we lose on their terms.

The honest and stronger position is **item identity and provenance**: the plugin that can
prove where a specific valuable item has been. Duplicate detection is a consequence of
identity, not the headline.

Two things worth considering later, arrived at independently rather than copied:

- **Hash-linking our audit ledger.** The FULL restore contract already admits an admin with
  file access can edit the database. Hash-linked rows would close that, and it matters more
  for us than for them because our ledger will authorise item *creation* via `/ig restore`.
- **A "what do I do now" runbook.** Their alert-response doc is better than anything we have,
  and writing our own costs nothing but time.

What we should **not** do: add blocking or balance reconciliation. That is their product,
built on a model we do not have, and a half-built version would be worse than none.

---

## The business problem, stated plainly

BetterAntiDupe being **free with no paid tier** is the finding that matters most, and it cuts
two ways.

**For LITE:** a free competitor that is strictly more capable *as an anti-dupe* now exists on
the same storefront. If a server owner compares "duplicate detection" head to head, we lose —
they block dupes, we only report; they cover stackables, we cannot. LITE cannot win that
comparison and should not invite it. It wins a different one: *which specific sword is real,
and where has it been?* That is a question their model cannot answer at all.

**For FULL:** their free tier sets the price of anti-dupe detection at zero. Anything we
charge for must be something they do not do. Checked against their page:

- They **remove** extra items. They do **not** restore a destroyed item, and nothing in their
  model could — a balance ledger has no snapshot of the sword that burned in lava.
- They have no concept of returning a scammed item to its owner with its history intact.
- Their audit is tamper-evident for *detection* records. Ours would need to be tamper-evident
  for *item creation*, which is a stronger requirement and a real selling point.

So `/ig restore` and `/ig transfer` — the exact things Thanh chose (direction B) — remain the
defensible paid features. That decision looks better after this research, not worse.

What is no longer defensible as paid: GUI browsing, search, Discord alerts, WorldGuard gating.
A free plugin does all of that today.
