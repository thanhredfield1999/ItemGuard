# Load benchmark — ItemGuard LITE `532a778c`

Date: 2026-09-15. Harness: `tools/load-runtime/`.

> **This ran on `532a778c`, not on the shipping candidate `03cf8bbd`.** The difference between
> them is in-hand tagging: items are now tagged on `PlayerItemHeldEvent` and on inventory
> click instead of waiting for the periodic sweep. That adds work on player actions, which is
> bounded by how fast a human clicks — not on the hopper path this benchmark loads. The result
> is therefore still indicative, but it is not a measurement of the published jar, and saying
> otherwise would be the same "evidence from an older build" mistake this project has already
> made once with screenshots.

## What was run

Six servers, alternating arms across three repeats, identical worlds. Each ran 400 hopper
pairs pushing named diamond swords back and forth — the `InventoryMoveItemEvent` path, which
is the only ItemGuard code whose cost scales with server activity rather than with player
commands. Tick duration sampled from `Bukkit.getServer().getTickTimes()` for 60 seconds after
a 20-second warm-up.

## Result

```
mean ms/tick      run 1   run 2   run 3    median   spread
without ItemGuard  0.87    0.79    0.87      0.87     0.08
with ItemGuard     0.82    0.99    0.76      0.82     0.23
```

**Difference between arms: 0.05 ms. Variation within a single arm: up to 0.23 ms.**

The signal is smaller than the noise. The correct reading is not "ItemGuard costs -0.05ms"
and not "ItemGuard is free" — it is that **its cost on this path is below what this harness
can resolve**, which is roughly 0.2 ms per tick against a 50 ms budget.

That is a real and useful answer to "does it lag", but it is a bound, not a measurement. Any
sentence of the form "ItemGuard adds X ms" would be unsupported by this data.

## Four harness defects found on the way here

Each produced a plausible-looking table before being caught. They are listed because the
numbers above are only trustworthy to the extent these were fixed.

1. **Measured the wrong quantity.** Sampled wall-clock time *between* scheduler runs, which a
   keeping-up server pins at exactly 50 ms. Reported 49.99 vs 50.01 — a measurement that
   cannot vary. Fixed by reading `getTickTimes()`, the server's own per-tick duration.

2. **Workload finished before sampling started.** Each hopper pushed into a chest below it,
   emptied on the first tick, and idled forever. 3000 hoppers did no work after two seconds.
   Fixed with hopper pairs that hand items back and forth for the whole window.

3. **Gated on the wrong statistic.** The "is the server busy" check used p50. Hoppers transfer
   on an 8-tick cooldown, so most ticks legitimately have no transfer work and the median sits
   near zero regardless of load. Switched to mean, which captures total work per tick.

4. **One run per arm.** A single GC pause moved `max` from 38 ms to 105 ms and looked like a
   finding. Fixed with three interleaved repeats reported as medians; interleaving also
   cancels thermal drift.

Two guards now live in `load_benchmark.py` and refuse to report a result when the baseline
looks unloaded or unmeasurable, because three of the four defects above produced confident
output rather than an error.

## Limits

- Synthetic hopper workload, not a hundred real players. Player behaviour touches different
  code paths (commands, GUI, joins) that this does not exercise.
- One machine, three repeats. Indicative, not authoritative.
- The server ran no other plugins; a real server shares the tick budget.
- **Memory growth is not covered.** The unbounded-set defect fixed in this candidate would
  take weeks of uptime to surface and no 60-second benchmark can see it.
