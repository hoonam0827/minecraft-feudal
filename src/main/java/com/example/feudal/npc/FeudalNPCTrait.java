package com.example.feudal.npc;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;

public class FeudalNPCTrait extends Trait {

    @Persist private String role;
    @Persist private Integer familyId;
    @Persist private Integer taxAmount = 0;
    @Persist private Long intervalMs = 300_000L;
    @Persist private Long nextCollectAtMs = 0L;

    public FeudalNPCTrait() {
        super("feudal");
    }

    public void setRole(NPCRole role) { this.role = role.name(); }

    public NPCRole getRole() {
        if (role == null) return null;
        return NPCRole.valueOf(role);
    }

    public Integer getFamilyId() { return familyId; }
    public void setFamilyId(Integer familyId) { this.familyId = familyId; }

    public int getTaxAmount() { return taxAmount == null ? 0 : taxAmount; }
    public void setTaxAmount(int taxAmount) { this.taxAmount = taxAmount; }

    public long getIntervalMs() { return intervalMs == null ? 300_000L : intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

    public long getNextCollectAtMs() { return nextCollectAtMs == null ? 0L : nextCollectAtMs; }
    public void setNextCollectAtMs(long nextCollectAtMs) { this.nextCollectAtMs = nextCollectAtMs; }

    public void initNextIfNeeded(long nowMs) {
        if (getNextCollectAtMs() <= 0L) setNextCollectAtMs(nowMs + getIntervalMs());
    }
}