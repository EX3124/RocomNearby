package com.rocom.nearby.net;

final class EntityRecord {
    
    static final long NO_VOICE = Long.MIN_VALUE;
    final long actorId;
    final long token;
    final long cfgId;
    final long refreshId;
    final long uniqueId;
    final long level;
    final long height;
    final long weight;
    final long mutation;
    final long voice;
    final long glassType;
    final long glassValue;
    double x;
    double y;
    double z;

    EntityRecord(long actorId, long token, long cfgId, long refreshId, long uniqueId,
                 long level, long height, long weight, long mutation, long voice,
                 long glassType, long glassValue, double x, double y, double z) {
        this.actorId = actorId;
        this.token = token;
        this.cfgId = cfgId;
        this.refreshId = refreshId;
        this.uniqueId = uniqueId;
        this.level = level;
        this.height = height;
        this.weight = weight;
        this.mutation = mutation;
        this.voice = voice;
        this.glassType = glassType;
        this.glassValue = glassValue;
        this.x = x;
        this.y = y;
        this.z = z;
    }
    
    double heightMeters() {
        return height / 100.0;
    }

    double weightKg() {
        return weight / 1000.0;
    }

    boolean hasVoice() {
        return voice != NO_VOICE;
    }
}
