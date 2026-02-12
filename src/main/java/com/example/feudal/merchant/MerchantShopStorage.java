package com.example.feudal.merchant;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MerchantShopStorage {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration yml;

    public MerchantShopStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shops.yml");
        reload();
    }

    public void reload() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("[MerchantShopStorage] shops.yml 생성 실패: " + e.getMessage());
            }
        }
        yml = YamlConfiguration.loadConfiguration(file);
    }

    public String getTitle(int npcId) {
        String path = "npc-" + npcId + ".title";
        return yml.getString(path, "§6상인 #" + npcId);
    }

    public int getSize(int npcId) {
        int size = yml.getInt("npc-" + npcId + ".size", 54);
        // 9의 배수로 보정(최소 9, 최대 54)
        size = Math.max(9, Math.min(54, size));
        size = ((size + 8) / 9) * 9;
        return size;
    }

    public Map<Integer, ShopItem> loadItems(int npcId) {
        Map<Integer, ShopItem> map = new HashMap<>();
        String base = "npc-" + npcId + ".items";
        ConfigurationSection sec = yml.getConfigurationSection(base);
        if (sec == null) return map;

        for (String key : sec.getKeys(false)) {
            String p = base + "." + key;
            int slot = yml.getInt(p + ".slot", -1);
            int price = yml.getInt(p + ".price", -1);

            Object raw = yml.get(p + ".item");
            ItemStack item = null;
            if (raw instanceof Map<?, ?> rawMap) {
                try {
                    //noinspection unchecked
                    item = ItemStack.deserialize((Map<String, Object>) rawMap);
                } catch (Exception ignored) {}
            }

            if (slot < 0 || slot >= 54) continue;
            if (price <= 0) continue;
            if (item == null || item.getType() == Material.AIR) continue;

            map.put(slot, new ShopItem(item, price));
        }
        return map;
    }

    public void saveFromEditorInventory(int npcId, int size, String title, ItemStack[] contents, PriceReader priceReader) {
        String root = "npc-" + npcId;
        yml.set(root + ".title", title);
        yml.set(root + ".size", size);

        // 기존 items 싹 비우고 다시 저장
        yml.set(root + ".items", null);

        int idx = 0;
        for (int slot = 0; slot < Math.min(contents.length, size); slot++) {
            ItemStack it = contents[slot];
            if (it == null || it.getType() == Material.AIR) continue;

            int price = priceReader.getPrice(it);
            if (price <= 0) continue;

            String p = root + ".items." + idx;
            yml.set(p + ".slot", slot);
            yml.set(p + ".price", price);
            yml.set(p + ".item", it.serialize());
            idx++;
        }

        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[MerchantShopStorage] shops.yml 저장 실패: " + e.getMessage());
        }
    }

    public record ShopItem(ItemStack item, int price) {}

    @FunctionalInterface
    public interface PriceReader {
        int getPrice(ItemStack item);
    }
}