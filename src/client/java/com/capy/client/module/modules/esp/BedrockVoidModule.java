package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.BedrockVoid;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.IntSetting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Bedrock Void ESP: outlines every cell in the lowest bedrock layer that is
 * not bedrock.
 *
 * <p>The overworld layer is the world floor (Y -64 in vanilla), the nether has
 * both its floor at Y 0 and its roof at Y 127, and the end has no such layer
 * and is skipped. The roof is opt-in: most players only care about the floor,
 * and the roof is twenty times the cells for the same question.</p>
 */
public final class BedrockVoidModule extends FinderModule {
	private static final int NETHER_FLOOR = 0;
	private static final int NETHER_ROOF = 127;

	private final BooleanSetting netherRoof = describe(setting(new BooleanSetting("nether_roof", false)),
			"Also read the nether roof at Y 127, not just the floor.");
	private final BooleanSetting beam = describe(setting(new BooleanSetting("beam", true)),
			"Draw a vertical beam above every void hole.");
	private final IntSetting beamHeight = describe(setting(new IntSetting("beam_height", 80, 10, 320, 1),
			() -> beam.value()), "How far the beam rises above the hole.");
	private final BooleanSetting throughWalls = describe(setting(new BooleanSetting("through_walls", true)),
			"Draw the holes through terrain instead of letting it hide them.");
	private final BooleanSetting tracer = describe(setting(new BooleanSetting("tracers", false)),
			"Draw a fading line from the crosshair to every hole.");
	private final BooleanSetting notifications = describe(setting(new BooleanSetting("notify", false)),
			"Show a short toast when a new void hole is found.");

	private final Map<Long, List<AABB>> holesByChunk = new HashMap<>();
	private List<AABB> renderBoxes = List.of();
	private int holeCount;
	private int appliedRoof = -1;

	public BedrockVoidModule() {
		super("bedrock_void", 0x8CA05AFF);
		useShape("both");
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		int roof = netherRoof.value() ? 1 : 0;
		if (appliedRoof != roof) {
			// The roof is a different set of cells; the old boxes cannot be
			// kept while the scanner revisits the chunks.
			appliedRoof = roof;
			holesByChunk.clear();
			rebuildRender();
			FinderScanner.get().queueAllLoaded();
		}
		refreshTerrain(flags.keySet());
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		boolean hadFlag = flags.containsKey(key);
		List<Integer> layers = bedrockLayers(level, netherRoof.value());
		List<AABB> holes = new ArrayList<>();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		for (int y : layers) {
			int index = (y - minY) >> 4;
			if (index < 0 || index >= sections.length) {
				continue;
			}
			LevelChunkSection section = sections[index];
			if (section == null) {
				continue;
			}
			boolean[] bedrock = new boolean[256];
			int localY = (y - minY) & 15;
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					bedrock[z * 16 + x] = section.getBlockState(x, localY, z).is(Blocks.BEDROCK);
				}
			}
			for (int[] hole : BedrockVoid.holes(bedrock)) {
				int x = baseX + hole[0];
				int z = baseZ + hole[1];
				holes.add(new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0));
			}
		}

		if (holes.isEmpty()) {
			holesByChunk.remove(key);
			unmarkFlag(key);
		} else {
			holesByChunk.put(key, List.copyOf(holes));
			markFlag(key, "bedrock void", holes.size(), holes);
			if (!hadFlag && notifications.value()) {
				notify.toast(Minecraft.getInstance(), "Bedrock void",
						holes.size() + " open cell(s) near " + (Chunks.x(key) * 16 + 8) + ", "
								+ (Chunks.z(key) * 16 + 8));
			}
		}
		rebuildRender();
		return true;
	}

	@Override
	public List<AABB> boxes() {
		return renderBoxes;
	}

	@Override
	public boolean throughWalls() {
		return throughWalls.value();
	}

	@Override
	public boolean tracers() {
		return false;
	}

	@Override
	protected boolean flagTracers() {
		return tracer.value();
	}

	@Override
	public boolean tracerFade() {
		return tracer.value();
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		drawSharedChunkFlags(sink, timeSec, timeMs);
	}

	@Override
	protected boolean sharedFlagBeam() {
		return beam.value();
	}

	@Override
	protected double sharedFlagBeamHeight() {
		return beamHeight.get();
	}

	private void rebuildRender() {
		List<AABB> all = new ArrayList<>();
		for (List<AABB> holes : holesByChunk.values()) {
			all.addAll(holes);
		}
		renderBoxes = List.copyOf(all);
		holeCount = all.size();
	}

	static List<Integer> bedrockLayers(ClientLevel level, boolean includeRoof) {
		if (level.dimension() == Level.OVERWORLD) {
			// The overworld floor is the bottom of the level's height, not a
			// constant: datapacks can move it.
			return List.of(level.getMinY());
		}
		if (level.dimension() == Level.NETHER) {
			int floor = Math.max(level.getMinY(), NETHER_FLOOR);
			int roof = Math.min(level.getMaxY() - 1, NETHER_ROOF);
			if (floor > roof) {
				return List.of();
			}
			if (!includeRoof) {
				return List.of(floor);
			}
			return floor == roof ? List.of(floor) : List.of(floor, roof);
		}
		return List.of();
	}

	@Override
	protected void onChunkUnloaded(long key) {
		holesByChunk.remove(key);
		rebuildRender();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		holesByChunk.clear();
		renderBoxes = List.of();
		holeCount = 0;
	}
}
