package com.rocom.nearby.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BattleTracker {
    
    static final long STALE_MS = 120000L;
    private static final int[] UNIT_OPCODES = {4886, 4890};
    private static final int[] END_OPCODES = {4908, 4922};
    private static final int[] ACTIVITY_OPCODES = {579, 4900, 4898, 4876, 4870, 4884};
    private List<BattleUnit> enemies = Collections.emptyList();
    private int onField;
    private final Set<Long> enemyTeams = new LinkedHashSet<>();
    private long updatedAt;
    private List<BattleUnit> lastEnemies = Collections.emptyList();
    private int lastOnField;
    private BattleParser.Result result;
    
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
    }

    static boolean handles(int opcode) {
        return contains(UNIT_OPCODES, opcode)
                || contains(END_OPCODES, opcode)
                || contains(ACTIVITY_OPCODES, opcode);
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
            if (!enemyTeams.isEmpty()) {
                return false;
            }
            List<BattleUnit> units = BattleParser.parse(plain);
            if (units.isEmpty()) {
                return false;
            }
            return acceptSingle(units.get(units.size() - 1), now);
        }
        if (contains(END_OPCODES, opcode)) {
            if (opcode == 4908) {
                result = BattleParser.parseResult(plain);
            }
            return end();
        }
        if (contains(ACTIVITY_OPCODES, opcode)) {
            touch(now);
        }
        return false;
    }

    void expireIfStale(long now) {
        if (!enemies.isEmpty() && now - updatedAt > STALE_MS) {
            reset();
        }
    }
    
    List<BattleUnit> enemies() {
        return enemies;
    }

    static final int MAX_ON_FIELD = 2;

    int onField() {
        if (onField > 0) {
            return Math.min(onField, enemies.size());
        }
        return Math.min(enemies.size(), MAX_ON_FIELD);
    }

    private boolean accept(BattleParser.EnemyGroup group, long now) {
        List<BattleUnit> next = group.units;
        if (group.onField > 0) {
            onField = group.onField;
        }
        boolean changed = !keys(enemies).equals(keys(next));
        enemies = next;
        lastEnemies = next;
        updatedAt = now;
        return changed;
    }

    private boolean acceptSingle(BattleUnit unit, long now) {
        boolean changed = enemies.size() != 1 || !enemies.get(0).key().equals(unit.key());
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
        for (int i = 0; i < units.size(); i++) {
            out.add(units.get(i).key());
        }
        return out;
    }

    private boolean end() {
        if (enemies.isEmpty()) {
            return false;
        }
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
        if (enemies.isEmpty()) {
            return;
        }
        updatedAt = now;
    }

    private static boolean contains(int[] table, int value) {
        for (int i = 0; i < table.length; i++) {
            if (table[i] == value) {
                return true;
            }
        }
        return false;
    }
}
