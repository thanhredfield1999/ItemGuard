package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.event.inventory.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogInventoryTest {
    @Test void catalogPreviewNeverResolvesAsPhysicalContainer() {
        var location=new org.bukkit.Location(proxy(org.bukkit.World.class,Map.of()),1,64,1);
        var holder=new CatalogInventory(UUID.randomUUID(),new CatalogScreen("preview",Map.of()),
            h->proxy(Inventory.class,Map.of("getHolder",h,"getSize",54,"getLocation",location)));
        var resolver=new com.itemguard.tracking.BlockContainerPhysicalSlotResolver();
        assertFalse(resolver.supports(holder.getInventory()));
        for(int slot=0;slot<54;slot++) assertTrue(resolver.resolve(holder.getInventory(),slot).isEmpty(),
            "preview slot must not reach requestBlockContainerSlotTag: "+slot);
    }
    @Test void cancelsEveryClickAndOnlyResolvesLeftTopForExactViewer() {
        UUID id=UUID.randomUUID(); Runnable action=()->{};
        var screen=new CatalogScreen("test",Map.of(9,new CatalogScreen.Icon("PAPER","test",List.of(),action)));
        var holder=new CatalogInventory(id,screen,h->proxy(Inventory.class,Map.of("getHolder",h,"getSize",54)));
        Player player=proxy(Player.class,Map.of("getUniqueId",id));
        var view=proxy(InventoryView.class,Map.of("getTopInventory",holder.getInventory(),"getPlayer",player));
        for (var click:ClickType.values()) {
            for (int slot:new int[]{9,54,-999}) {
                var event=new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,slot,click,InventoryAction.NOTHING);
                var resolved=holder.cancelAndResolve(event);
                assertTrue(event.isCancelled(),"preview must cancel "+click+" slot "+slot);
                if(click==ClickType.LEFT && slot==9) assertSame(action,resolved); else assertNull(resolved);
            }
        }
        var other=proxy(InventoryView.class,Map.of("getTopInventory",holder.getInventory(),"getPlayer",proxy(Player.class,Map.of("getUniqueId",UUID.randomUUID()))));
        var event=new InventoryClickEvent(other,InventoryType.SlotType.CONTAINER,9,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        assertNull(holder.cancelAndResolve(event)); assertTrue(event.isCancelled());
    }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Map<String,Object> values) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            if(m.getName().equals("equals")) return p==a[0];
            if(m.getName().equals("hashCode")) return System.identityHashCode(p);
            if(m.getName().equals("convertSlot")) return a[0];
            return values.get(m.getName());
        });
    }
}
