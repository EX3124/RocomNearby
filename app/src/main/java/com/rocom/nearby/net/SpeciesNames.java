package com.rocom.nearby.net;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.Nullable;

import com.rocom.nearby.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SpeciesNames {

    private static final String TAG = "NearbyNames";
    private static final Set<String> PLACEHOLDER_NAMES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("随机精灵", "未知精灵")));
    private static final Map<Integer, String> BATTLE_ONLY_NAMES;

    static {
        Map<Integer, String> special = new HashMap<>();
        special.put(9006, "龙棋士");
        BATTLE_ONLY_NAMES = Collections.unmodifiableMap(special);
    }

    private static volatile Map<Integer, String> table;
    
    public static void load(Context context) {
        if (table != null || context == null) {
            return;
        }
        Map<Integer, String> merged = new HashMap<>();
        try {
            Resources res = context.getResources();
            parseInto(res.getIntArray(R.array.cfgId), res.getIntArray(R.array.baseConfId),
                    res.getStringArray(R.array.name), merged);
        } catch (Throwable e) {
            Log.w(TAG, "Cannot read the species name arrays", e);
        }
        table = merged;
    }
    
    static void parseInto(int[] cfgIds, int[] baseIds, String[] names,
                          Map<Integer, String> into) {
        if (cfgIds == null || baseIds == null || names == null) {
            return;
        }
        int count = Math.min(cfgIds.length, Math.min(baseIds.length, names.length));
        for (int i = 0; i < count; i++) {
            if (cfgIds[i] > 0) {
                putName(into, cfgIds[i], names[i]);
            }
        }
        for (int i = 0; i < count; i++) {
            if (cfgIds[i] <= 0) {
                putName(into, baseIds[i], names[i]);
            }
        }
    }

    private static void putName(Map<Integer, String> into, int id, String raw) {
        String name = raw == null ? "" : raw.trim();
        if (id <= 0 || name.isEmpty() || PLACEHOLDER_NAMES.contains(name)) {
            return;
        }
        if (!into.containsKey(id)) {
            into.put(id, name);
        }
    }

    public static String name(long id) {
        return displayName(table, id);
    }

    static String displayName(@Nullable Map<Integer, String> loaded, long id) {
        String known = lookup(loaded, id);
        if (known != null) {
            return known;
        }
        return id > 0 && id <= Integer.MAX_VALUE ? Long.toString(id) : "";
    }

    @Nullable
    static String known(long id) {
        return lookup(table, id);
    }

    @Nullable
    static String lookup(@Nullable Map<Integer, String> loaded, long id) {
        if (id <= 0 || id > Integer.MAX_VALUE) {
            return null;
        }
        int key = (int) id;
        String name = loaded == null ? null : loaded.get(key);
        return name != null ? name : BATTLE_ONLY_NAMES.get(key);
    }

    public static int size() {
        Map<Integer, String> current = table;
        return current == null ? 0 : current.size();
    }
}
