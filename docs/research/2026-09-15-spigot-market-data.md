# SpigotMC market data — measured 2026-09-15

Source: Spiget public API (`api.spiget.org/v2`), read-only, no scraping. Every number below
came from a live query on this date, not from a blog post or a forum opinion.

Command shape used:

```python
get("https://api.spiget.org/v2/search/resources/<term>?field=name&size=20&sort=-downloads")
get("https://api.spiget.org/v2/resources/premium?size=40&sort=-downloads")
get("https://api.spiget.org/v2/resources/<id>")   # price only appears on the detail endpoint
```

## Finding 1 — "dupe" is not one market, it is two opposed ones

Top results for the term, by downloads:

```
145,829  Dupe Fixes / Illegal Stack Remover     4.6 (275)   prevention
 72,089  Dupe Machine                           4.7  (13)   HELPS duping
 63,612  FrameDupe                              4.4   (9)   HELPS duping
 41,501  Anti Dupe Plugin                       4.1  (38)   prevention
 24,367  GoldenDupes                            5.0   (8)   HELPS duping
 19,966  DupePlugin - Bring back the 1.12 dupe  4.6  (18)   HELPS duping
```

Roughly half the volume under this keyword is plugins that *enable* duping for anarchy
servers. A listing that leans on "dupe" alone gets filed next to those. The defensive
audience searches differently — closer to *log*, *history*, *track*, *protect*.

## Finding 2 — the real incumbent is a logging plugin

```
1,272,306  CoreProtect Community Edition   3.9 (640)  free
   21,674  CoreProtect TNT (addon)         4.7  (15)
    3,075  CoreProtect Lookup Web          4.3   (8)
    2,846  WildInspect (CoreProtect addon) 5.0   (4)
    2,311  StellarProtect (alternative)    4.0  (11)
```

CoreProtect has ~8.7x the downloads of the biggest anti-dupe plugin. Two consequences:

- The incumbent to differentiate against is **CoreProtect**, not the anti-dupe plugins. Its
  boundary is real and provable: it logs transactions by location and time; it cannot say
  whether two swords existing *right now* are the same physical sword.
- Its addons pull thousands of downloads on their own. Sitting *next to* CoreProtect rather
  than against it is a viable position — admins already run it.

Note its rating: **3.9 across 640 reviews**. A dominant plugin with mediocre satisfaction is
a better opening than a small one everybody loves.

## Finding 3 — paid plugins cluster at two price points

Detail endpoint, top premium resources by downloads:

```
 8.99 EUR  PetBlocks        131,221
 8.99 EUR  BlockBall         99,695
19.99 USD  mcMMO             34,346
19.99 USD  Citizens          28,592
 8.50 USD  LiteBans          20,751
19.99 EUR  ItemsAdder        18,457
20.00 USD  ShopGUI+          16,204
 9.99 EUR  Head Database     15,352
15.00 EUR  CMI               14,015
19.99 USD  Vulcan Anti-Cheat 13,402
```

Two bands: **~$9** and **~$20**. Nothing in between, nothing above ~$20 in the top ten.
Note also that the `/resources/premium` list endpoint returns `price: null` — the price only
appears when fetching each resource individually.

Review counts on paid plugins (239, 335, 732, 736, 660) are far higher than on free anti-dupe
plugins (8–38). Paid users engage; free users mostly download and vanish.

## What this changes for a listing

1. Do not rank on "dupe" alone — half that traffic wants the opposite product.
2. Name CoreProtect's boundary, since that is what the audience already uses.
3. Price a paid tier at ~$9 or ~$20, not in between, and not above.
4. Treat download count and review count as different signals; only the second shows use.

## Limits of this data

Downloads are cumulative since publication, so an old plugin outranks a good new one. These
numbers describe **supply and history**, not current demand and not willingness to pay for a
new entrant. Ratings reflect whoever chose to review. Nothing here predicts what a new
listing will do.
