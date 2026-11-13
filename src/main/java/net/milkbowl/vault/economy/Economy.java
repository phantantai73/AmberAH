package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

public interface Economy {
    boolean has(OfflinePlayer player, double amount);
    EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);
    EconomyResponse depositPlayer(OfflinePlayer player, double amount);
}
