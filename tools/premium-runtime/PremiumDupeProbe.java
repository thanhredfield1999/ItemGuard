package premiumdupe;

import com.itemguard.ItemGuard;
import com.itemguard.tasks.InventoryScanTask;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Fixture-only driver for the duplicate-detection runtime gate.
 *
 * <p>Two things must happen that no client can do by itself: put a second stack carrying the SAME
 * ItemGuard code and item UUID into another player's inventory (the fixture's controlled duplicate),
 * and force a scan epoch at a moment the harness chooses. Both are things a real server would never
 * let a plugin do, which is why this lives in the fixture and not in the product.
 *
 * <p>The cloning technique is the one the LITE harness already uses
 * (`tools/lite-runtime/LiteProbe.java`, action `duplicate`): clone the tracked stack from the staff's
 * inventory into the member's. Everything the gate then observes — the finding row, the staff alert,
 * the Discord payload, the fact that nothing was removed — comes from the product, not from here.
 */
public final class PremiumDupeProbe extends JavaPlugin {

    private ItemGuard guard;

    @Override
    public void onEnable() {
        guard = (ItemGuard) Bukkit.getPluginManager().getPlugin("ItemGuard");
        if (guard == null || !guard.isEnabled() || guard.isLiteEdition()) {
            throw new IllegalStateException("Premium ItemGuard is not enabled");
        }
        if (getCommand("premiumdupe") == null) {
            throw new IllegalStateException("premiumdupe command is missing");
        }
        getCommand("premiumdupe").setExecutor(this);
        getLogger().info("PREMIUM_DUPE_PROBE READY");
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("usage: /premiumdupe <duplicate|epoch|stacks>");
            return true;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "duplicate" -> duplicate();
            case "epoch" -> epoch();
            case "stacks" -> stacks();
            default -> sender.sendMessage("unknown action: " + args[0]);
        }
        return true;
    }

    /** Clones the staff's tracked stack into the member and lets one epoch observe both. */
    private void duplicate() {
        Player staff = Bukkit.getPlayerExact("PremiumStaff");
        Player member = Bukkit.getPlayerExact("PremiumMember");
        if (staff == null || member == null) {
            getLogger().severe("PREMIUM_DUPE_FAIL both clients must be online");
            return;
        }
        int slot = trackedSlot(staff);
        if (slot < 0) {
            getLogger().severe("PREMIUM_DUPE_FAIL staff holds no tracked item");
            return;
        }
        ItemStack source = staff.getInventory().getItem(slot);
        String code = guard.getTrackingService().getCodeFromItem(source);
        UUID itemUuid = guard.getTrackingService().getItemUuidFromItem(source);
        member.getInventory().setItem(0, source.clone());
        getLogger().info("PREMIUM_DUPE_CLONED code=" + code + " uuid=" + itemUuid
            + " from=PremiumStaff slot=" + slot + " to=PremiumMember slot=0");
        runEpoch();
    }

    /** One more audit, so the harness can watch what the anti-spam rules do with a known duplicate. */
    private void epoch() {
        runEpoch();
    }

    /** Both stacks, read through the product's own accessor: a removal would show up here. */
    private void stacks() {
        Player staff = Bukkit.getPlayerExact("PremiumStaff");
        Player member = Bukkit.getPlayerExact("PremiumMember");
        String staffCode = staff == null ? "absent" : codeIn(staff);
        String memberCode = member == null ? "absent" : codeIn(member);
        getLogger().info("PREMIUM_DUPE_STACKS staff=" + staffCode + " member=" + memberCode
            + " same=" + (staffCode.equals(memberCode) && !"none".equals(staffCode)));
    }

    private String codeIn(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            String code = guard.getTrackingService().getCodeFromItem(candidate);
            if (code != null) {
                return code;
            }
        }
        return "none";
    }

    private int trackedSlot(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (guard.getTrackingService().getCodeFromItem(player.getInventory().getItem(slot)) != null) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Runs one audit on the server thread. Called from the console thread, so it is scheduled rather
     * than invoked directly: the scan reads inventories and writes to the database afterwards, and
     * doing that off the server thread would be a different test than the one this gate is for.
     */
    private void runEpoch() {
        InventoryScanTask task = guard.getInventoryScanTask();
        if (task == null) {
            getLogger().severe("PREMIUM_DUPE_FAIL the scan task is not scheduled "
                + "(performance.inventory-scan-interval must be above zero)");
            return;
        }
        Bukkit.getScheduler().runTask(guard, () -> {
            task.run();
            getLogger().info("PREMIUM_DUPE_EPOCH_DONE");
        });
    }
}
