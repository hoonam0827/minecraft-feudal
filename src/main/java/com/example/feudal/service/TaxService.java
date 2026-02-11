package com.example.feudal.service;

import com.example.feudal.model.Job;
import com.example.feudal.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class TaxService {
    private final Database db;

    // ✅ 테스트하기 좋게: 10분마다 납부 마감 (원하면 24시간으로 바꿔)
    public static final long SERF_DUE_INTERVAL_MS = 10 * 60 * 1000L;

    // ✅ 미납 3회 이상이면 "가혹세" (세금 2배) + 경고 쿨 60초
    public static final int SERF_PUNISH_THRESHOLD = 3;
    public static final long SERF_WARN_COOLDOWN_MS = 60_000L;

    // ✅ 납품 포인트 정책
    // - 아이템 납품 -> 포인트 증가
    // - 포인트 100마다: due 10 감소 + 감면 10분 부여
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

    // --------------------
    // ✅ 농노 1,2,3,5 핵심
    // --------------------

    /** 농노 기본 세금(직업별) */
    public int computeBaseSerfTax(Job job) {
        return switch (job) {
            case FARMER -> 4;
            case MINER -> 6;
            case GUARD -> 5;
            default -> 3;
        };
    }

    /**
     * (1)(2)(5) 정기 납부 체크:
     * - now >= next_due_at 이면 due에 세금 추가
     * - 기존 due가 남아있으면 miss_count++ (미납)
     * - miss_count >= threshold 면 세금 2배(가혹세)
     *
     * @return 이번에 새로 추가된 세금(0이면 아무 일도 없음)
     */
    public int tickSerfDueIfNeeded(FeudalService service, UUID uuid, long nowMs) throws SQLException {
        if (!service.isSerf(uuid)) return 0;

        long nextDue = service.getSerfNextDueAt(uuid);
        if (nextDue == 0) {
            // 처음 농노로 지정되면 타이머 시작
            service.setSerfNextDueAt(uuid, nowMs + SERF_DUE_INTERVAL_MS);
            return 0;
        }

        if (nowMs < nextDue) return 0;

        // 미납 체크: due가 남아있으면 miss++
        int curDue = getDue(uuid.toString());
        if (curDue > 0) service.addSerfMissCount(uuid, 1);

        int miss = service.getSerfMissCount(uuid);
        Job job = service.getJob(uuid);

        int tax = computeBaseSerfTax(job);

        // 처벌: 미납 누적이면 가혹세 2배
        if (miss >= SERF_PUNISH_THRESHOLD) tax *= 2;

        // 감면: 할인 기간이면 반값
        long discUntil = service.getSerfTaxDiscountUntil(uuid);
        if (discUntil > nowMs) tax = Math.max(1, tax / 2);

        addDue(uuid.toString(), tax);

        // 다음 마감 갱신
        service.setSerfNextDueAt(uuid, nowMs + SERF_DUE_INTERVAL_MS);

        return tax;
    }

    /**
     * (3) 납품 포인트 추가 + 보상 처리
     * - points 100마다 due 10 감소 + 감면 10분 부여
     * @return 보상 발동 횟수
     */
    public int addSerfDeliverAndApplyRewards(FeudalService service, UUID uuid, int addPoints, long nowMs) throws SQLException {
        if (addPoints <= 0) return 0;

        int before = service.getSerfDeliverPoints(uuid);
        int after = before + addPoints;
        service.setSerfDeliverPoints(uuid, after);

        int rewards = after / SERF_POINTS_PER_REWARD;
        if (rewards <= 0) return 0;

        // 포인트 차감
        int remain = after % SERF_POINTS_PER_REWARD;
        service.setSerfDeliverPoints(uuid, remain);

        // due 감소
        int reduce = rewards * SERF_DUE_REDUCE_PER_REWARD;
        reduceDue(uuid.toString(), reduce);

        // 감면 부여(누적 연장)
        long curUntil = service.getSerfTaxDiscountUntil(uuid);
        long base = Math.max(curUntil, nowMs);
        service.setSerfTaxDiscountUntil(uuid, base + rewards * SERF_DISCOUNT_DURATION_MS);

        return rewards;
    }

    /**
     * (5) 경고 쿨타임 체크용
     * @return 경고 보내도 되는지
     */
    public boolean canWarnSerf(FeudalService service, UUID uuid, long nowMs) throws SQLException {
        long last = service.getSerfLastWarnAt(uuid);
        if (nowMs - last < SERF_WARN_COOLDOWN_MS) return false;
        service.setSerfLastWarnAt(uuid, nowMs);
        return true;
    }
}