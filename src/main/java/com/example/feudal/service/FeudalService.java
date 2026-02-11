package com.example.feudal.service;

import com.example.feudal.model.Job;
import com.example.feudal.model.Rank;
// ✅ 네 프로젝트에 Database 클래스가 있다면 import 경로를 맞춰줘!
// 예: com.example.feudal.db.Database
import com.example.feudal.storage.Database;

import java.sql.*;
import java.util.*;

/**
 * 목표: 현재 프로젝트 컴파일을 막고 있는 "없는 메서드"들을 전부 제공.
 * DB 스키마/테이블명은 네 프로젝트에 맞게 조정 가능.
 */
public class FeudalService {

    // ----------------------------
    // DTO/Record
    // ----------------------------
    public record FamilyLand(
            int familyId,
            String world,
            int x,
            int y,
            int z,
            int radius,
            boolean enabled
    ) {}

    // ----------------------------
    // DB 핸들링
    // ----------------------------
    private final Connection conn;

    // ✅ 기존 코드가 Connection을 주는 경우
    public FeudalService(Connection conn) {
        this.conn = conn;
    }

    // ✅ 네 FeudalPlugin이 Database를 주는 경우(스샷 에러 원인)
    public FeudalService(Database db) {
        this.conn = db.conn();
    }

    private PreparedStatement ps(String sql) throws SQLException {
        return conn.prepareStatement(sql);
    }

    // ----------------------------
    // Family / Member 기본
    // ----------------------------

    public Optional<Integer> getFamilyIdOf(UUID uuid) throws SQLException {
        try (PreparedStatement ps = ps("SELECT family_id FROM feudal_member WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(rs.getInt(1));
            }
        }
    }

    public Optional<Integer> getFamilyIdByName(String name) throws SQLException {
        try (PreparedStatement ps = ps("SELECT id FROM feudal_family WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(rs.getInt(1));
            }
        }
    }

    public String getFamilyNameById(int familyId) throws SQLException {
        try (PreparedStatement ps = ps("SELECT name FROM feudal_family WHERE id = ?")) {
            ps.setInt(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "UNKNOWN";
                return rs.getString(1);
            }
        }
    }

    public String getFamilyInfoById(int familyId) throws SQLException {
        // 가볍게 표시용(원하면 확장)
        String name = getFamilyNameById(familyId);
        int members = countMembers(familyId);
        return "가문: " + name + " / 인원: " + members;
    }

    private int countMembers(int familyId) throws SQLException {
        try (PreparedStatement ps = ps("SELECT COUNT(*) FROM feudal_member WHERE family_id = ?")) {
            ps.setInt(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void createFamily(String name, UUID owner) throws SQLException {
        // 1) 가문 생성
        int newId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO feudal_family(name, created_at) VALUES(?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setString(1, name);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("family id 생성 실패");
                newId = keys.getInt(1);
            }
        }

        // 2) 창립자는 KING으로 멤버 등록
        setMember(owner, newId, Rank.KING);
        setJob(owner, Job.NONE);
        setSerf(owner, false);
    }

    public Rank getRank(UUID uuid) throws SQLException {
        try (PreparedStatement ps = ps("SELECT rank FROM feudal_member WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Rank.PEASANT; // 무소속
                String s = rs.getString(1);
                try { return Rank.valueOf(s); } catch (Exception e) { return Rank.PEASANT; }
            }
        }
    }

    public void setMember(UUID uuid, int familyId, Rank rank) throws SQLException {
        // upsert
        try (PreparedStatement ps = ps("""
            INSERT INTO feudal_member(uuid, family_id, rank)
            VALUES(?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET family_id = excluded.family_id, rank = excluded.rank
        """)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, familyId);
            ps.setString(3, rank.name());
            ps.executeUpdate();
        }
    }

    public Job getJob(UUID uuid) throws SQLException {
        try (PreparedStatement ps = ps("SELECT job FROM feudal_member WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Job.NONE;
                String s = rs.getString(1);
                try { return Job.valueOf(s); } catch (Exception e) { return Job.NONE; }
            }
        }
    }

    public void setJob(UUID uuid, Job job) throws SQLException {
        try (PreparedStatement ps = ps("UPDATE feudal_member SET job = ? WHERE uuid = ?")) {
            ps.setString(1, job.name());
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public boolean isSerf(UUID uuid) throws SQLException {
        try (PreparedStatement ps = ps("SELECT serf FROM feudal_member WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return rs.getInt(1) == 1 || rs.getBoolean(1);
            }
        }
    }

    public void setSerf(UUID uuid, boolean serf) throws SQLException {
        try (PreparedStatement ps = ps("UPDATE feudal_member SET serf = ? WHERE uuid = ?")) {
            ps.setBoolean(1, serf);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /**
     * TaxCollectorLoop 에서 필요: getMembersOfFamily(Integer)
     */
    public List<UUID> getMembersOfFamily(Integer familyId) throws SQLException {
        List<UUID> list = new ArrayList<>();
        try (PreparedStatement ps = ps("SELECT uuid FROM feudal_member WHERE family_id = ?")) {
            ps.setInt(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try { list.add(UUID.fromString(rs.getString(1))); }
                    catch (Exception ignore) {}
                }
            }
        }
        return list;
    }

    // ----------------------------
    // NPC 멤버/농노/직업
    // ----------------------------

    public Optional<Integer> getNpcFamilyId(int npcId) throws SQLException {
        try (PreparedStatement ps = ps("SELECT family_id FROM feudal_npc WHERE npc_id = ?")) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                int v = rs.getInt(1);
                if (rs.wasNull()) return Optional.empty();
                return Optional.of(v);
            }
        }
    }

    public void setNpcMember(int npcId, int familyId) throws SQLException {
        try (PreparedStatement ps = ps("""
            INSERT INTO feudal_npc(npc_id, family_id)
            VALUES(?, ?)
            ON CONFLICT(npc_id) DO UPDATE SET family_id = excluded.family_id
        """)) {
            ps.setInt(1, npcId);
            ps.setInt(2, familyId);
            ps.executeUpdate();
        }
    }

    public boolean isNpcSerf(int npcId) throws SQLException {
        try (PreparedStatement ps = ps("SELECT serf FROM feudal_npc WHERE npc_id = ?")) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return rs.getInt(1) == 1 || rs.getBoolean(1);
            }
        }
    }

    public void setNpcSerf(int npcId, boolean serf) throws SQLException {
        try (PreparedStatement ps = ps("UPDATE feudal_npc SET serf = ? WHERE npc_id = ?")) {
            ps.setBoolean(1, serf);
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }
    }

    public Job getNpcJob(int npcId) throws SQLException {
        try (PreparedStatement ps = ps("SELECT job FROM feudal_npc WHERE npc_id = ?")) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Job.NONE;
                String s = rs.getString(1);
                try { return Job.valueOf(s); } catch (Exception e) { return Job.NONE; }
            }
        }
    }

    public void setNpcJob(int npcId, Job job) throws SQLException {
        try (PreparedStatement ps = ps("UPDATE feudal_npc SET job = ? WHERE npc_id = ?")) {
            ps.setString(1, job.name());
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }
    }

    // ----------------------------
    // Family Land (Listener가 필요로 함)
    // ----------------------------

    public Optional<FamilyLand> getFamilyLand(int familyId) throws SQLException {
        try (PreparedStatement ps = ps("""
            SELECT family_id, world, x, y, z, radius, enabled
            FROM feudal_land
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
                        rs.getBoolean("enabled")
                ));
            }
        }
    }

    public void upsertFamilyLand(int familyId, String world, int x, int y, int z, int radius, boolean enabled) throws SQLException {
        try (PreparedStatement ps = ps("""
            INSERT INTO feudal_land(family_id, world, x, y, z, radius, enabled)
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
            ps.setBoolean(7, enabled);
            ps.executeUpdate();
        }
    }

    public void setFamilyLandRadius(int familyId, int radius) throws SQLException {
        try (PreparedStatement ps = ps("UPDATE feudal_land SET radius = ? WHERE family_id = ?")) {
            ps.setInt(1, radius);
            ps.setInt(2, familyId);
            ps.executeUpdate();
        }
    }

    public void setFamilyLandEnabled(int familyId, boolean enabled) throws SQLException {
        try (PreparedStatement ps = ps("UPDATE feudal_land SET enabled = ? WHERE family_id = ?")) {
            ps.setBoolean(1, enabled);
            ps.setInt(2, familyId);
            ps.executeUpdate();
        }
    }

    public List<FamilyLand> listEnabledFamilyLands() throws SQLException {
        List<FamilyLand> list = new ArrayList<>();
        try (PreparedStatement ps = ps("""
            SELECT family_id, world, x, y, z, radius, enabled
            FROM feudal_land
            WHERE enabled = 1
        """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new FamilyLand(
                            rs.getInt("family_id"),
                            rs.getString("world"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getInt("radius"),
                            rs.getBoolean("enabled")
                    ));
                }
            }
        }
        return list;
    }

    // ----------------------------
    // ✅ Serf Tax Meta (TaxService가 찾던 메서드들)
    // ----------------------------
    // 권장 테이블: feudal_serf_meta(uuid TEXT PRIMARY KEY, next_due_at BIGINT, miss_count INT,
    //                              discount_until BIGINT, deliver_points INT, last_warn_at BIGINT)

    public long getSerfNextDueAt(UUID uuid) throws SQLException {
        return getSerfMetaLong(uuid, "next_due_at", 0L);
    }

    public void setSerfNextDueAt(UUID uuid, long nextDueAt) throws SQLException {
        upsertSerfMetaLong(uuid, "next_due_at", nextDueAt);
    }

    public void addSerfMissCount(UUID uuid, int add) throws SQLException {
        int cur = getSerfMissCount(uuid);
        setSerfMissCount(uuid, cur + add);
    }

    public int getSerfMissCount(UUID uuid) throws SQLException {
        return (int) getSerfMetaLong(uuid, "miss_count", 0L);
    }

    public void setSerfMissCount(UUID uuid, int v) throws SQLException {
        upsertSerfMetaLong(uuid, "miss_count", v);
    }

    public long getSerfTaxDiscountUntil(UUID uuid) throws SQLException {
        return getSerfMetaLong(uuid, "discount_until", 0L);
    }

    public void setSerfTaxDiscountUntil(UUID uuid, long until) throws SQLException {
        upsertSerfMetaLong(uuid, "discount_until", until);
    }

    public int getSerfDeliverPoints(UUID uuid) throws SQLException {
        return (int) getSerfMetaLong(uuid, "deliver_points", 0L);
    }

    public void setSerfDeliverPoints(UUID uuid, int points) throws SQLException {
        upsertSerfMetaLong(uuid, "deliver_points", points);
    }

    public long getSerfLastWarnAt(UUID uuid) throws SQLException {
        return getSerfMetaLong(uuid, "last_warn_at", 0L);
    }

    public void setSerfLastWarnAt(UUID uuid, long at) throws SQLException {
        upsertSerfMetaLong(uuid, "last_warn_at", at);
    }

    private long getSerfMetaLong(UUID uuid, String col, long def) throws SQLException {
        String sql = "SELECT " + col + " FROM feudal_serf_meta WHERE uuid = ?";
        try (PreparedStatement ps = ps(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return def;
                long v = rs.getLong(1);
                return rs.wasNull() ? def : v;
            }
        }
    }

    private void upsertSerfMetaLong(UUID uuid, String col, long value) throws SQLException {
        // row 없으면 생성, 있으면 col만 업데이트
        // (SQLite 기준 ON CONFLICT 지원)
        String sql = """
            INSERT INTO feudal_serf_meta(uuid, next_due_at, miss_count, discount_until, deliver_points, last_warn_at)
            VALUES(?, 0, 0, 0, 0, 0)
            ON CONFLICT(uuid) DO NOTHING
        """;
        try (PreparedStatement ps = ps(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }

        String upd = "UPDATE feudal_serf_meta SET " + col + " = ? WHERE uuid = ?";
        try (PreparedStatement ps = ps(upd)) {
            ps.setLong(1, value);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }
}