package com.capy.client.antivanish.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Optional active check (a tab-complete request for a watched name). It is the only thing in the module a server can see,
 * so it is humanized: one name at a time, random gaps of 25 to 75 seconds, at most 6 per 10 minutes, doubling backoff.
 */
public final class ProbePlanner {
    private final Random rnd;
    private final List<String> names = new ArrayList<>();
    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private int cursor;
    private long nextAt;
    private long backoffMs;

    public ProbePlanner(Random rnd) { this.rnd = rnd; }

    public void setNames(List<String> watched) { names.clear(); names.addAll(watched); if (cursor >= names.size()) cursor = 0; }

    /** Returns the name to probe now, or null. online = names currently in the tab list (lowercase). */
    public String next(long now, Set<String> online) {
        if (names.isEmpty() || now < nextAt) return null;
        while (!sent.isEmpty() && now - sent.peekFirst() > 600_000) sent.pollFirst();
        if (sent.size() >= 6) { nextAt = now + 60_000; return null; }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get((cursor + i) % names.size());
            if (online.contains(name.toLowerCase(Locale.ROOT))) continue;
            cursor = (cursor + i + 1) % names.size();
            sent.addLast(now);
            nextAt = now + 25_000 + rnd.nextInt(50_001) + backoffMs;
            return name;
        }
        nextAt = now + 10_000;
        return null;
    }

    public void rateLimited(long now) { backoffMs = Math.min(600_000, Math.max(30_000, backoffMs * 2)); nextAt = Math.max(nextAt, now + backoffMs); }

    public void reset() { sent.clear(); nextAt = 0; backoffMs = 0; cursor = 0; }
}
