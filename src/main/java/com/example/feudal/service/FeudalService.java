package com.example.feudal.service;

import com.example.feudal.model.Job;
import com.example.feudal.model.Rank;
import com.example.feudal.storage.Database;

import java.sql.*;
import java.util.*;

public class FeudalService {

    public record FamilyLand(
            int familyId,
            String world,
            int x,
            int y,
            int z,
            int radius,
            boolean enabled
    ) {}

    private final Connection conn;

    public FeudalService(Database db) {
        this.conn = db.conn();
    }

    // ----------------------------
    // Family / Member
    // ----------------------------

    public Optional<Integer> getFamilyIdOf(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT family_id FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                int fid = rs.getInt("family_id");
                return rs.wasNull() ? Optional.empty() : Optional.of(fid);
            }
        }
    }

    public Optional<Integer> getFamilyIdByName(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM families WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(rs.getInt("id"));
            }
        }
    }

    public String getFamilyNameById(int familyId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM families WHERE id = ?")) {
            ps.setInt(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("name") : "UNKNOWN";
            }
        }
    }

    public String getFamilyInfoById(int familyId) throws SQLException {
        String name = getFamilyNameById(familyId);
        int members = countMembers(familyId);
        return "가문: " + name + " / 인원: " + members;
    }

    private int countMembers(int familyId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) AS c FROM members WHERE family_id = ?")) {
            ps.setInt(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("c") : 0;
            }
        }
    }

    public void createFamily(String name, UUID owner) throws SQLException {
        int newId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO families(name, lord_uuid) VALUES(?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setString(1, name);
            ps.setString(2, owner.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("가문 ID 생성 실패");
                newId = keys.getInt(1);
            }
        }

        setMember(owner, newId, Rank.KING);
        setJob(owner, Job.NONE);
        setSerf(owner, false);
    }

    /** members upsert (uuid PK) */
    public void setMember(UUID uuid, int familyId, Rank rank) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO members(uuid, family_id, rank, job, is_serf)
            VALUES(?, ?, ?, 'NONE', 0)
            ON CONFLICT(uuid) DO UPDATE SET
              family_id = excluded.family_id,
              rank = excluded.rank
        """)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, familyId);
            ps.setString(3, rank.name());
            ps.executeUpdate();
        }
    }

    public Rank getRank(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT rank FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Rank.PEASANT;
                String r = rs.getString("rank");
                try { return Rank.valueOf(r); } catch (Exception ignored) { return Rank.PEASANT; }
            }
        }
    }

    public Job getJob(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT job FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Job.NONE;
                String j = rs.getString("job");
                try { return Job.valueOf(j); } catch (Exception ignored) { return Job.NONE; }
            }
        }
    }
    public void setJob(UUID uuid, Job job) throws SQLException {
        if (isSerf(uuid) && job != Job.NONE) {
            throw new SQLException("SERF_CANNOT_HAVE_JOB");
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE members SET job = ? WHERE uuid = ?")) {
            ps.setString(1, job.name());
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public boolean isSerf(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT is_serf FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return rs.getInt("is_serf") == 1;
            }
        }
    }


    public void setSerf(UUID uuid, boolean on) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE members SET is_serf = ? WHERE uuid = ?")) {
            ps.setInt(1, on ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }

        // 농노가 되면 직업 제거
        if (on) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE members SET job = 'NONE' WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        }
    }

    public List<UUID> getMembersOfFamily(int familyId) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM members WHERE family_id = ?")) {
            ps.setInt(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(UUID.fromString(rs.getString("uuid")));
            }
        }
        return out;
    }

    // ----------------------------
    // Land
    // ----------------------------

    public Optional<FamilyLand> getFamilyLand(int familyId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT family_id, world, x, y, z, radius, enabled
            FROM family_land
            WHERE family_id = ?
        """)) {
            ps.setInt(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new FamilyLand(
                        rs.getInt("family_id"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getInt("radius"),
                        rs.getInt("enabled") == 1
                ));
            }
        }
    }

    public List<FamilyLand> listEnabledFamilyLands() throws SQLException {
        List<FamilyLand> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT family_id, world, x, y, z, radius, enabled
            FROM family_land
            WHERE enabled = 1
        """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FamilyLand(
                            rs.getInt("family_id"),
                            rs.getString("world"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getInt("radius"),
                            rs.getInt("enabled") == 1
                    ));
                }
            }
        }
        return out;
    }

    public void upsertFamilyLand(int familyId, String world, int x, int y, int z, int radius, boolean enabled) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO family_land(family_id, world, x, y, z, radius, enabled)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(family_id) DO UPDATE SET
              world = excluded.world,
              x = excluded.x,
              y = excluded.y,
              z = excluded.z,
              radius = excluded.radius,
              enabled = excluded.enabled
        """)) {
            ps.setInt(1, familyId);
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setInt(6, radius);
            ps.setInt(7, enabled ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public void setFamilyLandRadius(int familyId, int radius) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE family_land SET radius = ? WHERE family_id = ?")) {
            ps.setInt(1, radius);
            ps.setInt(2, familyId);
            ps.executeUpdate();
        }
    }

    public void setFamilyLandEnabled(int familyId, boolean enabled) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE family_land SET enabled = ? WHERE family_id = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setInt(2, familyId);
            ps.executeUpdate();
        }
    }

    public Optional<Integer> getNpcFamilyId(int npcId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT family_id FROM npc_members WHERE npc_id = ?")) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                int fid = rs.getInt("family_id");
                return rs.wasNull() ? Optional.empty() : Optional.of(fid);
            }
        }
    }

    public Job getNpcJob(int npcId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT job FROM npc_members WHERE npc_id = ?")) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Job.NONE;
                String j = rs.getString("job");
                try { return Job.valueOf(j); } catch (Exception ignored) { return Job.NONE; }
            }
        }
    }

    public boolean isNpcSerf(int npcId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT is_serf FROM npc_members WHERE npc_id = ?")) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return rs.getInt("is_serf") == 1;
            }
        }
    }

    public void setNpcMember(int npcId, int familyId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO npc_members(npc_id, family_id, job, is_serf)
            VALUES(?, ?, 'NONE', 0)
            ON CONFLICT(npc_id) DO UPDATE SET
              family_id = excluded.family_id
        """)) {
            ps.setInt(1, npcId);
            ps.setInt(2, familyId);
            ps.executeUpdate();
        }
    }

    public void setNpcMember(int npcId, int familyId, Job job, boolean serf) throws SQLException {
        // NPC도 농노면 NONE만 허용 (통일)
        if (serf && job != Job.NONE) job = Job.NONE;

        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO npc_members(npc_id, family_id, job, is_serf)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(npc_id) DO UPDATE SET
              family_id = excluded.family_id,
              job = excluded.job,
              is_serf = excluded.is_serf
        """)) {
            ps.setInt(1, npcId);
            ps.setInt(2, familyId);
            ps.setString(3, job.name());
            ps.setInt(4, serf ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public void setNpcJob(int npcId, Job job) throws SQLException {
        if (isNpcSerf(npcId) && job != Job.NONE) {
            throw new SQLException("SERF_CANNOT_HAVE_JOB");
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE npc_members SET job = ? WHERE npc_id = ?")) {
            ps.setString(1, job.name());
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }
    }

    public void setNpcSerf(int npcId, boolean on) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE npc_members SET is_serf = ? WHERE npc_id = ?")) {
            ps.setInt(1, on ? 1 : 0);
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }

        // NPC 농노도 직업 제거(통일)
        if (on) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE npc_members SET job = 'NONE' WHERE npc_id = ?")) {
                ps.setInt(1, npcId);
                ps.executeUpdate();
            }
        }
    }


    public long getSerfNextDueAt(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT serf_next_due_at FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("serf_next_due_at") : 0L;
            }
        }
    }

    public void setSerfNextDueAt(UUID uuid, long nextDueAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE members SET serf_next_due_at = ? WHERE uuid = ?")) {
            ps.setLong(1, nextDueAt);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public int getSerfMissCount(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT serf_miss_count FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("serf_miss_count") : 0;
            }
        }
    }

    public void addSerfMissCount(UUID uuid, int add) throws SQLException {
        if (add == 0) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE members SET serf_miss_count = serf_miss_count + ? WHERE uuid = ?")) {
            ps.setInt(1, add);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public int getSerfDeliverPoints(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT serf_deliver_points FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("serf_deliver_points") : 0;
            }
        }
    }

    public void setSerfDeliverPoints(UUID uuid, int points) throws SQLException {
        int safe = Math.max(0, points);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE members SET serf_deliver_points = ? WHERE uuid = ?")) {
            ps.setInt(1, safe);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public long getSerfTaxDiscountUntil(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT serf_tax_discount_until FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("serf_tax_discount_until") : 0L;
            }
        }
    }

    public void setSerfTaxDiscountUntil(UUID uuid, long until) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE members SET serf_tax_discount_until = ? WHERE uuid = ?")) {
            ps.setLong(1, until);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public long getSerfLastWarnAt(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT serf_last_warn_at FROM members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("serf_last_warn_at") : 0L;
            }
        }
    }

    public void setSerfLastWarnAt(UUID uuid, long at) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE members SET serf_last_warn_at = ? WHERE uuid = ?")) {
            ps.setLong(1, at);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }
}