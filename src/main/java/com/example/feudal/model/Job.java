package com.example.feudal.model;

public enum Job {
    NONE,       // 무직(기본)
    FARMER,     // 농부
    MINER,      // 광부
    GUARD,      // 경비
    MERCHANT,   // 상인
    BUILDER,    // 건축가
    TAX_COLLECTOR;   // 납세자(원하면 삭제 가능)

    public static boolean isValid(String s) {
        try { Job.valueOf(s.toUpperCase()); return true; }
        catch (Exception e) { return false; }
    }
}