package com.example.feudal.merchant;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.InventoryView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class MerchantEditListener implements Listener {

    private final JavaPlugin plugin;
    private final MerchantService service;

    private final Map<UUID, PendingPrice> pending = new HashMap<>();

    private record PendingPrice(int npcId, int slot) {}

    public MerchantEditListener(JavaPlugin plugin, MerchantService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private boolean isEditor(InventoryView view) {
        String t = view.getTitle();
        return t != null && t.startsWith("§d[편집]");
    }

    @EventHandler
    public void onEditorClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isEditor(e.getView())) return;

        // 편집 GUI는 기본 아이템 이동 허용(취소 안 함)
        if (e.getClick() != ClickType.SHIFT_RIGHT) return;

        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() == Material.AIR) return;

        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;

        int npcId = extractNpcIdFromTitle(e.getView().getTitle());
        if (npcId < 0) {
            p.sendMessage("§c상점 NPC 정보를 읽지 못했어. 제목 형식을 확인해줘!");
            return;
        }

        pending.put(p.getUniqueId(), new PendingPrice(npcId, slot));
        p.sendMessage("§e가격을 채팅으로 입력해줘. (예: 12)  / 취소: cancel");
    }

    @EventHandler
    public void onEditorClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!isEditor(e.getView())) return;

        int npcId = extractNpcIdFromTitle(e.getView().getTitle());
        if (npcId < 0) return;

        Inventory inv = e.getInventory();
        String title = service.storage().getTitle(npcId);
        int size = inv.getSize();

        service.storage().saveFromEditorInventory(
                npcId,
                size,
                title,
                inv.getContents(),
                service.keys()::getPrice
        );

        pending.remove(p.getUniqueId());
        p.sendMessage("§a상점 저장 완료! (npcId=" + npcId + ")");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        PendingPrice pp = pending.get(p.getUniqueId());
        if (pp == null) return;

        e.setCancelled(true);

        String msg = e.getMessage().trim();
        if (msg.equalsIgnoreCase("cancel")) {
            pending.remove(p.getUniqueId());
            p.sendMessage("§7가격 설정 취소됨.");
            return;
        }

        int price;
        try { price = Integer.parseInt(msg); }
        catch (Exception ex) { p.sendMessage("§c숫자로 입력해줘! 예: 12"); return; }

        if (price <= 0) { p.sendMessage("§c가격은 1 이상!"); return; }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isEditor(p.getOpenInventory())) {
                pending.remove(p.getUniqueId());
                p.sendMessage("§c편집 GUI가 닫혀서 취소됐어.");
                return;
            }

            Inventory inv = p.getOpenInventory().getTopInventory();
            if (pp.slot < 0 || pp.slot >= inv.getSize()) {
                pending.remove(p.getUniqueId());
                p.sendMessage("§c슬롯 오류로 취소됐어.");
                return;
            }

            ItemStack it = inv.getItem(pp.slot);
            if (it == null || it.getType() == Material.AIR) {
                p.sendMessage("§c그 슬롯에 아이템이 없어!");
                return;
            }

            inv.setItem(pp.slot, service.keys().applyPriceTag(it, price, true));
            p.sendMessage("§a가격 설정 완료: §e" + price + " §a에메랄드");
            pending.remove(p.getUniqueId());
        });
    }

    private int extractNpcIdFromTitle(String title) {
        // 제목: "§d[편집] §6상인 #12" 또는 "§6상인 #12"
        String plain = title.replaceAll("§.", "");
        int idx = plain.lastIndexOf("#");
        if (idx < 0) return -1;
        try { return Integer.parseInt(plain.substring(idx + 1).trim()); }
        catch (Exception ignored) { return -1; }
    }
}