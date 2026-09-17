package com.itemguard.lite;

import com.itemguard.ItemGuard;
import com.itemguard.commands.ItemCodeInput;
import com.itemguard.data.ItemHistory;
import com.itemguard.data.ItemHistorySummary;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;


import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded read-only UI. No reclaim, teleport, live-container search or external storage entry point. */
public final class LiteCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int LIMIT = 45;
    private final ItemGuard plugin;
    private final AtomicBoolean pending = new AtomicBoolean();
    private boolean closed;

    public LiteCommand(ItemGuard plugin) { this.plugin = plugin; }

    private boolean isVietnamese() {
        return "vi".equalsIgnoreCase(plugin.getConfigs().getLanguage());
    }

    private String text(String english, String vietnamese) {
        return isVietnamese() ? vietnamese : english;
    }

    private void message(CommandSender sender, String english, String vietnamese) {
        sender.sendMessage("§6[ItemGuard LITE] §f" + text(english, vietnamese));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!List.of("check", "history", "search", "stats", "gui").contains(action)) {
            message(sender, "/ig check | history | search #ID | stats | gui",
                "/ig check | history | search #ID | stats | gui");
            return true;
        }
        String permission = "itemguard." + (action.equals("gui") ? "history" : action);
        if (!sender.hasPermission(permission)) {
            message(sender, "You do not have permission.", "Bạn không có quyền.");
            return true;
        }
        if (args.length > (action.equals("history") || action.equals("search") || action.equals("gui") ? 2 : 1)) {
            message(sender, "Too many arguments. Use /ig help.", "Dư tham số. Dùng /ig help.");
            return true;
        }
        if (action.equals("check")) {
            if (!(sender instanceof Player player)) {
                message(sender, "Hold an item in game to use this command.", "Cầm vật phẩm trong game để dùng lệnh.");
                return true;
            }
            ItemStack item = player.getInventory().getItemInMainHand();
            String code = plugin.getTrackingService().getCodeFromItem(item);
            // Say WHY there is no ID. This is the first command every admin runs, usually while
            // holding whatever is in hand - often a stackable block, which is excluded by
            // design. A bare "no tracking ID" reads as "the plugin is not working".
            message(sender, code == null
                    ? "This item has no tracking ID. Only non-stacking items are tracked "
                      + "(tools, weapons, armour, shulker boxes)."
                    : "Item ID: #" + code,
                code == null ? "Vật phẩm chưa có mã theo dõi." : "Mã vật phẩm: #" + code);
            return true;
        }
        if (action.equals("stats")) {
            query(sender, permission, () -> plugin.getDB().getStatsAsync(), stats ->
                renderStats(sender, stats));
            return true;
        }
        boolean explicitCode = args.length == 2;
        boolean staff = sender.hasPermission("itemguard.history.others");
        // A member may drill into one of their own items; the query stays owner-scoped, so no other
        // player's item can be reached this way. Investigating an arbitrary ID stays staff-only.
        boolean ownDrilldown = explicitCode && !staff && action.equals("history") && sender instanceof Player;
        if (explicitCode && !staff && !ownDrilldown) {
            message(sender, "Staff permission is required to investigate an item ID.", "Cần quyền nhân viên để tra mã vật phẩm.");
            return true;
        }
        if (!explicitCode && (!(sender instanceof Player) || action.equals("search"))) {
            // Two different refusals were sharing one sentence. A console sender is being told
            // it cannot browse without an ID; a player running bare /ig search is being told
            // search needs one. Pointing the console at /ig search was simply the wrong hint.
            if (!(sender instanceof Player)) {
                message(sender, "Console must name an item: /ig " + action + " #ID",
                    "Console phải nêu mã vật phẩm: /ig " + action + " #ID");
            } else {
                message(sender, "Use /ig search #ID (staff only).", "Dùng /ig search #ID (nhân viên).");
            }
            return true;
        }
        String code;
        try {
            code = explicitCode ? ItemCodeInput.normalize(args[1]) : null;
            if (code != null && code.length() > 64) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            message(sender, "Invalid item ID.", "Mã vật phẩm không hợp lệ.");
            return true;
        }
        Player player = sender instanceof Player p ? p : null;
        boolean ownerScoped = player != null && (!explicitCode || ownDrilldown);
        String required = ownerScoped ? permission : "itemguard.history.others";
        String ownFilter = ownDrilldown ? code : null;
        var originalView = player == null ? null : player.getOpenInventory();
        if (action.equals("gui") && code == null && player != null) {
            openOverviewPage(player, 0);
            return true;
        }
        query(sender, required,
            () -> ownerScoped ? plugin.getDB().getHistoryByPlayerAsync(player.getUniqueId(), LIMIT)
                : plugin.getDB().getHistoryAsync(code, LIMIT),
            rows -> {
                if (!sender.hasPermission(permission)) return;
                List<ItemHistory> visible = ownFilter == null ? rows
                    : rows.stream().filter(row -> ownFilter.equals(row.getCode())).toList();
                boolean windowFull = rows.size() >= LIMIT;
                if (visible.isEmpty()) {
                    message(sender, "No recorded history found; this is not proof the item does not exist.",
                        "Chưa có lịch sử; không có nghĩa vật phẩm không tồn tại.");
                } else if (code == null) {
                    List<LiteHistoryView.Entry> entries = LiteHistoryView.overview(visible);
                    printOverview(sender, entries, visible.size(), windowFull);
                } else if (action.equals("gui") && player != null) {
                    if (player.getOpenInventory() == originalView) openTimeline(player, code, visible);
                } else {
                    printTimeline(sender, code, visible, windowFull, staff);
                }
            });
        return true;
    }

    /** Bounded per-item overview of the loaded window: latest status, window event count, drilldown. */
    private void printOverview(CommandSender sender, List<LiteHistoryView.Entry> entries,
                               int windowSize, boolean windowFull) {
        message(sender, "Your tracked items in recent history (most recently active first):",
            "Vật phẩm của bạn trong lịch sử gần đây (hoạt động gần nhất trước):");
        message(sender,
            "Window: the " + windowSize + " most recent recorded events (max " + LIMIT
                + "); counts below are within this window, not lifetime totals."
                + (windowFull ? " Older events may not be loaded." : ""),
            "Cửa sổ: " + windowSize + " sự kiện gần đây nhất (tối đa " + LIMIT
                + "); số đếm bên dưới chỉ trong cửa sổ này, không phải tổng trọn đời."
                + (windowFull ? " Có thể còn sự kiện cũ hơn chưa được tải." : ""));
        entries.forEach(entry -> sender.sendMessage(LiteHistoryView.overviewLine(entry, isVietnamese())));
        message(sender, "Details for one item: /ig history #ID", "Xem chi tiết một vật phẩm: /ig history #ID");
    }

    private void renderStats(CommandSender sender, com.itemguard.data.PluginStats stats) {
        message(sender, "ItemGuard LITE statistics:", "Thống kê ItemGuard LITE:");
        message(sender, "Tracked items: " + stats.getTotalItems(), "Vật phẩm đã theo dõi: " + stats.getTotalItems());
        message(sender, "Recorded history: " + stats.getTotalHistory(), "Lịch sử đã ghi: " + stats.getTotalHistory());
        // The same duplicated item is re-detected every scan, so the raw count alone reads as that
        // many duplicated items; the distinct identity count is what a server owner is looking for.
        message(sender,
            "Duplicate detections: " + stats.getDuplicatesDetected()
                + " (distinct items: " + stats.getDistinctDuplicateItems() + ")",
            "Phát hiện trùng lặp: " + stats.getDuplicatesDetected()
                + " (số vật phẩm: " + stats.getDistinctDuplicateItems() + ")");
        message(sender, "Database: " + stats.getDatabaseType() + " (" + stats.getDatabaseStatus() + ")",
            "Cơ sở dữ liệu: " + stats.getDatabaseType() + " (" + stats.getDatabaseStatus() + ")");
    }

    /** Full event timeline for one explicit ID; repeated legitimate events are never collapsed. */
    private void printTimeline(CommandSender sender, String code, List<ItemHistory> rows,
                               boolean windowFull, boolean staffView) {
        java.util.UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        message(sender, "Recent history for #" + code + " (newest first, up to " + LIMIT + " entries):",
            "Lịch sử gần đây của #" + code + " (mới nhất trước, tối đa " + LIMIT + " dòng):");
        for (int i = 0; i < rows.size(); i++) {
            sender.sendMessage(LiteHistoryView.eventLine(i + 1, rows.get(i), isVietnamese(), viewer, staffView));
        }
        message(sender,
            "The oldest line above is only the oldest inside this window"
                + (windowFull ? " (older events may exist)" : "") + "; it is not proof of the item's origin.",
            "Dòng cũ nhất ở trên chỉ là cũ nhất trong cửa sổ này"
                + (windowFull ? " (có thể còn sự kiện cũ hơn)" : "") + "; không phải bằng chứng về nguồn gốc vật phẩm.");
    }

    private <T> void query(CommandSender sender, String permission,
                           java.util.function.Supplier<CompletableFuture<T>> request,
                           java.util.function.Consumer<T> render) {
        // Tell the sender up front rather than starting work whose result they may not see.
        // The post-await check below re-tests the same permission because it can be revoked
        // while the query is in flight, but silently dropping the FIRST call looks like a
        // broken command to the player, not a denial.
        if (!sender.hasPermission(permission)) {
            message(sender, "You do not have permission.", "Bạn không có quyền.");
            return;
        }
        if (closed || !pending.compareAndSet(false, true)) {
            message(sender, "A query is in progress. Try again shortly.", "Đang có truy vấn. Vui lòng thử lại sau.");
            return;
        }
        var scheduler = plugin.getServer().getScheduler();
        try {
            request.get().whenComplete((result, failure) -> {
                try {
                    scheduler.runTask(plugin, () -> {
                        pending.set(false);
                        if (closed || !sender.hasPermission(permission)
                            || (sender instanceof Player player && !player.isOnline())) return;
                        if (failure != null) {
                            message(sender, "Database query failed. Contact an administrator.", "Truy vấn thất bại. Liên hệ quản trị viên.");
                            plugin.getLogger().log(java.util.logging.Level.WARNING, "LITE database query failed", failure);
                        } else render.accept(result);
                    });
                } catch (org.bukkit.plugin.IllegalPluginAccessException disabled) {
                    pending.set(false);
                }
            });
        } catch (RuntimeException failed) {
            pending.set(false);
            plugin.getLogger().log(java.util.logging.Level.WARNING, "LITE database request failed", failed);
            message(sender, "Database unavailable.", "Cơ sở dữ liệu chưa sẵn sàng.");
        }
    }

    private void openOverviewPage(Player player, int offset) {
        query(player, "itemguard.history",
            () -> plugin.getDB().getHistorySummariesByPlayerAsync(
                player.getUniqueId(), LiteMenuLayout.PAGE_SIZE + 1, offset),
            summaries -> openOverview(player, summaries, offset));
    }

    private void openOverview(Player player, List<ItemHistorySummary> summaries, int offset) {
        int pageSize = LiteMenuLayout.PAGE_SIZE;
        boolean hasNext = summaries.size() > pageSize;
        List<ItemHistorySummary> page = summaries.size() > pageSize ? summaries.subList(0, pageSize) : summaries;
        var holder = new OverviewPreview(offset);
        holder.inventory = plugin.getServer().createInventory(holder, LiteMenuLayout.SIZE,
            text("ItemGuard - Recorded item history", "ItemGuard - Lịch sử vật phẩm đã ghi"));
        paintFrame(holder.inventory);
        int[] content = LiteMenuLayout.contentSlots();
        for (int i = 0; i < page.size(); i++) {
            ItemHistorySummary summary = page.get(i);
            holder.codes.put(content[i], summary.item().getCode());
            holder.inventory.setItem(content[i], itemPreview(summary));
        }
        if (page.isEmpty()) {
            holder.inventory.setItem(content[0], render(LiteMenuChrome.emptyState(isVietnamese())));
        }
        int currentPage = offset / pageSize + 1;
        int totalPages = hasNext ? currentPage + 1 : currentPage;
        holder.inventory.setItem(LiteMenuLayout.GUIDE_SLOT,
            render(LiteMenuChrome.overviewGuide(isVietnamese(), page.size(), pageSize)));
        holder.inventory.setItem(LiteMenuLayout.PREVIOUS_SLOT,
            render(LiteMenuChrome.previous(isVietnamese(), offset > 0)));
        holder.inventory.setItem(LiteMenuLayout.PAGE_SLOT,
            render(LiteMenuChrome.pageIndicator(isVietnamese(), currentPage, totalPages)));
        holder.inventory.setItem(LiteMenuLayout.NEXT_SLOT,
            render(LiteMenuChrome.next(isVietnamese(), hasNext)));
        player.openInventory(holder.inventory);
    }

    private void openTimeline(Player player, String code, List<ItemHistory> rows) {
        var holder = new TimelinePreview();
        holder.inventory = plugin.getServer().createInventory(holder, LiteMenuLayout.SIZE,
            text("ItemGuard - Timeline " + code, "ItemGuard - Dòng thời gian " + code));
        paintFrame(holder.inventory);
        boolean staffView = player.hasPermission("itemguard.history.others");
        int[] content = LiteMenuLayout.contentSlots();
        int shown = Math.min(LiteMenuLayout.PAGE_SIZE, rows.size());
        for (int i = 0; i < shown; i++) {
            ItemHistory row = rows.get(i);
            List<String> lore = new java.util.ArrayList<>(
                LiteHistoryView.eventIconLore(row, isVietnamese(), player.getUniqueId(), staffView));
            boolean ownRow = row.getPlayerUuid() != null
                && row.getPlayerUuid().equals(player.getUniqueId());

            holder.inventory.setItem(content[i], timelineIcon(
                row,
                "§6" + (i + 1) + ". " + LiteHistoryView.actionLabel(row.getAction(), isVietnamese()),
                lore,
                staffView || ownRow));
            holder.rows.put(content[i], row);
        }
        // Custody is derived from the same rows, so it describes this window and claims nothing more.
        var policy = custodyPolicy();
        var chain = com.itemguard.custody.CustodyChain.replay(rows, policy);
        var activity = com.itemguard.custody.CustodyActivity.of(rows, policy);
        List<String> guideLore = new java.util.ArrayList<>(
            LiteMenuChrome.timelineGuide(isVietnamese(), code, shown).lore());
        guideLore.addAll(com.itemguard.custody.CustodyPresentation.lore(
            chain, activity, staffView, isVietnamese()));
        holder.inventory.setItem(LiteMenuLayout.GUIDE_SLOT, render(
            new LiteMenuChrome.Element("BOOK",
                LiteMenuChrome.timelineGuide(isVietnamese(), code, shown).title(),
                List.copyOf(guideLore))));
        holder.inventory.setItem(LiteMenuLayout.BACK_SLOT, render(LiteMenuChrome.back(isVietnamese())));
        player.openInventory(holder.inventory);
    }

    /** Custody uses its own window: the duplicate cooldown is far too short to deter farming. */
    private com.itemguard.custody.CustodyTransferPolicy custodyPolicy() {
        Long configured = plugin.getConfig().contains(com.itemguard.custody.CustodyWindow.CONFIG_PATH)
            ? plugin.getConfig().getLong(com.itemguard.custody.CustodyWindow.CONFIG_PATH)
            : null;
        return new com.itemguard.custody.CustodyTransferPolicy(
            com.itemguard.custody.CustodyWindow.resolve(configured));
    }

    /** Border and content background are different panes so the zones read apart at a glance. */
    private void paintFrame(Inventory inventory) {
        ItemStack border = pane(LiteMenuChrome.BORDER_MATERIAL);
        ItemStack background = pane(LiteMenuChrome.CONTENT_BACKGROUND_MATERIAL);
        for (int slot = 0; slot < LiteMenuLayout.SIZE; slot++) {
            inventory.setItem(slot, LiteMenuLayout.isFrame(slot) ? border.clone() : background.clone());
        }
    }

    private ItemStack pane(String material) {
        Material type = Material.matchMaterial(material);
        ItemStack item = new ItemStack(type == null ? Material.GRAY_STAINED_GLASS_PANE : type);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack render(LiteMenuChrome.Element element) {
        Material type = Material.matchMaterial(element.material());
        ItemStack item = new ItemStack(type == null ? Material.PAPER : type);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6" + element.title());
            meta.setLore(element.lore().stream().map(line -> "§7" + line).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack itemPreview(ItemHistorySummary summary) {
        Material material = summary.item().getMaterial();
        ItemStack icon = new ItemStack(material != null && material != Material.AIR ? material : Material.PAPER);
        var meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        meta.setDisplayName("§6" + summary.item().getDisplayName() + " §7#" + summary.item().getCode());
        List<String> lore = new java.util.ArrayList<>();
        lore.add("§7" + LiteMenuChrome.historyDisclaimer(isVietnamese()));
        lore.add("§7" + summary.totalEvents()
            + (isVietnamese() ? " sự kiện đã lưu" : " retained events"));
        // Raw per-action totals are deliberately not shown here: dropping and re-taking your own item
        // inflates them without the item going anywhere. The timeline shows collapsed figures instead.
        lore.add("§8" + LiteMenuChrome.itemRowHint(isVietnamese()));
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    /**
     * Builds a row's icon: the place it happened, or the player it happened to.
     *
     * <p>Skins come from setting the head's owning player, so the client resolves the texture. No
     * HTTP request is made while rendering a GUI: on the main thread that stalls the whole server.
     */
    private ItemStack timelineIcon(ItemHistory row, String title, List<String> lore,
                                   boolean maySeeActor) {
        TimelineIcon choice = TimelineIconPolicy.iconFor(
            row.getAction(), row.getPlayerUuid() != null, maySeeActor);
        ItemStack stack = new ItemStack(choice.material());

        if (choice == TimelineIcon.ACTOR_HEAD
            && row.getPlayerUuid() != null
            && stack.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setOwningPlayer(plugin.getServer().getOfflinePlayer(row.getPlayerUuid()));
            stack.setItemMeta(skull);
        }
        return icon(stack, title, lore);
    }

    private ItemStack icon(ItemStack base, String title, List<String> lore) {
        var meta = base.getItemMeta();
        if (meta == null) {
            return base;
        }
        meta.setDisplayName(title);
        meta.setLore(lore);
        base.setItemMeta(meta);
        return base;
    }

    private ItemStack icon(String title, List<String> lore) {
        ItemStack icon = new ItemStack(Material.PAPER);
        var meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        meta.setDisplayName(title);
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Preview preview)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player) || slot < 0 || slot >= LiteMenuLayout.SIZE) return;
        if (preview instanceof OverviewPreview overview) {
            String code = overview.codes.get(slot);
            if (code != null) {
                query(player, "itemguard.history", () -> plugin.getDB().getHistoryByPlayerAsync(player.getUniqueId(), LIMIT),
                    rows -> openTimeline(player, code, rows.stream().filter(row -> code.equals(row.getCode())).toList()));
            } else if (slot == LiteMenuLayout.PREVIOUS_SLOT && overview.offset > 0) {
                openOverviewPage(player, Math.max(0, overview.offset - LiteMenuLayout.PAGE_SIZE));
            } else if (slot == LiteMenuLayout.NEXT_SLOT) {
                openOverviewPage(player, overview.offset + LiteMenuLayout.PAGE_SIZE);
            }
        } else if (preview instanceof TimelinePreview timeline) {
            if (slot == LiteMenuLayout.BACK_SLOT) {
                openOverviewPage(player, 0);
            }
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Preview) event.setCancelled(true);
    }

    public void close() {
        closed = true;
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Preview) player.closeInventory();
        });
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        return List.of("check", "history", "search", "stats", "gui").stream()
            .filter(action -> sender.hasPermission("itemguard."
                + switch (action) {
                    case "gui" -> "history";
                    default -> action;
                }))
            .filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }

    private abstract static class Preview implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class OverviewPreview extends Preview {
        private final int offset;
        private final Map<Integer, String> codes = new HashMap<>();
        private OverviewPreview(int offset) { this.offset = offset; }
    }

    private static final class TimelinePreview extends Preview {
        /** Slot to the row it renders, so a click knows which entry was pressed. */
        private final java.util.Map<Integer, ItemHistory> rows = new java.util.HashMap<>();
    }
}
