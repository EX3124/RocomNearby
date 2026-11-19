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
    @Nullable
    public final String name;
    public final int level;
    public final List<String> badges;
    public final double heightMeters;
    public final double weightKg;
    public final double voice;
    public final double heightPercentile;
    public final double weightPercentile;
    public final double worldX;
    public final double worldY;
    public final boolean fighting;
    public NearbyEntity(@Nullable String name, int level,
                        @Nullable List<String> badges,
                        double heightMeters, double weightKg, double voice,
                        double heightPercentile, double weightPercentile,
                        double worldX, double worldY, boolean fighting) {
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
    }

    public String identityKey() {
        return (name == null ? "" : name) + '|' + level
                + '|' + heightMeters + '|' + weightKg + '|' + voice;
    }

    private static List<String> freezeBadges(@Nullable List<String> badges) {
        if (badges == null || badges.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(badges));
    }
}
