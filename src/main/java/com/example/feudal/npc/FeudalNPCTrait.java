package com.example.feudal.npc;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;

public class FeudalNPCTrait extends Trait {

    @Persist private String role;
    @Persist private Integer familyId;

    // TAX
    @Persist private Integer taxAmount = 0;
    @Persist private Long intervalMs = 300_000L;   // 5분
    @Persist private Long nextCollectAtMs = 0L;

    // FARM
    @Persist private Long farmIntervalMs = 5_000L; // 5초
    @Persist private Long nextFarmAtMs = 0L;

    public FeudalNPCTrait() {
        super("feudal");
    }

    // -------- Role --------
    public void setRole(NPCRole role) {
        this.role = (role == null ? null : role.name());
    }

    public NPCRole getRole() {
        if (role == null) return null;
        try { return NPCRole.valueOf(role); }
        catch (Exception e) { return null; }
    }

    // -------- Family --------
    public Integer getFamilyId() { return familyId; }
    public void setFamilyId(Integer familyId) { this.familyId = familyId; }

    // -------- TAX --------
    public int getTaxAmount() { return taxAmount == null ? 0 : taxAmount; }
    public void setTaxAmount(int taxAmount) { this.taxAmount = taxAmount; }

    public long getIntervalMs() { return intervalMs == null ? 300_000L : intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

    public long getNextCollectAtMs() { return nextCollectAtMs == null ? 0L : nextCollectAtMs; }
    public void setNextCollectAtMs(long nextCollectAtMs) { this.nextCollectAtMs = nextCollectAtMs; }

    public void initNextIfNeeded(long nowMs) {
        if (getNextCollectAtMs() <= 0L) setNextCollectAtMs(nowMs + getIntervalMs());
    }

    // -------- FARM --------
    public long getFarmIntervalMs() { return farmIntervalMs == null ? 5_000L : farmIntervalMs; }
    public void setFarmIntervalMs(long ms) { this.farmIntervalMs = ms; }

    public long getNextFarmAtMs() { return nextFarmAtMs == null ? 0L : nextFarmAtMs; }
    public void setNextFarmAtMs(long ms) { this.nextFarmAtMs = ms; }

    public void initFarmNextIfNeeded(long nowMs) {
        if (getNextFarmAtMs() <= 0L) setNextFarmAtMs(nowMs);
    }
}