package com.example.feudal.npc;

import com.example.feudal.service.FeudalService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GuardLoop {

    private final JavaPlugin plugin;
    private final FeudalService feudalService;

    // ---- 튜닝 값 ----
    private static final double AGGRO_RADIUS = 14.0;       // 탐지 반경
    private static final double MELEE_RANGE = 2.7;         // 근접 사거리
    private static final long ATTACK_COOLDOWN_MS = 1200;   // 근접 공격 쿨타임

    // 원거리(활/석궁) 관련
    private static final double RANGED_MAX_DISTANCE = 18.0; // 이 거리 안이면 발사
    private static final long RANGED_COOLDOWN_MS = 1800;    // 발사 쿨타임
    private static final float ARROW_SPEED = 1.6f;          // 화살 속도

    // ---- 경고 시스템 ----
    private static final long WARN_COOLDOWN_MS = 10_000;   // 같은 플레이어에게 경고 재발동 최소 간격
    private static final long WARN_GRACE_MS = 3_000;       // 경고 후 공격까지 유예 시간

    private final Map<Integer, Long> lastAttackAt = new HashMap<>();
    private final Map<Integer, Long> lastRangedAt = new HashMap<>();
    private final Map<UUID, Long> lastWarnAt = new HashMap<>();
    private final Map<UUID, Long> warnedUntil = new HashMap<>();

    public GuardLoop(JavaPlugin plugin, FeudalService feudalService) {
        this.plugin = plugin;
        this.feudalService = feudalService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();

            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (!npc.hasTrait(FeudalNPCTrait.class)) continue;

                FeudalNPCTrait trait = npc.getTrait(FeudalNPCTrait.class);
                if (trait.getRole() != NPCRole.GUARD) continue;

                if (!npc.isSpawned() || npc.getEntity() == null) continue;
                if (!(npc.getEntity() instanceof LivingEntity guard)) continue;

                // 1) 가드가 속한 가문 확인
                Integer guardFamilyId;
                try {
                    var fidOpt = feudalService.getNpcFamilyId(npc.getId());
                    if (fidOpt.isEmpty()) continue;
                    guardFamilyId = fidOpt.get();
                } catch (SQLException e) {
                    continue;
                }

                // 2) 가문 영지 확인 (활성 + 가드가 영지 안에 있을 때만 작동)
                FeudalService.FamilyLand land;
                try {
                    var landOpt = feudalService.getFamilyLand(guardFamilyId);
                    if (landOpt.isEmpty()) continue;
                    land = landOpt.get();
                    if (!land.enabled()) continue;
                } catch (SQLException e) {
                    continue;
                }

                Location gl = guard.getLocation();
                if (!isInside(gl, land)) continue;

                // 3) 침입자(다른 가문 플레이어) 찾기
                Player target = findNearestEnemy(gl, land, guardFamilyId);
                if (target == null) continue;

                // 4) 추적
                try {
                    npc.getNavigator().setTarget(target, true);
                } catch (Throwable ignored) {}

                // 5) 경고/유예
                UUID tuid = target.getUniqueId();
                long until = warnedUntil.getOrDefault(tuid, 0L);
                if (now < until) continue;

                // 경고를 먼저 주고 유예 시작 (10초에 한 번만 경고)
                long lastW = lastWarnAt.getOrDefault(tuid, 0L);
                if (now - lastW >= WARN_COOLDOWN_MS) {
                    lastWarnAt.put(tuid, now);
                    warnedUntil.put(tuid, now + WARN_GRACE_MS);
                    target.sendMessage("§c[경고] §f여기는 영지입니다. 즉시 떠나세요! §7(3초 후 공격)");
                    continue;
                }

                // 거리 계산
                double distSq = gl.distanceSquared(target.getLocation());

                // 6) 무기 기반 공격
                ItemStack weapon = getMainHand(guard);
                boolean ranged = isRangedWeapon(weapon);

                if (ranged) {
                    if (distSq <= (RANGED_MAX_DISTANCE * RANGED_MAX_DISTANCE)) {
                        long last = lastRangedAt.getOrDefault(npc.getId(), 0L);
                        if (now - last >= RANGED_COOLDOWN_MS) {
                            doRangedAttack(guard, target);
                            lastRangedAt.put(npc.getId(), now);
                        }
                    }
                } else {
                    if (distSq <= (MELEE_RANGE * MELEE_RANGE)) {
                        long last = lastAttackAt.getOrDefault(npc.getId(), 0L);
                        if (now - last >= ATTACK_COOLDOWN_MS) {
                            double dmg = calculateMeleeDamage(guard, weapon);
                            target.damage(dmg, guard);
                            lastAttackAt.put(npc.getId(), now);
                        }
                    }
                }
            }
        }, 20L, 10L);
    }

    private ItemStack getMainHand(LivingEntity e) {
        if (e.getEquipment() == null) return null;
        ItemStack it = e.getEquipment().getItemInMainHand();
        if (it == null || it.getType() == Material.AIR) return null;
        return it;
    }

    private boolean isRangedWeapon(ItemStack weapon) {
        if (weapon == null) return false;
        Material t = weapon.getType();
        return t == Material.BOW || t == Material.CROSSBOW;
    }

    private void doRangedAttack(LivingEntity guard, Player target) {
        Location from = guard.getEyeLocation();
        Location to = target.getLocation().add(0, 1.1, 0);

        Vector dir = to.toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 0.0001) return;

        dir.normalize().multiply(ARROW_SPEED);

        Arrow arrow = guard.launchProjectile(Arrow.class);
        arrow.setShooter(guard);
        arrow.setVelocity(dir);
        arrow.setCritical(false);
    }

    private double calculateMeleeDamage(LivingEntity guard, ItemStack weapon) {
        double weaponBase = 1.0;

        if (weapon != null) {
            switch (weapon.getType()) {
                case WOODEN_SWORD, GOLDEN_SWORD -> weaponBase = 4.0;
                case STONE_SWORD -> weaponBase = 5.0;
                case IRON_SWORD -> weaponBase = 6.0;
                case DIAMOND_SWORD -> weaponBase = 7.0;
                case NETHERITE_SWORD -> weaponBase = 8.0;

                case WOODEN_AXE, GOLDEN_AXE -> weaponBase = 7.0;
                case STONE_AXE -> weaponBase = 9.0;
                case IRON_AXE -> weaponBase = 9.0;
                case DIAMOND_AXE -> weaponBase = 9.0;
                case NETHERITE_AXE -> weaponBase = 10.0;

                case TRIDENT -> weaponBase = 8.0;
                default -> weaponBase = 2.0;
            }
        }

        double attr = 0.0;
        try {
            var a = guard.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
            if (a != null) attr = Math.max(0.0, a.getValue() - 1.0);
        } catch (Throwable ignored) {}

        return Math.max(1.0, weaponBase + (attr * 0.25));
    }

    private Player findNearestEnemy(Location guardLoc, FeudalService.FamilyLand land, int guardFamilyId) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;

        for (Player p : guardLoc.getWorld().getPlayers()) {
            if (!p.isOnline() || p.isDead()) continue;
            if (p.hasPermission("feudal.admin")) continue;
            if (!isInside(p.getLocation(), land)) continue;

            try {
                var pf = feudalService.getFamilyIdOf(p.getUniqueId());
                if (pf.isPresent() && pf.get() == guardFamilyId) continue;
            } catch (SQLException e) {
                continue;
            }

            double d = guardLoc.distanceSquared(p.getLocation());
            if (d <= (AGGRO_RADIUS * AGGRO_RADIUS) && d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private boolean isInside(Location loc, FeudalService.FamilyLand land) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(land.world())) return false;

        int dx = loc.getBlockX() - land.x();
        int dz = loc.getBlockZ() - land.z();
        int r = land.radius();
        return (dx * dx + dz * dz) <= (r * r);
    }
}