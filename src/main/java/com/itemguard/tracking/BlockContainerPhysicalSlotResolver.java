package com.itemguard.tracking;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;

import java.util.Optional;

public final class BlockContainerPhysicalSlotResolver {

    private final DoubleChestSlotPolicy doubleChestSlotPolicy =
        new DoubleChestSlotPolicy();

    public boolean supports(Inventory inventory) {
        if (inventory instanceof DoubleChestInventory doubleChest) {
            return resolveSide(doubleChest.getLeftSide(), 0).isPresent()
                && resolveSide(doubleChest.getRightSide(), 0).isPresent();
        }
        return resolveSide(inventory, 0).isPresent();
    }

    public Optional<ResolvedSlot> resolve(Inventory inventory, int rawSlot) {
        if (inventory == null || rawSlot < 0 || rawSlot >= inventory.getSize()) {
            return Optional.empty();
        }
        if (inventory instanceof DoubleChestInventory doubleChest) {
            Inventory left = doubleChest.getLeftSide();
            Inventory right = doubleChest.getRightSide();
            return doubleChestSlotPolicy.resolve(rawSlot, left.getSize(), right.getSize())
                .flatMap(resolved -> resolveSide(
                    resolved.side() == DoubleChestSlotPolicy.Side.LEFT ? left : right,
                    resolved.localSlot()
                ));
        }
        return resolveSide(inventory, rawSlot);
    }

    public Optional<ResolvedSlot> resolveLoaded(Location location, int localSlot) {
        if (location == null || location.getWorld() == null || localSlot < 0) {
            return Optional.empty();
        }
        World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return Optional.empty();
        }
        if (!(world.getBlockAt(x, y, z).getState(false) instanceof Container container)) {
            return Optional.empty();
        }
        Inventory inventory = container instanceof Chest chest
            ? chest.getBlockInventory()
            : container.getInventory();
        return resolveSide(inventory, localSlot).filter(resolved ->
            resolved.location().getWorld().getUID().equals(world.getUID())
                && resolved.location().getBlockX() == x
                && resolved.location().getBlockY() == y
                && resolved.location().getBlockZ() == z
        );
    }

    private Optional<ResolvedSlot> resolveSide(Inventory sideInventory, int localSlot) {
        if (sideInventory == null
            || localSlot < 0
            || localSlot >= sideInventory.getSize()
            || !(sideInventory.getHolder() instanceof Container)) {
            return Optional.empty();
        }
        Location location = sideInventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedSlot(sideInventory, location, localSlot));
    }

    public record ResolvedSlot(
        Inventory inventory,
        Location location,
        int localSlot
    ) {}
}
