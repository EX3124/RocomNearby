package com.rocom.nearby.map;

public final class MapZoomMath {

    private static final double TOLERANCE_M = 1e-6;

    public static double spanForPinch(double startSpan, double startDistance, double currentDistance, double minSpan, double maxSpan) {
        if (!isPositiveFinite(startSpan))
            return clampSpan(0, minSpan, maxSpan);
        if (!isPositiveFinite(startDistance) || !isPositiveFinite(currentDistance))
            return clampSpan(startSpan, minSpan, maxSpan);
        return clampSpan(startSpan * startDistance / currentDistance, minSpan, maxSpan);
    }

    public static double zoomInSpan(double[] levels, double span) {
        double best = Double.NaN;
        for (double level : levels) {
            if (level < span - TOLERANCE_M && (Double.isNaN(best) || level > best))
                best = level;
        }
        return Double.isNaN(best) ? tightestSpan(levels, span) : best;
    }

    public static double zoomOutSpan(double[] levels, double span) {
        double best = Double.NaN;
        for (double level : levels) {
            if (level > span + TOLERANCE_M && (Double.isNaN(best) || level < best))
                best = level;
        }
        return Double.isNaN(best) ? widestSpan(levels, span) : best;
    }

    public static String formatSpan(double span) {
        if (!Double.isFinite(span))
            return "";
        return Math.max(1L, Math.round(span)) + "m";
    }

    public static double sourceAtScreen(double centreSource, double screenPx, double viewportPx, double scale) {
        if (!(scale > 0))
            return centreSource;
        return centreSource + (screenPx - viewportPx / 2.0) / scale;
    }

    public static double centreForSourceAtScreen(double sourcePx, double screenPx, double viewportPx, double scale) {
        if (!(scale > 0))
            return sourcePx;
        return sourcePx - (screenPx - viewportPx / 2.0) / scale;
    }

    private static boolean isPositiveFinite(double value) {
        return value > 0 && Double.isFinite(value);
    }

    private static double clampSpan(double span, double minSpan, double maxSpan) {
        double low = Math.min(minSpan, maxSpan);
        double high = Math.max(minSpan, maxSpan);
        if (!Double.isFinite(span))
            return high;
        return Math.max(low, Math.min(high, span));
    }

    private static double tightestSpan(double[] levels, double fallback) {
        double tightest = isPositiveFinite(fallback) ? fallback : Double.POSITIVE_INFINITY;
        for (double level : levels) {
            if (isPositiveFinite(level))
                tightest = Math.min(tightest, level);
        }
        return Double.isFinite(tightest) ? tightest : fallback;
    }

    private static double widestSpan(double[] levels, double fallback) {
        double widest = isPositiveFinite(fallback) ? fallback : 0;
        for (double level : levels) {
            if (isPositiveFinite(level))
                widest = Math.max(widest, level);
        }
        return widest;
    }
}
