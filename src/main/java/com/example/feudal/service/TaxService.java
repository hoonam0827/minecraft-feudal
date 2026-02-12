package com.example.feudal.service;

import com.example.feudal.model.Job;
import com.example.feudal.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class TaxService {
    private final Database db;

    public static final long SERF_DUE_INTERVAL_MS = 10 * 60 * 1000L;

    public static final int SERF_PUNISH_THRESHOLD = 3;
    public static final long SERF_WARN_COOLDOWN_MS = 60_000L;

    public static final int SERF_POINTS_PER_REWARD = 100;
    public static final int SERF_DUE_REDUCE_PER_REWARD = 10;
    public static final long SERF_DISCOUNT_DURATION_MS = 10 * 60 * 1000L;

    public TaxService(Database db) {
        this.db = db;
    }

    // --------------------
    // bank
    // --------------------
    public void ensureBankRow(int familyId) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("""
            INSERT INTO family_bank(family_id, balance)
            VALUES(?, 0)
            ON CONFLICT(family_id) DO NOTHING
        """)) {
            ps.setInt(1, familyId);
            ps.executeUpdate();
        }
    }

    public void addToBank(int familyId, int amount) throws SQLException {
        ensureBankRow(familyId);
        try (PreparedStatement ps = db.conn().prepareStatement("""
            UPDATE family_bank
            SET balance = balance + ?
            WHERE family_id = ?
        """)) {
            ps.setInt(1, amount);
            ps.setInt(2, familyId);
            ps.executeUpdate();
        }
    }

    public int getBankBalance(int familyId) throws SQLException {
        ensureBankRow(familyId);
        try (PreparedStatement ps = db.conn().prepareStatement("""
            SELECT balance FROM family_bank WHERE family_id = ?
        """)) {
            ps.setInt(1, familyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("balance") : 0;
            }
        }
    }

    public boolean withdrawFromBank(int familyId, int amount) throws SQLException {
        if (amount <= 0) return false;
        ensureBankRow(familyId);

        int bal = getBankBalance(familyId);
        if (bal < amount) return false;

        try (PreparedStatement ps = db.conn().prepareStatement("""
            UPDATE family_bank
            SET balance = balance - ?
            WHERE family_id = ?
        """)) {
            ps.setInt(1, amount);
            ps.setInt(2, familyId);
            ps.executeUpdate();
            return true;
        }
    }

    public void recordLedger(int familyId, int npcId, int amount, String reason, long createdAtMs) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("""
            INSERT INTO tax_ledger(family_id, npc_id, amount, reason, created_at)
            VALUES(?, ?, ?, ?, ?)
        """)) {
            ps.setInt(1, familyId);
            ps.setInt(2, npcId);
            ps.setInt(3, amount);
            ps.setString(4, reason);
            ps.setLong(5, createdAtMs);
            ps.executeUpdate();
        }
    }

    // --------------------
    // due
    // --------------------
    public void addDue(String uuid, int amount) throws SQLException {
        int safe = Math.max(0, amount);
        try (PreparedStatement ps = db.conn().prepareStatement("""
            INSERT INTO tax_due(uuid, due)
            VALUES(?, ?)
            ON CONFLICT(uuid) DO UPDATE SET due = due + excluded.due
        """)) {
            ps.setString(1, uuid);
            ps.setInt(2, safe);
            ps.executeUpdate();
        }
    }

    public int getDue(String uuid) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("""
            SELECT due FROM tax_due WHERE uuid = ?
        """)) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("due") : 0;
            }
        }
    }

    public void setDue(String uuid, int due) throws SQLException {
        int safe = Math.max(0, due);
        try (PreparedStatement ps = db.conn().prepareStatement("""
            INSERT INTO tax_due(uuid, due)
            VALUES(?, ?)
            ON CONFLICT(uuid) DO UPDATE SET due = excluded.due
        """)) {
            ps.setString(1, uuid);
            ps.setInt(2, safe);
            ps.executeUpdate();
        }
    }

    public void reduceDue(String uuid, int paidAmount) throws SQLException {
        int cur = getDue(uuid);
        int next = Math.max(0, cur - Math.max(0, paidAmount));
        setDue(uuid, next);
    }

    /** 농노 기본 세금(직업별) */
    public int computeBaseSerfTax(Job job) {
        return switch (job) {
            case FARMER -> 4;
            case MINER -> 6;
            case GUARD -> 5;
            default -> 3;
        };
    }

    public int tickSerfDueIfNeeded(FeudalService service, UUID uuid, long nowMs) throws SQLException {
        if (!service.isSerf(uuid)) return 0;

        long nextDue = service.getSerfNextDueAt(uuid);
        if (nextDue == 0) {
            service.setSerfNextDueAt(uuid, nowMs + SERF_DUE_INTERVAL_MS);
            return 0;
        }

        if (nowMs < nextDue) return 0;

        int curDue = getDue(uuid.toString());
        if (curDue > 0) service.addSerfMissCount(uuid, 1);

        int miss = service.getSerfMissCount(uuid);
        Job job = service.getJob(uuid);

        int tax = computeBaseSerfTax(job);

        if (miss >= SERF_PUNISH_THRESHOLD) tax *= 2;

        long discUntil = service.getSerfTaxDiscountUntil(uuid);
        if (discUntil > nowMs) tax = Math.max(1, tax / 2);

        addDue(uuid.toString(), tax);

        service.setSerfNextDueAt(uuid, nowMs + SERF_DUE_INTERVAL_MS);

        return tax;
    }

    public int addSerfDeliverAndApplyRewards(FeudalService service, UUID uuid, int addPoints, long nowMs) throws SQLException {
        if (addPoints <= 0) return 0;

        int before = service.getSerfDeliverPoints(uuid);
        int after = before + addPoints;
        service.setSerfDeliverPoints(uuid, after);

        int rewards = after / SERF_POINTS_PER_REWARD;
        if (rewards <= 0) return 0;

        int remain = after % SERF_POINTS_PER_REWARD;
        service.setSerfDeliverPoints(uuid, remain);

        int reduce = rewards * SERF_DUE_REDUCE_PER_REWARD;
        reduceDue(uuid.toString(), reduce);

        long curUntil = service.getSerfTaxDiscountUntil(uuid);
        long base = Math.max(curUntil, nowMs);
        service.setSerfTaxDiscountUntil(uuid, base + rewards * SERF_DISCOUNT_DURATION_MS);

        return rewards;
    }

    public boolean canWarnSerf(FeudalService service, UUID uuid, long nowMs) throws SQLException {
        long last = service.getSerfLastWarnAt(uuid);
        if (nowMs - last < SERF_WARN_COOLDOWN_MS) return false;
        service.setSerfLastWarnAt(uuid, nowMs);
        return true;
    }
}