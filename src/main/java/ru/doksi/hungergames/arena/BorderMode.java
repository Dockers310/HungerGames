package ru.doksi.hungergames.arena;

public enum BorderMode {
    DAMAGE,
    SOFT_TELEPORT,
    CHANGING;

    public BorderMode next() {
        return switch (this) {
            case DAMAGE -> SOFT_TELEPORT;
            case SOFT_TELEPORT -> CHANGING;
            case CHANGING -> DAMAGE;
        };
    }

    public String displayName() {
        return switch (this) {
            case DAMAGE -> "Урон";
            case SOFT_TELEPORT -> "Мягкая";
            case CHANGING -> "Меняющаяся";
        };
    }
}
