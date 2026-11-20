package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.IntSetting;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Spawner Beam: a vertical beam above every mob spawner in the loaded chunks.
 *
 * <p>This reads the chunk's block-entity list, not its blocks: a spawner is
 * already a block entity the client keeps, so there is no palette walk and no
 * cell visit. A chunk rescan replaces its list and an unload removes it.</p>
 *
 * <p>Beam Width is a tenths-of-a-block value: 4 draws a 0.4 block column, wide
 * enough to see from above without becoming a wall. Pulse changes the height
 * and brightness on a slow cycle; Rainbow replaces the chosen hue with one that
 * cycles. Spawners in one chunk share a beam, so a dungeon corridor does not
 * grow a picket fence.</p>
 */
public final class SpawnerBeamModule extends FinderModule {
	private final ColorSetting beamColor = describe(setting(new ColorSetting("beam_color", 0xFFAA6EFF)),
			"The colour of the beam and the spawner box.");
	private final BooleanSetting rainbow = describe(setting(new BooleanSetting("rainbow", false)),
			"Cycle the beam hue instead of using one colour.");
	private final IntSetting beamHeight = describe(setting(new IntSetting("beam_height", 120, 10, 320, 1)),
			"How far above the spawner the beam reaches.");
	private final IntSetting beamWidth = describe(setting(new IntSetting("beam_width", 4, 1, 10, 1)),
			"Beam footprint in tenths of a block (4 = 0.4).");
	private final BooleanSetting pulse = describe(setting(new BooleanSetting("pulse", true)),
			"Breathe the beam height and brightness.");
	private final BooleanSetting outline = describe(setting(new BooleanSetting("outline", true)),
			"Draw a box around the spawner itself.");
	private final BooleanSetting throughWalls = describe(setting(new BooleanSetting("through_walls", true)),
			"Draw through terrain instead of letting it hide the spawner.");
	private final BooleanSetting tracer = describe(setting(new BooleanSetting("tracers", false)),
			"Draw a fading line from the crosshair to every spawner.");
	private final BooleanSetting notifications = describe(setting(new BooleanSetting("notify", false)),
			"Show a short toast when a new spawner is found.");

	private final Map<Long, List<BlockPos>> spawnersByChunk = new HashMap<>();
	private final List<BlockPos> renderSpawners = new ArrayList<>();
	private List<AABB> boxes = List.of();

	public SpawnerBeamModule() {
		super("spawner_beam");
	}

	/** The scanner registration does the work; no per-tick pass is needed. */
	@Override
	public void onTick(Minecraft mc) {
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		boolean had = spawnersByChunk.containsKey(key);
		List<BlockPos> found = new ArrayList<>();
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			if (blockEntity != null && blockEntity.getType() == BlockEntityType.MOB_SPAWNER) {
				found.add(blockEntity.getBlockPos().immutable());
			}
		}
		if (found.isEmpty()) {
			spawnersByChunk.remove(key);
		} else {
			spawnersByChunk.put(key, List.copyOf(found));
			if (!had && notifications.value()) {
				notify.toast(Minecraft.getInstance(), "Spawner beam",
						"Spawner near " + (found.get(0).getX() + 0.5) + ", "
								+ (found.get(0).getZ() + 0.5));
			}
		}
		rebuildRender();
		return true;
	}

	@Override
	public List<AABB> boxes() {
		return boxes;
	}

	@Override
	public boolean fills() {
		return false;
	}

	@Override
	public boolean outlines() {
		return outline.value();
	}

	@Override
	public boolean tracers() {
		return tracer.value();
	}

	@Override
	public boolean tracerFade() {
		return tracer.value();
	}

	@Override
	public boolean throughWalls() {
		return throughWalls.value();
	}

	@Override
	public int colorFor(AABB box, double timeSec) {
		return beamArgb(timeSec);
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (renderSpawners.isEmpty()) {
			return;
		}
		int argb = beamArgb(timeSec);
		double width = beamWidth.get() / 10.0;
		double height = beamHeight.get();
		double phase = pulse.value() ? 0.92 + 0.08 * Math.sin(timeMs / 420.0) : 1.0;
		int alpha = (int) Math.min(255L, Math.round((argb >>> 24) * phase));
		int beamArgb = (alpha << 24) | (argb & 0xFFFFFF);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		List<BlockPos> ordered = new ArrayList<>(renderSpawners);
		ordered.sort(Comparator.comparingDouble(pos -> mc.player.distanceToSqr(
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
		Set<Long> beamed = new HashSet<>();
		for (BlockPos pos : ordered) {
			long key = Chunks.key(pos.getX() >> 4, pos.getZ() >> 4);
			if (!beamed.add(key)) {
				continue;
			}
			double x = pos.getX() + 0.5;
			double z = pos.getZ() + 0.5;
			double bottom = pos.getY() + 1.0;
			double distance = boxDistance(new AABB(pos));
			if (!drawableFlag(distance)) {
				continue;
			}
			// The width is applied to every beam; the helix is extra detail on
			// top of it rather than a separate style that ignores the setting.
			emitBeam(sink, x, z, bottom, bottom + height * phase, width, beamArgb);
			if (fullStyle(distance, 110)) {
				FlagStyles.helixBeam(sink, x, z, bottom, bottom + height * phase,
						beamArgb & 0xFFFFFF, timeMs);
			}
		}
	}

	private int beamArgb(double timeSec) {
		return rainbow.value() ? rainbowArgb(timeSec, 220) : beamColor.argbAt(timeSec);
	}

	private void rebuildRender() {
		renderSpawners.clear();
		boxes = List.of();
		if (spawnersByChunk.isEmpty()) {
			return;
		}
		List<AABB> next = new ArrayList<>();
		for (List<BlockPos> positions : spawnersByChunk.values()) {
			for (BlockPos pos : positions) {
				renderSpawners.add(pos);
				next.add(new AABB(pos).inflate(0.003));
			}
		}
		boxes = List.copyOf(next);
	}

	@Override
	public String reliability() {
		return "can be hidden by anti-xray";
	}

	@Override
	protected void onChunkUnloaded(long key) {
		spawnersByChunk.remove(key);
		rebuildRender();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		spawnersByChunk.clear();
		renderSpawners.clear();
		boxes = List.of();
	}
}
