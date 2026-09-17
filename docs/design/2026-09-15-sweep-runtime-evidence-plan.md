# Chunk container sweep — runtime evidence plan

**What must be proven on a real Paper server**, because no unit test can:
a duplicate identity sitting in **two closed chests** raises `ITEMGUARD_DUPLICATE_CONFIRMED`.

That single sentence is the whole feature. Before this change it was impossible; the scan only
saw a container while a player had it open.

---

## The case that matters: `sweepclosedchests`

The probe, entirely server-side (no client steps needed — closing a chest is not required
because the chests are never opened):

1. Place two chests at known coordinates in a loaded chunk, far enough apart to be separate
   block entities and **not** two halves of one double chest.
2. Give the staff actor a tracked item; read its code and item UUID from the plugin's own
   tag, not from the probe's expectations.
3. Put the tracked stack into chest A.
4. Create the duplicate the way a duper would: write the **same identity tag** onto a second
   ItemStack and put it into chest B. This is a deliberate forgery by the probe — it is
   exactly the situation the feature exists to catch, and it must be created without any
   plugin command, or the test would be proving the plugin can find its own output.
5. Close nothing, open nothing. Neither chest is ever opened by any player.
6. Wait for one complete sweep pass.
7. Assert on the console: `ITEMGUARD_DUPLICATE_CONFIRMED code=<code>` with
   `locations=2`.

### The control that makes it meaningful

Run the same case with `anti-dupe.sweep.enabled=false` and assert the alarm **does not**
appear. Without this control, a PASS proves only that duplicate detection works at all — it
does not prove the sweep is what found it. The open-container path could be doing the work.

A receipt line records both halves:

```
LITE_SWEEP closedchests enabled=<bool> chestA=<x,y,z> chestB=<x,y,z>
  sameDoubleChest=false code=<code> uuid=<uuid> sweepPasses=<n>
  confirmed=<bool> locations=<n>
```

`sameDoubleChest=false` must be asserted, not assumed: if the two chests happened to be
adjacent halves, one location would be correct behaviour and the test would be meaningless.

---

## Supporting cases

### `sweepdoublechest`
One double chest, one tracked item. Assert `locations=1`. The sweep visits both halves as
separate block entities, so this pins that the physical-slot resolver still collapses them —
the single most likely source of a false alarm in this change.

### `sweepepochintegrity`
Assert from the console that a pass which spans several ticks finalises **once** and that
every observation in it shares one epoch id. If a pass were split across epochs, detection
would silently stop working while appearing healthy — the failure this whole design is
shaped to prevent.

### `sweepunloadmidpass`
Start a pass, unload a chunk that the cursor has not reached, and assert the pass still
completes and finalises. A stranded epoch blocks every later scan, so this is a liveness
test, not a cosmetic one.

---

## What the fixture must NOT do

- Must not open either chest, at any point, for any reason. One open chest and the old path
  could be the thing that found the duplicate.
- Must not use a plugin command to create the second copy.
- Must not extend the sweep interval or budget to make timing easier; a pass that needs an
  inflated budget to finish is not the shipped behaviour.
- Must not assert on the probe's own log lines where a plugin line exists. The verdict comes
  from `ITEMGUARD_DUPLICATE_CONFIRMED`, which the plugin writes.

## Limits this evidence will NOT establish

Even a PASS here proves only the cases run. It says nothing about containers in unloaded
chunks, ender chests, entity inventories, or offline players — all still out of scope, and
all still stated as such in the README.
