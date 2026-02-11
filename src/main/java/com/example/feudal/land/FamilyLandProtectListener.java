package com.example.feudal.land;

import com.example.feudal.service.FeudalService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.sql.SQLException;

public class FamilyLandProtectListener implements Listener {
    private final FeudalService service;

    public FamilyLandProtectListener(FeudalService service) {
        this.service = service;
    }

    private boolean isInside(Block b, FeudalService.FamilyLand land) {
        if (b == null || land == null) return false;
        if (!b.getWorld().getName().equals(land.world())) return false;

        // ✅ 2D 반경(가문영지는 보통 x,z로 보는게 편함)
        int dx = b.getX() - land.x();
        int dz = b.getZ() - land.z();
        int r = land.radius();
        return (dx * dx + dz * dz) <= (r * r);
    }

    private boolean canBuild(Player p, Location loc) throws SQLException {
        if (p.hasPermission("feudal.admin")) return true;

        var myF = service.getFamilyIdOf(p.getUniqueId());
        if (myF.isEmpty()) return true; // 무소속은 기본적으로 밖에서만 행동하도록 "막고 싶다"면 여기 false로 바꾸면 됨

        for (FeudalService.FamilyLand land : service.listEnabledFamilyLands()) {
            if (loc == null || loc.getWorld() == null) continue;
            if (!loc.getWorld().getName().equals(land.world())) continue;

            int dx = loc.getBlockX() - land.x();
            int dz = loc.getBlockZ() - land.z();
            int r = land.radius();

            if ((dx * dx + dz * dz) <= (r * r)) {
                // ✅ 어떤 영지 안이면, 그 영지의 가문과 같아야 통과
                return myF.get() == land.familyId();
            }
        }
        return true; // 어떤 영지도 아니면 OK
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        try {
            if (!canBuild(p, b.getLocation())) {
                e.setCancelled(true);
                p.sendMessage("§c[영지] 다른 가문의 영지에서는 파괴할 수 없어!");
            }
        } catch (SQLException ex) {
            e.setCancelled(true);
            p.sendMessage("§cDB 오류로 영지 보호가 동작 중단됨: " + ex.getMessage());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        try {
            if (!canBuild(p, b.getLocation())) {
                e.setCancelled(true);
                p.sendMessage("§c[영지] 다른 가문의 영지에서는 설치할 수 없어!");
            }
        } catch (SQLException ex) {
            e.setCancelled(true);
            p.sendMessage("§cDB 오류로 영지 보호가 동작 중단됨: " + ex.getMessage());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        // ✅ 상자/문/레버 등 “블록 우클릭” 보호
        if (e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();

        try {
            if (!canBuild(p, b.getLocation())) {
                e.setCancelled(true);
                p.sendMessage("§c[영지] 다른 가문의 영지에서는 사용할 수 없어!");
            }
        } catch (SQLException ex) {
            e.setCancelled(true);
            p.sendMessage("§cDB 오류로 영지 보호가 동작 중단됨: " + ex.getMessage());
        }
    }
}