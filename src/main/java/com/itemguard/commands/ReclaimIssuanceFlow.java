package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemHistory;
import com.itemguard.reclaim.ReclaimClaim;
import com.itemguard.reclaim.ReclaimClaimService;
import com.itemguard.reclaim.ReclaimIssuanceDecision;
import com.itemguard.reclaim.ReclaimIssuanceService;
import com.itemguard.reclaim.ReclaimIssuanceStatus;
import com.itemguard.reclaim.ReclaimItemRecord;
import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.PaperItemSnapshotCodec;
import com.itemguard.snapshot.SnapshotValidationException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The hand-over protocol, in one place because two commands need it.
 *
 * <p>{@code /matdo sos} runs it for a player asking for their own item; {@code /finditem giveoldid}
 * runs it for an admin returning a proven-absent item to its owner. Both must arm, deliver and record
 * in exactly the same order, so the order lives here rather than in either command.
 *
 * <p>The order, and why each step is where it is:
 *
 * <ol>
 *   <li><b>Arm</b> (off the server thread): PENDING → PREPARED. This is what puts the identity inside
 *       the claim table's unique index, so while a hand-over is in flight no second claim can exist.
 *   <li><b>Deliver</b> (server thread): one stack into the player's inventory. The slot is checked
 *       immediately before the insert; if the insert leaves a leftover, nothing landed and the item
 *       stays claimable.
 *   <li><b>Settle</b> (off the server thread, with the delivery answer): delivered → COMMITTED
 *       (permanent), not delivered → DENIED (retryable). The answer decides the state, never the
 *       state the answer.
 * </ol>
 *
 * <p>A commit that loses its race after a successful delivery is the one outcome that needs a human:
 * the player is told the item is theirs but unrecorded, and the claim stays PREPARED, which keeps the
 * identity locked so no later command can hand out a second copy.
 */
final class ReclaimIssuanceFlow {

    private final ItemGuard plugin;
    private final PaperItemSnapshotCodec itemStacks = new PaperItemSnapshotCodec(1_048_576);

    ReclaimIssuanceFlow(ItemGuard plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Runs the whole protocol for one claim.
     *
     * @param reply receives every message meant for whoever asked (the player, or the admin who ran
     *              the command); the delivered player is notified separately when it was not the asker.
     */
    void issue(
        UUID playerUuid,
        ReclaimItemRecord item,
        ReclaimClaim claim,
        ItemSnapshot snapshot,
        ReclaimClaimService claims,
        Consumer<String> reply
    ) {
        ReclaimIssuanceService issuance = new ReclaimIssuanceService(
            plugin.getDB(),
            plugin.getConfigs()::isReclaimIssuanceEnabled,
            System::currentTimeMillis
        );
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ReclaimIssuanceDecision armed = issuance.arm(claim);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (armed.status() != ReclaimIssuanceStatus.ARMED) {
                    reply.accept(refusal(armed.status()));
                    return;
                }
                deliver(playerUuid, item, claim, snapshot, issuance, reply);
            });
        });
    }

    private void deliver(
        UUID playerUuid,
        ReclaimItemRecord item,
        ReclaimClaim claim,
        ItemSnapshot snapshot,
        ReclaimIssuanceService issuance,
        Consumer<String> reply
    ) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            settle(playerUuid, claim, issuance, false, "player offline", null, reply);
            return;
        }
        ItemStack stack;
        try {
            stack = itemStacks.restore(snapshot);
        } catch (SnapshotValidationException invalid) {
            settle(playerUuid, claim, issuance, false,
                "snapshot could not be restored: " + invalid.getMessage(), null, reply);
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            settle(playerUuid, claim, issuance, false, "inventory full", null, reply);
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            plugin.getLogger().warning(
                "Reclaim delivery did not fit for claim " + claim.claimId()
                    + " despite an empty slot; left for a retry"
            );
            settle(playerUuid, claim, issuance, false, "inventory changed during delivery", null, reply);
            return;
        }
        settle(playerUuid, claim, issuance, true, "delivered to player inventory", item, reply);
    }

    private void settle(
        UUID playerUuid,
        ReclaimClaim claim,
        ReclaimIssuanceService issuance,
        boolean delivered,
        String detail,
        ReclaimItemRecord item,
        Consumer<String> reply
    ) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ReclaimIssuanceDecision settled;
            try {
                settled = issuance.settle(claim, delivered, actorName(playerUuid), detail);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Reclaim settle failed for claim " + claim.claimId()
                        + " (delivered=" + delivered + ")",
                    failure
                );
                runOnServerThread(() -> reply.accept(unsettled(delivered)));
                return;
            }
            if (settled.issued() && item != null) {
                logIssuanceHistory(item, playerUuid);
            }
            String message = delivered
                ? success(item)
                : refusal(settled.status()) + " §7(" + detail + ")";
            runOnServerThread(() -> {
                reply.accept(message);
                notifyDeliveredPlayer(playerUuid, delivered, message);
            });
        });
    }

    /** The delivered player hears about their own item even when an admin ran the command. */
    private void notifyDeliveredPlayer(UUID playerUuid, boolean delivered, String adminMessage) {
        if (!delivered) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            player.sendMessage("§e§l[ItemGuard] §aVat pham cua ban da duoc tra lai.");
        }
    }

    private void runOnServerThread(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private void logIssuanceHistory(ReclaimItemRecord item, UUID playerUuid) {
        String actorName = actorName(playerUuid);
        try {
            plugin.getDB().updateItemLastAction(
                item.code(), "RECLAIM_ISSUED", null, actorName, playerUuid
            );
            plugin.getDB().logHistory(new ItemHistory(
                item.code(),
                item.itemUuid(),
                "RECLAIM_ISSUED",
                actorName,
                playerUuid,
                null
            ));
        } catch (RuntimeException failure) {
            plugin.getLogger().log(
                java.util.logging.Level.WARNING,
                "Issued " + item.code() + " but could not write its history row",
                failure
            );
        }
    }

    private String actorName(UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        return player == null ? "unknown" : player.getName();
    }

    private String success(ReclaimItemRecord item) {
        String code = item == null ? "" : item.code();
        return "§e§l[ItemGuard] §aDa tra lai vat pham §f" + code
            + "§a. Kiem tra tui do cua ban.";
    }

    private String unsettled(boolean delivered) {
        return delivered
            ? "§e§l[ItemGuard] §cVat pham da tra nhung KHONG ghi duoc vao lich su. "
                + "Bao staff kem ma claim va ID ngay."
            : "§e§l[ItemGuard] §cKhong ghi duoc ket qua yeu cau; khong co item nao duoc cap.";
    }

    static String refusal(ReclaimIssuanceStatus status) {
        return switch (status) {
            case REFUSED_DISABLED ->
                "§e§l[ItemGuard] §cServer chua bat cap lai do (reclaim.issuance-enabled=false).";
            case REFUSED_STATE ->
                "§e§l[ItemGuard] §cYeu cau nay khong con o trang thai co the cap do.";
            case REFUSED_STALE ->
                "§e§l[ItemGuard] §cYeu cau vua bi thay doi boi phien khac; chay lai /matdo check.";
            case ARMED, ISSUED, ABORTED_RETRYABLE ->
                "§e§l[ItemGuard] §cKhong cap duoc vat pham; chay lai /matdo sos <id>.";
        };
    }
}
