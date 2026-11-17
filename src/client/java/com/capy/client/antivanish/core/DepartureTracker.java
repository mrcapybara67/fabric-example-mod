package com.capy.client.antivanish.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab-list departures. A player who leaves normally also produces a leave message; one who vanishes does not.
 * After a short delay: still listed means nothing happened; a leave message near in time means a normal logout;
 * a body still in the world means "entity remained"; a silent removal counts only on servers that normally send leave messages.
 */
public final class DepartureTracker {
    public record Finding(String uuid, String name, String reason, int score, int distance) {}
    public interface View { boolean listed(String uuid); /** distance in blocks, or -1 when no body is loaded */ int distanceIfNearby(String uuid); }

    private record Pending(String uuid, String name, long dueMs) {}

    public static final long DELAY_MS = 1000, LEAVE_WINDOW_MS = 8000, CONFIRM_MS = 5000;

    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Long> confirmed = new HashMap<>();
    private final List<Object[]> leaves = new ArrayList<>(); // {lowerName, time}
    private boolean serverSendsLeaveMessages;

    public boolean serverSendsLeaveMessages() { return serverSendsLeaveMessages; }

    public void tabRemoved(String uuid, String name, long now) { pending.put(uuid, new Pending(uuid, name, now + DELAY_MS)); }

    /** Call for every system chat line, with {uuid, name} pairs for the players you know. */
    public void chat(String text, java.util.Collection<String[]> knownUuidAndName, long now) {
        for (String[] known : knownUuidAndName) {
            if (VanishText.looksLikeLeave(text, known[1])) {
                serverSendsLeaveMessages = true;
                leaves.add(new Object[]{known[1].toLowerCase(Locale.ROOT), now});
                confirmed.put(known[0], now + CONFIRM_MS);
                pending.remove(known[0]);
            }
        }
        leaves.removeIf(l -> now - (Long) l[1] > LEAVE_WINDOW_MS);
    }

    public boolean recentLeave(String name, long now) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Object[] l : leaves) if (l[0].equals(n) && now - (Long) l[1] <= LEAVE_WINDOW_MS) return true;
        return false;
    }

    /** Entry hidden from the tab list while still connected (listed=false). */
    public Finding hiddenFromTab(String uuid, String name, long now) {
        if (!VanishText.plausibleName(name) || recentLeave(name, now)) return null;
        return new Finding(uuid, name, "Vanish Event: hidden from TAB", 100, -1);
    }

    public List<Finding> due(long now, View view) {
        List<Finding> out = new ArrayList<>();
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.dueMs() > now) continue;
            it.remove();
            if (view.listed(p.uuid()) || confirmed.getOrDefault(p.uuid(), 0L) > now) continue;
            if (recentLeave(p.name(), now)) { serverSendsLeaveMessages = true; continue; }
            int d = view.distanceIfNearby(p.uuid());
            if (d >= 0) out.add(new Finding(p.uuid(), p.name(), "Vanish Event: entity remained", 100, d));
            else if (serverSendsLeaveMessages) out.add(new Finding(p.uuid(), p.name(), "Vanish Event: silent TAB disappearance", 100, -1));
        }
        confirmed.values().removeIf(t -> t <= now);
        return out;
    }

    public void clear() { pending.clear(); confirmed.clear(); leaves.clear(); serverSendsLeaveMessages = false; }
}
