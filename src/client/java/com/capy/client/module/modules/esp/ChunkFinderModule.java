package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ModuleManager;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

/**
 * Read-only light-update diff finder. No packets are sent and no blocks are
 * changed. It reads the light payload the server already sent.
 */
public final class ChunkFinderModule extends BaseFinderModule {
	private final BooleanSetting lightDebug = setting(new BooleanSetting("light_debug", false));
	private final BooleanSetting flagOnLoad = setting(new BooleanSetting("flag_on_load", false));

	public ChunkFinderModule() {
		super("chunk_finder", 0xFFF2C14E);
	}

	public static void onChunkPacket(int x, int z, ClientboundLightUpdatePacketData data) {
		LightSignalQueue.onChunkPacket(x, z, data);
	}

	public static void onLightPacket(int x, int z, ClientboundLightUpdatePacketData data) {
		LightSignalQueue.onLightPacket(x, z, data);
	}

	public static void onLocalBreak(net.minecraft.core.BlockPos pos) {
		LightSignalQueue.noteLocalBreak(pos, System.nanoTime());
	}

	public static boolean debugLogging() {
		ChunkFinderModule module = ModuleManager.get().get(ChunkFinderModule.class);
		return module != null && module.lightDebug.value();
	}

	public static boolean flagOnLoad() {
		ChunkFinderModule module = ModuleManager.get().get(ChunkFinderModule.class);
		return module != null && module.flagOnLoad.value();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		beginScan(mc);
		if (level == null) {
			return;
		}
		long now = System.nanoTime();
		LightSignalQueue.drain(level, now, 3_000_000L);
		syncFindings(now);
		status(mc, "light", 0, 1.0);
	}

	private void syncFindings(long now) {
		Map<Long, Long> found = LightSignalQueue.findings();
		Set<Long> live = new HashSet<>();
		for (Map.Entry<Long, Long> entry : found.entrySet()) {
			if (now - entry.getValue() <= 300_000_000_000L) {
				live.add(entry.getKey());
			}
		}
		for (long key : new ArrayList<>(flags)) {
			if (!live.contains(key)) {
				unmark(key);
			}
		}
		for (long key : live) {
			mark(key);
		}
	}

	@Override
	protected void onChunkLoaded(long key) {
		// The chunk-light packet itself is the light event; nothing else is needed.
	}

	@Override
	protected void onChunkChanged(long key) {
		if (level != null) {
			// A block/light update is delivered through the packet queue.
		}
	}

	@Override
	protected void onToggle(boolean state) {
		super.onToggle(state);
		if (!state) {
			LightSignalQueue.clear();
		}
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		lightDebug.set(ConfigValues.boolValue(json, "light_debug", false));
		flagOnLoad.set(ConfigValues.boolValue(json, "flag_on_load", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("light_debug", lightDebug.value());
		json.addProperty("flag_on_load", flagOnLoad.value());
	}
}
