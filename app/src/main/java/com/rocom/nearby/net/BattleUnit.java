package com.rocom.nearby.net;

final class BattleUnit {
    
    final long gid;
    final long speciesId;
    final long confId;
    final long level;
    final long height;
    final long weight;
    final long voice;
    final long nature;
    final long variant;
    final long bloodLine;
    final long gender;
    final long battlestate;
    final long battleorder;

    BattleUnit(long gid, long speciesId, long confId, long level,
               long height, long weight, long voice, long nature,
               long variant, long bloodLine, long gender, long battlestate, long battleorder) {
        this.gid = gid;
        this.speciesId = speciesId;
        this.confId = confId;
        this.level = level;
        this.height = height;
        this.weight = weight;
        this.voice = voice;
        this.nature = nature;
        this.variant = variant;
        this.bloodLine = bloodLine;
        this.gender = gender;
        this.battlestate = battlestate;
        this.battleorder = battleorder;
    }

    double heightMeters() {
        return height / 100.0;
    }

    double weightKg() {
        return weight / 1000.0;
    }

    String key() {
        return gid + ":" + speciesId + ":" + height + ":" + weight + ":" + voice;
    }
}
