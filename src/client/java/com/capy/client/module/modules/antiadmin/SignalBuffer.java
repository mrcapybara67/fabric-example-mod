package com.capy.client.module.modules.antiadmin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * Rolling per-subject window of observations.
 *
 * A subject is either a real account UUID or, for a signal that has no account
 * behind it, a UUID derived from the block it happened at ({@link #spatial}).
 * That is what lets an orphan particle and an orphan footstep three blocks
 * apart be recognised as the same unseen player instead of two unrelated
 * curiosities, and it keeps every downstream type keyed the same way.
 *
 * Threading: writes come from the packet handlers and reads from the tick.
 * Vanilla runs those handlers on the client thread, and every entry point here
 * is guarded by that check, but the map is still a {@link ConcurrentHashMap}
 * because the HUD reads a snapshot while the game thread may be pushing. All
 * mutation of one subject's deque happens inside a single {@code compute}, so
 * the bin lock covers the whole read-modify-write and no deque is ever touched
 * from two threads at once.
 */
public final class SignalBuffer {
	/** How far back a signal is still considered part of the picture. */
	public static final int WINDOW_TICKS = 60;
	/** Hard cap per subject, so a burst cannot grow without bound. */
	private static final int MAX_PER_SUBJECT = 60;
	/** Hard cap on tracked subjects, so a griefing server cannot exhaust memory. */
	private static final int MAX_SUBJECTS = 256;
	/** Positional subjects are grouped into cells of this size. */
	private static final int CELL_XZ = 4;
	private static final int CELL_Y = 8;

	private final Map<UUID, Deque<Observation>> buffers = new ConcurrentHashMap<>();
	/**
	 * Names for subjects whose account the client can no longer look up.
	 *
	 * A probed player is known by name only — their player info entry is gone —
	 * so the name has to travel with the subject or the alert could not say who
	 * it caught.
	 */
	private static final Map<UUID, String> SUBJECT_NAMES = new ConcurrentHashMap<>();
	/** Enough names for a session; a longer list is not worth the memory. */
	private static final int MAX_NAMES = 256;
	private volatile long tick;

	/** Advances the clock every subject is stamped against. */
	public void tick(long current) {
		this.tick = current;
	}

	public long tick() {
		return tick;
	}

	/** Adds one observation. Called from the thread handling the packet. */
	public void push(UUID subject, Observation observation) {
		if (subject == null || observation == null) {
			return;
		}
		if (buffers.size() >= MAX_SUBJECTS && !buffers.containsKey(subject)) {
			evictColdest();
		}
		buffers.compute(subject, (key, deque) -> {
			Deque<Observation> target = deque == null ? new ArrayDeque<>() : deque;
			target.addLast(observation);
			long oldest = tick - WINDOW_TICKS;
			while (!target.isEmpty()
					&& (target.size() > MAX_PER_SUBJECT || target.peekFirst().tick() < oldest)) {
				target.pollFirst();
			}
			return target.isEmpty() ? null : target;
		});
	}

	/**
	 * A copy of every live subject and its window, oldest observation first.
	 *
	 * The copy is taken under the same per-subject lock as a write, so the
	 * correlator can walk it at leisure while packets keep arriving.
	 */
	public Map<UUID, List<Observation>> snapshot() {
		Map<UUID, List<Observation>> out = new LinkedHashMap<>();
		long oldest = tick - WINDOW_TICKS;
		for (UUID subject : buffers.keySet()) {
			buffers.computeIfPresent(subject, (key, deque) -> {
				while (!deque.isEmpty() && deque.peekFirst().tick() < oldest) {
					deque.pollFirst();
				}
				if (deque.isEmpty()) {
					return null;
				}
				out.put(key, List.copyOf(deque));
				return deque;
			});
		}
		return out;
	}

	/** Drops everything. Called on a world change, a disconnect or a toggle. */
	public void clear() {
		buffers.clear();
		SUBJECT_NAMES.clear();
		tick = 0L;
	}

	/** Remembers what an otherwise anonymous subject is called. */
	public static void nameSubject(UUID subject, String name) {
		if (subject == null || name == null || name.isBlank()) {
			return;
		}
		if (SUBJECT_NAMES.size() >= MAX_NAMES && !SUBJECT_NAMES.containsKey(subject)) {
			SUBJECT_NAMES.clear();
		}
		SUBJECT_NAMES.put(subject, name);
	}

	/** The remembered name of a subject, or null. */
	public static String subjectName(UUID subject) {
		return subject == null ? null : SUBJECT_NAMES.get(subject);
	}

	/** How many subjects currently hold evidence. */
	public int subjects() {
		return buffers.size();
	}

	/** Total observations held, for the debug readout. */
	public int observations() {
		int total = 0;
		for (Deque<Observation> deque : buffers.values()) {
			total += deque.size();
		}
		return total;
	}

	/**
	 * The subject a positional signal belongs to.
	 *
	 * Quantising is what makes two orphan sounds a few blocks apart correlate:
	 * exact block positions almost never match twice, a cell does.
	 */
	public static UUID spatial(BlockPos pos) {
		if (pos == null) {
			return null;
		}
		int x = Math.floorDiv(pos.getX(), CELL_XZ);
		int y = Math.floorDiv(pos.getY(), CELL_Y);
		int z = Math.floorDiv(pos.getZ(), CELL_XZ);
		String key = "capy:cell:" + x + ":" + y + ":" + z;
		return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
	}

	/** True when the subject was made by {@link #spatial}. */
	public static boolean isSpatial(UUID subject) {
		return subject != null && subject.version() == 3;
	}

	/** Drops the subject holding the least evidence. */
	private void evictColdest() {
		UUID coldest = null;
		int fewest = Integer.MAX_VALUE;
		for (Map.Entry<UUID, Deque<Observation>> entry : buffers.entrySet()) {
			int size = entry.getValue().size();
			if (size < fewest) {
				fewest = size;
				coldest = entry.getKey();
			}
		}
		if (coldest != null) {
			buffers.remove(coldest);
		}
	}

	/** Every subject currently held, for the correlator's debug view. */
	public List<UUID> subjectsList() {
		return new ArrayList<>(buffers.keySet());
	}
}
