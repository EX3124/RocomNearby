package com.rocom.nearby.net;

import com.rocom.nearby.entity.NearbyListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BattleTracker {

    static final long STALE_MS = 120000L;
    static final int MAX_ON_FIELD = 2;
    private static final int[] UNIT_OPCODES = {4886, 4890};
    private static final int[] END_OPCODES = {4908, 4922};
    private static final int[] ACTIVITY_OPCODES = {579, 4900, 4898, 4876, 4870, 4884};
    private static final int RANK_FIELD = 0;
    private static final int RANK_PREPARE = 1;
    private static final int RANK_BENCH = 2;
    private static final int RANK_REVERTED = 3;
    private List<BattleUnit> enemies = Collections.emptyList();
    private int onField;
    private final Set<Long> enemyTeams = new LinkedHashSet<>();
    private long updatedAt;
    private List<BattleUnit> lastEnemies = Collections.emptyList();
    private int lastOnField;
    private BattleParser.Result result;
    private final Set<String> foughtKeys = new HashSet<>();
    private final Map<String, Long> leftAt = new HashMap<>();
    private long leftCount;

    boolean reverted(BattleUnit unit) {
        return unit != null && leftAt.containsKey(unit.key());
    }

    void reset() {
        clearFrame();
        lastEnemies = Collections.emptyList();
        result = null;
    }

    private void clearFrame() {
        enemies = Collections.emptyList();
        onField = 0;
        enemyTeams.clear();
        updatedAt = 0L;
        foughtKeys.clear();
        leftAt.clear();
        leftCount = 0L;
    }

    static boolean handles(int opcode) {
        return contains(UNIT_OPCODES, opcode) || contains(END_OPCODES, opcode) || contains(ACTIVITY_OPCODES, opcode);
    }

    static boolean carriesUnits(int opcode) {
        return contains(UNIT_OPCODES, opcode);
    }

    boolean consume(int opcode, byte[] plain, long now) {
        if (contains(UNIT_OPCODES, opcode)) {
            BattleParser.EnemyGroup group = BattleParser.parseEnemyGroup(plain, enemyTeams);
            if (group != null) {
                if (!group.teams.isEmpty()) {
                    enemyTeams.clear();
                    enemyTeams.addAll(group.teams);
                }
                return accept(group, now);
            }
            if (!enemyTeams.isEmpty())
                return false;
            List<BattleUnit> units = BattleParser.parse(plain);
            if (units.isEmpty())
                return false;
            return acceptSingle(units.get(units.size() - 1), now);
        }
        if (contains(END_OPCODES, opcode)) {
            if (opcode == 4908)
                result = BattleParser.parseResult(plain);
            return end();
        }
        if (contains(ACTIVITY_OPCODES, opcode))
            touch(now);
        return false;
    }

    void expireIfStale(long now) {
        if (!enemies.isEmpty() && now - updatedAt > STALE_MS)
            reset();
    }

    List<BattleUnit> enemies() {
        return enemies;
    }

    int onField() {
        if (onField > 0)
            return Math.min(onField, enemies.size());
        return Math.min(enemies.size(), MAX_ON_FIELD);
    }

    private boolean accept(BattleParser.EnemyGroup group, long now) {
        List<BattleUnit> next = order(group.units);
        if (group.onField > 0)
            onField = group.onField;
        boolean changed = !keys(enemies).equals(keys(next)) || !signatures(enemies).equals(signatures(next));
        enemies = next;
        lastEnemies = next;
        updatedAt = now;
        return changed;
    }

    private boolean acceptSingle(BattleUnit unit, long now) {
        boolean changed = enemies.size() != 1 || !enemies.get(0).key().equals(unit.key()) || enemies.get(0).bloodLine != unit.bloodLine || enemies.get(0).variant != unit.variant || enemies.get(0).gender != unit.gender || enemies.get(0).battleState != unit.battleState || enemies.get(0).battleOrder != unit.battleOrder;
        List<BattleUnit> next = new ArrayList<>(1);
        next.add(unit);
        enemies = next;
        lastEnemies = next;
        onField = 1;
        updatedAt = now;
        return changed;
    }

    private static List<String> keys(List<BattleUnit> units) {
        List<String> out = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++)
            out.add(units.get(i).key());
        return out;
    }

    private static List<String> signatures(List<BattleUnit> units) {
        List<String> out = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            BattleUnit unit = units.get(i);
            out.add(unit.bloodLine + ":" + unit.variant + ":" + unit.gender + ":" + unit.battleState + ":" + unit.battleOrder);
        }
        return out;
    }

    private List<BattleUnit> order(List<BattleUnit> units) {
        trackSlots(units);
        if (units.size() < 2 || !isMultiBattle(units))
            return units;
        List<BattleUnit> sorted = new ArrayList<>(units);
        Collections.sort(sorted, battleOrder());
        return sorted;
    }

    private static boolean isMultiBattle(List<BattleUnit> units) {
        for (int i = 0; i < units.size(); i++) {
            if (units.get(i).battleOrder != 0L)
                return true;
        }
        return false;
    }

    private static boolean isPreparation(List<BattleUnit> units) {
        for (int i = 0; i < units.size(); i++) {
            if (units.get(i).battleState == NearbyListView.BATTLE_PREPARATION_MULTI)
                return true;
        }
        return false;
    }

    private static boolean isOnField(long state) {
        return state == NearbyListView.BATTLE_FIGHTING_MULTI || state == NearbyListView.BATTLE_FIGHTING || state == NearbyListView.BATTLE_FIGHTING_STORY || state == NearbyListView.BATTLE_FIGHTING_TIMED || state == NearbyListView.BATTLE_FIGHTING_BOSS || state == NearbyListView.BATTLE_DIZZINESS;
    }

    private static boolean isSubstitute(long state) {
        return state == NearbyListView.BATTLE_SUBSTITUTE || state == NearbyListView.BATTLE_SUBSTITUTE_STORY;
    }

    private void trackSlots(List<BattleUnit> units) {
        boolean anyOnField = false;
        for (int i = 0; i < units.size(); i++) {
            if (isOnField(units.get(i).battleState)) {
                anyOnField = true;
                break;
            }
        }
        if (!anyOnField && isPreparation(units)) {
            foughtKeys.clear();
            leftAt.clear();
            leftCount = 0L;
        }
        for (int i = 0; i < units.size(); i++) {
            BattleUnit unit = units.get(i);
            if (unit.battleOrder == 0L)
                continue;
            String key = unit.key();
            if (isOnField(unit.battleState)) {
                foughtKeys.add(key);
                leftAt.remove(key);
            } else if (isSubstitute(unit.battleState) && foughtKeys.contains(key) && !leftAt.containsKey(key))
                leftAt.put(key, ++leftCount);
        }
    }

    private Comparator<BattleUnit> battleOrder() {
        return (left, right) -> {
            int rankLeft = rank(left);
            int rankRight = rank(right);
            int byRank = Integer.compare(rankLeft, rankRight);
            if (byRank != 0)
                return byRank;
            if (rankLeft == RANK_REVERTED) {
                int byStamp = Long.compare(stampOf(left), stampOf(right));
                if (byStamp != 0)
                    return byStamp;
            }
            int bySlot = Integer.compare((int) (left.battleOrder % 10L), (int) (right.battleOrder % 10L));
            if (bySlot != 0)
                return bySlot;
            return Long.compare(left.battleOrder / 10L, right.battleOrder / 10L);
        };
    }

    private int rank(BattleUnit unit) {
        long state = unit.battleState;
        if (isOnField(state))
            return RANK_FIELD;
        if (isSubstitute(state))
            return leftAt.containsKey(unit.key()) ? RANK_REVERTED : RANK_BENCH;
        return RANK_PREPARE;
    }

    private long stampOf(BattleUnit unit) {
        Long stamp = leftAt.get(unit.key());
        return stamp == null ? 0L : stamp;
    }

    private boolean end() {
        if (enemies.isEmpty())
            return false;
        lastEnemies = enemies;
        lastOnField = onField();
        clearFrame();
        return true;
    }

    int lastOnField() {
        return lastOnField;
    }

    List<BattleUnit> lastEnemies() {
        return lastEnemies;
    }

    BattleParser.Result result() {
        return result;
    }

    void clearResult() {
        result = null;
        lastEnemies = Collections.emptyList();
    }

    private void touch(long now) {
        if (enemies.isEmpty())
            return;
        updatedAt = now;
    }

    private static boolean contains(int[] table, int value) {
        for (int i = 0; i < table.length; i++) {
            if (table[i] == value)
                return true;
        }
        return false;
    }
}
