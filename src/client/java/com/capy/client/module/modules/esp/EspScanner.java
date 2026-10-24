package com.capy.client.module.modules.esp;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Walks the loaded chunks around the player, nearest first.
 *
 * Both world scanners start from a position and a radius, so the walk lives
 * here instead of being copied into each of them. The order matters: a scan
 * that stops after it has collected enough boxes must keep the ones closest to
 * the player, and starting at the centre is what guarantees that.
 *
 * Only chunks the client actually holds are visited — {@code getChunkNow} is a
 * plain array lookup and returns null for anything not loaded, so the walk
 * never triggers a request for chunk data from the server.
 */
final class EspScanner {
	/** Visits one loaded chunk. Returning false stops the walk. */
	interface ChunkVisitor {
		boolean visit(LevelChunk chunk);
	}

	private EspScanner() {
	}

	/**
	 * Visits every loaded chunk whose column can hold a block within
	 * {@code range} blocks of {@code center}, starting at the player's own
	 * chunk and moving outwards in rings.
	 */
	static void forEachChunk(ClientLevel level, BlockPos center, int range, ChunkVisitor visitor) {
		ChunkSource source = level.getChunkSource();
		int chunkRange = (range >> 4) + 1;
		int centerX = center.getX() >> 4;
		int centerZ = center.getZ() >> 4;
		for (int ring = 0; ring <= chunkRange; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					// Only the perimeter of each ring; the inside was visited by
					// a previous, smaller ring.
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					LevelChunk chunk = source.getChunkNow(centerX + dx, centerZ + dz);
					if (chunk != null && !visitor.visit(chunk)) {
						return;
					}
				}
			}
		}
	}

	/** Squared distance from a block position to a point, in blocks. */
	static double distanceSqr(BlockPos pos, double x, double y, double z) {
		double dx = pos.getX() + 0.5 - x;
		double dy = pos.getY() + 0.5 - y;
		double dz = pos.getZ() + 0.5 - z;
		return dx * dx + dy * dy + dz * dz;
	}
}
