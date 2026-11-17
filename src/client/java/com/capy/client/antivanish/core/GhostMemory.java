package com.capy.client.antivanish.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Timed, allocation-light state behind the ghost sound and particle gates. */
public final class GhostMemory {
	public record Pos(int x, int y, int z) {
		public static Pos of(double x, double y, double z) {
			return new Pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		}
	}

	private record Explosion(double x, double y, double z, double radius, long at) {
	}

	private record Attempt(String blockId, Pos pos, long at) {
	}

	public static final long EXPLOSION_TTL_MS = 3_000L;
	public static final long POWERED_TTL_MS = 5_000L;
	public static final long PLACED_TTL_MS = 6_000L;
	public static final long CONTAINER_TTL_MS = 2_500L;
	public static final long PLACE_WINDOW_MS = 500L;

	private final ArrayDeque<Explosion> explosions = new ArrayDeque<>();
	private final Map<Pos, Long> powered = new HashMap<>();
	private final Map<Pos, Long> placed = new HashMap<>();
	private final ArrayDeque<Attempt> attempts = new ArrayDeque<>();
	private final SoundGate.ParticleBurst bursts = new SoundGate.ParticleBurst();
	private boolean hasAction;
	private long lastAction;
	private boolean hasContainer;
	private long lastContainer;

	public synchronized void addExplosion(double x, double y, double z, double radius, long now) {
		explosions.addLast(new Explosion(x, y, z, radius, now));
		while (explosions.size() > 32) {
			explosions.pollFirst();
		}
	}

	public synchronized boolean explosionRelated(double x, double y, double z, long now) {
		explosions.removeIf(e -> now - e.at() > EXPLOSION_TTL_MS);
		for (Explosion e : explosions) {
			double reach = e.radius() + 4.0;
			if ((e.x() - x) * (e.x() - x) + (e.y() - y) * (e.y() - y) + (e.z() - z) * (e.z() - z) <= reach * reach) {
				return true;
			}
		}
		return false;
	}

	public synchronized void markPowered(Pos pos, long now) {
		if (pos != null) {
			powered.put(pos, now);
		}
	}

	public synchronized boolean poweredMechanism(Pos pos, long now) {
		if (pos == null) {
			return false;
		}
		Long at = powered.get(pos);
		if (at == null) {
			return false;
		}
		if (now - at > POWERED_TTL_MS) {
			powered.remove(pos);
			return false;
		}
		return true;
	}

	/** Records the block position a use-item call is about to place into. */
	public synchronized void placeAttempt(String blockId, Pos pos, long now) {
		if (blockId == null || pos == null) {
			return;
		}
		attempts.addLast(new Attempt(blockId, pos, now));
		while (attempts.size() > 32) {
			attempts.pollFirst();
		}
	}

	/** Confirms a block update that matches a recent placement attempt. */
	public synchronized void blockUpdate(String blockId, Pos pos, long now) {
		if (blockId == null || pos == null) {
			return;
		}
		Iterator<Attempt> it = attempts.iterator();
		while (it.hasNext()) {
			Attempt attempt = it.next();
			if (now - attempt.at() > PLACE_WINDOW_MS) {
				it.remove();
				continue;
			}
			if (!blockId.equals(attempt.blockId())) {
				continue;
			}
			int dx = pos.x() - attempt.pos().x();
			int dy = pos.y() - attempt.pos().y();
			int dz = pos.z() - attempt.pos().z();
			if (dx * dx + dy * dy + dz * dz <= 4) {
				placed.put(pos, now);
			}
		}
	}

	public synchronized boolean selfPlaced(Pos pos, long now) {
		if (pos == null) {
			return false;
		}
		Long at = placed.get(pos);
		if (at == null) {
			return false;
		}
		if (now - at > PLACED_TTL_MS) {
			placed.remove(pos);
			return false;
		}
		return true;
	}

	public synchronized void action(long now) {
		hasAction = true;
		lastAction = now;
	}

	public synchronized long msSinceAction(long now) {
		return hasAction ? Math.max(0L, now - lastAction) : Long.MAX_VALUE;
	}

	public synchronized void container(long now) {
		hasContainer = true;
		lastContainer = now;
	}

	public synchronized boolean containerBusy(long now) {
		return hasContainer && Math.max(0L, now - lastContainer) <= CONTAINER_TTL_MS;
	}

	public synchronized boolean particleBurst(String particle, long now) {
		return bursts.ready(particle, now);
	}

	public SoundGate.ParticleBurst bursts() {
		return bursts;
	}

	public synchronized void clear() {
		explosions.clear();
		powered.clear();
		placed.clear();
		attempts.clear();
		hasAction = false;
		lastAction = 0L;
		hasContainer = false;
		lastContainer = 0L;
	}
}
