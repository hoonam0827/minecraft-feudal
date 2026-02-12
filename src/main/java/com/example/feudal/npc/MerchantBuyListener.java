package com.example.feudal.npc;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MerchantBuyListener implements Listener {

    private static final String SHOP_TITLE_PREFIX = "§6상인";

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String title = e.getView().getTitle();
        if (title == null || !title.startsWith(SHOP_TITLE_PREFIX)) return;

        // 상점 GUI는 클릭으로 아이템 이동 못하게 막기
        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        int cost = parseCost(clicked);
        if (cost <= 0) {
            p.sendMessage("§c이 상품은 가격이 설정되어 있지 않아!");
            return;
        }

        if (!hasEmerald(p, cost)) {
            p.sendMessage("§c에메랄드가 부족해! (§e" + cost + "§c 필요)");
            return;
        }

        removeEmerald(p, cost);

        ItemStack give = removePriceLore(clicked.clone());

        Map<Integer, ItemStack> leftover = p.getInventory().addItem(give);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(it ->
                    p.getWorld().dropItemNaturally(p.getLocation(), it)
            );
        }

        p.sendMessage("§a구매 완료! §e-" + cost + " §a에메랄드");
    }

    private int parseCost(ItemStack item) {
        if (!item.hasItemMeta()) return -1;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getLore() == null) return -1;

        for (String line : meta.getLore()) {
            String plain = stripColor(line).trim();

            if (!plain.contains("가격")) continue;


            String digits = plain.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) continue;

            try { return Integer.parseInt(digits); }
            catch (Exception ignored) {}
        }
        return -1;
    }

    private String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }


    private ItemStack removePriceLore(ItemStack item) {
        if (!item.hasItemMeta()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getLore() == null) return item;

        List<String> lore = meta.getLore();
        List<String> newLore = new ArrayList<>();

        for (String line : lore) {
            String plain = stripColor(line);
            if (plain.contains("가격")) continue;
            newLore.add(line);
        }

        meta.setLore(newLore.isEmpty() ? null : newLore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasEmerald(Player p, int amount) {
        return p.getInventory().containsAtLeast(new ItemStack(Material.EMERALD), amount);
    }

    private void removeEmerald(Player p, int amount) {
        int left = amount;
        var inv = p.getInventory();

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != Material.EMERALD) continue;

            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left -= take;

            if (it.getAmount() <= 0) inv.setItem(i, null);
            if (left <= 0) return;
        }
    }
}