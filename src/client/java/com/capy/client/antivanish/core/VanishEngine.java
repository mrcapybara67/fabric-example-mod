package com.capy.client.antivanish.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Signal fusion. Weak clues become a CRITICAL alert only when clues of at least two different types agree inside a 15 second
 * window, their strongest weights add up to 35 or more, and one clue has proof that the person is really near (a body or a region).
 * Clues that have a position but no person can still form a hidden-presence CLUSTER.
 */
public final class VanishEngine {
    public enum Type { VANISH, INVISIBLE, SOUND, PARTICLE }

    public record Proof(String key, int distance) {}
    public record Signal(Type type, String subject, Proof proof, String reason, int weight, long at) {}
    public record Detection(String name, String reason, int score, long expiresAt) {}
    public record Critical(String name, Proof proof, int score) {}
    public record Cluster(double x, double y, double z, int score, int signals) {}

    private record Located(Type type, double x, double y, double z, int weight, long at) {}

    public static final long WINDOW_MS = 15_000, TTL_MS = 20_000, CRITICAL_COOLDOWN_MS = 12_000, CLUSTER_COOLDOWN_MS = 20_000;
    public static final int CRITICAL_SCORE = 35;

    private final ArrayDeque<Signal> signals = new ArrayDeque<>();
    private final ArrayDeque<Located> located = new ArrayDeque<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<String, Detection> detections = new HashMap<>();
    private long lastCritical = Long.MIN_VALUE / 2, lastCluster = Long.MIN_VALUE / 2;
    private String lastCriticalSubject;

    /** Adds a clue unless the same type and subject fired within cooldownMs. Returns true when it was added. */
    public synchronized boolean add(Type type, String subject, Proof proof, String reason, int weight, long cooldownMs, long now) {
        String key = type.name() + "|" + subject.toLowerCase(Locale.ROOT);
        Long last = cooldowns.get(key);
        if (last != null && now - last < cooldownMs) return false;
        cooldowns.put(key, now);
        signals.addLast(new Signal(type, subject, proof, reason, weight, now));
        Detection old = detections.get(key);
        if (old == null || weight >= old.score()) detections.put(key, new Detection(subject, reason, weight, now + TTL_MS));
        else detections.put(key, new Detection(old.name(), old.reason(), old.score(), now + TTL_MS));
        return true;
    }

    /** A clue with a position but no person attached (a chest sound, a particle). */
    public synchronized void addLocated(Type type, double x, double y, double z, int weight, long now) {
        located.addLast(new Located(type, x, y, z, weight, now));
        while (located.size() > 64) located.pollFirst();
    }

    public synchronized Critical evaluate(long now) {
        return evaluate(now, critical -> true);
    }

    /**
     * Evaluates fusion and accepts only candidates the caller can actually
     * show. Filtering here, before the cooldown is consumed and before the
     * highest-scoring candidate is chosen, keeps a far or otherwise unusable
     * critical from hiding a nearby accepted one for the next twelve seconds.
     */
    public synchronized Critical evaluate(long now, Predicate<Critical> accept) {
        prune(now);
        // Fusion is per subject. Summing every watched player's clues into one
        // score could pair Steve's vanish with Alex's invisible body and then
        // alert on whichever proof happened to be last in the queue.
        Map<String, Map<Type, Integer>> weights = new HashMap<>();
        Map<String, Proof> proofs = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Signal signal : signals) {
            if (signal.subject() == null) continue;
            String key = signal.subject().toLowerCase(Locale.ROOT);
            names.putIfAbsent(key, signal.subject());
            weights.computeIfAbsent(key, ignored -> new HashMap<>())
                    .merge(signal.type(), signal.weight(), Math::max);
            if (signal.proof() != null) proofs.put(key, signal.proof());
        }
        Critical best = null;
        for (Map.Entry<String, Map<Type, Integer>> entry : weights.entrySet()) {
            if (entry.getValue().size() < 2 || proofs.get(entry.getKey()) == null) continue;
            int score = 0;
            for (int weight : entry.getValue().values()) score += weight;
            if (score < CRITICAL_SCORE) continue;
            Critical candidate = new Critical(names.get(entry.getKey()), proofs.get(entry.getKey()), score);
            if (accept != null && !accept.test(candidate)) continue;
            if (best == null || score > best.score()
                    || (score == best.score() && candidate.name().compareToIgnoreCase(best.name()) < 0)) {
                best = candidate;
            }
        }
        if (best != null && now - lastCritical >= CRITICAL_COOLDOWN_MS) {
            lastCritical = now;
            lastCriticalSubject = best.name();
            return best;
        }
        return null;
    }

    /** Hidden presence from clues with no person: two different types, or three of one type, within 8 blocks inside the window. */
    public synchronized Cluster evaluateCluster(long now) {
        prune(now);
        if (now - lastCluster < CLUSTER_COOLDOWN_MS) return null;
        for (Located a : located) {
            Set<Type> types = new HashSet<>();
            int n = 0, score = 0; double sx = 0, sy = 0, sz = 0;
            for (Located b : located) {
                double d = Math.sqrt((a.x() - b.x()) * (a.x() - b.x()) + (a.y() - b.y()) * (a.y() - b.y()) + (a.z() - b.z()) * (a.z() - b.z()));
                if (d <= 8.0) { types.add(b.type()); n++; score += b.weight(); sx += b.x(); sy += b.y(); sz += b.z(); }
            }
            if ((types.size() >= 2 && n >= 2) || n >= 3) { lastCluster = now; return new Cluster(sx / n, sy / n, sz / n, score, n); }
        }
        return null;
    }

    /** Top rows for a compact HUD: highest score first, then name; identical (name, reason) rows collapse. */
    public synchronized List<Detection> detections(long now, int limit) {
        detections.values().removeIf(d -> d.expiresAt() <= now);
        List<Detection> out = new ArrayList<>(detections.values());
        out.sort(Comparator.comparingInt(Detection::score).reversed().thenComparing(Detection::name, String.CASE_INSENSITIVE_ORDER));
        List<Detection> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Detection d : out) if (seen.add(d.name().toLowerCase(Locale.ROOT) + "\0" + d.reason())) unique.add(d);
        return unique.size() > limit ? new ArrayList<>(unique.subList(0, limit)) : unique;
    }

    /** A read-only copy of the clues still inside the window. */
    public synchronized List<Signal> signals(long now) {
        prune(now);
        return new ArrayList<>(signals);
    }

    /** A leave message proved the person left normally: drop their tab-related clues. */
    public synchronized void forgetVanish(String name) {
        signals.removeIf(s -> s.type() == Type.VANISH && s.subject().equalsIgnoreCase(name));
        detections.values().removeIf(d -> d.name().equalsIgnoreCase(name) && d.reason().toLowerCase(Locale.ROOT).startsWith("vanish event"));
    }

    /** A death proved the account did not vanish: drop every clue about it. */
    public synchronized void forgetSubject(String name) {
        if (name == null) return;
        boolean removed = signals.removeIf(s -> s.subject() != null && s.subject().equalsIgnoreCase(name));
        detections.values().removeIf(d -> d.name().equalsIgnoreCase(name));
        String suffix = "|" + name.toLowerCase(Locale.ROOT);
        cooldowns.keySet().removeIf(key -> key.endsWith(suffix));
        // Only release the gate when the subject that consumed it is the one
        // whose evidence was just disproved. A dead unrelated player must not
        // shorten the cooldown and let the same alert replay every few ticks.
        if (removed && lastCriticalSubject != null && lastCriticalSubject.equalsIgnoreCase(name)) {
            lastCritical = Long.MIN_VALUE / 2;
            lastCriticalSubject = null;
        }
    }

    private void prune(long now) {
        while (!signals.isEmpty() && now - signals.peekFirst().at() > WINDOW_MS) signals.pollFirst();
        while (!located.isEmpty() && now - located.peekFirst().at() > WINDOW_MS) located.pollFirst();
        if (cooldowns.size() > 512) cooldowns.values().removeIf(t -> now - t > 60_000);
    }

    public synchronized void clear() { signals.clear(); located.clear(); cooldowns.clear(); detections.clear(); lastCritical = lastCluster = Long.MIN_VALUE / 2; lastCriticalSubject = null; }
}
