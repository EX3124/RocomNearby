package com.rocom.nearby.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BattleParser {

    private static final int FIELD_GID = 1;
    private static final int FIELD_CONF_ID = 2;
    private static final int FIELD_NATURE = 7;
    private static final int FIELD_LEVEL = 10;
    private static final int FIELD_SPECIES = 15;
    private static final int FIELD_HEIGHT = 26;
    private static final int FIELD_WEIGHT = 27;
    private static final int FIELD_VOICE = 93;
    private static final int FIELD_VARIANT = 45;
    private static final int FIELD_BLOODLINE = 47;
    private static final int FIELD_GENDER = 8;
    private static final int FIELD_BATTLESTATE = 24;
    private static final int FIELD_BATTLEORDER = 25;
    private static final int FIELD_SLOT_INFO = 1;
    private static final int FIELD_KILL_MARKER = 4;
    private static final int FIELD_PET_SPECIES = 12;
    private static final long MIN_HEIGHT_CM = 10L;
    private static final long MAX_HEIGHT_CM = 500L;
    private static final long MIN_WEIGHT_G = 100L;
    private static final long MAX_WEIGHT_G = 1000000L;
    private static final int FIELD_SIDES = 6;
    private static final int FIELD_ENEMY_SIDE = 6;
    private static final int FIELD_SLOT_4886 = 2;
    private static final int FIELD_SLOT_4890 = 3;
    private static final int FIELD_GROUP_4890 = 44;
    private static final int FIELD_ENTRY_4890 = 8;
    private static final int FIELD_TEAM_SNAPSHOT = 20;
    private static final int FIELD_TEAM_ID = 1;
    private static final int FIELD_UNIT = 2;
    private static final int FIELD_ON_FIELD = 5;
    private static final int FIELD_UPDATE = 3;
    private static final long MIN_SPECIES = 1000L;
    private static final int MAX_DEPTH = 8;
    private static final int MIN_START = 8;
    private static final int MAX_START = 20;
    private static final int MIN_FRAME = 16;

    static List<BattleUnit> parse(byte[] plain) {
        if (plain == null || plain.length < MIN_FRAME)
            return Collections.emptyList();
        List<BattleUnit> best = Collections.emptyList();
        int limit = Math.min(MAX_START, plain.length - 1);
        for (int start = MIN_START; start <= limit; start++) {
            List<BattleUnit> found = new ArrayList<>();
            collect(plain, start, plain.length, 0, found);
            if (found.size() > best.size())
                best = found;
        }
        if (best.isEmpty())
            return Collections.emptyList();
        Set<String> seen = new LinkedHashSet<>();
        List<BattleUnit> out = new ArrayList<>(best.size());
        for (int i = 0; i < best.size(); i++) {
            BattleUnit unit = best.get(i);
            if (seen.add(unit.key()))
                out.add(unit);
        }
        return out;
    }

    static final class EnemyGroup {

        final List<BattleUnit> units;
        final int onField;
        final Set<Long> teams;

        EnemyGroup(List<BattleUnit> units, int onField, Set<Long> teams) {
            this.units = units;
            this.onField = onField;
            this.teams = teams;
        }
    }

    static EnemyGroup parseEnemyGroup(byte[] plain, Set<Long> enemyTeams) {
        if (plain == null || plain.length < MIN_FRAME)
            return null;
        int start = ProtoScan.findMessageStart(plain);
        if (start < 0)
            return null;
        List<ProtoScan.Field> top = ProtoScan.scan(plain, start, plain.length);
        ProtoScan.Field sides = ProtoScan.first(top, FIELD_SIDES, ProtoScan.WIRE_LENGTH);
        if (sides != null) {
            List<ProtoScan.Field> children = ProtoScan.scan(plain, sides.valueStart, sides.valueEnd);
            List<BattleUnit> units = new ArrayList<>();
            int onField = 0;
            for (int i = 0; i < children.size(); i++) {
                ProtoScan.Field child = children.get(i);
                if (child.number != FIELD_ENEMY_SIDE || child.wire != ProtoScan.WIRE_LENGTH)
                    continue;
                if (onField == 0)
                    onField = onFieldCount(plain, child);
                List<BattleUnit> line = slots(plain, child, FIELD_SLOT_4886);
                for (int j = 0; j < line.size(); j++) {
                    BattleUnit unit = line.get(j);
                    if (!hasKey(units, unit.key()))
                        units.add(unit);
                }
            }
            if (!units.isEmpty() || onField > 0)
                return new EnemyGroup(units, onField, enemyTeamsOf(plain, children, units));
        }
        ProtoScan.Field update = ProtoScan.first(top, FIELD_UPDATE, ProtoScan.WIRE_LENGTH);
        if (update == null)
            return null;
        List<Long> teams = new ArrayList<>();
        List<List<BattleUnit>> rosters = new ArrayList<>();
        List<ProtoScan.Field> buckets = ProtoScan.scan(plain, update.valueStart, update.valueEnd);
        for (int i = 0; i < buckets.size(); i++) {
            ProtoScan.Field bucket = buckets.get(i);
            if (bucket.wire != ProtoScan.WIRE_LENGTH)
                continue;
            List<ProtoScan.Field> groups = ProtoScan.scan(plain, bucket.valueStart, bucket.valueEnd);
            for (int j = 0; j < groups.size(); j++) {
                ProtoScan.Field group = groups.get(j);
                if (group.number != FIELD_GROUP_4890 || group.wire != ProtoScan.WIRE_LENGTH)
                    continue;
                List<ProtoScan.Field> entries = ProtoScan.scan(plain, group.valueStart, group.valueEnd);
                for (int k = 0; k < entries.size(); k++) {
                    ProtoScan.Field entry = entries.get(k);
                    if (entry.number != FIELD_ENTRY_4890 || entry.wire != ProtoScan.WIRE_LENGTH)
                        continue;
                    List<BattleUnit> roster = slots(plain, entry, FIELD_SLOT_4890);
                    if (roster.isEmpty())
                        continue;
                    rosters.add(roster);
                    teams.add(value(ProtoScan.scan(plain, entry.valueStart, entry.valueEnd), FIELD_TEAM_ID));
                }
            }
        }
        if (rosters.isEmpty())
            return null;
        if (enemyTeams != null && !enemyTeams.isEmpty()) {
            List<BattleUnit> units = new ArrayList<>();
            for (int i = 0; i < rosters.size(); i++) {
                if (!enemyTeams.contains(teams.get(i)))
                    continue;
                List<BattleUnit> roster = rosters.get(i);
                for (int j = 0; j < roster.size(); j++) {
                    BattleUnit unit = roster.get(j);
                    if (!hasKey(units, unit.key()))
                        units.add(unit);
                }
            }
            return units.isEmpty() ? null : new EnemyGroup(units, 0, Collections.<Long>emptySet());
        }
        return new EnemyGroup(rosters.get(rosters.size() - 1), 0, Collections.<Long>emptySet());
    }

    private static Set<Long> enemyTeamsOf(byte[] plain, List<ProtoScan.Field> children, List<BattleUnit> enemy) {
        Set<Long> teams = new LinkedHashSet<>();
        if (enemy.isEmpty())
            return teams;
        for (int i = 0; i < children.size(); i++) {
            ProtoScan.Field child = children.get(i);
            if (child.number != FIELD_TEAM_SNAPSHOT || child.wire != ProtoScan.WIRE_LENGTH)
                continue;
            long id = value(ProtoScan.scan(plain, child.valueStart, child.valueEnd), FIELD_TEAM_ID);
            if (id == 0L)
                continue;
            List<BattleUnit> roster = new ArrayList<>();
            collect(plain, child.valueStart, child.valueEnd, 0, roster);
            for (int j = 0; j < roster.size(); j++) {
                if (hasFingerprint(enemy, roster.get(j))) {
                    teams.add(id);
                    break;
                }
            }
        }
        return teams;
    }

    private static String fingerprint(BattleUnit unit) {
        return unit.speciesId + ":" + unit.height + ":" + unit.weight + ":" + unit.voice;
    }

    private static boolean hasFingerprint(List<BattleUnit> units, BattleUnit other) {
        String key = fingerprint(other);
        for (int i = 0; i < units.size(); i++) {
            if (fingerprint(units.get(i)).equals(key))
                return true;
        }
        return false;
    }

    private static boolean hasKey(List<BattleUnit> units, String key) {
        for (int i = 0; i < units.size(); i++) {
            if (units.get(i).key().equals(key))
                return true;
        }
        return false;
    }

    private static int onFieldCount(byte[] plain, ProtoScan.Field side) {
        List<ProtoScan.Field> header = ProtoScan.scan(plain, side.valueStart, side.valueEnd);
        ProtoScan.Field bare = ProtoScan.first(header, FIELD_ON_FIELD, ProtoScan.WIRE_VARINT);
        if (bare != null)
            return (int) bare.varint;
        ProtoScan.Field box = ProtoScan.first(header, FIELD_ON_FIELD, ProtoScan.WIRE_LENGTH);
        if (box == null)
            return 0;
        List<ProtoScan.Field> inner = ProtoScan.scan(plain, box.valueStart, box.valueEnd);
        for (int i = 0; i < inner.size(); i++) {
            if (inner.get(i).wire == ProtoScan.WIRE_VARINT)
                return (int) inner.get(i).varint;
        }
        return 0;
    }

    private static List<BattleUnit> slots(byte[] plain, ProtoScan.Field group, int slotNumber) {
        List<BattleUnit> out = new ArrayList<>();
        List<ProtoScan.Field> candidates = ProtoScan.scan(plain, group.valueStart, group.valueEnd);
        for (int i = 0; i < candidates.size(); i++) {
            ProtoScan.Field slot = candidates.get(i);
            if (slot.number != slotNumber || slot.wire != ProtoScan.WIRE_LENGTH)
                continue;
            ProtoScan.Field unit = ProtoScan.first(ProtoScan.scan(plain, slot.valueStart, slot.valueEnd), FIELD_UNIT, ProtoScan.WIRE_LENGTH);
            if (unit == null)
                continue;
            BattleUnit parsed = accept(ProtoScan.scan(plain, unit.valueStart, unit.valueEnd));
            if (parsed != null && !holds(out, parsed.key()))
                out.add(withSlot(parsed, plain, slot));
        }
        return out;
    }

    private static boolean holds(List<BattleUnit> units, String key) {
        for (int i = 0; i < units.size(); i++) {
            if (units.get(i).key().equals(key))
                return true;
        }
        return false;
    }

    private static void collect(byte[] buf, int start, int end, int depth, List<BattleUnit> out) {
        if (depth > MAX_DEPTH || start >= end) return;
        List<ProtoScan.Field> fields = ProtoScan.scan(buf, start, end);
        BattleUnit unit = accept(fields);
        if (unit != null) {
            out.add(unit);
            return;
        }
        for (int i = 0; i < fields.size(); i++) {
            ProtoScan.Field field = fields.get(i);
            if (field.wire == ProtoScan.WIRE_LENGTH)
                collect(buf, field.valueStart, field.valueEnd, depth + 1, out);
        }
    }

    private static BattleUnit accept(List<ProtoScan.Field> fields) {
        ProtoScan.Field species = ProtoScan.first(fields, FIELD_SPECIES, ProtoScan.WIRE_VARINT);
        if (species == null || species.varint <= MIN_SPECIES)
            return null;
        ProtoScan.Field height = ProtoScan.first(fields, FIELD_HEIGHT, ProtoScan.WIRE_VARINT);
        if (height == null || height.varint <= 0)
            return null;
        ProtoScan.Field weight = ProtoScan.first(fields, FIELD_WEIGHT, ProtoScan.WIRE_VARINT);
        if (weight == null || weight.varint <= 0)
            return null;
        ProtoScan.Field voice = ProtoScan.first(fields, FIELD_VOICE, ProtoScan.WIRE_VARINT);
        if (voice == null)
            return null;
        return new BattleUnit(value(fields, FIELD_GID), species.varint, value(fields, FIELD_CONF_ID), value(fields, FIELD_LEVEL), height.varint, weight.varint, voice.varint, value(fields, FIELD_NATURE), value(fields, FIELD_VARIANT), value(fields, FIELD_BLOODLINE), value(fields, FIELD_GENDER), 0L, 0L);
    }

    static long parseSnapshotToken(byte[] plain) {
        if (plain == null || plain.length < 16)
            return 0L;
        int start = ProtoScan.findMessageStart(plain);
        if (start < 0)
            return 0L;
        List<ProtoScan.Field> top = ProtoScan.scan(plain, start, plain.length);
        for (int i = 0; i < top.size(); i++) {
            ProtoScan.Field field = top.get(i);
            if (field.number == 9 && field.wire == ProtoScan.WIRE_VARINT)
                return field.varint;
        }
        return 0L;
    }

    private static long value(List<ProtoScan.Field> fields, int number) {
        ProtoScan.Field field = ProtoScan.first(fields, number, ProtoScan.WIRE_VARINT);
        return field == null ? 0L : field.varint;
    }

    static final class Result {

        final boolean hasResult;
        final List<long[]> captured;

        Result(boolean hasResult, List<long[]> captured) {
            this.hasResult = hasResult;
            this.captured = captured;
        }

        boolean capturedMatches(BattleUnit unit) {
            for (int i = 0; i < captured.size(); i++) {
                long[] print = captured.get(i);
                if (print[0] == unit.level && print[1] == unit.height && print[2] == unit.weight && print[3] == unit.voice)
                    return true;
            }
            return false;
        }
    }

    static Result parseResult(byte[] plain) {
        if (plain == null || plain.length < MIN_FRAME)
            return new Result(false, Collections.emptyList());
        int start = ProtoScan.findMessageStart(plain);
        if (start < 0)
            return new Result(false, Collections.emptyList());
        List<ProtoScan.Field> top = ProtoScan.scan(plain, start, plain.length);
        ProtoScan.Field loot = null;
        for (int i = 0; i < top.size(); i++) {
            ProtoScan.Field field = top.get(i);
            if (field.number == FIELD_KILL_MARKER && field.wire == ProtoScan.WIRE_LENGTH)
                loot = field;
        }
        boolean hasResult = loot != null && loot.valueEnd > loot.valueStart;
        List<long[]> captured = new ArrayList<>();
        collectCaptured(plain, loot, 0, captured);
        return new Result(hasResult, captured);
    }

    private static void collectCaptured(byte[] buf, ProtoScan.Field parent, int depth, List<long[]> out) {
        if (parent == null || depth > MAX_DEPTH) return;
        List<ProtoScan.Field> fields = ProtoScan.scan(buf, parent.valueStart, parent.valueEnd);
        long[] print = fingerprint(fields);
        if (print != null) {
            for (int i = 0; i < out.size(); i++) {
                long[] seen = out.get(i);
                if (seen[0] == print[0] && seen[1] == print[1] && seen[2] == print[2] && seen[3] == print[3])
                    return;
            }
            out.add(print);
            return;
        }
        for (int i = 0; i < fields.size(); i++) {
            ProtoScan.Field field = fields.get(i);
            if (field.wire == ProtoScan.WIRE_LENGTH)
                collectCaptured(buf, field, depth + 1, out);
        }
    }

    private static BattleUnit withSlot(BattleUnit unit, byte[] plain, ProtoScan.Field slot) {
        List<ProtoScan.Field> kids = ProtoScan.scan(plain, slot.valueStart, slot.valueEnd);
        ProtoScan.Field info = ProtoScan.first(kids, FIELD_SLOT_INFO, ProtoScan.WIRE_LENGTH);
        long battlestate = 0L;
        long battleorder = 0L;
        if (info != null) {
            List<ProtoScan.Field> inner = ProtoScan.scan(plain, info.valueStart, info.valueEnd);
            battlestate = value(inner, FIELD_BATTLESTATE);
            battleorder = value(inner, FIELD_BATTLEORDER);
        }
        return new BattleUnit(unit.gid, unit.speciesId, unit.confId, unit.level, unit.height, unit.weight, unit.voice, unit.nature, unit.variant, unit.bloodLine, unit.gender, battlestate, battleorder);
    }

    private static long[] fingerprint(List<ProtoScan.Field> fields) {
        ProtoScan.Field level = ProtoScan.first(fields, FIELD_LEVEL, ProtoScan.WIRE_VARINT);
        ProtoScan.Field height = ProtoScan.first(fields, FIELD_HEIGHT, ProtoScan.WIRE_VARINT);
        ProtoScan.Field weight = ProtoScan.first(fields, FIELD_WEIGHT, ProtoScan.WIRE_VARINT);
        ProtoScan.Field voice = ProtoScan.first(fields, FIELD_VOICE, ProtoScan.WIRE_VARINT);
        ProtoScan.Field species = ProtoScan.first(fields, FIELD_PET_SPECIES, ProtoScan.WIRE_VARINT);
        ProtoScan.Field other = ProtoScan.first(fields, FIELD_SPECIES, ProtoScan.WIRE_VARINT);
        if (level == null || height == null || weight == null || voice == null)
            return null;
        long sp = 0L;
        if (species != null)
            sp = Math.max(sp, species.varint);
        if (other != null)
            sp = Math.max(sp, other.varint);
        if (sp <= MIN_SPECIES)
            return null;
        if (height.varint < MIN_HEIGHT_CM || height.varint > MAX_HEIGHT_CM)
            return null;
        if (weight.varint < MIN_WEIGHT_G || weight.varint > MAX_WEIGHT_G)
            return null;
        return new long[]{level.varint, height.varint, weight.varint, voice.varint};
    }
}
