# FULL pricing research — what the market actually lists (2026-09-19)

**Method.** Pages were read directly (not search snippets) on 2026-09-19; figures below are quoted from
the pages named. Where a figure could not be read, it is marked **unverified** rather than estimated.

---

## Observed competitor facts

| Resource | Marketplace | Price observed | Notes from the page |
|---|---|---|---|
| **DupeWatch — "Most advanced Anti-Dupe plugin"** | BuiltByBit | **$39.99** one-time ("Buy a license now"), Standard EULA | "Advanced fingerprinting system to detect dupes"; compatible Paper/Spigot 1.20–26.x; page shows an "Enhanced" tier alongside, whose price is **unverified**. Rating 4.0/5 from 5 ratings (search result excerpt, not read on page). |
| **Protect — Anti-Exploit & Anti-Dupe** v2.10.15 | BuiltByBit | **$14.97** one-time, Standard EULA | "Protect your economy against raids, duplicated items or zero-day exploits"; protection/anticheat/patch categories; latest review mentions a responsive developer and a v2.0.0 bug fix. |
| Plugins shown in the same page sidebars (price band context) | BuiltByBit | $9.97 – $29.97 typical; $58.00 for a premium config; $22.00 for an anticheat; $13.99 for an anti-crash/dupe plugin | Observed while reading the two pages above; useful as the band this audience is used to, not as competitor evidence for item tracking. |

Source URLs: `builtbybit.com/resources/dupewatch-most-advanced-anti-dupe-plugin.74800/`,
`builtbybit.com/resources/protect-anti-exploit-anti-dupe.44847/`.

## Buyer-problem evidence (not demand proof)

- SpigotMC thread "Any Anti-Dupe Plugins that are really good?" (opened 2025-09-18,
  `spigotmc.org/threads/713318`): the opening post is a server owner whose current anti-dupe plugin
  (`coffee-protect`) is being bypassed — *"I have people who join my server using dupes even though I
  already have a anti-duping plugin"*. One reply recommends DupeWatch and describes exactly what it
  provides: *"requires a paid license (one-time fee) … helps spot and handle potential dupes very
  effectively including timelines, warnings, discord reports and automatic tracing"*. Another reply
  argues anti-dupe plugins are band-aids and recommends CoreProtect for rollbacks.
- Protect's own page leans on the same pain: *"CoreProtect, Prism or LogBlock helped with grief, but they
  cannot alert you when illegal actions occur."*

**What this does and does not establish.** It establishes that (a) a paid, item-forensics anti-dupe
product already sells in this exact niche at $14.97–$39.99 one-time, (b) the feature set ItemGuard FULL
is built around — timelines, alerts, Discord, tracing — is the recognised shape of the category, and
(c) buyers arrive with a failed cheap/free plugin already installed. It does **not** establish volume:
no purchase counts were read for either resource, ratings are few (5 and 7), and a listing existing is
not demand. The LITE page's own adoption numbers are still the only first-party signal.

## Where ItemGuard FULL sits, honestly

**Same band, one differentiator the two above do not advertise:** a *network*. DupeWatch and Protect are
per-server tools; ItemGuard FULL's `server_id` schema, cross-server sighting rule and shared-schema
gates exist for servers that already run more than one Paper instance. The second differentiator is the
hand-over with a proof obligation: it refuses unless absence is proven, gives back the full ItemStack
snapshot, locks the identity permanently once issued, and retries instead of duplicating when the
inventory is full — with a runtime gate for each of those claims.

**Gaps a buyer would find in week one, which the price must not pretend away:** no per-player
cooldown/quota on reclaim; no give-back queue; no player-facing reclaim screen; closed-container sweeps
unproven on Premium/MySQL; PlayerVaults/zAuctionHouse contents unreadable by design; a crash between
delivery and commit unmeasured. Protect and DupeWatch also list far wider version support (1.8/1.20 →
26.x) than ItemGuard's verified 1.21.11.

## Proposal (proposed, not observed)

| Option | Price | Reasoning |
|---|---|---|
| Under-cut | $19.99 one-time | Sits between Protect ($14.97) and DupeWatch ($39.99); easy yes for a server that already lost items; leaves room to raise after the cooldown/quota gap closes. |
| **Recommended** | **$24.99 one-time** | Prices the network differentiator (a Bungee/Velocity owner cannot buy it elsewhere) while staying clearly under the category's flagship; "one-time, updates included for the 1.x line" is what this audience expects. |
| Premium | $34.99–$39.99 | Only defensible once reclaim has quota/cooldown, the player screen exists, and the closed-container sweep is proven on Premium — i.e. after the gaps above are closed. |

Marketplace economics, as far as they could be read today: **SpigotMC takes no cut** (you handle
payment processing and refunds yourself); **Polymart charges a percentage** (their current schedule was
not read — **unverified**); **BuiltByBit charges a transaction fee with tiered seller accounts** (exact
figures not read — **unverified**). Net-per-sale must be computed against the schedule you pick, not
against the sticker price.

## What to do with this

1. Decide the price model and whether the first paid release ships before or after the cooldown/quota
   work (recommendation: ship the page only after that gap closes, or price at $19.99 and say plainly
   what is not there).
2. The page can cite this file's competitors only as "the category costs $14.97–$39.99 one-time"; naming
   competitors on a listing is a decision, not a default.
3. No purchase counts were collected. If a volume estimate is wanted, the next step is a bounded
   collection pass over the two product pages' purchase counters and reviews — an evidence task, not a
   guess.
