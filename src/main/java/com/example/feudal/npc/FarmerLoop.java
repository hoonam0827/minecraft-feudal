package com.example.feudal.npc;

import com.example.feudal.service.FeudalService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class FarmerLoop {
    private final JavaPlugin plugin;
    private final FeudalService feudalService;

    private static final int RADIUS = 6;

    public FarmerLoop(JavaPlugin plugin, FeudalService feudalService) {
        this.plugin = plugin;
        this.feudalService = feudalService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();

            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (!npc.hasTrait(FeudalNPCTrait.class)) continue;

                FeudalNPCTrait trait = npc.getTrait(FeudalNPCTrait.class);
                if (trait.getRole() != NPCRole.FARMER) continue;

                if (!npc.isSpawned() || npc.getEntity() == null) continue;

                // 농노 NPC만 농사
                try {
                    if (!feudalService.isNpcSerf(npc.getId())) continue;
                } catch (Exception ignored) {
                    continue;
                }

                Entity ent = npc.getEntity();
                if (!(ent instanceof Player npcPlayer)) continue;
                Inventory inv = npcPlayer.getInventory();

                trait.initFarmNextIfNeeded(now);
                if (now < trait.getNextFarmAtMs()) continue;

                try {
                    doFarmAround(npcPlayer, inv, npcPlayer.getLocation());
                } catch (Exception e) {
                    plugin.getLogger().warning("[FarmerLoop] 처리 실패: " + e.getMessage());
                }

                trait.setNextFarmAtMs(now + trait.getFarmIntervalMs());
            }
        }, 20L, 20L); // 1초마다 체크
    }

    private void doFarmAround(Player farmer, Inventory inv, Location center) {
        World world = center.getWorld();
        if (world == null) return;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {

                Block base = world.getBlockAt(cx + dx, cy, cz + dz);

                Block soil;
                Block crop;

                if (base.getType() == Material.FARMLAND) {
                    soil = base;
                    crop = base.getRelative(0, 1, 0);
                } else if (base.getRelative(0, -1, 0).getType() == Material.FARMLAND) {
                    soil = base.getRelative(0, -1, 0);
                    crop = base;
                } else {
                    continue;
                }

                // (A) 성숙 작물 수확
                if (isCrop(crop.getType())) {
                    var data = crop.getBlockData();
                    if (data instanceof Ageable ageable) {
                        if (ageable.getAge() >= ageable.getMaximumAge()) {
                            harvestAndMaybeReplant(farmer, inv, crop);
                        }
                    }
                    continue;
                }

                if (crop.getType() == Material.AIR) {
                    tryPlant(farmer, inv, crop, Material.WHEAT);
                }
            }
        }
    }

    private void harvestAndMaybeReplant(Player farmer, Inventory inv, Block crop) {
        Material cropType = crop.getType();

        // 1) 수확물 지급
        giveHarvestToNpc(farmer, inv, cropType);

        // 2) 작물 제거
        crop.setType(Material.AIR, true);

        // 3) 씨앗(또는 당근/감자/비트씨앗) 있으면 재파종
        tryPlant(farmer, inv, crop, cropType);
    }

    private void tryPlant(Player farmer, Inventory inv, Block cropBlock, Material cropType) {
        Material seed = seedOf(cropType);
        if (seed == null) return;

        if (!consumeOne(inv, seed)) {
            // 씨앗 없으면 심지 않음
            return;
        }

        cropBlock.setType(cropType, true);
        var data = cropBlock.getBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(0);
            cropBlock.setBlockData(ageable, true);
        }
    }

    private void giveHarvestToNpc(Player farmer, Inventory inv, Material cropType) {
        ThreadLocalRandom r = ThreadLocalRandom.current();

        switch (cropType) {
            case WHEAT -> {
                addOrDrop(farmer, inv, new ItemStack(Material.WHEAT, 1));
                int seeds = r.nextInt(0, 3);
                if (seeds > 0) addOrDrop(farmer, inv, new ItemStack(Material.WHEAT_SEEDS, seeds));
            }
            case CARROTS -> addOrDrop(farmer, inv, new ItemStack(Material.CARROT, r.nextInt(1, 4)));
            case POTATOES -> {
                addOrDrop(farmer, inv, new ItemStack(Material.POTATO, r.nextInt(1, 4)));
                if (r.nextInt(0, 20) == 0) addOrDrop(farmer, inv, new ItemStack(Material.POISONOUS_POTATO, 1));
            }
            case BEETROOTS -> {
                addOrDrop(farmer, inv, new ItemStack(Material.BEETROOT, 1));
                int seeds = r.nextInt(0, 3);
                if (seeds > 0) addOrDrop(farmer, inv, new ItemStack(Material.BEETROOT_SEEDS, seeds));
            }
            default -> {}
        }
    }

    private void addOrDrop(Player farmer, Inventory inv, ItemStack item) {
        if (item == null || item.getAmount() <= 0) return;
        Map<Integer, ItemStack> leftover = inv.addItem(item);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(it -> farmer.getWorld().dropItemNaturally(farmer.getLocation(), it));
        }
    }

    private boolean consumeOne(Inventory inv, Material mat) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != mat) continue;
            if (it.getAmount() <= 1) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - 1);
            return true;
        }
        return false;
    }

    private Material seedOf(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;          // 당근은 자기 자신
            case POTATOES -> Material.POTATO;        // 감자도 자기 자신
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            default -> null;
        };
    }

    private boolean isCrop(Material m) {
        return m == Material.WHEAT
                || m == Material.CARROTS
                || m == Material.POTATOES
                || m == Material.BEETROOTS;
    }
}