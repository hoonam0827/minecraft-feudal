package com.example.feudal.npc;

import com.example.feudal.service.FeudalService;
import com.example.feudal.service.TaxService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class TaxCollectorLoop {
    private final JavaPlugin plugin;
    private final TaxService taxService;
    private final FeudalService feudalService;

    public TaxCollectorLoop(JavaPlugin plugin, TaxService taxService, FeudalService feudalService) {
        this.plugin = plugin;
        this.taxService = taxService;
        this.feudalService = feudalService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();

            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (!npc.hasTrait(FeudalNPCTrait.class)) continue;
                FeudalNPCTrait trait = npc.getTrait(FeudalNPCTrait.class);
                if (trait.getRole() != NPCRole.TAX_COLLECTOR) continue;

                Integer familyId = trait.getFamilyId();
                int amount = trait.getTaxAmount();
                if (familyId == null || amount <= 0) continue;

                trait.initNextIfNeeded(now);
                if (now < trait.getNextCollectAtMs()) continue;

                // 1) 이번 주기 세금 청구(각 구성원 due + amount)
                try {
                    for (UUID uuid : feudalService.getMembersOfFamily(familyId)) {
                        taxService.addDue(uuid.toString(), amount);

                        Player pl = Bukkit.getPlayer(uuid);
                        if (pl == null || !pl.isOnline()) continue;

                        // 2) 자동 징수: 에메랄드로 due에서 가능한 만큼 걷기
                        int due = taxService.getDue(uuid.toString());
                        if (due <= 0) continue;

                        int emeralds = countItem(pl, Material.EMERALD);
                        int pay = Math.min(emeralds, due);

                        if (pay > 0) {
                            removeItem(pl, Material.EMERALD, pay);
                            taxService.reduceDue(uuid.toString(), pay);
                            taxService.addToBank(familyId, pay);
                            taxService.recordLedger(familyId, npc.getId(), pay, "AUTO_TAX(EMERALD)", now);

                            int left = taxService.getDue(uuid.toString());
                            pl.sendMessage("§a[세금] 자동 징수 완료: §e" + pay + "§a (에메랄드) / 미납: §e" + left);
                        }

                        // 3) 미납 남아있으면 경고
                        int left = taxService.getDue(uuid.toString());
                        if (left > 0) {
                            pl.sendMessage("§c[세금] 미납이 있습니다! §e" + left + "§c (에메랄드가 부족함)");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[TaxLoop] 처리 실패: " + e.getMessage());
                    trait.setNextCollectAtMs(now + 10_000L);
                    continue;
                }

                trait.setNextCollectAtMs(now + trait.getIntervalMs());
            }
        }, 20L, 20L); // 1초마다 체크
    }

    private int countItem(Player p, Material mat) {
        int sum = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null) continue;
            if (it.getType() == mat) sum += it.getAmount();
        }
        return sum;
    }

    private void removeItem(Player p, Material mat, int amount) {
        int left = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != mat) continue;

            int take = Math.min(it.getAmount(), left);
            it.setAmount(it.getAmount() - take);
            left -= take;

            if (it.getAmount() <= 0) contents[i] = null;
            if (left <= 0) break;
        }
        p.getInventory().setContents(contents);
        p.updateInventory();
    }
}