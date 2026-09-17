package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogUiTest {
    @Test void displayCannotHideTextOrSplitUnicodeSurrogatePair() {
        assertEquals("Name",CatalogUi.display("§aName"));
        assertFalse(CatalogUi.display("A\nB\u202eC").contains("\u202e"));
        String result=CatalogUi.display("a".repeat(159)+"😀END");
        for(int i=0;i<result.length();i++) {
            if(Character.isHighSurrogate(result.charAt(i))) {
                assertTrue(i+1<result.length() && Character.isLowSurrogate(result.charAt(++i)),"preview cannot split Unicode pair");
            }
        }
    }
    @Test void listenerCancelsBeforeTrackingAndDoesNotExecuteDuringClick() throws Exception {
        var queue=new ArrayList<Runnable>(); var ui=new CatalogUi((CatalogUi.Host)null,queue::add);
        var id=UUID.randomUUID(); int[] actions={0};
        var holder=new CatalogInventory(id,new CatalogScreen("preview",Map.of(9,new CatalogScreen.Icon("PAPER","item",List.of(),()->actions[0]++))),
            h->CatalogInventoryTest.proxy(Inventory.class,Map.of("getHolder",h,"getSize",54)));
        var player=CatalogInventoryTest.proxy(Player.class,Map.of("getUniqueId",id));
        var view=CatalogInventoryTest.proxy(InventoryView.class,Map.of("getTopInventory",holder.getInventory(),"getPlayer",player));
        var e=new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,9,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        ui.onClick(e);
        assertTrue(e.isCancelled(),"listener must cancel preview click"); assertEquals(0,actions[0]);
        assertEquals(EventPriority.LOWEST,CatalogUi.class.getMethod("onClick",InventoryClickEvent.class).getAnnotation(EventHandler.class).priority());
        queue.forEach(Runnable::run); assertEquals(0,actions[0],"unregistered/stale view cannot execute");
    }
}
