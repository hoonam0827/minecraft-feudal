package com.example.feudal.merchant;

import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class MerchantKeys {

    private final NamespacedKey PRICE_KEY;

    public MerchantKeys(JavaPlugin plugin) {
        this.PRICE_KEY = new NamespacedKey(plugin, "merchant_price");
    }

    public int getPrice(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return -1;
        if (!item.hasItemMeta()) return -1;

        ItemMeta meta = item.getItemMeta();
        Integer v = meta.getPersistentDataContainer().get(PRICE_KEY, PersistentDataType.INTEGER);
        return (v == null ? -1 : v);
    }

    public ItemStack applyPriceTag(ItemStack item, int price, boolean addLoreLine) {
        if (item == null || item.getType() == Material.AIR) return item;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(PRICE_KEY, PersistentDataType.INTEGER, price);

        if (addLoreLine) {
            List<String> lore = meta.getLore();
            if (lore == null) lore = new ArrayList<>();

            lore.removeIf(s -> strip(s).startsWith("가격:"));
            lore.add("§7가격: §e" + price + " §7에메랄드");
            meta.setLore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack stripPriceTag(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return item;
        if (!item.hasItemMeta()) return item;

        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(PRICE_KEY);

        List<String> lore = meta.getLore();
        if (lore != null) {
            lore.removeIf(s -> strip(s).startsWith("가격:"));
            meta.setLore(lore.isEmpty() ? null : lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private String strip(String s) {
        if (s == null) return "";
        // 색코드 간단 제거(§)
        return s.replaceAll("§.", "");
    }
}