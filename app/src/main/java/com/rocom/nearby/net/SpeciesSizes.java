package com.rocom.nearby.net;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.Nullable;

import com.rocom.nearby.R;
import com.rocom.nearby.entity.NearbyEntity;

import java.util.HashMap;
import java.util.Map;

public final class SpeciesSizes {

    private static final String TAG = "NearbySizes";
    static final double BIG_PERCENTILE = NearbyEntity.WEIGHT_PERCENTILE_BIG;
    static final double SMALL_PERCENTILE = NearbyEntity.WEIGHT_PERCENTILE_SMALL;
    private static volatile Map<Long, double[]> ranges;
    private static volatile Map<Long, double[]> heightRanges;
    private static volatile Map<Long, Long> forms;
    
    public static void load(Context context) {
        if (context == null) {
            return;
        }
        Resources res = context.getResources();
        Map<Long, double[]> newRanges = new HashMap<>();
        Map<Long, double[]> newHeightRanges = new HashMap<>();
        Map<Long, Long> newForms = new HashMap<>();
        int[] cfg = intArray(res, R.array.cfgId);
        int[] base = intArray(res, R.array.baseConfId);
        parseRanges(base, intArray(res, R.array.weight_min),
                intArray(res, R.array.weight_max), newRanges);
        parseRanges(base, intArray(res, R.array.height_min),
                intArray(res, R.array.height_max), newHeightRanges);
        parseForms(cfg, base, newForms);
        ranges = newRanges;
        heightRanges = newHeightRanges;
        forms = newForms;
    }
    
    public static double weightPercentileForForm(long cfgId, double weightGrams) {
        return percentile(weightGrams, rangeForForm(ranges, forms, cfgId));
    }

    public static double weightPercentileForSpecies(long speciesId, double weightGrams) {
        return percentile(weightGrams, rangeForSpecies(ranges, speciesId));
    }

    public static double heightPercentileForForm(long cfgId, double heightCm) {
        return percentile(heightCm, rangeForForm(heightRanges, forms, cfgId));
    }

    public static double heightPercentileForSpecies(long speciesId, double heightCm) {
        return percentile(heightCm, rangeForSpecies(heightRanges, speciesId));
    }
    
    public static long speciesForForm(long cfgId) {
        Map<Long, Long> current = forms;
        if (current == null) {
            return 0L;
        }
        Long species = current.get(cfgId);
        return species == null ? 0L : species;
    }

    private static int[] intArray(Resources res, int id) {
        try {
            int[] values = res.getIntArray(id);
            return values == null ? new int[0] : values;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read integer array " + id, e);
            return new int[0];
        }
    }

    static void parseRanges(int[] ids, int[] lows, int[] highs, Map<Long, double[]> into) {
        if (ids == null || lows == null || highs == null) {
            return;
        }
        int count = Math.min(ids.length, Math.min(lows.length, highs.length));
        for (int i = 0; i < count; i++) {
            long id = ids[i];
            double low = lows[i];
            double high = highs[i];
            if (id <= 0L || high <= low) {
                continue;
            }
            into.put(id, new double[]{low, high});
        }
    }

    static void parseForms(int[] cfgIds, int[] speciesIds, Map<Long, Long> into) {
        if (cfgIds == null || speciesIds == null) {
            return;
        }
        int count = Math.min(cfgIds.length, speciesIds.length);
        for (int i = 0; i < count; i++) {
            long cfgId = cfgIds[i];
            long species = speciesIds[i];
            if (cfgId <= 0L || species <= 0L) {
                continue;
            }
            into.put(cfgId, species);
        }
    }

    @Nullable
    static double[] rangeForForm(@Nullable Map<Long, double[]> rangeTable,
                                 @Nullable Map<Long, Long> formMap, long cfgId) {
        if (rangeTable == null || formMap == null || cfgId <= 0L) {
            return null;
        }
        Long species = formMap.get(cfgId);
        if (species == null) {
            return null;
        }
        return rangeTable.get(species);
    }

    @Nullable
    static double[] rangeForSpecies(@Nullable Map<Long, double[]> rangeTable, long speciesId) {
        if (rangeTable == null || speciesId <= 0L) {
            return null;
        }
        return rangeTable.get(speciesId);
    }

    static double percentile(double value, @Nullable double[] range) {
        if (range == null || range.length < 2) {
            return Double.NaN;
        }
        double low = range[0];
        double high = range[1];
        if (!(value > 0) || !Double.isFinite(low) || !Double.isFinite(high) || high <= low) {
            return Double.NaN;
        }
        double percent = (value - low) / (high - low) * 100.0;
        if (percent < 0.0) {
            return 0.0;
        }
        if (percent > 100.0) {
            return 100.0;
        }
        return percent;
    }
}
