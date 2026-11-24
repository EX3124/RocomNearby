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

    private static volatile Map<Integer, String> species;
    private static volatile Map<Integer, String> forms;

    public static void load(Context context) {
        if (species != null || context == null) {
            return;
        }
        Map<Integer, String> speciesTable = new HashMap<>();
        Map<Integer, String> formTable = new HashMap<>();
        try {
            Resources res = context.getResources();
            parseInto(res.getIntArray(R.array.cfgId), res.getIntArray(R.array.baseConfId),
                    res.getStringArray(R.array.name), speciesTable, formTable);
        } catch (Throwable e) {
            Log.w(TAG, "Cannot read the species name arrays", e);
        }
        species = speciesTable;
        forms = formTable;
    }

    static void parseInto(int[] cfgIds, int[] baseIds, String[] names,
                          Map<Integer, String> speciesInto, Map<Integer, String> formInto) {
        if (cfgIds == null || baseIds == null || names == null) {
            return;
        }
        int count = Math.min(cfgIds.length, Math.min(baseIds.length, names.length));
        for (int i = 0; i < count; i++) {
            if (cfgIds[i] > 0) {
                putName(formInto, cfgIds[i], names[i], false);
                putName(speciesInto, baseIds[i], names[i], false);
            }
        }
        for (int i = 0; i < count; i++) {
            if (cfgIds[i] <= 0) {
                putName(speciesInto, baseIds[i], names[i], true);
            }
        }
    }

    private static void putName(Map<Integer, String> into, int id, String raw, boolean override) {
        String name = raw == null ? "" : raw.trim();
        if (id <= 0 || name.isEmpty() || PLACEHOLDER_NAMES.contains(name)) {
            return;
        }
        if (override || !into.containsKey(id)) {
            into.put(id, name);
        }
    }

    public static String formName(long id) {
        return displayName(forms, id, false);
    }

    static String displayName(@Nullable Map<Integer, String> loaded, long id, boolean battle) {
        String known = lookup(loaded, id, battle);
        if (known != null) {
            return known;
        }
        return id > 0 && id <= Integer.MAX_VALUE ? Long.toString(id) : "";
    }

    @Nullable
    static String speciesLookup(long id) {
        return lookup(species, id, true);
    }

    @Nullable
    static String lookup(@Nullable Map<Integer, String> loaded, long id, boolean battle) {
        if (id <= 0 || id > Integer.MAX_VALUE) {
            return null;
        }
        int key = (int) id;
        String name = loaded == null ? null : loaded.get(key);
        if (name != null || !battle) {
            return name;
        }
        return BATTLE_ONLY_NAMES.get(key);
    }

    public static String speciesName(long baseConfId) {
        return displayName(species, baseConfId, true);
    }
}
