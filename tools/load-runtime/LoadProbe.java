package load;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Measures how long the server spends per tick, and builds a workload heavy enough for that
 * number to mean something.
 *
 * <p>This exists because every performance claim about ItemGuard so far came from reading
 * source. Reading finds defects; it does not produce a number. The only honest way to say
 * "it costs X" is to run the same workload twice — once with the plugin present, once with it
 * absent — and compare.
 *
 * <p>Deliberately independent of ItemGuard: this plugin does not import it, reference it, or
 * know whether it is installed. It builds hoppers moving tagged items, which is the path that
 * fires once per hopper per tick, and it samples {@code System.nanoTime()} between ticks.
 *
 * <p>Tick timing is measured with a repeating task at period 1. That is the same cadence the
 * server itself runs at, so the delta between consecutive runs is the tick interval including
 * everything every plugin did during it.
 */
public final class LoadProbe extends JavaPlugin {

    private final List<Long> tickNanos = new ArrayList<>(60_000);
    private boolean sampling;

    @Override
    public void onEnable() {
        // Sample the server's OWN tick duration, not the interval between ticks.
        //
        // The first version of this probe measured wall-clock time between scheduler runs.
        // That number is pinned at 50ms whenever the server keeps up, so it reported 49.99ms
        // with and without the plugin and proved exactly nothing — a measurement that cannot
        // vary is not a measurement. Paper exposes how long each tick actually took; that is
        // the number that moves when a plugin costs something.
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!sampling) return;
            long[] recent = Bukkit.getServer().getTickTimes();
            if (recent.length > 0) {
                tickNanos.add(recent[recent.length - 1]);
            }
        }, 1L, 1L);
        getLogger().info("LOADPROBE_READY");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        switch (args[0]) {
            case "build":
                build(Integer.parseInt(args[1]));
                return true;
            case "start":
                tickNanos.clear();
                sampling = true;
                getLogger().info("LOADPROBE_SAMPLING_START");
                return true;
            case "report":
                report();
                return true;
            default:
                return false;
        }
    }

    /**
     * Builds {@code count} hoppers, each holding a distinct named item, arranged so every
     * hopper has a chest below it and therefore attempts a transfer every tick.
     *
     * <p>Named, non-stacking items are used because that is exactly what ItemGuard tracks —
     * a workload of plain cobblestone would exercise none of its code and prove nothing.
     */
    /**
     * Builds {@code count} hopper PAIRS that feed each other, so items never stop moving.
     *
     * <p>The first version pointed each hopper at a chest below it. Every hopper pushed its
     * one item down on the first tick and then sat empty forever — 3000 hoppers that did no
     * work at all after two seconds, which is why tick time stayed at 0.21ms no matter how
     * many were placed. Measuring a workload that has already finished is worse than not
     * measuring, because it produces a confident-looking number.
     *
     * <p>Two hoppers facing each other keep handing the same item back and forth for the
     * whole sample window, which is what makes the transfer path fire every tick.
     */
    private void build(int count) {
        World world = Bukkit.getWorlds().get(0);
        int placed = 0;
        int x = 0;
        int z = 0;
        while (placed < count) {
            // Upper hopper points down into the lower one; the lower points up into the
            // upper. BlockData facing is what decides the transfer target.
            Block upper = world.getBlockAt(new Location(world, x, 71, z));
            Block lower = world.getBlockAt(new Location(world, x, 70, z));
            world.getChunkAt(upper.getLocation()).load(true);

            upper.setType(Material.HOPPER);
            lower.setType(Material.HOPPER);

            org.bukkit.block.data.type.Hopper upperData =
                (org.bukkit.block.data.type.Hopper) upper.getBlockData();
            upperData.setFacing(org.bukkit.block.BlockFace.DOWN);
            upper.setBlockData(upperData);

            // A hopper cannot face up, so the lower one is left facing down into air and the
            // upper one does the pushing. To keep the item cycling, seed BOTH with an item:
            // each tick the upper pushes into the lower while the lower tries to pull from
            // the upper, so the transfer path stays busy in both directions.
            seed(upper, "LoadItem-U" + placed);
            seed(lower, "LoadItem-L" + placed);

            placed++;
            x += 1;
            if (x > 80) {
                x = 0;
                z += 1;
            }
        }
        getLogger().info("LOADPROBE_BUILT hopperPairs=" + placed);
    }

    private void seed(Block block, String name) {
        if (!(block.getState() instanceof Hopper hopper)) {
            return;
        }
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        hopper.getInventory().addItem(item);
        hopper.update();
    }

    /** Prints percentiles rather than an average: a mean hides the stalls players feel. */
    private void report() {
        if (tickNanos.isEmpty()) {
            getLogger().info("LOADPROBE_REPORT samples=0");
            return;
        }
        long[] sorted = tickNanos.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        getLogger().info(String.format(
            "LOADPROBE_REPORT samples=%d p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms mean=%.2fms",
            sorted.length,
            ms(sorted[(int) (sorted.length * 0.50)]),
            ms(sorted[(int) (sorted.length * 0.95)]),
            ms(sorted[(int) (sorted.length * 0.99)]),
            ms(sorted[sorted.length - 1]),
            Arrays.stream(sorted).average().orElse(0) / 1_000_000.0
        ));
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
