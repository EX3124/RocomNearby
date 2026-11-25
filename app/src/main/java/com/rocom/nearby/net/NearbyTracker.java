package com.rocom.nearby.net;

import com.rocom.nearby.entity.Badge;
import com.rocom.nearby.entity.NearbyEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NearbyTracker {

    static final int MAX_RECORDS = 512;
    static final int MAX_CARDS = 256;
    static final double BIND_RADIUS_CM = 1200;
    static final double SELF_TOKEN_RADIUS_CM = 80;
    static final int SELF_TOKEN_VOTES = 4;
    static final double MAX_DISTANCE_M = 120;
    static final long MAX_AGE_MS = 90000L;
    private static final long BLOODLINE_NIGHTMARE = 23L;
    private static final long BLOODLINE_STRANGE = 24L;
    private static final long VARIANT_COLOUR = 1L;
    private static final long VARIANT_WILD_BLIGHT = 4L;
    private static final long VARIANT_DRAGON_BLIGHT = 32L;
    static final long CAUGHT_SUPPRESS_MS = 600000L;
    static final long CAUGHT_SWEEP_MS = 60000L;
    private static final int MIN_CFG_ID = EntityParser.MIN_CFG_ID;
    private static final int MAX_CFG_ID = EntityParser.MAX_CFG_ID;

    private static final class Entry {

        final String key;
        final EntityRecord record;
        long seenAt;
        boolean left;
        long movedAt;

        Entry(String key, EntityRecord record, long seenAt) {
            this.key = key;
            this.record = record;
            this.seenAt = seenAt;
        }
    }

    private final BattleTracker battle = new BattleTracker();
    private final LinkedHashMap<String, Entry> byId = new LinkedHashMap<>();
    private final HashMap<String, String> tokenToKey = new HashMap<>();
    private final HashMap<String, Integer> selfVotes = new HashMap<>();
    private String selfToken;
    private double selfX = Double.NaN;
    private double selfY = Double.NaN;
    private long battleEntityToken;
    private String battleEntryKey;
    private final Set<Long> goneTokens = new HashSet<>();
    private final Map<String, Long> caughtSuppress = new HashMap<>();
    private long caughtSuppressSweepAt;
    private final List<String> fightingKeys = new ArrayList<>();
    private long releasedToken;

    void reset() {
        byId.clear();
        tokenToKey.clear();
        selfVotes.clear();
        selfToken = null;
        battle.reset();
        battleEntityToken = 0L;
        battleEntryKey = null;
        goneTokens.clear();
        fightingKeys.clear();
        releasedToken = 0L;
        caughtSuppress.clear();
        caughtSuppressSweepAt = 0L;
    }

    void setSelfPosition(double worldX, double worldY) {
        if (Double.isFinite(worldX) && Double.isFinite(worldY)) {
            selfX = worldX;
            selfY = worldY;
        }
    }

    boolean consume(int opcode, byte[] plain, long now) {
        if (plain == null || plain.length == 0)
            return false;
        if (opcode == 330) {
            boolean changed = false;
            List<EntityRecord> records = EntityParser.parseSpawn(plain);
            for (int i = 0; i < records.size(); i++) {
                EntityRecord record = records.get(i);
                if (record.token != 0 && goneTokens.contains(record.token))
                    continue;
                changed |= upsert(record, now);
            }
            return changed;
        }
        if (opcode == 1043 || opcode == 1044) {
            boolean changed = false;
            List<EntityRecord> records = EntityParser.parseBatch(plain);
            for (int i = 0; i < records.size(); i++) {
                EntityRecord record = records.get(i);
                if (record.token != 0 && goneTokens.contains(record.token))
                    continue;
                changed |= upsert(record, now);
            }
            List<Long> gone = EntityParser.parseDeparted(plain);
            for (int i = 0; i < gone.size(); i++)
                changed |= markGone(gone.get(i), now);
            List<EntityParser.Move> moves = EntityParser.parseMoves(plain);
            for (int i = 0; i < moves.size(); i++)
                changed |= applyMove(moves.get(i), now);
            return changed;
        }
        return false;
    }

    List<NearbyEntity> snapshot(long now) {
        battle.expireIfStale(now);
        List<BattleUnit> enemies = battle.enemies();
        BattleUnit enemy = enemies.isEmpty() ? null : enemies.get(0);
        if (enemy == null && (battleEntityToken != 0L || battleEntryKey != null || battle.result() != null))
            settleBattle(now);
        Entry fighting = enemy == null ? null : findBattleEntry(enemy);
        List<Entry> fightingEntries = new ArrayList<>();
        if (enemy != null) {
            fightingKeys.clear();
            fightingKeys.add(fighting == null ? null : fighting.key);
        }
        if (fighting != null)
            fightingEntries.add(fighting);
        boolean hasSelf = Double.isFinite(selfX) && Double.isFinite(selfY);
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : byId.values()) {
            if (entry.left || fightingEntries.contains(entry))
                continue;
            if (hasSelf) {
                if (distanceMeters(entry.record) > MAX_DISTANCE_M)
                    continue;
            } else if (now - entry.seenAt > MAX_AGE_MS)
                continue;
            visible.add(entry);
        }
        Collections.sort(visible, order(hasSelf));
        List<NearbyEntity> out = new ArrayList<>(Math.min(visible.size(), MAX_CARDS));
        for (int i = 0; i < visible.size() && i < MAX_CARDS; i++)
            out.add(toEntity(visible.get(i).record));
        if (enemy != null) {
            List<NearbyEntity> cards = new ArrayList<>(enemies.size());
            for (int i = 0; i < enemies.size(); i++) {
                BattleUnit unit = enemies.get(i);
                cards.add(toBattleEntity(unit));
            }
            out.addAll(0, cards);
            while (out.size() > MAX_CARDS)
                out.remove(out.size() - 1);
        }
        return out;
    }

    private void settleBattle(long now) {
        BattleParser.Result result = battle.result();
        List<BattleUnit> units = battle.lastEnemies();
        boolean hasResult = result != null && result.hasResult;
        int onField = battle.lastOnField();
        for (int i = 0; i < units.size(); i++) {
            BattleUnit unit = units.get(i);
            Entry entry = entryOf(i, unit);
            boolean captured = result != null && result.capturedMatches(unit);
            boolean killed = !captured && hasResult && i < onField;
            if (captured || killed)
                drop(entry, now, captured);
            else if (entry != null && entry.left) {
                entry.left = false;
                entry.seenAt = now;
            }
        }
        releasedToken = hasResult ? 0L : battleEntityToken;
        battle.clearResult();
        battleEntityToken = 0L;
        battleEntryKey = null;
        fightingKeys.clear();
    }

    private Entry entryOf(int index, BattleUnit unit) {
        String key = index < fightingKeys.size() ? fightingKeys.get(index) : null;
        if (key != null) {
            Entry entry = byId.get(key);
            if (entry != null)
                return entry;
        }
        return index == 0 ? findBattleEntry(unit) : null;
    }

    private void drop(Entry entry, long now, boolean ban) {
        if (entry == null) return;
        if (!entry.left) {
            entry.left = true;
            entry.seenAt = now;
            dropTokens(entry.key);
        }
        if (ban && entry.record.token != 0)
            goneTokens.add(entry.record.token);
    }

    int consumeCaught(List<CaughtParser.Caught> caught, long now) {
        if (caught == null || caught.isEmpty())
            return 0;
        int removed = 0;
        for (int i = 0; i < caught.size(); i++)
            removed += removeCaught(caught.get(i), now);
        return removed;
    }

    private int removeCaught(CaughtParser.Caught caught, long now) {
        int removed = 0;
        for (Iterator<Map.Entry<String, Entry>> iterator = byId.entrySet().iterator(); iterator.hasNext();) {
            Entry entry = iterator.next().getValue();
            if (!sameSprite(entry.record, caught))
                continue;
            iterator.remove();
            dropTokens(entry.key);
            removed++;
        }
        if (removed == 0)
            return 0;
        caughtSuppress.put(caughtSignature(caught.baseConfId, caught.height, caught.weight), now + CAUGHT_SUPPRESS_MS);
        long sweepAt = now + CAUGHT_SWEEP_MS;
        caughtSuppressSweepAt = caughtSuppressSweepAt == 0L ? sweepAt : Math.min(caughtSuppressSweepAt, sweepAt);
        return removed;
    }

    private static boolean sameSprite(EntityRecord record, CaughtParser.Caught caught) {
        if (record.height != caught.height || record.weight != caught.weight)
            return false;
        long species = SpeciesSizes.speciesForForm(record.cfgId);
        if (species <= 0L || species != caught.baseConfId)
            return false;
        return !(caught.hasVoice && record.hasVoice() && record.voice != caught.voice);
    }

    private static String caughtSignature(long species, long height, long weight) {
        return species + ":" + height + ":" + weight;
    }

    private static String caughtSignature(EntityRecord record) {
        long species = SpeciesSizes.speciesForForm(record.cfgId);
        if (species <= 0L)
            return null;
        return caughtSignature(species, record.height, record.weight);
    }

    private boolean isSuppressed(EntityRecord record, long now) {
        if (caughtSuppress.isEmpty())
            return false;
        if (caughtSuppressSweepAt != 0L && now > caughtSuppressSweepAt) {
            for (Iterator<Map.Entry<String, Long>> iterator = caughtSuppress.entrySet().iterator(); iterator.hasNext();) {
                if (iterator.next().getValue() <= now)
                    iterator.remove();
            }
            caughtSuppressSweepAt = now + CAUGHT_SWEEP_MS;
        }
        String signature = caughtSignature(record);
        if (signature == null)
            return false;
        Long until = caughtSuppress.get(signature);
        if (until == null)
            return false;
        if (until > now)
            return true;
        caughtSuppress.remove(signature);
        return false;
    }

    boolean consumeBattle(int opcode, byte[] plain, long now) {
        boolean changed = battle.consume(opcode, plain, now);
        if (BattleTracker.carriesUnits(opcode)) {
            long token = BattleParser.parseSnapshotToken(plain);
            if (token != 0L && token != battleEntityToken) {
                battleEntityToken = token;
                battleEntryKey = null;
                releasedToken = 0L;
                changed = true;
            }
        }
        return changed;
    }

    private NearbyEntity toBattleEntity(BattleUnit unit) {
        String name = null;
        if (unit.speciesId > 0 && unit.speciesId <= Integer.MAX_VALUE)
            name = SpeciesNames.speciesLookup(unit.speciesId);
        if (name == null)
            name = SpeciesNames.speciesName(unit.speciesId);
        return new NearbyEntity(name, (int) unit.level, battleBadges(unit), unit.heightMeters(), unit.weightKg(), unit.voice, SpeciesSizes.heightPercentileForSpecies(unit.speciesId, unit.height), SpeciesSizes.weightPercentileForSpecies(unit.speciesId, unit.weight), Double.NaN, Double.NaN, true, unit.gender, unit.battlestate);
    }

    private static void addNatureBadge(List<Badge> out, long nature) {
        addBadge(out, Badge.nature(nature));
    }

    private List<Badge> battleBadges(BattleUnit unit) {
        List<Badge> out = new ArrayList<>(6);
        addNatureBadge(out, unit.nature);
        if (unit.bloodLine == BLOODLINE_NIGHTMARE && isBlightVariant(unit.variant))
            addBadge(out, Badge.of(Badge.Kind.POLLUTION));
        if (unit.bloodLine == BLOODLINE_STRANGE)
            addBadge(out, Badge.of(Badge.Kind.STRANGE));
        if (unit.variant == VARIANT_COLOUR)
            addBadge(out, Badge.of(Badge.Kind.COLORFUL));
        addBloodlineBadge(out, unit.bloodLine);
        addSizeBadge(out, SpeciesSizes.weightPercentileForSpecies(unit.speciesId, unit.weight));
        addVoiceBadge(out, unit.voice);
        return out;
    }

    private static boolean isBlightVariant(long variant) {
        return variant == VARIANT_WILD_BLIGHT || variant == VARIANT_DRAGON_BLIGHT;
    }

    private static void addBloodlineBadge(List<Badge> out, long bloodline) {
        addBadge(out, Badge.bloodline(bloodline));
    }

    private static void addBadge(List<Badge> out, Badge badge) {
        if (badge != null && !out.contains(badge))
            out.add(badge);
    }

    private Entry findBattleEntry(BattleUnit unit) {
        Entry bound = battleEntryKey == null ? null : byId.get(battleEntryKey);
        if (bound != null)
            return bound;
        battleEntryKey = null;
        Entry resolved = resolveBattleEntry(unit);
        battleEntryKey = resolved == null ? null : resolved.key;
        return resolved;
    }

    private Entry resolveBattleEntry(BattleUnit unit) {
        if (battleEntityToken != 0) {
            String boundKey = tokenToKey.get(Long.toHexString(battleEntityToken));
            if (boundKey != null) {
                Entry bound = byId.get(boundKey);
                if (bound != null)
                    return bound;
            }
            for (Entry candidate : byId.values()) {
                if (candidate.record.token == battleEntityToken)
                    return candidate;
            }
        }
        return null;
    }

    private Comparator<Entry> order(final boolean hasSelf) {
        return (left, right) -> {
            int leftKinds = badges(left.record).size();
            int rightKinds = badges(right.record).size();
            if (leftKinds != rightKinds)
                return rightKinds - leftKinds;
            if (hasSelf) {
                int byDistance = Double.compare(distanceMeters(left.record), distanceMeters(right.record));
                if (byDistance != 0)
                    return byDistance;
            }
            return Long.compare(left.record.actorId, right.record.actorId);
        };
    }

    private double distanceMeters(EntityRecord record) {
        return Math.hypot(record.x - selfX, record.y - selfY) / 100.0;
    }

    private NearbyEntity toEntity(EntityRecord record) {
        String name = SpeciesNames.formName(record.cfgId);
        return new NearbyEntity(name, (int) record.level, badges(record), record.heightMeters(), record.weightKg(), record.hasVoice() ? record.voice : NearbyEntity.UNKNOWN, SpeciesSizes.heightPercentileForForm(record.cfgId, record.height), SpeciesSizes.weightPercentileForForm(record.cfgId, record.weight), record.x, record.y, false, 0L, 0L);
    }

    static List<Badge> badges(EntityRecord record) {
        List<Badge> out = mutationBadges(record.mutation, record.glassType);
        addSizeBadge(out, SpeciesSizes.weightPercentileForForm(record.cfgId, record.weight));
        addVoiceBadge(out, record.hasVoice() ? record.voice : 0L);
        return out;
    }

    private static List<Badge> mutationBadges(long mutation, long glassType) {
        List<Badge> out = new ArrayList<>(3);
        if (glassType != 0 || (mutation & 8L) != 0)
            out.add(Badge.of(Badge.Kind.COLORFUL));
        if ((mutation & 1L) != 0)
            out.add(Badge.of(Badge.Kind.SHINY));
        if ((mutation & 166L) != 0)
            out.add(Badge.of(Badge.Kind.POLLUTION));
        return out;
    }

    private static void addVoiceBadge(List<Badge> out, long voice) {
        if (voice >= NearbyEntity.VOICE_HIGH)
            out.add(Badge.of(Badge.Kind.HIGH));
        else if (voice <= NearbyEntity.VOICE_LOW)
            out.add(Badge.of(Badge.Kind.LOW));
    }

    static void addSizeBadge(List<Badge> out, double percentile) {
        if (!Double.isFinite(percentile)) return;
        if (percentile >= SpeciesSizes.BIG_PERCENTILE)
            out.add(Badge.of(Badge.Kind.BIG));
        else if (percentile <= SpeciesSizes.SMALL_PERCENTILE)
            out.add(Badge.of(Badge.Kind.SMALL));
    }

    private boolean upsert(EntityRecord record, long now) {
        if (!isTrackable(record))
            return false;
        if (isSuppressed(record, now))
            return false;
        String key = keyOf(record);
        if (key == null)
            return false;
        if (record.token != 0) {
            tokenToKey.put(Long.toHexString(record.token), key);
            if (record.token == releasedToken)
                releasedToken = 0L;
        }
        Entry existing = byId.get(key);
        if (existing == null) {
            if (byId.size() >= MAX_RECORDS)
                makeRoom();
            if (byId.size() >= MAX_RECORDS)
                return false;
            byId.put(key, new Entry(key, record, now));
            return true;
        }
        if (existing.record.cfgId != record.cfgId || existing.record.height != record.height || existing.record.weight != record.weight) {
            String variantKey = key + "#" + record.height + ":" + record.weight;
            Entry variant = byId.get(variantKey);
            if (variant != null) {
                setPosition(variant, record.x, record.y, record.z, now);
                return false;
            }
            if (byId.size() >= MAX_RECORDS)
                makeRoom();
            if (byId.size() >= MAX_RECORDS)
                return false;
            byId.put(variantKey, new Entry(variantKey, record, now));
            return true;
        }
        if (existing.movedAt != 0) {
            boolean wasLeft = existing.left;
            existing.seenAt = now;
            existing.left = false;
            return wasLeft;
        }
        boolean wasLeft = existing.left;
        boolean moved = setPosition(existing, record.x, record.y, record.z, now);
        return moved || wasLeft;
    }

    private static boolean isTrackable(EntityRecord record) {
        if (record.height <= 0 || record.weight <= 0)
            return false;
        if (record.cfgId < MIN_CFG_ID || record.cfgId > MAX_CFG_ID)
            return false;
        if (record.uniqueId == 0)
            return false;
        return ProtoScan.isWorldCoordinate(record.x) && ProtoScan.isWorldCoordinate(record.y);
    }

    private static String keyOf(EntityRecord record) {
        if (record.uniqueId != 0)
            return "u" + record.uniqueId;
        if (record.refreshId != 0)
            return "r" + record.cfgId + ":" + record.refreshId;
        return "c" + record.cfgId + ":" + Math.round(record.x / 1000.0) + ":" + Math.round(record.y / 1000.0);
    }

    private boolean markGone(long id, long now) {
        if (id == releasedToken)
            return false;
        Entry entry = byId.get("u" + id);
        if (entry == null) {
            for (Entry candidate : byId.values()) {
                if (candidate.record.uniqueId == id || candidate.record.actorId == id || candidate.record.token == id) {
                    entry = candidate;
                    break;
                }
            }
        }
        if (entry == null || entry.left)
            return false;
        entry.left = true;
        entry.seenAt = now;
        dropTokens(entry.key);
        goneTokens.add(entry.record.token != 0 ? entry.record.token : id);
        return true;
    }

    private boolean applyMove(EntityParser.Move move, long now) {
        if (move.tokenHex.equals(selfToken))
            return false;
        String boundKey = tokenToKey.get(move.tokenHex);
        if (boundKey != null) {
            Entry bound = byId.get(boundKey);
            if (bound != null)
                return setPosition(bound, move.x, move.y, move.z, now);
            tokenToKey.remove(move.tokenHex);
        }
        if (voteSelfToken(move))
            return false;
        Entry nearest = nearestUnbound(move.x, move.y);
        if (nearest == null)
            return false;
        tokenToKey.put(move.tokenHex, nearest.key);
        return setPosition(nearest, move.x, move.y, move.z, now);
    }

    private boolean voteSelfToken(EntityParser.Move move) {
        if (selfToken != null)
            return false;
        if (!Double.isFinite(selfX) || !Double.isFinite(selfY))
            return false;
        double distance = Math.hypot(move.x - selfX, move.y - selfY);
        if (distance > SELF_TOKEN_RADIUS_CM) {
            selfVotes.remove(move.tokenHex);
            return false;
        }
        Integer seen = selfVotes.get(move.tokenHex);
        int votes = (seen == null ? 0 : seen) + 1;
        selfVotes.put(move.tokenHex, votes);
        if (votes >= SELF_TOKEN_VOTES)
            selfToken = move.tokenHex;
        return true;
    }

    private Entry nearestUnbound(double x, double y) {
        Set<String> bound = new HashSet<>(tokenToKey.values());
        Entry best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entry entry : byId.values()) {
            if (entry.left || bound.contains(entry.key))
                continue;
            double distance = Math.hypot(entry.record.x - x, entry.record.y - y);
            if (distance > BIND_RADIUS_CM || distance >= bestDistance)
                continue;
            bestDistance = distance;
            best = entry;
        }
        return best;
    }

    private boolean setPosition(Entry entry, double x, double y, double z, long now) {
        boolean changed = entry.record.x != x || entry.record.y != y;
        entry.record.x = x;
        entry.record.y = y;
        entry.record.z = z;
        entry.seenAt = now;
        entry.left = false;
        if (changed)
            entry.movedAt = now;
        return changed;
    }

    private void makeRoom() {
        String oldestLeft = null;
        long oldestLeftSeen = Long.MAX_VALUE;
        String oldest = null;
        long oldestSeen = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> candidate : byId.entrySet()) {
            Entry entry = candidate.getValue();
            if (entry.left && entry.seenAt < oldestLeftSeen) {
                oldestLeftSeen = entry.seenAt;
                oldestLeft = candidate.getKey();
            }
            if (entry.seenAt < oldestSeen) {
                oldestSeen = entry.seenAt;
                oldest = candidate.getKey();
            }
        }
        String victim = oldestLeft != null ? oldestLeft : oldest;
        if (victim != null) {
            byId.remove(victim);
            dropTokens(victim);
        }
    }

    private void dropTokens(String key) {
        for (Iterator<Map.Entry<String, String>> iterator = tokenToKey.entrySet().iterator(); iterator.hasNext();) {
            if (key.equals(iterator.next().getValue()))
                iterator.remove();
        }
    }
}
