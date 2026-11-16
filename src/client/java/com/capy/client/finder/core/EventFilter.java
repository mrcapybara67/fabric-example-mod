package com.capy.client.finder.core;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Natural-noise suppression. Feed it what it needs to know, then ask judge() for a weight multiplier (0..1) before an event is added to the grid.
 *  - Falling blocks (sand, gravel, anvils) land with place and break sounds: MINING and BUILDING within 3 blocks of one in the last 2.5 s are dropped.
 *  - Natural structures (trial chambers, ancient cities, villages, spawner rooms): REDSTONE, STORAGE and MACHINE near one are cut to 25 percent for 10 minutes.
 *  - Explosions count only when TNT was primed within 24 blocks in the last 12 s (creepers explode all the time).
 *  - Mining and building must repeat: fewer than 3 events within 6 blocks in 20 s count as nothing.
 *  - Storage needs 3 events, or 2 different blocks, within 8 blocks in 60 s to count fully (one player looting a natural chest opens it once).
 *  - Redstone needs 2 events within 8 blocks in 20 s, otherwise half.
 */
public final class EventFilter {
	private record Spot(double x, double y, double z, long t, long key) {
	}

	private final ArrayDeque<Spot> falling = new ArrayDeque<>(), tnt = new ArrayDeque<>(), structures = new ArrayDeque<>();
	private final ArrayDeque<Spot> mining = new ArrayDeque<>(), building = new ArrayDeque<>(), storage = new ArrayDeque<>(),
			redstone = new ArrayDeque<>();

	public synchronized void noteFallingBlock(double x, double y, double z, long now) {
		push(falling, x, y, z, now, 64);
	}

	public synchronized void noteStructure(double x, double y, double z, long now) {
		push(structures, x, y, z, now, 64);
	}

	public synchronized void noteTnt(double x, double y, double z, long now) {
		push(tnt, x, y, z, now, 64);
	}

	private static void push(ArrayDeque<Spot> q, double x, double y, double z, long now, int cap) {
		q.addLast(new Spot(x, y, z, now, Chunks.block((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))));
		while (q.size() > cap) {
			q.pollFirst();
		}
	}

	private static void expire(ArrayDeque<Spot> q, long now, long maxAge) {
		while (!q.isEmpty() && now - q.peekFirst().t() > maxAge) {
			q.pollFirst();
		}
	}

	private static double dist(Spot s, double x, double y, double z) {
		return Math.sqrt((s.x() - x) * (s.x() - x) + (s.y() - y) * (s.y() - y) + (s.z() - z) * (s.z() - z));
	}

	private static boolean near(ArrayDeque<Spot> q, double x, double y, double z, double radius, long now, long maxAge) {
		expire(q, now, maxAge);
		for (Spot s : q) {
			if (dist(s, x, y, z) <= radius) {
				return true;
			}
		}
		return false;
	}

	private static int count(ArrayDeque<Spot> q, double x, double y, double z, double radius, long now, long maxAge) {
		expire(q, now, maxAge);
		int n = 0;
		for (Spot s : q) {
			if (dist(s, x, y, z) <= radius) {
				n++;
			}
		}
		return n;
	}

	private static int distinct(ArrayDeque<Spot> q, double x, double y, double z, double radius, long now, long maxAge) {
		expire(q, now, maxAge);
		java.util.HashSet<Long> keys = new java.util.HashSet<>();
		for (Iterator<Spot> it = q.iterator(); it.hasNext(); ) {
			Spot s = it.next();
			if (dist(s, x, y, z) <= radius) {
				keys.add(s.key());
			}
		}
		return keys.size();
	}

	/** Returns the weight multiplier for this event. The event is also remembered for the repeat gates. */
	public synchronized double judge(SoundKinds.Kind kind, double x, double y, double z, long now) {
		switch (kind) {
			case MINING, BUILDING -> {
				if (near(falling, x, y, z, 3.0, now, 2500)) {
					return 0.0;
				}
				ArrayDeque<Spot> q = kind == SoundKinds.Kind.MINING ? mining : building;
				push(q, x, y, z, now, 256);
				return count(q, x, y, z, 6.0, now, 20_000) >= 3 ? 1.0 : 0.0;
			}
			case EXPLOSION -> {
				return near(tnt, x, y, z, 24.0, now, 12_000) ? 1.0 : 0.0;
			}
			case STORAGE -> {
				push(storage, x, y, z, now, 256);
				boolean repeated = count(storage, x, y, z, 8.0, now, 60_000) >= 3
						|| distinct(storage, x, y, z, 8.0, now, 60_000) >= 2;
				return (repeated ? 1.0 : 0.4) * structureFactor(x, y, z, now);
			}
			case REDSTONE -> {
				push(redstone, x, y, z, now, 256);
				return (count(redstone, x, y, z, 8.0, now, 20_000) >= 2 ? 1.0 : 0.5) * structureFactor(x, y, z, now);
			}
			case MACHINE -> {
				return structureFactor(x, y, z, now);
			}
			default -> {
				return 0.0;
			}
		}
	}

	private double structureFactor(double x, double y, double z, long now) {
		expire(structures, now, 600_000);
		for (Spot s : structures) {
			if (Math.hypot(s.x() - x, s.z() - z) <= 48 && Math.abs(s.y() - y) <= 24) {
				return 0.25;
			}
		}
		return 1.0;
	}

	public synchronized void clear() {
		falling.clear();
		tnt.clear();
		structures.clear();
		mining.clear();
		building.clear();
		storage.clear();
		redstone.clear();
	}
}
