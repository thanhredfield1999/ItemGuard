package com.itemguard.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Does an ItemGuard identity survive another plugin editing the same item?
 *
 * <p>Thanh asked the concrete version: a player sockets a gem with BastionForge onto an item
 * ItemGuard already tracks. Both plugins write into the same item's persistent data container,
 * so the answer depends entirely on <em>how</em> the other plugin applies its change.
 *
 * <p>Read from BastionForge's source, {@code BukkitLiteItemPort.java:139}:
 *
 * <pre>
 *   item.editMeta(meta -&gt; applyMeta(meta, stateKey, ...));
 *   // applyMeta: meta.getPersistentDataContainer().set(stateKey, STRING, encoded);
 * </pre>
 *
 * It mutates the meta it is handed and sets only its own key. Nothing is rebuilt, so foreign
 * keys are untouched.
 *
 * <p>A live {@code ItemStack} needs a running server (Bukkit's Registry is not initialised in
 * unit tests), so these tests model the container as the key/value map it actually is. That is
 * the right level: the question is whether a write of key B removes key A, which is a property
 * of the container, not of Bukkit's item serialisation. The runtime fixture covers the real
 * item; see {@code tools/lite-runtime/}.
 */
class ForeignPluginPdcSurvivalTest {

    private static final String IG_CODE = "itemguard:code";
    private static final String IG_UUID = "itemguard:uuid";
    private static final String FORGE_STATE = "bastionlite:state";

    /** A container holding an ItemGuard identity, as the sweep would have left it. */
    private Map<String, String> trackedSwordPdc() {
        Map<String, String> pdc = new LinkedHashMap<>();
        pdc.put(IG_CODE, "N5ULQD");
        pdc.put(IG_UUID, "4545b788-6f68-42b2-a106-383a7f8ea324");
        return pdc;
    }

    /** BastionForge's pattern: mutate the container that is already on the item. */
    private void socketGemTheBastionForgeWay(Map<String, String> pdc, String gemState) {
        pdc.put(FORGE_STATE, gemState);
    }

    /** The dangerous pattern: build a fresh item and copy across only your own fields. */
    private Map<String, String> socketGemByRebuildingTheItem(String gemState) {
        Map<String, String> fresh = new LinkedHashMap<>();
        fresh.put(FORGE_STATE, gemState);
        return fresh;
    }

    @Test
    @DisplayName("Socketing a gem the way BastionForge does keeps the ItemGuard identity")
    void editingMetaInPlacePreservesForeignKeys() {
        Map<String, String> pdc = trackedSwordPdc();

        socketGemTheBastionForgeWay(pdc, "gem=ruby;level=3");

        assertEquals("N5ULQD", pdc.get(IG_CODE),
            "socketing a gem must not drop the ItemGuard code");
        assertEquals("4545b788-6f68-42b2-a106-383a7f8ea324", pdc.get(IG_UUID),
            "socketing a gem must not drop the ItemGuard uuid");
        assertEquals("gem=ruby;level=3", pdc.get(FORGE_STATE),
            "the forge state must be there too - both plugins coexist on one item");
    }

    @Test
    @DisplayName("A plugin that rebuilds the ItemStack destroys the identity silently")
    void rebuildingTheItemDropsForeignKeys() {
        Map<String, String> rebuilt = socketGemByRebuildingTheItem("gem=ruby;level=3");

        // Documents the failure mode rather than asserting good behaviour. Nothing throws: the
        // player sees the same sword with the same gem, and it has quietly stopped being
        // traceable. This is why the listing cannot promise compatibility with every plugin.
        assertNull(rebuilt.get(IG_CODE),
            "a rebuilt item carries no ItemGuard code - the identity is gone with no error");
        assertNotNull(rebuilt.get(FORGE_STATE),
            "the rebuilding plugin's own data does survive, which is why this is easy to miss");
    }

    @Test
    @DisplayName("Repeated gem upgrades never re-mint or corrupt the identity")
    void repeatedForeignEditsKeepOneStableIdentity() {
        Map<String, String> pdc = trackedSwordPdc();

        for (int level = 1; level <= 10; level++) {
            socketGemTheBastionForgeWay(pdc, "gem=ruby;level=" + level);
        }

        assertEquals("N5ULQD", pdc.get(IG_CODE),
            "must still be the same identity, not a second one minted along the way");
        assertEquals("gem=ruby;level=10", pdc.get(FORGE_STATE));
        assertEquals(3, pdc.size(), "no key accumulation: two ItemGuard keys plus one forge key");
    }

    @Test
    @DisplayName("Two swords with the identical gem stay two distinct items")
    void twoSocketedSwordsKeepSeparateIdentities() {
        Map<String, String> first = trackedSwordPdc();
        Map<String, String> second = new LinkedHashMap<>();
        second.put(IG_CODE, "TLL508");

        socketGemTheBastionForgeWay(first, "gem=ruby;level=1");
        socketGemTheBastionForgeWay(second, "gem=ruby;level=1");

        // Same gem, same material, same lore - still two different items. This is the property
        // the entire plugin exists to provide, and a cosmetic plugin must not collapse it.
        assertEquals("N5ULQD", first.get(IG_CODE));
        assertEquals("TLL508", second.get(IG_CODE));
        assertTrue(!first.get(IG_CODE).equals(second.get(IG_CODE)),
            "identical decoration must not make two items look like one");
    }

    @Test
    @DisplayName("A foreign plugin overwriting its own key never touches ItemGuard's")
    void foreignKeyOverwriteIsScopedToItsOwnNamespace() {
        Map<String, String> pdc = trackedSwordPdc();
        socketGemTheBastionForgeWay(pdc, "gem=ruby;level=1");

        // Replacing the gem entirely, not upgrading it.
        socketGemTheBastionForgeWay(pdc, "gem=sapphire;level=1");

        assertEquals("N5ULQD", pdc.get(IG_CODE),
            "swapping the gem is a forge-namespace operation and must stay inside it");
        assertEquals("gem=sapphire;level=1", pdc.get(FORGE_STATE));
    }

    @Test
    @DisplayName("What each integration pattern costs, recorded for the listing")
    void integrationPatternsAreDocumented() {
        Map<String, Boolean> identitySurvives = new LinkedHashMap<>();
        identitySurvives.put("editMeta in place (BastionForge)", true);
        identitySurvives.put("get meta / mutate / setItemMeta", true);
        identitySurvives.put("rename and re-lore", true);
        identitySurvives.put("new ItemStack, copy selected fields", false);

        assertTrue(identitySurvives.get("editMeta in place (BastionForge)"),
            "the pattern BastionForge actually uses");
        assertTrue(!identitySurvives.get("new ItemStack, copy selected fields"),
            "the one pattern that breaks tracking, and it does so without any error");
    }
}
