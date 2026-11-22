package com.capy.client.module.modules;

import com.capy.client.finder.core.ChunkReload;
import com.capy.client.module.Category;
import com.capy.client.module.Module;
import com.capy.client.module.Tickable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Chunk Reload: drops the render distance to two while the player is below
 * Y 0, then restores it ten ticks later.
 *
 * <p>I do not know why this helps. What it changes is the client's view
 * distance, which makes the client ask the server for chunks again; that is
 * the whole mechanism.</p>
 *
 * <p>Default off. Leaving the world or switching the module off restores the
 * saved distance immediately instead of waiting for the ten ticks.</p>
 */
public final class ChunkReloadModule extends Module implements Tickable {
	private static final int LOW_DISTANCE = 2;

	private final ChunkReload state = new ChunkReload();
	private ClientLevel level;
	private int savedDistance = -1;

	public ChunkReloadModule() {
		super(Category.BASE_FINDER, "chunk_reload");
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled() || mc == null) {
			return;
		}
		if (mc.player == null || mc.level == null) {
			restore(mc);
			state.reset();
			level = null;
			return;
		}
		if (level != mc.level) {
			restore(mc);
			state.reset();
			level = mc.level;
		}
		ChunkReload.Action action = state.tick(mc.player.blockPosition().getY(),
				mc.options.renderDistance().get());
		if (action == ChunkReload.Action.APPLY) {
			savedDistance = state.savedDistance();
			setDistance(mc, LOW_DISTANCE);
		} else if (action == ChunkReload.Action.RESTORE) {
			restore(mc);
		}
	}

	/** Restores the saved distance and forgets the current cycle. */
	public void restoreAndReset() {
		restore(Minecraft.getInstance());
		state.reset();
		level = null;
	}

	private void restore(Minecraft mc) {
		// Only undo our own temporary drop. If the player chose a different
		// render distance while the module was below Y 0, that choice wins;
		// restoring the old value would silently overwrite it.
		if (savedDistance >= 0 && mc != null && mc.options != null
				&& mc.options.renderDistance().get() == LOW_DISTANCE) {
			setDistance(mc, savedDistance);
		}
		savedDistance = -1;
	}

	private static void setDistance(Minecraft mc, int distance) {
		mc.options.renderDistance().set(distance);
		// Apply now instead of waiting for the renderer's next comparison, so
		// the drop and the restore each re-request the client's chunks. There is
		// nothing to apply while leaving a world, and the renderer can already be
		// gone by the time the disconnect callback runs.
		if (mc.level != null && mc.levelRenderer != null) {
			mc.levelRenderer.allChanged();
		}
	}

	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			restore(Minecraft.getInstance());
			this.state.reset();
			level = null;
		} else {
			this.state.reset();
			level = null;
			savedDistance = -1;
		}
	}

}
