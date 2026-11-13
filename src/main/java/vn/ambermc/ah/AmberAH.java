package vn.ambermc.ah;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
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
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AmberAH extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Economy economy;
    private File dataFile;
    private YamlConfiguration dataConfig;

    private final Map<String, AuctionListing> activeListings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> expiredItems = new HashMap<>();
    private final List<TransactionRecord> transactionHistory = new ArrayList<>();
    private final Map<UUID, ViewerState> viewerStates = new HashMap<>();
    private final Map<UUID, Location> activeSignSessions = new HashMap<>();
    private final Set<UUID> transitioningGui = new HashSet<>();

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private final DecimalFormat priceFormat = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.US));
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Override
    public void onEnable() {
        if (getResource("config.yml") != null) {
            saveDefaultConfig();
        } else {
            writeDefaultConfigIfMissing();
        }
        reloadConfig();

        loadData();
        setupEconomy();
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
        for (UUID uuid : new ArrayList<>(activeSignSessions.keySet())) {
            cleanupSignSession(uuid);
        }
        saveData();
    }

    private void writeDefaultConfigIfMissing() {
        File cfgFile = new File(getDataFolder(), "config.yml");
        if (cfgFile.exists()) return;
        try {
            getDataFolder().mkdirs();
            String defaultYaml = """
                    max-listings: 10
                    listing-duration-hours: 72
                    min-price: 1.0
                    max-price: 1000000000000000.0
                    titles:
                      main: "&8CHỢ ĐẤU GIÁ (Trang %page%)"
                      confirm: "&8XÁC NHẬN MUA HÀNG"
                      your-items: "&8VẬT PHẨM CỦA BẠN"
                      history: "&8LỊCH SỬ GIAO DỊCH (Trang %page%)"
                      shulker: "&8XEM TRƯỚC SHULKER BOX"
                    messages:
                      prefix: "&#00d2ff&lAmberAH &8» &r"
                      no-permission: "&cBạn không có quyền thực hiện lệnh này."
                      player-only: "&cLệnh này chỉ dùng được trong game."
                      hold-item: "&cBạn phải cầm vật phẩm trên tay chính để treo bán!"
                      invalid-price: "&cGiá tiền không hợp lệ! Ví dụ: 500, 10k, 1.5m, 1b, 1t"
                      price-range: "&cGiá bán phải nằm trong khoảng từ &f$%min% &cđến &f$%max%&c."
                      invalid-amount: "&cSố lượng không hợp lệ (tối đa %max% vật phẩm trên tay)."
                      max-listings-reached: "&cBạn đã đạt giới hạn treo bán tối đa (&f%max% &cvật phẩm)!"
                      listed: "&aĐã treo bán &f%amount%x %item% &avới giá &e$%price%&a!"
                      bought: "&aBạn đã mua &f%amount%x %item% &acủa &f%seller% &avới giá &e$%price%&a!"
                      sold-notify: "&aNgười chơi &f%buyer% &ađã mua &f%amount%x %item% &acủa bạn (+&e$%price%&a)!"
                      not-enough-money: "&cBạn không đủ tiền để mua vật phẩm này (cần &e$%price%&c)."
                      cannot-buy-own: "&cBạn không thể tự mua vật phẩm của chính mình."
                      already-sold: "&cVật phẩm này vừa được người khác mua hoặc đã bị gỡ xuống."
                      cancelled: "&eĐã gỡ vật phẩm khỏi chợ và trả lại vào túi đồ."
                      claimed: "&aĐã nhận lại vật phẩm hết hạn vào túi đồ."
                      claimed-all: "&aĐã nhận lại toàn bộ &f%count% &avật phẩm vào túi đồ!"
                      no-expired: "&cBạn không có vật phẩm hết hạn nào để nhận."
                      reloaded: "&aĐã tải lại cấu hình AmberAH!"
                      usage: "&cSử dụng: /ah [sell <giá> [số_lượng] | search <tên> | expired | history | reload]"
                    """;
            Files.writeString(cfgFile.toPath(), defaultYaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == Economy.class) {
            setupEconomy();
        }
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
        transactionHistory.clear();

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

        ConfigurationSection histSec = dataConfig.getConfigurationSection("history");
        if (histSec != null) {
            for (String id : histSec.getKeys(false)) {
                ConfigurationSection entry = histSec.getConfigurationSection(id);
                if (entry == null) continue;
                ItemStack item = entry.getItemStack("item");
                String buyerStr = entry.getString("buyer-uuid");
                String buyerName = entry.getString("buyer-name", "Unknown");
                String sellerStr = entry.getString("seller-uuid");
                String sellerName = entry.getString("seller-name", "Unknown");
                double price = entry.getDouble("price");
                long timestamp = entry.getLong("timestamp");

                if (item != null && !item.getType().isAir() && buyerStr != null && sellerStr != null) {
                    transactionHistory.add(new TransactionRecord(
                            id,
                            UUID.fromString(buyerStr),
                            buyerName,
                            UUID.fromString(sellerStr),
                            sellerName,
                            item,
                            price,
                            timestamp
                    ));
                }
            }
            transactionHistory.sort(Comparator.comparingLong((TransactionRecord r) -> r.timestamp).reversed());
        }

        checkExpirations();
    }

    private void saveData() {
        if (dataFile == null) return;
        if (dataConfig == null) dataConfig = new YamlConfiguration();
        dataConfig.set("active", null);
        dataConfig.set("expired", null);
        dataConfig.set("history", null);

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

        for (TransactionRecord rec : transactionHistory) {
            String path = "history." + rec.id;
            dataConfig.set(path + ".buyer-uuid", rec.buyerUuid.toString());
            dataConfig.set(path + ".buyer-name", rec.buyerName);
            dataConfig.set(path + ".seller-uuid", rec.sellerUuid.toString());
            dataConfig.set(path + ".seller-name", rec.sellerName);
            dataConfig.set(path + ".item", rec.item);
            dataConfig.set(path + ".price", rec.price);
            dataConfig.set(path + ".timestamp", rec.timestamp);
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

    private void resetState(UUID uuid) {
        ViewerState state = getState(uuid);
        state.page = 0;
        state.historyPage = 0;
        state.category = FilterCategory.ALL;
        state.sort = SortMode.NEWEST;
        state.searchQuery = "";
    }

    private void openHolderInventory(Player player, Inventory inv) {
        UUID uuid = player.getUniqueId();
        transitioningGui.add(uuid);
        try {
            player.openInventory(inv);
        } finally {
            transitioningGui.remove(uuid);
        }
    }

    private void openMainGui(Player player) {
        cleanupSignSession(player.getUniqueId());
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

        int personalHistoryCount = countHistoryByPlayer(player.getUniqueId());
        inv.setItem(46, createButton(Material.BOOK, "&6Lịch sử giao dịch",
                List.of(
                        "&7Xem lại các vật phẩm đã mua",
                        "&7và đã bán thành công của bạn.",
                        "&7Tổng giao dịch: &f" + personalHistoryCount,
                        "",
                        "&eBấm để xem lịch sử!"
                )));

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
            searchLore.add("&7Tìm kiếm vật phẩm qua bảng hiệu.");
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

        openHolderInventory(player, inv);
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

        openHolderInventory(player, inv);
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

        openHolderInventory(player, inv);
    }

    private void openHistoryGui(Player player) {
        UUID uuid = player.getUniqueId();
        ViewerState state = getState(uuid);

        List<TransactionRecord> personal = new ArrayList<>();
        for (TransactionRecord rec : transactionHistory) {
            if (rec.buyerUuid.equals(uuid) || rec.sellerUuid.equals(uuid)) {
                personal.add(rec);
            }
        }

        int totalPages = Math.max(1, (int) Math.ceil(personal.size() / 45.0));
        if (state.historyPage >= totalPages) state.historyPage = totalPages - 1;
        if (state.historyPage < 0) state.historyPage = 0;

        String titleRaw = getConfig().getString("titles.history", "&8LỊCH SỬ GIAO DỊCH (Trang %page%)")
                .replace("%page%", String.valueOf(state.historyPage + 1));
        AmberAHHolder holder = new AmberAHHolder(GuiType.HISTORY);
        Inventory inv = Bukkit.createInventory(holder, 54, color(titleRaw));
        holder.setInventory(inv);

        if (personal.isEmpty()) {
            inv.setItem(22, createButton(Material.BARRIER, "&cChưa có lịch sử giao dịch",
                    List.of("&7Bạn chưa mua hoặc bán vật phẩm nào trên chợ.")));
        } else {
            int start = state.historyPage * 45;
            int end = Math.min(start + 45, personal.size());

            for (int i = start; i < end; i++) {
                int slot = i - start;
                TransactionRecord rec = personal.get(i);
                ItemStack display = rec.item.clone();
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.add(color("&8&m--------------------"));
                    if (rec.buyerUuid.equals(uuid)) {
                        lore.add(color("&7Giao dịch: &aĐã mua hàng"));
                        lore.add(color("&7Mua của: &f" + rec.sellerName));
                        lore.add(color("&7Người mua: &f" + rec.buyerName + " &7(Bạn)"));
                        lore.add(color("&7Giá đã trả: &e$" + formatPrice(rec.price)));
                    } else {
                        lore.add(color("&7Giao dịch: &bĐã bán hàng"));
                        lore.add(color("&7Người bán: &f" + rec.sellerName + " &7(Bạn)"));
                        lore.add(color("&7Người mua: &f" + rec.buyerName));
                        lore.add(color("&7Tiền thu về: &a+$" + formatPrice(rec.price)));
                    }
                    lore.add(color("&7Thời gian: &f" + timeFormatter.format(Instant.ofEpochMilli(rec.timestamp))));
                    meta.lore(lore);
                    display.setItemMeta(meta);
                }
                inv.setItem(slot, display);
            }
        }

        if (state.historyPage > 0) {
            inv.setItem(45, createButton(Material.ARROW, "&aTrang trước",
                    List.of("&7Quay lại trang " + state.historyPage)));
        }

        inv.setItem(49, createButton(Material.ARROW, "&cQuay lại Chợ Đấu Giá",
                List.of("&7Trở về danh sách đấu giá chính.")));

        if (state.historyPage + 1 < totalPages) {
            inv.setItem(53, createButton(Material.ARROW, "&aTrang sau",
                    List.of("&7Chuyển sang trang " + (state.historyPage + 2))));
        }

        openHolderInventory(player, inv);
    }

    private void openShulkerPreview(Player player, ItemStack shulkerItem) {
        if (!(shulkerItem.getItemMeta() instanceof BlockStateMeta bsm)) return;
        if (!(bsm.getBlockState() instanceof ShulkerBox shulkerBox)) return;

        String titleRaw = getConfig().getString("titles.shulker", "&8XEM TRƯỚC SHULKER BOX");
        AmberAHHolder holder = new AmberAHHolder(GuiType.SHULKER_PREVIEW);
        Inventory inv = Bukkit.createInventory(holder, 27, color(titleRaw));
        holder.setInventory(inv);
        inv.setContents(shulkerBox.getInventory().getContents());

        openHolderInventory(player, inv);
    }

    private void openSignSearch(Player player) {
        UUID uuid = player.getUniqueId();
        cleanupSignSession(uuid);

        Location signLoc = findAirLocationNear(player);
        if (signLoc == null) {
            player.sendMessage(color(getPrefix() + "&cKhông tìm thấy khoảng trống quanh bạn để mở bảng tìm kiếm!"));
            return;
        }

        Block block = signLoc.getBlock();
        block.setType(Material.OAK_SIGN, false);
        if (!(block.getState() instanceof Sign sign)) {
            block.setType(Material.AIR, false);
            return;
        }

        sign.setWaxed(false);
        SignSide front = sign.getSide(Side.FRONT);
        front.setLine(0, "");
        front.setLine(1, "^^^^^^^^^^^^^^^");
        front.setLine(2, "Nhập tên vật phẩm");
        front.setLine(3, "rồi bấm Xong");
        sign.update(true, false);

        activeSignSessions.put(uuid, signLoc);
        transitioningGui.add(uuid);
        try {
            player.closeInventory();
        } finally {
            transitioningGui.remove(uuid);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                cleanupSignSession(uuid);
                return;
            }
            Location currentLoc = activeSignSessions.get(uuid);
            if (currentLoc == null) return;
            Block currentBlock = currentLoc.getBlock();
            if (currentBlock.getState() instanceof Sign currentSign) {
                player.openSign(currentSign, Side.FRONT);
            } else {
                cleanupSignSession(uuid);
            }
        }, 2L);
    }

    private Location findAirLocationNear(Player player) {
        Location base = player.getLocation().getBlock().getLocation();
        World world = base.getWorld();
        if (world == null) return null;

        int[] dyOffsets = {2, 3, 1, 0, -1};
        for (int dy : dyOffsets) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int y = base.getBlockY() + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                    Block candidate = world.getBlockAt(base.getBlockX() + dx, y, base.getBlockZ() + dz);
                    if (candidate.getType().isAir()) {
                        return candidate.getLocation();
                    }
                }
            }
        }
        return null;
    }

    private void cleanupSignSession(UUID uuid) {
        Location loc = activeSignSessions.remove(uuid);
        if (loc != null && loc.getBlock().getType() == Material.OAK_SIGN) {
            loc.getBlock().setType(Material.AIR, false);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location signLoc = activeSignSessions.remove(uuid);
        if (signLoc == null) return;

        event.setCancelled(true);
        if (signLoc.getBlock().getType() == Material.OAK_SIGN) {
            signLoc.getBlock().setType(Material.AIR, false);
        }

        String rawLine = event.getLine(0);
        String query = rawLine == null ? "" : rawLine.trim();

        ViewerState state = getState(uuid);
        state.searchQuery = query;
        state.page = 0;

        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                openMainGui(player);
            }
        });
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
                case 46 -> {
                    state.historyPage = 0;
                    openHistoryGui(player);
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
                        openSignSearch(player);
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
                if (economy == null && !setupEconomy()) {
                    player.sendMessage(color(getPrefix() + "&cHệ thống tiền tệ chưa sẵn sàng, vui lòng thử lại."));
                    return;
                }
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

                transactionHistory.add(0, new TransactionRecord(
                        UUID.randomUUID().toString(),
                        player.getUniqueId(),
                        player.getName(),
                        listing.sellerUuid,
                        listing.sellerName,
                        listing.item.clone(),
                        listing.price,
                        System.currentTimeMillis()
                ));
                if (transactionHistory.size() > 300) {
                    transactionHistory.subList(300, transactionHistory.size()).clear();
                }

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
            return;
        }

        if (holder.type == GuiType.HISTORY) {
            if (slot == 45 && state.historyPage > 0) {
                state.historyPage--;
                openHistoryGui(player);
            } else if (slot == 49) {
                openMainGui(player);
            } else if (slot == 53) {
                state.historyPage++;
                openHistoryGui(player);
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
        if (!(event.getInventory().getHolder() instanceof AmberAHHolder holder)) return;

        UUID uuid = player.getUniqueId();
        if (transitioningGui.contains(uuid)) return;

        if (holder.type == GuiType.SHULKER_PREVIEW) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    openMainGui(player);
                }
            });
            return;
        }

        resetState(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cleanupSignSession(uuid);
        transitioningGui.remove(uuid);
        viewerStates.remove(uuid);
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

    private int countHistoryByPlayer(UUID uuid) {
        int count = 0;
        for (TransactionRecord rec : transactionHistory) {
            if (rec.buyerUuid.equals(uuid) || rec.sellerUuid.equals(uuid)) count++;
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
            resetState(player.getUniqueId());
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
            resetState(player.getUniqueId());
            openYourItemsGui(player);
            return true;
        }

        if (sub.equals("history") || sub.equals("lichsu")) {
            resetState(player.getUniqueId());
            openHistoryGui(player);
            return true;
        }

        if (sub.equals("search") || sub.equals("tim")) {
            resetState(player.getUniqueId());
            ViewerState state = getState(player.getUniqueId());
            if (args.length >= 2) {
                state.searchQuery = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                openMainGui(player);
            } else {
                openSignSearch(player);
            }
            return true;
        }

        resetState(player.getUniqueId());
        ViewerState state = getState(player.getUniqueId());
        state.searchQuery = String.join(" ", args);
        openMainGui(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String last = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("sell", "search", "expired", "history"));
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
        MAIN, CONFIRM, YOUR_ITEMS, HISTORY, SHULKER_PREVIEW
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
        int historyPage = 0;
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

    private static final class TransactionRecord {
        final String id;
        final UUID buyerUuid;
        final String buyerName;
        final UUID sellerUuid;
        final String sellerName;
        final ItemStack item;
        final double price;
        final long timestamp;

        TransactionRecord(String id, UUID buyerUuid, String buyerName, UUID sellerUuid, String sellerName, ItemStack item, double price, long timestamp) {
            this.id = id;
            this.buyerUuid = buyerUuid;
            this.buyerName = buyerName;
            this.sellerUuid = sellerUuid;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.timestamp = timestamp;
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
