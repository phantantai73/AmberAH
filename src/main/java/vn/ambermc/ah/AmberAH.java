package vn.ambermc.ah;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AmberAH extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Economy economy;
    private File dataFile;
    private YamlConfiguration dataConfig;

    private final Map<String, AuctionListing> activeListings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> expiredItems = new HashMap<>();
    private final Map<UUID, ViewerState> viewerStates = new HashMap<>();
    private final Map<UUID, ViewerState> pendingSearch = new HashMap<>();

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private final DecimalFormat priceFormat = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.US));

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Không tìm thấy Vault hoặc plugin tiền tệ! Đang tắt AmberAH.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);

        var cmd = getCommand("ah");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::checkExpirations, 200L, 600L);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        activeListings.clear();
        expiredItems.clear();

        ConfigurationSection activeSec = dataConfig.getConfigurationSection("active");
        if (activeSec != null) {
            for (String id : activeSec.getKeys(false)) {
                ConfigurationSection entry = activeSec.getConfigurationSection(id);
                if (entry == null) continue;
                ItemStack item = entry.getItemStack("item");
                String sellerStr = entry.getString("seller-uuid");
                String sellerName = entry.getString("seller-name", "Unknown");
                double price = entry.getDouble("price");
                long createdAt = entry.getLong("created-at");
                long expiresAt = entry.getLong("expires-at");

                if (item != null && !item.getType().isAir() && sellerStr != null) {
                    activeListings.put(id, new AuctionListing(
                            id, UUID.fromString(sellerStr), sellerName, item, price, createdAt, expiresAt
                    ));
                }
            }
        }

        ConfigurationSection expSec = dataConfig.getConfigurationSection("expired");
        if (expSec != null) {
            for (String uuidStr : expSec.getKeys(false)) {
                UUID owner = UUID.fromString(uuidStr);
                List<?> rawList = expSec.getList(uuidStr);
                if (rawList == null) continue;
                List<ItemStack> items = new ArrayList<>();
                for (Object obj : rawList) {
                    if (obj instanceof ItemStack stack && !stack.getType().isAir()) {
                        items.add(stack);
                    }
                }
                if (!items.isEmpty()) {
                    expiredItems.put(owner, items);
                }
            }
        }
        checkExpirations();
    }

    private void saveData() {
        if (dataConfig == null) dataConfig = new YamlConfiguration();
        dataConfig.set("active", null);
        dataConfig.set("expired", null);

        for (AuctionListing listing : activeListings.values()) {
            String path = "active." + listing.id;
            dataConfig.set(path + ".seller-uuid", listing.sellerUuid.toString());
            dataConfig.set(path + ".seller-name", listing.sellerName);
            dataConfig.set(path + ".item", listing.item);
            dataConfig.set(path + ".price", listing.price);
            dataConfig.set(path + ".created-at", listing.createdAt);
            dataConfig.set(path + ".expires-at", listing.expiresAt);
        }

        for (Map.Entry<UUID, List<ItemStack>> entry : expiredItems.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                dataConfig.set("expired." + entry.getKey(), entry.getValue());
            }
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void checkExpirations() {
        long now = System.currentTimeMillis();
        List<String> expiredIds = new ArrayList<>();
        for (AuctionListing listing : activeListings.values()) {
            if (now >= listing.expiresAt) {
                expiredIds.add(listing.id);
                expiredItems.computeIfAbsent(listing.sellerUuid, k -> new ArrayList<>()).add(listing.item);
            }
        }
        if (!expiredIds.isEmpty()) {
            for (String id : expiredIds) {
                activeListings.remove(id);
            }
            saveData();
        }
    }

    private ViewerState getState(UUID uuid) {
        return viewerStates.computeIfAbsent(uuid, k -> new ViewerState());
    }

    private void openMainGui(Player player) {
        checkExpirations();
        ViewerState state = getState(player.getUniqueId());

        List<AuctionListing> filtered = new ArrayList<>();
        String query = state.searchQuery == null ? "" : state.searchQuery.toLowerCase(Locale.ROOT);

        for (AuctionListing listing : activeListings.values()) {
            if (!state.category.matches(listing.item.getType())) continue;
            if (!query.isEmpty() && !matchesSearch(listing.item, query)) continue;
            filtered.add(listing);
        }

        switch (state.sort) {
            case NEWEST -> filtered.sort(Comparator.comparingLong((AuctionListing l) -> l.createdAt).reversed());
            case PRICE_ASC -> filtered.sort(Comparator.comparingDouble(l -> l.price));
            case PRICE_DESC -> filtered.sort(Comparator.comparingDouble((AuctionListing l) -> l.price).reversed());
            case EXPIRING -> filtered.sort(Comparator.comparingLong(l -> l.expiresAt));
        }

        int totalPages = Math.max(1, (int) Math.ceil(filtered.size() / 45.0));
        if (state.page >= totalPages) state.page = totalPages - 1;
        if (state.page < 0) state.page = 0;

        String titleRaw = getConfig().getString("titles.main", "&8CHỢ ĐẤU GIÁ (Trang %page%)")
                .replace("%page%", String.valueOf(state.page + 1));
        AmberAHHolder holder = new AmberAHHolder(GuiType.MAIN);
        Inventory inv = Bukkit.createInventory(holder, 54, color(titleRaw));
        holder.setInventory(inv);

        int start = state.page * 45;
        int end = Math.min(start + 45, filtered.size());
        long now = System.currentTimeMillis();

        for (int i = start; i < end; i++) {
            int slot = i - start;
            AuctionListing listing = filtered.get(i);
            holder.slotListings.put(slot, listing.id);
            inv.setItem(slot, buildListingDisplayItem(listing, player.getUniqueId(), now));
        }

        if (state.page > 0) {
            inv.setItem(45, createButton(Material.ARROW, "&aTrang trước",
                    List.of("&7Quay lại trang " + state.page)));
        }

        List<String> filterLore = new ArrayList<>();
        for (FilterCategory cat : FilterCategory.values()) {
            if (cat == state.category) {
                filterLore.add("&a▸ " + cat.label);
            } else {
                filterLore.add("&7  " + cat.label);
            }
        }
        filterLore.add("");
        filterLore.add("&eBấm để đổi danh mục!");
        inv.setItem(47, createButton(Material.HOPPER, "&eBộ lọc: &f" + state.category.label, filterLore));

        List<String> sortLore = new ArrayList<>();
        for (SortMode mode : SortMode.values()) {
            if (mode == state.sort) {
                sortLore.add("&a▸ " + mode.label);
            } else {
                sortLore.add("&7  " + mode.label);
            }
        }
        sortLore.add("");
        sortLore.add("&eBấm để đổi cách sắp xếp!");
        inv.setItem(48, createButton(Material.ANVIL, "&6Sắp xếp: &f" + state.sort.label, sortLore));

        inv.setItem(49, createButton(Material.NETHER_STAR, "&bLàm mới",
                List.of("&7Tải lại danh sách vật phẩm mới nhất.", "", "&eBấm để làm mới!")));

        List<String> searchLore = new ArrayList<>();
        if (query.isEmpty()) {
            searchLore.add("&7Tìm kiếm vật phẩm theo tên.");
            searchLore.add("");
            searchLore.add("&eChuột trái để nhập từ khóa!");
        } else {
            searchLore.add("&7Đang lọc từ khóa: &f" + state.searchQuery);
            searchLore.add("");
            searchLore.add("&eChuột trái để tìm từ khác");
            searchLore.add("&cChuột phải để xóa bộ lọc tìm kiếm");
        }
        inv.setItem(50, createButton(Material.OAK_SIGN, "&aTìm kiếm", searchLore));

        int ownActive = countActiveByPlayer(player.getUniqueId());
        int ownExpired = expiredItems.getOrDefault(player.getUniqueId(), List.of()).size();
        inv.setItem(51, createButton(Material.CHEST, "&dVật phẩm của bạn",
                List.of(
                        "&7Đang treo bán: &f" + ownActive,
                        "&7Hết hạn chờ nhận: &e" + ownExpired,
                        "",
                        "&eBấm để quản lý!"
                )));

        if (state.page + 1 < totalPages) {
            inv.setItem(53, createButton(Material.ARROW, "&aTrang sau",
                    List.of("&7Chuyển sang trang " + (state.page + 2))));
        }

        player.openInventory(inv);
    }

    private void openConfirmGui(Player player, AuctionListing listing) {
        String titleRaw = getConfig().getString("titles.confirm", "&8XÁC NHẬN MUA HÀNG");
        AmberAHHolder holder = new AmberAHHolder(GuiType.CONFIRM);
        holder.confirmListingId = listing.id;
        Inventory inv = Bukkit.createInventory(holder, 27, color(titleRaw));
        holder.setInventory(inv);

        String formattedPrice = formatPrice(listing.price);
        inv.setItem(11, createButton(Material.LIME_STAINED_GLASS_PANE, "&a&lXÁC NHẬN MUA",
                List.of(
                        "&7Vật phẩm: &f" + getItemDisplayName(listing.item),
                        "&7Số lượng: &f" + listing.item.getAmount(),
                        "&7Người bán: &f" + listing.sellerName,
                        "&7Giá thanh toán: &e$" + formattedPrice,
                        "",
                        "&aBấm để hoàn tất giao dịch!"
                )));

        inv.setItem(13, listing.item.clone());

        inv.setItem(15, createButton(Material.RED_STAINED_GLASS_PANE, "&c&lHỦY BỎ",
                List.of("&7Quay lại Chợ Đấu Giá.")));

        player.openInventory(inv);
    }

    private void openYourItemsGui(Player player) {
        checkExpirations();
        String titleRaw = getConfig().getString("titles.your-items", "&8VẬT PHẨM CỦA BẠN");
        AmberAHHolder holder = new AmberAHHolder(GuiType.YOUR_ITEMS);
        Inventory inv = Bukkit.createInventory(holder, 54, color(titleRaw));
        holder.setInventory(inv);

        int slot = 0;
        long now = System.currentTimeMillis();
        for (AuctionListing listing : activeListings.values()) {
            if (!listing.sellerUuid.equals(player.getUniqueId())) continue;
            if (slot >= 45) break;

            ItemStack display = listing.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(color("&8&m--------------------"));
                lore.add(color("&7Trạng thái: &aĐang treo bán"));
                lore.add(color("&7Giá bán: &e$" + formatPrice(listing.price)));
                lore.add(color("&7Còn lại: &f" + formatDuration(listing.expiresAt - now)));
                lore.add(Component.empty());
                lore.add(color("&c▸ Bấm để gỡ bán và thu hồi"));
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            holder.slotListings.put(slot, listing.id);
            inv.setItem(slot++, display);
        }

        List<ItemStack> expList = expiredItems.getOrDefault(player.getUniqueId(), List.of());
        for (int i = 0; i < expList.size() && slot < 45; i++) {
            ItemStack raw = expList.get(i);
            ItemStack display = raw.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(color("&8&m--------------------"));
                lore.add(color("&7Trạng thái: &cĐã hết hạn"));
                lore.add(Component.empty());
                lore.add(color("&a▸ Bấm để nhận lại vào túi"));
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            holder.expiredSlotIndex.put(slot, i);
            inv.setItem(slot++, display);
        }

        inv.setItem(49, createButton(Material.ARROW, "&cQuay lại Chợ Đấu Giá",
                List.of("&7Trở về danh sách đấu giá chính.")));

        if (!expList.isEmpty()) {
            inv.setItem(53, createButton(Material.EMERALD, "&aNhận tất cả vật phẩm hết hạn",
                    List.of("&7Thu hồi toàn bộ &f" + expList.size() + " &7vật phẩm hết hạn.")));
        }

        player.openInventory(inv);
    }

    private void openShulkerPreview(Player player, ItemStack shulkerItem) {
        if (!(shulkerItem.getItemMeta() instanceof BlockStateMeta bsm)) return;
        if (!(bsm.getBlockState() instanceof ShulkerBox shulkerBox)) return;

        String titleRaw = getConfig().getString("titles.shulker", "&8XEM TRƯỚC SHULKER BOX");
        AmberAHHolder holder = new AmberAHHolder(GuiType.SHULKER_PREVIEW);
        Inventory inv = Bukkit.createInventory(holder, 27, color(titleRaw));
        holder.setInventory(inv);
        inv.setContents(shulkerBox.getInventory().getContents());

        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AmberAHHolder holder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        ViewerState state = getState(player.getUniqueId());

        if (holder.type == GuiType.MAIN) {
            if (slot < 45) {
                String id = holder.slotListings.get(slot);
                if (id == null) return;
                AuctionListing listing = activeListings.get(id);
                if (listing == null || System.currentTimeMillis() >= listing.expiresAt) {
                    sendMsg(player, "already-sold");
                    openMainGui(player);
                    return;
                }

                if (event.getClick() == ClickType.RIGHT && isShulkerBox(listing.item)) {
                    openShulkerPreview(player, listing.item);
                    return;
                }

                if (listing.sellerUuid.equals(player.getUniqueId())) {
                    activeListings.remove(id);
                    saveData();
                    giveOrDrop(player, listing.item);
                    sendMsg(player, "cancelled");
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
                    openMainGui(player);
                    return;
                }

                openConfirmGui(player, listing);
                return;
            }

            switch (slot) {
                case 45 -> {
                    if (state.page > 0) {
                        state.page--;
                        openMainGui(player);
                    }
                }
                case 47 -> {
                    state.category = state.category.next();
                    state.page = 0;
                    openMainGui(player);
                }
                case 48 -> {
                    state.sort = state.sort.next();
                    state.page = 0;
                    openMainGui(player);
                }
                case 49 -> {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                    openMainGui(player);
                }
                case 50 -> {
                    if (event.getClick() == ClickType.RIGHT && state.searchQuery != null && !state.searchQuery.isEmpty()) {
                        state.searchQuery = "";
                        state.page = 0;
                        openMainGui(player);
                    } else {
                        pendingSearch.put(player.getUniqueId(), state);
                        player.closeInventory();
                        sendMsg(player, "search-prompt");
                    }
                }
                case 51 -> openYourItemsGui(player);
                case 53 -> {
                    state.page++;
                    openMainGui(player);
                }
            }
            return;
        }

        if (holder.type == GuiType.CONFIRM) {
            if (slot == 15) {
                openMainGui(player);
                return;
            }
            if (slot == 11 && holder.confirmListingId != null) {
                AuctionListing listing = activeListings.get(holder.confirmListingId);
                if (listing == null || System.currentTimeMillis() >= listing.expiresAt) {
                    sendMsg(player, "already-sold");
                    openMainGui(player);
                    return;
                }
                if (listing.sellerUuid.equals(player.getUniqueId())) {
                    sendMsg(player, "cannot-buy-own");
                    openMainGui(player);
                    return;
                }
                if (!economy.has(player, listing.price)) {
                    String msg = getRawMsg("not-enough-money").replace("%price%", formatPrice(listing.price));
                    player.sendMessage(color(getPrefix() + msg));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    return;
                }

                activeListings.remove(listing.id);
                economy.withdrawPlayer(player, listing.price);
                OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.sellerUuid);
                economy.depositPlayer(seller, listing.price);
                saveData();

                giveOrDrop(player, listing.item);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);

                String itemName = getItemDisplayName(listing.item);
                String formattedPrice = formatPrice(listing.price);
                String boughtMsg = getRawMsg("bought")
                        .replace("%amount%", String.valueOf(listing.item.getAmount()))
                        .replace("%item%", itemName)
                        .replace("%seller%", listing.sellerName)
                        .replace("%price%", formattedPrice);
                player.sendMessage(color(getPrefix() + boughtMsg));

                Player onlineSeller = Bukkit.getPlayer(listing.sellerUuid);
                if (onlineSeller != null) {
                    onlineSeller.playSound(onlineSeller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                    String soldMsg = getRawMsg("sold-notify")
                            .replace("%buyer%", player.getName())
                            .replace("%amount%", String.valueOf(listing.item.getAmount()))
                            .replace("%item%", itemName)
                            .replace("%price%", formattedPrice);
                    onlineSeller.sendMessage(color(getPrefix() + soldMsg));
                }

                openMainGui(player);
            }
            return;
        }

        if (holder.type == GuiType.YOUR_ITEMS) {
            if (slot == 49) {
                openMainGui(player);
                return;
            }
            if (slot == 53) {
                List<ItemStack> list = expiredItems.remove(player.getUniqueId());
                if (list == null || list.isEmpty()) {
                    sendMsg(player, "no-expired");
                    return;
                }
                int count = list.size();
                for (ItemStack stack : list) {
                    giveOrDrop(player, stack);
                }
                saveData();
                String msg = getRawMsg("claimed-all").replace("%count%", String.valueOf(count));
                player.sendMessage(color(getPrefix() + msg));
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
                openYourItemsGui(player);
                return;
            }
            if (slot < 45) {
                String activeId = holder.slotListings.get(slot);
                if (activeId != null) {
                    AuctionListing listing = activeListings.remove(activeId);
                    if (listing != null) {
                        saveData();
                        giveOrDrop(player, listing.item);
                        sendMsg(player, "cancelled");
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
                    }
                    openYourItemsGui(player);
                    return;
                }
                Integer expIdx = holder.expiredSlotIndex.get(slot);
                if (expIdx != null) {
                    List<ItemStack> list = expiredItems.get(player.getUniqueId());
                    if (list != null && expIdx >= 0 && expIdx < list.size()) {
                        ItemStack removed = list.remove(expIdx.intValue());
                        if (list.isEmpty()) {
                            expiredItems.remove(player.getUniqueId());
                        }
                        saveData();
                        giveOrDrop(player, removed);
                        sendMsg(player, "claimed");
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
                    }
                    openYourItemsGui(player);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AmberAHHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof AmberAHHolder holder && holder.type == GuiType.SHULKER_PREVIEW) {
            Bukkit.getScheduler().runTask(this, () -> openMainGui(player));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ViewerState state = pendingSearch.remove(player.getUniqueId());
        if (state == null) return;

        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        Bukkit.getScheduler().runTask(this, () -> {
            if (input.equalsIgnoreCase("huy") || input.equalsIgnoreCase("cancel")) {
                sendMsg(player, "search-cancelled");
            } else {
                state.searchQuery = input;
                state.page = 0;
            }
            openMainGui(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        viewerStates.remove(uuid);
        pendingSearch.remove(uuid);
    }

    private ItemStack buildListingDisplayItem(AuctionListing listing, UUID viewerUuid, long now) {
        ItemStack display = listing.item.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) return display;

        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(color("&8&m--------------------"));
        lore.add(color("&7Giá bán: &a$" + formatPrice(listing.price)));
        lore.add(color("&7Người bán: &f" + listing.sellerName));
        lore.add(color("&7Hết hạn sau: &e" + formatDuration(listing.expiresAt - now)));
        lore.add(Component.empty());

        if (listing.sellerUuid.equals(viewerUuid)) {
            lore.add(color("&c▸ Chuột trái để gỡ bán"));
        } else {
            lore.add(color("&e▸ Chuột trái để mua"));
        }

        if (isShulkerBox(listing.item)) {
            lore.add(color("&b▸ Chuột phải để xem bên trong"));
        }

        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack createButton(Material mat, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(color(name));
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(line.isEmpty() ? Component.empty() : color(line));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isShulkerBox(ItemStack item) {
        return item != null && Tag.SHULKER_BOXES.isTagged(item.getType());
    }

    private boolean matchesSearch(ItemStack item, String query) {
        String matName = item.getType().name().toLowerCase(Locale.ROOT).replace("_", " ");
        if (matName.contains(query)) return true;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName() && meta.displayName() != null) {
                String custom = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT);
                if (custom.contains(query)) return true;
            }
            if (meta.hasLore() && meta.lore() != null) {
                for (Component line : meta.lore()) {
                    String plain = PlainTextComponentSerializer.plainText().serialize(line).toLowerCase(Locale.ROOT);
                    if (plain.contains(query)) return true;
                }
            }
        }
        return false;
    }

    private String getItemDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        String[] words = item.getType().name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private int countActiveByPlayer(UUID uuid) {
        int count = 0;
        for (AuctionListing l : activeListings.values()) {
            if (l.sellerUuid.equals(uuid)) count++;
        }
        return count;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
        for (ItemStack rem : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rem);
        }
    }

    private double parsePrice(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "");
        double multiplier = 1.0;
        if (s.endsWith("k")) {
            multiplier = 1_000.0;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            multiplier = 1_000_000.0;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("b")) {
            multiplier = 1_000_000_000.0;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("t")) {
            multiplier = 1_000_000_000_000.0;
            s = s.substring(0, s.length() - 1);
        }
        try {
            double val = Double.parseDouble(s) * multiplier;
            if (Double.isNaN(val) || Double.isInfinite(val) || val <= 0) return -1;
            return val;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String formatPrice(double price) {
        if (price >= 1_000_000_000_000.0) {
            return priceFormat.format(price / 1_000_000_000_000.0) + "T";
        }
        if (price >= 1_000_000_000.0) {
            return priceFormat.format(price / 1_000_000_000.0) + "B";
        }
        if (price >= 1_000_000.0) {
            return priceFormat.format(price / 1_000_000.0) + "M";
        }
        if (price >= 1_000.0) {
            return priceFormat.format(price / 1_000.0) + "K";
        }
        return priceFormat.format(price);
    }

    private String formatDuration(long millis) {
        long totalSec = Math.max(0L, millis / 1000L);
        if (totalSec < 60L) return "< 1p";
        long days = totalSec / 86400L;
        long hours = (totalSec % 86400L) / 3600L;
        long mins = (totalSec % 3600L) / 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (mins > 0) sb.append(mins).append("p ");
        return sb.toString().trim();
    }

    private String getPrefix() {
        return getConfig().getString("messages.prefix", "&#00d2ff&lAmberAH &8» &r");
    }

    private String getRawMsg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    private void sendMsg(Player player, String key) {
        player.sendMessage(color(getPrefix() + getRawMsg(key)));
    }

    private Component color(String text) {
        return Component.text()
                .decoration(TextDecoration.ITALIC, false)
                .append(legacy.deserialize(text))
                .build();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("amberah.admin")) {
                sender.sendMessage(color(getPrefix() + getRawMsg("no-permission")));
                return true;
            }
            reloadConfig();
            sender.sendMessage(color(getPrefix() + getRawMsg("reloaded")));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(getPrefix() + getRawMsg("player-only")));
            return true;
        }

        if (args.length == 0) {
            ViewerState state = getState(player.getUniqueId());
            state.page = 0;
            openMainGui(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("sell") || sub.equals("ban")) {
            if (args.length < 2) {
                sendMsg(player, "usage");
                return true;
            }

            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir() || hand.getAmount() <= 0) {
                sendMsg(player, "hold-item");
                return true;
            }

            double price = parsePrice(args[1]);
            if (price <= 0) {
                sendMsg(player, "invalid-price");
                return true;
            }

            double minPrice = getConfig().getDouble("min-price", 1.0);
            double maxPrice = getConfig().getDouble("max-price", 1_000_000_000_000_000.0);
            if (price < minPrice || price > maxPrice) {
                String msg = getRawMsg("price-range")
                        .replace("%min%", formatPrice(minPrice))
                        .replace("%max%", formatPrice(maxPrice));
                player.sendMessage(color(getPrefix() + msg));
                return true;
            }

            int amount = hand.getAmount();
            if (args.length >= 3) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    amount = -1;
                }
                if (amount <= 0 || amount > hand.getAmount()) {
                    String msg = getRawMsg("invalid-amount").replace("%max%", String.valueOf(hand.getAmount()));
                    player.sendMessage(color(getPrefix() + msg));
                    return true;
                }
            }

            int maxListings = Math.max(1, getConfig().getInt("max-listings", 10));
            if (countActiveByPlayer(player.getUniqueId()) >= maxListings) {
                String msg = getRawMsg("max-listings-reached").replace("%max%", String.valueOf(maxListings));
                player.sendMessage(color(getPrefix() + msg));
                return true;
            }

            ItemStack toList = hand.clone();
            toList.setAmount(amount);

            if (amount == hand.getAmount()) {
                player.getInventory().setItemInMainHand(null);
            } else {
                hand.setAmount(hand.getAmount() - amount);
            }

            long now = System.currentTimeMillis();
            long durationMillis = Math.max(1L, getConfig().getLong("listing-duration-hours", 72L)) * 3600_000L;
            String id = UUID.randomUUID().toString();

            activeListings.put(id, new AuctionListing(
                    id, player.getUniqueId(), player.getName(), toList, price, now, now + durationMillis
            ));
            saveData();

            String msg = getRawMsg("listed")
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%item%", getItemDisplayName(toList))
                    .replace("%price%", formatPrice(price));
            player.sendMessage(color(getPrefix() + msg));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            return true;
        }

        if (sub.equals("expired") || sub.equals("my")) {
            openYourItemsGui(player);
            return true;
        }

        if (sub.equals("search") || sub.equals("tim")) {
            ViewerState state = getState(player.getUniqueId());
            if (args.length >= 2) {
                state.searchQuery = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                state.page = 0;
            } else {
                state.searchQuery = "";
            }
            openMainGui(player);
            return true;
        }

        ViewerState state = getState(player.getUniqueId());
        state.searchQuery = String.join(" ", args);
        state.page = 0;
        openMainGui(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String last = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("sell", "search", "expired"));
            if (sender.hasPermission("amberah.admin")) opts.add("reload");
            return filterPrefix(opts, last);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("sell") || args[0].equalsIgnoreCase("ban"))) {
            return filterPrefix(List.of("10k", "1m", "1b", "1t"), last);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("sell") || args[0].equalsIgnoreCase("ban"))) {
            return filterPrefix(List.of("1", "16", "32", "64"), last);
        }
        return List.of();
    }

    private List<String> filterPrefix(List<String> list, String prefix) {
        if (prefix.isEmpty()) return list;
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(s);
        }
        return out;
    }

    private enum GuiType {
        MAIN, CONFIRM, YOUR_ITEMS, SHULKER_PREVIEW
    }

    private enum FilterCategory {
        ALL("Tất cả"),
        TOOLS("Công cụ"),
        COMBAT("Vũ khí & Giáp"),
        BLOCKS("Khối xây dựng"),
        CONSUMABLES("Thuốc & Sách"),
        MISC("Khác");

        final String label;

        FilterCategory(String label) {
            this.label = label;
        }

        FilterCategory next() {
            FilterCategory[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        boolean matches(Material mat) {
            if (this == ALL) return true;
            String name = mat.name();
            boolean isTool = name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                    || name.endsWith("_HOE") || mat == Material.SHEARS || mat == Material.FISHING_ROD
                    || mat == Material.FLINT_AND_STEEL;
            boolean isCombat = name.endsWith("_SWORD") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || mat == Material.BOW
                    || mat == Material.CROSSBOW || mat == Material.TRIDENT || mat == Material.MACE
                    || mat == Material.SHIELD || mat == Material.ELYTRA || mat == Material.TOTEM_OF_UNDYING;
            boolean isConsumable = mat == Material.POTION || mat == Material.SPLASH_POTION
                    || mat == Material.LINGERING_POTION || mat == Material.ENCHANTED_BOOK
                    || mat == Material.GOLDEN_APPLE || mat == Material.ENCHANTED_GOLDEN_APPLE
                    || mat == Material.ENDER_PEARL || mat == Material.EXPERIENCE_BOTTLE
                    || mat == Material.FIREWORK_ROCKET;
            boolean isBlock = mat.isBlock();

            return switch (this) {
                case TOOLS -> isTool;
                case COMBAT -> isCombat;
                case BLOCKS -> isBlock;
                case CONSUMABLES -> isConsumable;
                case MISC -> !isTool && !isCombat && !isBlock && !isConsumable;
                default -> true;
            };
        }
    }

    private enum SortMode {
        NEWEST("Mới đăng"),
        PRICE_ASC("Giá thấp nhất"),
        PRICE_DESC("Giá cao nhất"),
        EXPIRING("Sắp hết hạn");

        final String label;

        SortMode(String label) {
            this.label = label;
        }

        SortMode next() {
            SortMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    private static final class ViewerState {
        int page = 0;
        FilterCategory category = FilterCategory.ALL;
        SortMode sort = SortMode.NEWEST;
        String searchQuery = "";
    }

    private static final class AuctionListing {
        final String id;
        final UUID sellerUuid;
        final String sellerName;
        final ItemStack item;
        final double price;
        final long createdAt;
        final long expiresAt;

        AuctionListing(String id, UUID sellerUuid, String sellerName, ItemStack item, double price, long createdAt, long expiresAt) {
            this.id = id;
            this.sellerUuid = sellerUuid;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    private static final class AmberAHHolder implements InventoryHolder {
        final GuiType type;
        final Map<Integer, String> slotListings = new HashMap<>();
        final Map<Integer, Integer> expiredSlotIndex = new HashMap<>();
        String confirmListingId;
        private Inventory inventory;

        AmberAHHolder(GuiType type) {
            this.type = type;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
