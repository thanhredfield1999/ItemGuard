package com.itemguard.commands;

import com.itemguard.ItemGuard;
import com.itemguard.data.ItemData;
import com.itemguard.reclaim.ExternalPresenceProbeFactory;
import com.itemguard.reclaim.PlayerInventoryPresenceProbe;
import com.itemguard.reclaim.PresenceEvidence;
import com.itemguard.reclaim.PresenceStatus;
import com.itemguard.reclaim.ReclaimCapabilityEvaluator;
import com.itemguard.reclaim.ReclaimClaim;
import com.itemguard.reclaim.ReclaimClaimService;
import com.itemguard.reclaim.ReclaimDecision;
import com.itemguard.reclaim.ReclaimDecisionStatus;
import com.itemguard.reclaim.ReclaimItemRecord;
import com.itemguard.reclaim.ReclaimPreparationResult;
import com.itemguard.reclaim.ReclaimPreparationService;
import com.itemguard.reclaim.ReclaimPreparationStatus;
import com.itemguard.reclaim.ReclaimTarget;

import com.itemguard.snapshot.ItemSnapshot;
import com.itemguard.snapshot.ItemSnapshotCodec;
import com.itemguard.snapshot.SnapshotValidationException;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class MatDoCommand implements CommandExecutor, TabCompleter {

    private static final int LIST_LIMIT = 100;

    private final ItemGuard plugin;
    private final MatDoCommandParser parser = new MatDoCommandParser();
    private final ItemSnapshotCodec snapshotCodec = new ItemSnapshotCodec(1_048_576);

    public MatDoCommand(ItemGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("itemguard.matdo")) {
            plugin.getMessages().send(player, "no-permission");
            return true;
        }

        MatDoCommandAction action;
        try {
            action = parser.parse(args);
        } catch (IllegalArgumentException invalid) {
            player.sendMessage("§e§l[ItemGuard] §c" + invalid.getMessage());
            return true;
        }

        UUID playerUuid = player.getUniqueId();
        if (action == MatDoCommandAction.Check.INSTANCE) {
            runCheck(playerUuid);
        } else if (action instanceof MatDoCommandAction.Sos sos) {
            runSosDataPhase(playerUuid, sos.code());
        }
        return true;
    }

    private void runCheck(UUID playerUuid) {
        long cutoff = System.currentTimeMillis() - Math.multiplyExact(
            plugin.getConfigs().getReclaimHistoryDays(),
            86_400_000L
        );
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ItemData> eligible = plugin.getDB().getItemsByPlayer(playerUuid).stream()
                .filter(item -> item.getLastSeenAt() >= cutoff)
                .limit(LIST_LIMIT)
                .toList();
            Bukkit.getScheduler().runTask(plugin, () -> renderCheck(playerUuid, eligible));
        });
    }

    private void renderCheck(UUID playerUuid, List<ItemData> eligible) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendMessage("§e§l=== Vat pham trong thoi gian ho tro ===");
        if (eligible.isEmpty()) {
            player.sendMessage("§7Khong co vat pham nao.");
            return;
        }
        for (ItemData item : eligible) {
            player.sendMessage("§7- §f" + item.getCode()
                + " §8| §e" + item.getDisplayName()
                + " §8| §7lan cuoi §f" + item.getLastSeenAt());
        }
    }

    private void runSosDataPhase(UUID playerUuid, String code) {
        ReclaimClaimService claims = new ReclaimClaimService(
            plugin.getDB(),
            UUID::randomUUID,
            System::currentTimeMillis
        );
        ReclaimPreparationService preparationService = new ReclaimPreparationService(
            requestedCode -> plugin.getDB().getItem(requestedCode).map(item ->
                new ReclaimItemRecord(
                    item.getCode(),
                    item.getItemUuid(),
                    item.getOwnerUuid(),
                    item.getLastSeenAt()
                )
            ),
            plugin.getDB()::getSnapshot,
            snapshotCodec,
            claims
        );
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ReclaimPreparationResult preparation = preparationService.prepare(
                playerUuid,
                code
            );
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (preparation.status() != ReclaimPreparationStatus.RESERVED) {
                    sendToOnline(playerUuid, preparationFailure(preparation.status()));
                    return;
                }
                runCapabilityPhase(playerUuid, preparation, claims);
            });
        });
    }

    private String preparationFailure(ReclaimPreparationStatus status) {
        return switch (status) {
            case NOT_TRACKED -> "§e§l[ItemGuard] §cID chua duoc theo doi.";
            case NOT_OWNER -> "§e§l[ItemGuard] §cVat pham khong thuoc ho so cua ban.";
            case SNAPSHOT_MISSING ->
                "§e§l[ItemGuard] §cKhong co full ItemStack snapshot; tu choi cap lai.";
            case SNAPSHOT_INVALID ->
                "§e§l[ItemGuard] §cSnapshot bi hong/khong ho tro; tu choi cap lai.";
            case CLAIM_CONFLICT ->
                "§e§l[ItemGuard] §cVat pham da co yeu cau dang xu ly hoac da cap lai.";
            case RESERVED -> throw new IllegalArgumentException(
                "Reserved reclaim preparation is not a failure"
            );
        };
    }

    private void runCapabilityPhase(
        UUID playerUuid,
        ReclaimPreparationResult preparation,
        ReclaimClaimService claims
    ) {
        ReclaimItemRecord item = preparation.item().orElseThrow();
        ReclaimClaim claim = preparation.claim().orElseThrow();
        ReclaimTarget target = new ReclaimTarget(
            playerUuid,
            item.code(),
            item.itemUuid()
        );
        ReclaimDecision decision = new ReclaimCapabilityEvaluator().evaluate(target, () -> {
            ExternalPresenceProbeFactory externalProbes = new ExternalPresenceProbeFactory(
                this::enabledPluginVersion
            );
            return List.of(
                new PlayerInventoryPresenceProbe(plugin.getTrackingService()),
                externalProbes.playerVaultsProbe(),
                externalProbes.zAuctionHouseProbe()
            );
        });
        if (decision.status() != ReclaimDecisionStatus.ELIGIBLE) {
            PresenceEvidence blocker = decision.blockingEvidence().orElseThrow();
            persistDenial(
                playerUuid,
                claims,
                claim,
                blocker.source() + ": " + blocker.detail(),
                "§e§l[ItemGuard] §cTu choi lay lai: §f"
                    + blocker.source() + " §8- §7" + blocker.detail()
            );
            return;
        }

        if (!plugin.getConfigs().isReclaimIssuanceEnabled()) {
            persistDenial(
                playerUuid,
                claims,
                claim,
                "ISSUANCE_DISABLED",
                "§e§l[ItemGuard] §cVat pham du dieu kien nhung server chua bat cap lai do "
                    + "(reclaim.issuance-enabled=false)."
            );
            return;
        }
        runIssuancePhase(playerUuid, preparation, claims);
    }

    /**
     * Hands the snapshot back through the shared protocol: arm, deliver on the server thread, then
     * record what actually happened. The ordering and its guarantees live in
     * {@link ReclaimIssuanceFlow}; this method only wires the player's own request to it.
     */
    private void runIssuancePhase(
        UUID playerUuid,
        ReclaimPreparationResult preparation,
        ReclaimClaimService claims
    ) {
        new ReclaimIssuanceFlow(plugin).issue(
            playerUuid,
            preparation.item().orElseThrow(),
            preparation.claim().orElseThrow(),
            preparation.snapshot().orElseThrow(),
            claims,
            message -> sendToOnline(playerUuid, message)
        );
    }

    private void persistDenial(
        UUID playerUuid,
        ReclaimClaimService claims,
        ReclaimClaim claim,
        String detail,
        String playerMessage
    ) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean persisted;
            try {
                persisted = claims.deny(claim.claimId(), detail);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Failed to persist denied reclaim claim " + claim.claimId(),
                    failure
                );
                persisted = false;
            }
            boolean denialPersisted = persisted;
            Bukkit.getScheduler().runTask(plugin, () -> sendToOnline(
                playerUuid,
                denialPersisted
                    ? playerMessage
                    : "§e§l[ItemGuard] §cKhong ghi duoc ket qua yeu cau; khong co item nao duoc cap."
            ));
        });
    }

    private void sendToOnline(UUID playerUuid, String message) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }

    private Optional<String> enabledPluginVersion(String pluginName) {
        org.bukkit.plugin.Plugin dependency = Bukkit.getPluginManager().getPlugin(pluginName);
        if (dependency == null || !dependency.isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(dependency.getPluginMeta().getVersion());
    }

    @Override
    public List<String> onTabComplete(
        CommandSender sender,
        Command command,
        String alias,
        String[] args
    ) {
        if (!sender.hasPermission("itemguard.matdo") || args.length != 1) {
            return List.of();
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        return List.of("check", "sos").stream()
            .filter(value -> value.startsWith(input))
            .toList();
    }
}
