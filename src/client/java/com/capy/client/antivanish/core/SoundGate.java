package com.capy.client.antivanish.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** False-positive gates for ghost sounds and particles. Pure rules; the mod computes the booleans from the world. */
public final class SoundGate {
    private SoundGate() {}

    public record Context(boolean nearMe, boolean visibleCause, boolean explosionRelated, boolean poweredMechanism, long msSinceMyAction,
                          boolean selfPlaced, boolean villagerDoor, boolean myContainerBusy, boolean containerSound) {}

    public static boolean path(String id, String... needles) {
        String p = idPath(id);
        for (String n : needles) if (p.contains(n)) return true;
        return false;
    }

    public static String idPath(String id) {
        if (id == null) return "unknown";
        int c = id.indexOf(':');
        return (c >= 0 && c + 1 < id.length() ? id.substring(c + 1) : id).toLowerCase(Locale.ROOT);
    }

    /** Sounds a hidden person makes: storage, doors, gates, buttons, levers. */
    public static boolean suspiciousSound(String id) {
        return path(id, "chest.open", "chest.close", "chest.locked", "barrel.open", "barrel.close", "shulker_box.open", "shulker_box.close",
            "door.open", "door.close", "fence_gate.open", "fence_gate.close", "button.click", "lever.click");
    }

    public static boolean suspiciousParticle(String id) {
        String p = idPath(id);
        return p.contains("crit") || p.contains("enchanted_hit") || p.contains("damage_indicator") || p.contains("smoke") || p.equals("block");
    }

    public static boolean containerSound(String id) { return path(id, "chest", "barrel", "shulker"); }

    public static boolean soundTriggers(Context c) {
        return c.nearMe() && !c.visibleCause() && !c.explosionRelated() && !c.poweredMechanism() && c.msSinceMyAction() >= 1000
            && !c.selfPlaced() && !c.villagerDoor() && !(c.myContainerBusy() && c.containerSound());
    }

    /** Smoke and block-crack particles are weak: they need two within 2 s, and are ignored right next to me or near campfires, furnaces, torches, fire. */
    public static final class ParticleBurst {
        private final Map<String, ArrayDeque<Long>> bursts = new HashMap<>();

        public boolean ready(String particle, long now) {
            ArrayDeque<Long> q = bursts.computeIfAbsent(particle, k -> new ArrayDeque<>());
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > 2000) q.pollFirst();
            if (q.size() < 2) return false;
            q.clear();
            return true;
        }
    }

    public static boolean particleTriggers(String particle, boolean nearMe, boolean visibleCause, boolean explosionRelated, long msSinceMyAction,
                                           boolean ambientSource, boolean withinTwoAndAHalfBlocksOfMe, ParticleBurst burst, long now) {
        if (!suspiciousParticle(particle) || !nearMe || visibleCause || explosionRelated || msSinceMyAction < 900) return false;
        String p = idPath(particle);
        boolean weak = p.equals("block") || p.contains("smoke");
        if (p.contains("smoke") && ambientSource) return false;
        if (weak && withinTwoAndAHalfBlocksOfMe) return false;
        return !weak || burst.ready(p, now);
    }
}
