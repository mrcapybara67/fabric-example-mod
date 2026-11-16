package com.capy.client.finder.core;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-driven feed for Sound Bypass. Packet handlers decode a packet, call one
 * of the on... methods here, and this class does the proximity rejection, the
 * natural-noise weighting, the grid update and the channel accounting.
 *
 * <p>It contains no Minecraft types on purpose: the packet decoder can be
 * tested with fake positions, ids and timestamps without a running client.</p>
 */
public final class SoundBypassFeed {
	public enum Tracked { FALLING_BLOCK, TNT, ITEM, XP_ORB }

	private record Spot(double x, double y, double z, long t) {
	}

	private record Track(Tracked kind, double x, double y, double z, boolean naturalItem) {
	}

	private static final SoundBypassFeed INSTANCE = new SoundBypassFeed();
	private static final double SELF_RADIUS_SQ = 36.0;
	private static final double ENTITY_RADIUS_SQ = 9.0;
	private static final double ITEM_RADIUS_SQ = 64.0;
	private static final long ITEM_WINDOW_MS = 5_000L;
	private static final int ITEM_CLUSTER = 4;

	private final EventFilter filter = new EventFilter();
	private final ActivityGrid grid = new ActivityGrid();
	private final ChannelStats stats = new ChannelStats();
	private final Map<Integer, Track> tracked = new HashMap<>();
	private final Map<Long, Integer> destruction = new HashMap<>();
	private final Map<ChannelStats.Channel, ArrayDeque<Spot>> clusters = new EnumMap<>(ChannelStats.Channel.class);

	private double playerX;
	private double playerY;
	private double playerZ;
	private double[] nearby = new double[0];
	private boolean snapshotValid;
	private int users;

	private SoundBypassFeed() {
		clusters.put(ChannelStats.Channel.ITEM_ENTITY, new ArrayDeque<>());
		clusters.put(ChannelStats.Channel.XP_ORB, new ArrayDeque<>());
	}

	public static SoundBypassFeed get() {
		return INSTANCE;
	}

	public synchronized void acquire() {
		if (users++ == 0) {
			reset();
		}
	}

	public synchronized void release() {
		if (users > 0) {
			users--;
		}
		if (users == 0) {
			reset();
		}
	}

	public synchronized boolean active() {
		return users > 0;
	}

	/**
	 * The 5-tick snapshot used by the proximity rule. {@code nearbyPositions} is
	 * a flat x, y, z array of visible living entities; it is copied so packet
	 * threads cannot observe a half-written array.
	 */
	public synchronized void refreshSnapshot(double playerX, double playerY, double playerZ, double[] nearbyPositions) {
		this.playerX = playerX;
		this.playerY = playerY;
		this.playerZ = playerZ;
		this.nearby = nearbyPositions == null ? new double[0] : nearbyPositions.clone();
		snapshotValid = true;
	}

	public synchronized void onSound(String soundId, double x, double y, double z, long now) {
		if (!active()) {
			return;
		}
		if (SoundKinds.isStructureSound(soundId)) {
			filter.noteStructure(x, y, z, now);
		}
		if (SoundKinds.isTntPrimed(soundId)) {
			filter.noteTnt(x, y, z, now);
		}
		SoundKinds.Hit hit = SoundKinds.classify(soundId);
		if (hit.kind() == SoundKinds.Kind.IGNORE) {
			return;
		}
		offer(hit.kind(), hit.weight(), x, y, z, now, ChannelStats.Channel.SOUND);
	}

	public synchronized void onBlockEvent(SoundKinds.Kind kind, double baseWeight, double x, double y, double z, long now) {
		if (!active() || kind == SoundKinds.Kind.IGNORE) {
			return;
		}
		offer(kind, baseWeight, x, y, z, now, ChannelStats.Channel.BLOCK_EVENT);
	}

	public synchronized void onLevelEvent(SoundKinds.Kind kind, double baseWeight, double x, double y, double z, long now) {
		if (!active() || kind == SoundKinds.Kind.IGNORE) {
			return;
		}
		offer(kind, baseWeight, x, y, z, now, ChannelStats.Channel.LEVEL_EVENT);
	}

	public synchronized void onStructure(double x, double y, double z, long now) {
		if (active()) {
			filter.noteStructure(x, y, z, now);
		}
	}

	public synchronized void onTnt(double x, double y, double z, long now) {
		if (active()) {
			filter.noteTnt(x, y, z, now);
		}
	}

	public synchronized void onFallingBlock(double x, double y, double z, long now) {
		if (active()) {
			filter.noteFallingBlock(x, y, z, now);
		}
	}

	public synchronized void onDestruction(int blockX, int blockY, int blockZ, int progress, long now) {
		if (!active()) {
			return;
		}
		long key = Chunks.block(blockX, blockY, blockZ);
		Integer previous = destruction.put(key, progress);
		if (previous != null && previous == progress) {
			return;
		}
		if (destruction.size() > 8192) {
			destruction.clear();
		}
		offer(SoundKinds.Kind.MINING, 0.5, blockX + 0.5, blockY + 0.5, blockZ + 0.5, now,
				ChannelStats.Channel.DESTRUCTION);
	}

	public synchronized void onEntityAdd(int id, Tracked kind, double x, double y, double z,
			boolean naturalItem, long now) {
		if (!active() || kind == null) {
			return;
		}
		Track track = new Track(kind, x, y, z, naturalItem);
		tracked.put(id, track);
		process(track, now);
	}

	public synchronized void onEntityMove(int id, double x, double y, double z, boolean naturalItem, long now) {
		if (!active()) {
			return;
		}
		Track previous = tracked.get(id);
		if (previous == null) {
			return;
		}
		Track next = new Track(previous.kind(), x, y, z, naturalItem);
		tracked.put(id, next);
		process(next, now);
	}

	public synchronized void onEntityRemove(int id, long now) {
		if (!active()) {
			return;
		}
		Track track = tracked.remove(id);
		if (track == null) {
			return;
		}
		if (track.kind() == Tracked.FALLING_BLOCK) {
			filter.noteFallingBlock(track.x(), track.y(), track.z(), now);
		} else if (track.kind() == Tracked.TNT) {
			filter.noteTnt(track.x(), track.y(), track.z(), now);
		}
	}

	public synchronized List<ActivityGrid.Cell> top(long now, double minScore, int max) {
		return grid.top(now, minScore, max);
	}

	public synchronized ChannelStats stats() {
		return stats;
	}

	public synchronized ActivityGrid grid() {
		return grid;
	}

	public synchronized EventFilter filter() {
		return filter;
	}

	public synchronized void reset() {
		filter.clear();
		grid.clear();
		stats.reset();
		tracked.clear();
		destruction.clear();
		for (ArrayDeque<Spot> queue : clusters.values()) {
			queue.clear();
		}
		nearby = new double[0];
		snapshotValid = false;
		playerX = 0;
		playerY = 0;
		playerZ = 0;
	}

	private void offer(SoundKinds.Kind kind, double baseWeight, double x, double y, double z, long now,
			ChannelStats.Channel channel) {
		if (kind == SoundKinds.Kind.IGNORE || baseWeight <= 0 || nearAny(x, y, z)) {
			return;
		}
		double weight = baseWeight * filter.judge(kind, x, y, z, now);
		if (weight <= 0) {
			return;
		}
		grid.add(kind, weight, x, y, z, now);
		stats.count(channel, y, playerY);
	}

	private void process(Track track, long now) {
		switch (track.kind()) {
			case FALLING_BLOCK -> filter.noteFallingBlock(track.x(), track.y(), track.z(), now);
			case TNT -> filter.noteTnt(track.x(), track.y(), track.z(), now);
			case ITEM -> itemEvent(ChannelStats.Channel.ITEM_ENTITY, 0.8, track, now);
			case XP_ORB -> itemEvent(ChannelStats.Channel.XP_ORB, 1.5, track, now);
		}
	}

	private void itemEvent(ChannelStats.Channel channel, double baseWeight, Track track, long now) {
		if (track.naturalItem() || nearAny(track.x(), track.y(), track.z())) {
			return;
		}
		stats.count(channel, track.y(), playerY);
		ArrayDeque<Spot> queue = clusters.get(channel);
		queue.addLast(new Spot(track.x(), track.y(), track.z(), now));
		while (!queue.isEmpty() && now - queue.peekFirst().t() > ITEM_WINDOW_MS) {
			queue.pollFirst();
		}
		int count = 0;
		for (Spot spot : queue) {
			double dx = spot.x() - track.x();
			double dy = spot.y() - track.y();
			double dz = spot.z() - track.z();
			if (dx * dx + dy * dy + dz * dz <= ITEM_RADIUS_SQ) {
				count++;
			}
		}
		if (count < ITEM_CLUSTER) {
			return;
		}
		double weight = baseWeight * filter.judge(SoundKinds.Kind.MINING, track.x(), track.y(), track.z(), now);
		if (weight > 0) {
			grid.add(SoundKinds.Kind.MINING, weight, track.x(), track.y(), track.z(), now);
		}
	}

	private boolean nearAny(double x, double y, double z) {
		if (!snapshotValid) {
			return false;
		}
		double dx = x - playerX;
		double dy = y - playerY;
		double dz = z - playerZ;
		if (dx * dx + dy * dy + dz * dz <= SELF_RADIUS_SQ) {
			return true;
		}
		for (int i = 0; i + 2 < nearby.length; i += 3) {
			dx = x - nearby[i];
			dy = y - nearby[i + 1];
			dz = z - nearby[i + 2];
			if (dx * dx + dy * dy + dz * dz <= ENTITY_RADIUS_SQ) {
				return true;
			}
		}
		return false;
	}
}
