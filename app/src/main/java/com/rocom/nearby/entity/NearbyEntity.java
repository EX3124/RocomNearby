package com.rocom.nearby.entity;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NearbyEntity {
    
    public static final double UNKNOWN = Double.NaN;
    public static final double WEIGHT_PERCENTILE_BIG = 98.0;
    public static final double WEIGHT_PERCENTILE_SMALL = 2.0;
    public static final long VOICE_HIGH = 96L;
    public static final long VOICE_LOW = -96L;
    public static final long GENDER_MALE = 1L;
    public static final long GENDER_FEMALE = 2L;
    @Nullable
    public final String name;
    public final int level;
    public final List<Badge> badges;
    public final double heightMeters;
    public final double weightKg;
    public final double voice;
    public final double heightPercentile;
    public final double weightPercentile;
    public final double worldX;
    public final double worldY;
    public final boolean fighting;
    public final long gender;
    public final long battlestate;
    public NearbyEntity(@Nullable String name, int level,
                        @Nullable List<Badge> badges,
                        double heightMeters, double weightKg, double voice,
                        double heightPercentile, double weightPercentile,
                        double worldX, double worldY, boolean fighting,
                        long gender, long battlestate) {
        this.name = name;
        this.level = level;
        this.badges = freezeBadges(badges);
        this.heightMeters = heightMeters;
        this.weightKg = weightKg;
        this.voice = voice;
        this.heightPercentile = heightPercentile;
        this.weightPercentile = weightPercentile;
        this.worldX = worldX;
        this.worldY = worldY;
        this.fighting = fighting;
        this.gender = gender;
        this.battlestate = battlestate;
    }

    public boolean hasBadge(Badge.Kind kind) {
        for (int i = 0; i < badges.size(); i++) {
            if (badges.get(i).kind == kind) {
                return true;
            }
        }
        return false;
    }

    public String identityKey() {
        return (name == null ? "" : name) + '|' + level
                + '|' + heightMeters + '|' + weightKg + '|' + voice;
    }

    private static List<Badge> freezeBadges(@Nullable List<Badge> badges) {
        if (badges == null || badges.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(badges));
    }
}
