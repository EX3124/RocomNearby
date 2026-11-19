package com.rocom.nearby.entity;

import java.util.Locale;

public final class NearbyEntityFormat {
    
    public static String distance(double meters) {
        if (!Double.isFinite(meters) || meters < 0) {
            return "";
        }
        if (meters < 1000) {
            return Math.round(meters) + "m";
        }
        return String.format(Locale.US, "%.1fkm", meters / 1000.0);
    }
    
    public static String height(double meters, double percentile) {
        if (!Double.isFinite(meters) || meters <= 0) {
            return "";
        }
        return String.format(Locale.US, "%.2fm", meters) + percent(percentile, 0);
    }
    
    public static String weight(double kg, double percentile) {
        if (!Double.isFinite(kg) || kg <= 0) {
            return "";
        }
        return String.format(Locale.US, "%.2fkg", kg) + percent(percentile, 1);
    }
    
    private static String percent(double percentile, int decimals) {
        if (!Double.isFinite(percentile)) {
            return "";
        }
        double clamped = Math.max(0.0, Math.min(100.0, percentile));
        return String.format(Locale.US, "(%." + decimals + "f%%)", clamped);
    }
    
    public static String voice(double voice) {
        if (!Double.isFinite(voice)) {
            return "";
        }
        return Math.round(voice) + "dB";
    }

    public static double distanceMeters(double dx, double dy) {
        return Math.sqrt(dx * dx + dy * dy) / 100.0;
    }

    public static int bearingIndex(double dx, double dy) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dy));
        double normalized = ((degrees % 360) + 360) % 360;
        return (int) Math.round(normalized / 45.0) % 8;
    }
}
