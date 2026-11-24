package com.rocom.nearby.entity;

public final class Badge {

    public enum Kind {
        NATURE,
        BLOODLINE,
        COLORFUL,
        SHINY,
        POLLUTION,
        STRANGE,
        BIG,
        SMALL,
        HIGH,
        LOW
    }
    public static final int BLOODLINE_MIN = 1;
    public static final int BLOODLINE_MAX = 19;
    public static final int NO_VALUE = 0;
    public final Kind kind;
    public final int value;

    public Badge(Kind kind, int value) {
        this.kind = kind;
        this.value = value;
    }

    public static Badge of(Kind kind) {
        return new Badge(kind, NO_VALUE);
    }

    public static Badge bloodline(long id) {
        if (id < BLOODLINE_MIN || id > BLOODLINE_MAX) {
            return null;
        }
        return new Badge(Kind.BLOODLINE, (int) id);
    }

    public static Badge nature(long id) {
        if (id <= 0) {
            return null;
        }
        return new Badge(Kind.NATURE, (int) id);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Badge)) {
            return false;
        }
        Badge badge = (Badge) other;
        return kind == badge.kind && value == badge.value;
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + value;
    }
}