# Premium: issuance monitoring

Requested by Thanh, 2026-09-15: *"chặn admin nào có op nếu tuồn đồ liên tiếp hoặc là mem dùng
op tuồn liên tiếp"* — catch an operator quietly funnelling items out, whether that is a real
staff member abusing access or a member who obtained op.

**Status: designed, not built.** Nothing in this document ships in LITE 1.0.0.

## Why LITE cannot do this

LITE answers *what is this item and where has it been*. It has no opinion on **how items enter
the world**. An operator running `/give` a hundred times produces a hundred perfectly valid,
individually traceable items — every one legitimate by LITE's rules, because LITE only compares
an item against itself.

The abuse is not visible in any single item. It is visible in the **rate and the pattern**,
which requires looking across items and across accounts.

## What is actually being detected

Three distinguishable situations, and conflating them would make the feature useless:

| Situation | Signal | Right response |
|---|---|---|
| Admin running a legitimate event | High issuance, announced, to many players | Nothing. Must not fire. |
| Staff quietly funnelling to one alt | Repeated high-value issuance, same few recipients | Alert staff, record it |
| Compromised or self-granted op | Issuance from an account that gained op recently | Alert loudly, offer to block |

The third is the one worth paying for: an account that was not op an hour ago and is now
issuing netherite is the shape of a break-in, not of an event.

## Design constraints

**Detect and record before blocking.** An anti-cheat that freezes a real admin during a live
event costs the server owner more than the theft would have. Blocking is a setting, off by
default, and the alert path works whether or not it is enabled.

**The counter must survive a restart.** An abuser who learns that `/stop` clears the window
will use `/stop`. The rate state belongs in the database, not in memory.

**Log the op grant, not just the issuance.** "This account gained op at 03:14 and issued 40
items at 03:16" is an actionable sentence. "40 items were issued" is not.

**Every admin action already gets an audit row.** That is the existing Premium promise
(`/ig restore`, `/ig transfer`); issuance monitoring extends the same ledger rather than
inventing a parallel one.

## Open questions

- What counts as "valuable"? Probably configurable per material, with a sane default, because
  a creative-mode build server and a survival economy disagree completely.
- Should it cover `/give` only, or also creative-mode inventory pulls and third-party plugin
  grants? Creative pulls fire different events and may need the same in-hand tagging path that
  LITE 1.0.0 added for `/give`.
- Rate window shape: fixed window, sliding window, or burst-plus-sustained. Needs a real abuse
  log to choose sensibly rather than guessing.

## Dependency already shipped

LITE 1.0.0 tags items the moment they land in a player's inventory, rather than waiting up to
30 seconds for the periodic sweep. Without that, a burst of issued items would all be untagged
during the window and each would later receive a separate identity — the monitor would have
nothing coherent to count. See `InHandTaggingPolicy`.
