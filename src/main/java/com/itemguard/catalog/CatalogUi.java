package com.itemguard.catalog;

import com.itemguard.ItemGuard;
import com.itemguard.gui.UiMainThreadHandoff;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class CatalogUi implements Listener {
    interface Host {
        boolean enabled();
        CatalogController.Data data();
        CatalogInventory render(Player player, CatalogScreen screen);
        BukkitTask later(Runnable action, long ticks);
        void releaseLegacy(UUID id);
    }
    private final Host host;
    private final Consumer<Runnable> schedule;
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final CatalogReadGate readGate = new CatalogReadGate(System::nanoTime);

    public CatalogUi(ItemGuard plugin) { this(plugin, action -> UiMainThreadHandoff.dispatch(plugin, action)); }
    CatalogUi(ItemGuard plugin, Consumer<Runnable> schedule) { this(new Host() {
        public boolean enabled() { return plugin.isEnabled(); }
        public CatalogController.Data data() {
            CatalogRepositoryPort repository = plugin.getDB().getCatalog();
            return new CatalogController.Data() {
                public java.util.concurrent.CompletableFuture<CatalogPage> find(CatalogQuery query) { return repository.find(query); }
                public java.util.concurrent.CompletableFuture<List<CatalogEvent>> history(String code,String uuid) { return repository.history(code,uuid); }
                public java.util.concurrent.CompletableFuture<CatalogObservations> observations(String code,String uuid) { return repository.observations(code,uuid); }
            };
        }
        public CatalogInventory render(Player player, CatalogScreen screen) {
            var next=new CatalogInventory(player.getUniqueId(),screen,holder -> Bukkit.createInventory(holder,54,Component.text(display(screen.title()))));
            for (int slot=0;slot<54;slot++) next.getInventory().setItem(slot,preview(new CatalogScreen.Icon("GRAY_STAINED_GLASS_PANE"," ",List.of(),null)));
            screen.icons().forEach((slot,icon)->next.getInventory().setItem(slot,preview(icon)));
            return next;
        }
        public BukkitTask later(Runnable action,long ticks) { return Bukkit.getScheduler().runTaskLater(plugin,action,ticks); }
        public void releaseLegacy(UUID id) { plugin.getGuiListener().releasePlayer(id); }
    }, schedule); }
    CatalogUi(Host host, Consumer<Runnable> schedule) { this.host=host; this.schedule=schedule; }

    public void open(Player player) {
        if (!player.hasPermission("itemguard.gui") || !player.hasPermission("itemguard.search")) {
            player.sendMessage(Component.text("Bạn cần quyền itemguard.gui và itemguard.search.", NamedTextColor.RED)); return;
        }
        release(player.getUniqueId());
        host.releaseLegacy(player.getUniqueId());
        var view = new View(player); views.put(player.getUniqueId(), view); view.controller.open();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CatalogInventory holder)) return;
        Runnable action = holder.cancelAndResolve(event);
        if (action == null) return;
        UUID id = event.getWhoClicked().getUniqueId();
        schedule.accept(() -> {
            View view=views.get(id);
            if (view != null && view.current == holder && view.allowed(false)) action.run();
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CatalogInventory) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        View view=views.get(event.getPlayer().getUniqueId());
        if (view != null && !view.switching && view.current != null
            && event.getInventory() == view.current.getInventory()) release(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        View view=views.get(event.getPlayer().getUniqueId());
        if (view != null && (view.current == null || event.getInventory() != view.current.getInventory())) {
            release(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { release(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        View view=views.get(event.getPlayer().getUniqueId());
        if (view != null && view.prompt != null) release(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID id=event.getPlayer().getUniqueId();
        Prompt prompt=prompts.remove(id);
        if (prompt == null) return;
        event.setCancelled(true);
        String value=event.getMessage();
        schedule.accept(() -> {
            View view=views.get(id);
            if (view == null || view.prompt != prompt || !view.allowed(false)) return;
            view.cancelPrompt(); prompt.completion.accept(value);
        });
    }

    private void release(UUID id) {
        View view=views.remove(id);
        if (view != null) { view.controller.close(); view.cancelPrompt(); }
        prompts.remove(id);
    }
    public void clear() {
        for (View view : List.copyOf(views.values())) {
            boolean ours=view.current != null && view.player.getOpenInventory().getTopInventory() == view.current.getInventory();
            release(view.player.getUniqueId());
            if (ours) view.player.closeInventory();
        }
    }
    private record Prompt(Consumer<String> completion) {}

    private final class View implements CatalogController.Port {
        private final Player player;
        private final CatalogController controller;
        private CatalogInventory current;
        private boolean switching;
        private Prompt prompt;
        private BukkitTask timeout;

        private View(Player player) {
            this.player=player;
            var repository=host.data();
            controller=new CatalogController(new CatalogController.Data() {
                public java.util.concurrent.CompletableFuture<CatalogPage> find(CatalogQuery query) { return readGate.submit(() -> repository.find(query)); }
                public java.util.concurrent.CompletableFuture<List<CatalogEvent>> history(String code,String uuid) { return readGate.submit(() -> repository.history(code,uuid)); }
                public java.util.concurrent.CompletableFuture<CatalogObservations> observations(String code,String uuid) { return readGate.submit(() -> repository.observations(code,uuid)); }
            },this);
        }
        @Override public boolean allowed(boolean history) {
            return views.get(player.getUniqueId()) == this && host.enabled() && player.isOnline()
                && player.hasPermission("itemguard.gui") && player.hasPermission("itemguard.search")
                && (!history || (player.hasPermission("itemguard.history") && player.hasPermission("itemguard.history.others")))
                && (current == null || switching || player.getOpenInventory().getTopInventory() == current.getInventory());
        }
        @Override public void main(Runnable action) { schedule.accept(action); }
        @Override public void show(CatalogScreen screen) {
            if (!allowed(false)) { close(); return; }
            cancelPrompt();
            var next=host.render(player,screen);
            var inventory=next.getInventory();
            switching=true; current=next;
            try { player.openInventory(inventory); }
            finally { switching=false; }
            if (player.getOpenInventory().getTopInventory() != inventory) release(player.getUniqueId());
        }
        @Override public void close() {
            boolean ours=current != null && player.getOpenInventory().getTopInventory() == current.getInventory();
            release(player.getUniqueId()); if (ours) player.closeInventory();
        }
        @Override public void prompt(boolean owner, Consumer<String> completion) {
            cancelPrompt();
            switching=true;
            try { player.closeInventory(); current=null; } finally { switching=false; }
            prompt=new Prompt(completion); Prompt captured=prompt;
            prompts.put(player.getUniqueId(), captured);
            message(owner ? "Nhập tên trong bản ghi (tối đa 16 ký tự). * để bỏ lọc; Hủy để quay lại."
                : "Nhập tên / mã / material (tối đa 64 ký tự). * để bỏ lọc; Hủy để quay lại.");
            timeout=host.later(()->{
                if (views.get(player.getUniqueId()) == this && prompt == captured) {
                    release(player.getUniqueId()); message("Đã hết thời gian nhập. Mở lại /ig browser.");
                }
            },600L);
        }
        private void cancelPrompt() {
            if (prompt != null) prompts.remove(player.getUniqueId(),prompt);
            prompt=null;
            if (timeout != null) { timeout.cancel(); timeout=null; }
        }
        @Override public void message(String message) { player.sendMessage(Component.text(message,NamedTextColor.YELLOW)); }
    }

    private static ItemStack preview(CatalogScreen.Icon icon) {
        Material material=icon.material() == null ? null : Material.matchMaterial(icon.material());
        if (material == null || material.isAir() || !material.isItem()) material=Material.PAPER;
        var item=new ItemStack(material);
        var meta=item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(display(icon.title()),NamedTextColor.GOLD).decoration(TextDecoration.ITALIC,false));
            meta.lore(icon.lore().stream().map(line->Component.text(display(line),NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());
            item.setItemMeta(meta);
        }
        return item;
    }
    static String display(String value) {
        if (value == null) return "Chưa ghi nhận";
        String safe=value.replaceAll("§[0-9a-fk-orxA-FK-ORX]","").replaceAll("[\\p{Cntrl}\\p{Cf}]"," ");
        int end=Math.min(safe.length(),160);
        if (end<safe.length() && end>0 && Character.isHighSurrogate(safe.charAt(end-1))) end--;
        return end<safe.length() ? safe.substring(0,end)+"…" : safe;
    }
}
