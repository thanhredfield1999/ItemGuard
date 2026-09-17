package com.itemguard.custody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which recorded actions represent a real player taking custody.
 *
 * <p>"Changing hands" means a player ends up holding the item. Putting it into a container is the
 * player letting go, not someone else receiving it, so only the taking side moves custody. Machine
 * transfers move items between containers with nobody holding them, so they never move custody.
 */
class CustodyActionPolicyTest {

    @Test void takingAnItemIntoYourOwnHandsClaimsCustody() {
        assertTrue(CustodyActionPolicy.claimsCustody("PICKUP"));
        assertTrue(CustodyActionPolicy.claimsCustody("CONTAINER_TAKE"));
        assertTrue(CustodyActionPolicy.claimsCustody("SPAWN"), "first tracking starts the chain");
    }

    @Test void lettingGoDoesNotClaimCustodyBecauseNobodyReceivedIt() {
        assertFalse(CustodyActionPolicy.claimsCustody("DROP"));
        assertFalse(CustodyActionPolicy.claimsCustody("CONTAINER_PUT"));
        assertFalse(CustodyActionPolicy.claimsCustody("DEATH"),
            "dying releases the item; custody moves when another player picks it up");
    }

    @Test void movingAnItemAroundYourOwnInventoryIsNotAHandover() {
        assertFalse(CustodyActionPolicy.claimsCustody("INVENTORY_MOVE"));
        assertFalse(CustodyActionPolicy.claimsCustody("INVENTORY_DRAG"));
        assertFalse(CustodyActionPolicy.claimsCustody("USE"));
    }

    @Test void automatedTransfersNeverClaimCustody() {
        assertFalse(CustodyActionPolicy.claimsCustody("HOPPER_TRANSFER"),
            "a hopper moves items between containers; no player is holding it");
        assertFalse(CustodyActionPolicy.claimsCustody("CONTAINER_OPEN"));
    }

    @Test void unknownOrMissingActionsAreTreatedAsNotClaimingCustody() {
        assertFalse(CustodyActionPolicy.claimsCustody(null));
        assertFalse(CustodyActionPolicy.claimsCustody(""));
        assertFalse(CustodyActionPolicy.claimsCustody("SOMETHING_NEW"),
            "a new action must be reviewed before it can move custody");
    }

    @Test void matchingIsCaseInsensitiveAndTrimmed() {
        assertTrue(CustodyActionPolicy.claimsCustody("pickup"));
        assertTrue(CustodyActionPolicy.claimsCustody("  Container_Take  "));
    }
}
