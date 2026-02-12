package com.example.feudal.model;

public enum Rank {
    KING,        // 왕
    GRAND_DUKE,  // 대공
    DUKE,        // 공작
    COUNT,       // 백작
    BARON,       // 남작
    KNIGHT,      // 기사
    PEASANT;     // 평민

    public static Rank promote(Rank cur) {
        if (cur == null) return PEASANT;
        return switch (cur) {
            case PEASANT -> KNIGHT;
            case KNIGHT -> BARON;
            case BARON -> COUNT;
            case COUNT -> DUKE;
            case DUKE -> GRAND_DUKE;
            case GRAND_DUKE -> KING;
            case KING -> KING; // 최고는 그대로
        };
    }

    public static Rank demote(Rank cur) {
        if (cur == null) return PEASANT;
        return switch (cur) {
            case KING -> GRAND_DUKE;
            case GRAND_DUKE -> DUKE;
            case DUKE -> COUNT;
            case COUNT -> BARON;
            case BARON -> KNIGHT;
            case KNIGHT -> PEASANT;
            case PEASANT -> PEASANT; // 최저는 그대로
        };
    }

    public String displayNameKo() {
        return switch (this) {
            case KING -> "왕";
            case GRAND_DUKE -> "대공";
            case DUKE -> "공작";
            case COUNT -> "백작";
            case BARON -> "남작";
            case KNIGHT -> "기사";
            case PEASANT -> "평민";
        };
    }
}