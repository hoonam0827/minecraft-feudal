package com.example.feudal.merchant;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

import java.util.Map;

public class MerchantService {

    private final JavaPlugin plugin;
    private final MerchantShopStorage storage;
    private final MerchantKeys keys;

    public MerchantService(JavaPlugin plugin, MerchantShopStorage storage, MerchantKeys keys) {
        this.plugin = plugin;
        this.storage = storage;
        this.keys = keys;
    }

    public void openShop(Player p, int npcId) {
        String title = storage.getTitle(npcId);
        int size = storage.getSize(npcId);

        Inventory inv = Bukkit.createInventory(null, size, title);

        Map<Integer, MerchantShopStorage.ShopItem> items = storage.loadItems(npcId);
        for (var e : items.entrySet()) {
            int slot = e.getKey();
            var si = e.getValue();

            ItemStack display = keys.applyPriceTag(si.item().clone(), si.price(), true);
            inv.setItem(slot, display);
        }

        p.openInventory(inv);
    }

    public void openEditor(Player p, int npcId) {
        String title = "§d[편집] " + storage.getTitle(npcId);
        int size = storage.getSize(npcId);

        Inventory inv = Bukkit.createInventory(null, size, title);

        Map<Integer, MerchantShopStorage.ShopItem> items = storage.loadItems(npcId);
        for (var e : items.entrySet()) {
            int slot = e.getKey();
            var si = e.getValue();

            ItemStack editItem = keys.applyPriceTag(si.item().clone(), si.price(), true);
            inv.setItem(slot, editItem);
        }

        p.openInventory(inv);
    }

    public MerchantShopStorage storage() { return storage; }
    public MerchantKeys keys() { return keys; }
}