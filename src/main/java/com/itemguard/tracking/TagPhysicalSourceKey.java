package com.itemguard.tracking;

import java.util.Objects;
import java.util.UUID;

public final class TagPhysicalSourceKey {

    private TagPhysicalSourceKey() {
    }

    public static String playerSlot(UUID playerUuid, int slot) {
        return "PLAYER_SLOT:" + Objects.requireNonNull(playerUuid, "playerUuid")
            + ':' + requireSlot(slot);
    }

    public static String blockContainerSlot(
        UUID worldUuid,
        int blockX,
        int blockY,
        int blockZ,
        int slot
    ) {
        return "BLOCK_CONTAINER_SLOT:"
            + Objects.requireNonNull(worldUuid, "worldUuid")
            + ':' + blockX + ':' + blockY + ':' + blockZ
            + ':' + requireSlot(slot);
    }

    public static String entity(UUID entityUuid) {
        return "ENTITY:" + Objects.requireNonNull(entityUuid, "entityUuid");
    }

    /**
     * The key for an identity whose physical holder cannot be resolved — an ender chest slot, a
     * storage minecart, a virtual inventory.
     *
     * <p>Its only purpose is to ask the journal whether this identity is <em>already</em>
     * canonical, because the in-memory readiness cache does not survive a restart and the scan
     * paths do not reach those holders. The prefix is deliberately outside the namespace of any
     * key the tagging pipeline mints, so this can never match a {@code PREPARED} publication:
     * the call can confirm an identity that already exists and cannot complete one that does not.
     */
    public static String unresolvedHolder(String code, UUID itemUuid) {
        return "UNRESOLVED_HOLDER:"
            + Objects.requireNonNull(code, "code")
            + ':' + Objects.requireNonNull(itemUuid, "itemUuid");
    }


    private static int requireSlot(int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("Physical source slot must be non-negative");
        }
        return slot;
    }
}
