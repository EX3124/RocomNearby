package com.rocom.nearby.net;

final class BattleUnit {
    
    final long gid;
    final long speciesId;
    final long confId;
    final long level;
    final long gender;
    final long height;
    final long weight;
    final long voice;
    final long nature;

    BattleUnit(long gid, long speciesId, long confId, long level, long gender,
               long height, long weight, long voice, long nature) {
        this.gid = gid;
        this.speciesId = speciesId;
        this.confId = confId;
        this.level = level;
        this.gender = gender;
        this.height = height;
        this.weight = weight;
        this.voice = voice;
        this.nature = nature;
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
