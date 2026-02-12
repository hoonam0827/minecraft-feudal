package com.example.feudal.merchant;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class MerchantPriceInputListener implements Listener {

    private static final String ANVIL_TITLE = "§e가격 입력";

    private final JavaPlugin plugin;

    // 플레이어가 "어느 npc shop", "어느 슬롯 아이템" 가격을 수정 중인지
    private final Map<UUID, EditContext> editing = new HashMap<>();

    public MerchantPriceInputListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private record EditContext(int npcId, int slot, Inventory editorInv, ItemStack baseItem) {}

    @EventHandler
    public void onEditorClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String title = e.getView().getTitle();
        if (!isEditorTitle(title)) return;

        if (e.getClickedInventory() == null) return;

        boolean top = (e.getClickedInventory() == e.getView().getTopInventory());

        if (!top) return;

        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        int slot = e.getSlot();

        int npcId = parseNpcIdFromTitle(title);
        if (npcId < 0) {
            p.sendMessage("§c에디터 제목에서 npcId를 못 읽었어. (예: [편집] 상인 #2)");
            return;
        }

        openPriceAnvil(p, npcId, slot, e.getView().getTopInventory(), clicked.clone());
    }

    private boolean isEditorTitle(String title) {
        if (title == null) return false;
        String plain = stripColor(title);
        return plain.contains("편집") && plain.contains("상인");
    }

    private int parseNpcIdFromTitle(String title) {
        String plain = stripColor(title).trim();
        String digits = plain.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try { return Integer.parseInt(digits); } catch (Exception e) { return -1; }
    }

    private void openPriceAnvil(Player p, int npcId, int slot, Inventory editorInv, ItemStack baseItem) {
        Inventory anvil = Bukkit.createInventory(p, InventoryType.ANVIL, ANVIL_TITLE);

        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta pm = paper.getItemMeta();
        if (pm != null) {
            pm.setDisplayName("§a여기에 가격 숫자 입력");
            pm.setLore(List.of("§7예: 12", "§7완료하려면 결과칸 클릭"));
            paper.setItemMeta(pm);
        }
        anvil.setItem(0, paper);

        editing.put(p.getUniqueId(), new EditContext(npcId, slot, editorInv, baseItem));
        p.openInventory(anvil);
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        if (!(e.getView().getPlayer() instanceof Player p)) return;

        EditContext ctx = editing.get(p.getUniqueId());
        if (ctx == null) return;

        String title = e.getView().getTitle();
        if (title == null || !title.equals(ANVIL_TITLE)) return;

        AnvilInventory inv = e.getInventory();

        String text;
        try {
            text = inv.getRenameText(); // Paper 권장
        } catch (Throwable t) {
            e.setResult(null);
            return;
        }

        int price = safeParseInt(text);
        if (price <= 0) {
            e.setResult(makeResultItem("§c가격은 1 이상", Material.BARRIER));
            return;
        }

        e.setResult(makeResultItem("§a설정: §e" + price + " §a에메랄드", Material.LIME_WOOL));
    }

    @EventHandler
    public void onAnvilClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        EditContext ctx = editing.get(p.getUniqueId());
        if (ctx == null) return;

        String title = e.getView().getTitle();
        if (title == null || !title.equals(ANVIL_TITLE)) return;

        e.setCancelled(true);

        // 결과칸(2)만 확정
        if (e.getRawSlot() != 2) return;

        if (!(e.getInventory() instanceof AnvilInventory anvil)) return;

        String text;
        try { text = anvil.getRenameText(); }
        catch (Throwable t) { p.sendMessage("§c가격 입력을 읽을 수 없어(Paper 권장)."); return; }

        int price = safeParseInt(text);
        if (price <= 0) { p.sendMessage("§c가격은 1 이상의 숫자만 가능!"); return; }

        ItemStack newItem = applyPriceLore(ctx.baseItem.clone(), price);
        ctx.editorInv.setItem(ctx.slot, newItem);

        p.sendMessage("§a가격 설정 완료: §e" + price);

        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(ctx.editorInv));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        String title = e.getView().getTitle();
        if (title != null && title.equals(ANVIL_TITLE)) {
            editing.remove(p.getUniqueId());
        }
    }

    // -------------------------
    // Utils
    // -------------------------
    private int safeParseInt(String s) {
        if (s == null) return -1;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try { return Integer.parseInt(digits); } catch (Exception e) { return -1; }
    }

    private ItemStack makeResultItem(String name, Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack applyPriceLore(ItemStack item, int price) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<String> lore = meta.getLore();
        List<String> newLore = new ArrayList<>();
        if (lore != null) {
            for (String line : lore) {
                String plain = stripColor(line);
                if (plain.contains("가격")) continue;
                newLore.add(line);
            }
        }

        newLore.add("§a가격: §e" + price + " §a에메랄드");
        meta.setLore(newLore);
        item.setItemMeta(meta);
        return item;
    }

    private String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
}