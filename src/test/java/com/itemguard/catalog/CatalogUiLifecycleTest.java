package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/** Real CatalogUi/Controller and Bukkit event objects; host/render/player are test doubles, NOT Paper runtime. */
class CatalogUiLifecycleTest {
    @Test void observationPermissionsAndSharedGateAreEnforcedAtAdapterBoundary() {
        for(String denied:List.of("itemguard.history","itemguard.history.others","")) {
            var h=new Harness(); h.ui.open(h.player); h.finish(); h.click(9); h.drain();
            if(!denied.isEmpty()) h.deniedNodes.add(denied);
            h.click(24); h.drain();
            assertEquals(0,h.observationReads,"permission/shared admission must prevent observation submission");
            if(denied.isEmpty()) assertTrue(h.screen.title().contains("Lỗi"),"catalog and observations share cooldown");
            else assertNull(h.top.getHolder());
        }
    }
    @Test void historyPermissionNodesAreEnforcedIndividually() {
        for(String denied:List.of("itemguard.history","itemguard.history.others")) {
            var h=new Harness(); h.ui.open(h.player); h.finish(); h.click(9); h.drain();
            assertTrue(h.screen.title().startsWith("Hồ sơ"));
            h.deniedNodes.add(denied); h.click(20); h.drain();
            assertEquals(0,h.historyReads,denied+" must prevent history submission");
            assertNull(h.top.getHolder());
        }
    }
    @Test void closeWhileLoadingDoesNotReopen() {
        var h=new Harness(); h.ui.open(h.player);
        assertEquals(1,h.renders); h.closeWindow(); h.finish();
        assertEquals(1,h.renders); assertNull(h.top.getHolder());
    }
    @Test void replacesInventoryWithoutReleasingOwnSessionAndDefersClicks() {
        var h=new Harness(); h.ui.open(h.player); h.finish();
        assertEquals(2,h.renders); h.click(9);
        assertEquals(2,h.renders,"click must defer inventory changes"); h.drain();
        assertEquals(3,h.renders); assertTrue(h.screen.title().startsWith("Hồ sơ"));
    }
    @Test void promptHidesChatAndResumesOnlyOnMainThread() {
        var h=new Harness(); h.ui.open(h.player); h.finish(); h.click(2); h.drain();
        var chat=h.chat("hủy"); assertTrue(chat.isCancelled()); int before=h.renders;
        assertNull(h.top.getHolder()); h.drain();
        assertEquals(before+1,h.renders); assertTrue(h.screen.title().startsWith("Kho tra cứu"));
        assertEquals(1,h.cancelledTimers);
        assertFalse(h.chat("normal chat").isCancelled());
    }
    @Test void quitCommandForeignInventoryAndTimeoutDisposePrompt() {
        for(String mode:List.of("quit","command","foreign","timeout","clear")) {
            var h=new Harness(); h.ui.open(h.player); h.finish(); h.click(2); h.drain();
            switch(mode) {
                case "quit" -> h.ui.onQuit(new PlayerQuitEvent(h.player,net.kyori.adventure.text.Component.text("quit")));
                case "command" -> h.ui.onCommand(new PlayerCommandPreprocessEvent(h.player,"/help",new HashSet<>()));
                case "foreign" -> h.openWindow(h.emptyInventory());
                case "timeout" -> h.timers.getFirst().run();
                case "clear" -> h.ui.clear();
            }
            int before=h.renders;
            assertFalse(h.chat("normal chat").isCancelled(),mode+" must release chat capture");
            h.drain(); assertEquals(before,h.renders,mode+" must not reopen");
        }
    }
    @Test void revokedPermissionOrDisabledPluginDropsCompletion() {
        for(boolean permission:List.of(true,false)) {
            var h=new Harness(); h.ui.open(h.player);
            if(permission) h.authorized=false; else h.enabled=false;
            h.finish(); assertEquals(1,h.renders); assertNull(h.top.getHolder());
        }
    }
    @Test void chatAlreadyQueuedCannotReviveClosedSession() {
        var h=new Harness(); h.ui.open(h.player); h.finish(); h.click(2); h.drain();
        assertTrue(h.chat("hủy").isCancelled()); h.ui.clear(); int before=h.renders;
        h.drain(); assertEquals(before,h.renders);
    }
    @Test void cancelledOpenAndClearWhileLoadingDropLateResults() {
        for(boolean cancelledOpen:List.of(true,false)) {
            var h=new Harness(); h.cancelOpen=cancelledOpen; h.ui.open(h.player);
            if(!cancelledOpen) h.ui.clear(); h.finish();
            assertEquals(1,h.renders); assertNull(h.top.getHolder());
        }
    }
    static final class Harness implements CatalogUi.Host {
        final UUID id=UUID.randomUUID();
        final List<Runnable> queue=new ArrayList<>(),timers=new ArrayList<>();
        final Set<String> deniedNodes=new HashSet<>();
        final CompletableFuture<CatalogPage> result=new CompletableFuture<>();
        final CatalogUi ui=new CatalogUi(this,queue::add);
        final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->{
            return switch(m.getName()) {
                case "getUniqueId" -> id;
                case "hasPermission" -> this.authorized && !deniedNodes.contains(a[0]);
                case "isOnline" -> true;
                case "getOpenInventory" -> view();
                case "openInventory" -> { openWindow((Inventory)a[0]); yield view(); }
                case "closeInventory" -> { closeWindow(); yield null; }
                case "sendMessage" -> null;
                case "equals" -> p==a[0];
                case "hashCode" -> System.identityHashCode(p);
                default -> null;
            };
        });
        Inventory top=emptyInventory(); CatalogScreen screen;
        int renders,cancelledTimers,historyReads,observationReads; boolean authorized=true,enabled=true,cancelOpen;
        Inventory emptyInventory() { return CatalogInventoryTest.proxy(Inventory.class,Map.of("getSize",36)); }
        InventoryView view() { return CatalogInventoryTest.proxy(InventoryView.class,Map.of("getTopInventory",top,"getPlayer",player)); }
        void openWindow(Inventory next) {
            ui.onClose(new InventoryCloseEvent(view()));
            if(cancelOpen) { top=emptyInventory(); return; }
            top=next; ui.onOpen(new InventoryOpenEvent(view()));
        }
        void closeWindow() { ui.onClose(new InventoryCloseEvent(view())); top=emptyInventory(); }
        void click(int slot) {
            var event=new InventoryClickEvent(view(),InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);
            ui.onClick(event); assertTrue(event.isCancelled());
        }
        AsyncPlayerChatEvent chat(String text) {
            var event=new AsyncPlayerChatEvent(true,player,text,new HashSet<>()); ui.onChat(event); return event;
        }
        void drain() { while(!queue.isEmpty()) queue.removeFirst().run(); }
        void finish() {
            result.complete(new CatalogPage(List.of(new CatalogRow("ABC123","id1","PAPER","Recorded item","Staff","world",1000L)),false)); drain();
        }
        public boolean enabled() { return enabled; }
        public CatalogController.Data data() { return new CatalogController.Data() {
            public CompletableFuture<CatalogPage> find(CatalogQuery query) { return result; }
            public CompletableFuture<List<CatalogEvent>> history(String code,String uuid) { historyReads++; return CompletableFuture.completedFuture(List.of()); }
            public CompletableFuture<CatalogObservations> observations(String code,String uuid) { observationReads++; return CompletableFuture.completedFuture(new CatalogObservations(List.of(),false)); }
        }; }
        public CatalogInventory render(Player player,CatalogScreen screen) {
            this.screen=screen; renders++;
            return new CatalogInventory(id,screen,h->CatalogInventoryTest.proxy(Inventory.class,Map.of("getHolder",h,"getSize",54)));
        }
        public BukkitTask later(Runnable action,long ticks) {
            assertEquals(600L,ticks); timers.add(action);
            return (BukkitTask)Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),new Class<?>[]{BukkitTask.class},(p,m,a)->{
                if(m.getName().equals("cancel")) cancelledTimers++;
                return null;
            });
        }
        public void releaseLegacy(UUID id) {}
    }
}
