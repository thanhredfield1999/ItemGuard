package com.itemguard.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemListenerEventContractTest {

    @Test
    void entityAddSchedulesTrackingAfterPaperWorldInsertion() throws Exception {
        // The handler moved to PaperEntitySpawnListener: Bukkit refuses an entire listener
        // class when one handler names an event the server lacks, so keeping this Paper-only
        // handler inside ItemListener disabled all six of its handlers on Spigot.
        EventHandler handler = PaperEntitySpawnListener.class
            .getDeclaredMethod(
                "onEntityAdded",
                com.destroystokyo.paper.event.entity.EntityAddToWorldEvent.class
            )
            .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGH, handler.priority());
    }

    @Test
    void itemListenerNamesNoPaperOnlyEventType() {
        // Guards the split itself. If a Paper event creeps back into ItemListener, item
        // tracking silently dies on Spigot with only a log line to show for it.
        for (var method : ItemListener.class.getDeclaredMethods()) {
            for (var parameter : method.getParameterTypes()) {
                assertTrue(
                    !parameter.getName().startsWith("com.destroystokyo.paper")
                        && !parameter.getName().startsWith("io.papermc.paper"),
                    "ItemListener." + method.getName() + " takes " + parameter.getName()
                        + ", a Paper-only event; move it to its own conditionally registered "
                        + "listener or Spigot loses every ItemListener handler"
                );
            }
        }
    }

    @Test
    void preInsertionItemSpawnHandlerIsRemoved() {
        assertThrows(
            NoSuchMethodException.class,
            () -> ItemListener.class.getDeclaredMethod(
                "onItemSpawn",
                org.bukkit.event.entity.ItemSpawnEvent.class
            )
        );
    }
}
