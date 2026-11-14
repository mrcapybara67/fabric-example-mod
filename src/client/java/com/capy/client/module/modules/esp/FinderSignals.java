package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * Groups the flags of different finders when they point at the same area.
 *
 * <p>A single finder is evidence, not proof. When two finders independently
 * flag chunks within two chunks of each other, the HUD should say that both
 * kinds of evidence agree instead of presenting two unrelated rows. Grouping is
 * deliberately per finder and not per flag: two flags from the same finder are
 * still two separate observations.</p>
 */
public final class FinderSignals {
	private static final int MERGE_RADIUS_CHUNKS = 2;

	public record Group(String label, String reason, long representativeKey, int chunkX, int chunkZ,
			int distanceBlocks, boolean combined, List<Long> chunks, int color, int signals) {
	}

	private record Node(FinderModule finder, FinderModule.Flag flag, long key, int chunkX, int chunkZ,
			int distanceBlocks) {
	}

	private FinderSignals() {
	}

	/** Every visible signal, nearest first. The HUD caps the list; the world pass does not. */
	public static List<Group> collect(Minecraft mc) {
		if (mc == null) {
			return List.of();
		}
		List<Node> nodes = new ArrayList<>();
		for (Module module : ModuleManager.get().modules()) {
			if (!(module instanceof FinderModule finder) || !finder.isEnabled()) {
				continue;
			}
			for (FinderModule.Flag flag : finder.flagRecords()) {
				long key = flag.chunkKey();
				nodes.add(new Node(finder, flag, key, Chunks.x(key), Chunks.z(key), distance(mc, key)));
			}
		}
		if (nodes.isEmpty()) {
			return List.of();
		}

		int count = nodes.size();
		int[] parent = new int[count];
		for (int i = 0; i < count; i++) {
			parent[i] = i;
		}
		Map<Long, List<Integer>> byChunk = new HashMap<>();
		for (int i = 0; i < count; i++) {
			Node node = nodes.get(i);
			byChunk.computeIfAbsent(Chunks.key(node.chunkX(), node.chunkZ()), ignored -> new ArrayList<>()).add(i);
		}
		for (int i = 0; i < count; i++) {
			Node node = nodes.get(i);
			for (int dz = -MERGE_RADIUS_CHUNKS; dz <= MERGE_RADIUS_CHUNKS; dz++) {
				for (int dx = -MERGE_RADIUS_CHUNKS; dx <= MERGE_RADIUS_CHUNKS; dx++) {
					List<Integer> candidates = byChunk.get(Chunks.key(node.chunkX() + dx, node.chunkZ() + dz));
					if (candidates == null) {
						continue;
					}
					for (int other : candidates) {
						if (other <= i || nodes.get(other).finder() == node.finder()) {
							continue;
						}
						union(parent, i, other);
					}
				}
			}
		}

		Map<Integer, List<Integer>> grouped = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			grouped.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(i);
		}

		List<Group> groups = new ArrayList<>(grouped.size());
		for (List<Integer> members : grouped.values()) {
			LinkedHashSet<FinderModule> finders = new LinkedHashSet<>();
			LinkedHashSet<String> reasons = new LinkedHashSet<>();
			LinkedHashSet<Long> chunks = new LinkedHashSet<>();
			Node nearest = null;
			for (int index : members) {
				Node node = nodes.get(index);
				finders.add(node.finder());
				reasons.add(node.flag().reason());
				chunks.add(node.key());
				if (nearest == null || node.distanceBlocks() < nearest.distanceBlocks()) {
					nearest = node;
				}
			}
			if (nearest == null) {
				continue;
			}
			boolean combined = finders.size() > 1;
			String label = combined ? finders.size() + " signals" : nearest.finder().displayName();
			String reason = combined ? String.join(" + ", reasons) : nearest.flag().reason();
			int color = combined ? blend(finders) : nearest.finder().colorArgb();
			groups.add(new Group(label, reason, nearest.key(), nearest.chunkX(), nearest.chunkZ(),
					nearest.distanceBlocks(), combined, List.copyOf(chunks), color, finders.size()));
		}
		groups.sort(Comparator.comparingInt(Group::distanceBlocks)
				.thenComparingInt(Group::chunkX)
				.thenComparingInt(Group::chunkZ));
		return groups;
	}

	private static int distance(Minecraft mc, long key) {
		if (mc.player == null) {
			return 0;
		}
		double dx = mc.player.getX() - (Chunks.x(key) * 16.0 + 8.0);
		double dz = mc.player.getZ() - (Chunks.z(key) * 16.0 + 8.0);
		return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
	}

	/** A brighter average, so agreement reads stronger than either input. */
	private static int blend(Set<FinderModule> finders) {
		int r = 0;
		int g = 0;
		int b = 0;
		int n = 0;
		for (FinderModule finder : finders) {
			int argb = finder.colorArgb();
			r += (argb >> 16) & 0xFF;
			g += (argb >> 8) & 0xFF;
			b += argb & 0xFF;
			n++;
		}
		if (n == 0) {
			return 0xFF8FD3FF;
		}
		r = Math.min(255, r / n + (255 - r / n) * 28 / 100);
		g = Math.min(255, g / n + (255 - g / n) * 28 / 100);
		b = Math.min(255, b / n + (255 - b / n) * 28 / 100);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static int find(int[] parent, int index) {
		while (parent[index] != index) {
			parent[index] = parent[parent[index]];
			index = parent[index];
		}
		return index;
	}

	private static void union(int[] parent, int a, int b) {
		int ra = find(parent, a);
		int rb = find(parent, b);
		if (ra != rb) {
			parent[rb] = ra;
		}
	}
}
