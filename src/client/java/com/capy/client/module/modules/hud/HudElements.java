package com.capy.client.module.modules.hud;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.Tickable;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;

/**
 * The HUD elements that ship with the client.
 *
 * Every value here is measured from information the client actually has, so
 * nothing on the HUD can be wrong: FPS is the client's own frame rate, ping is
 * the latency the server reports, coordinates and biome come from the world the
 * player is standing in, and the speed is the distance the player actually
 * moved. There is deliberately no armour display - the vanilla HUD already
 * shows armour, and a second copy would only take space.
 *
 * The elements are registered together so the whole set stays in one place and
 * the stacking order inside a grid cell is obvious.
 */
public final class HudElements {
	private HudElements() {
	}

	/** Registers every HUD element. Order is the stacking order per cell. */
	public static void register(ModuleManager modules) {
		modules.register(new Watermark());
		modules.register(new Fps());
		modules.register(new Ping());
		modules.register(new Players());
		modules.register(new Coordinates());
		modules.register(new Biome());
		modules.register(new Facing());
		modules.register(new Speed());
		modules.register(new Session());
		modules.register(new Day());
	}

	// ------------------------------------------------------------- elements

	/** The client name, top left, the one element that is always on. */
	private static final class Watermark extends HudElementModule {
		Watermark() {
			super("hud_watermark", "", ANCHOR_TOP_LEFT);
		}

		@Override
		public String value(Minecraft mc) {
			return "Capy Client";
		}
	}

	private static final class Fps extends HudElementModule {
		Fps() {
			super("hud_fps", "FPS", ANCHOR_TOP_RIGHT);
		}

		@Override
		public String value(Minecraft mc) {
			return String.valueOf(mc.getFps());
		}
	}

	private static final class Ping extends HudElementModule {
		Ping() {
			super("hud_ping", "PING", ANCHOR_TOP_RIGHT);
		}

		@Override
		public String value(Minecraft mc) {
			if (mc.getConnection() == null || mc.player == null) {
				return "-";
			}
			PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
			return info == null ? "-" : info.getLatency() + " ms";
		}
	}

	private static final class Players extends HudElementModule {
		Players() {
			super("hud_players", "PLAYERS", ANCHOR_TOP_RIGHT);
		}

		@Override
		public String value(Minecraft mc) {
			if (mc.getConnection() == null) {
				return "-";
			}
			int listed = mc.getConnection().getOnlinePlayers().size();
			return String.valueOf(listed);
		}
	}

	private static final class Coordinates extends HudElementModule {
		Coordinates() {
			super("hud_coords", "XYZ", ANCHOR_BOTTOM_LEFT);
		}

		@Override
		public String value(Minecraft mc) {
			LocalPlayer player = mc.player;
			if (player == null) {
				return "-";
			}
			return String.format(Locale.ROOT, "%.0f %.0f %.0f", player.getX(), player.getY(), player.getZ());
		}
	}

	private static final class Biome extends HudElementModule {
		Biome() {
			super("hud_biome", "BIOME", ANCHOR_BOTTOM_LEFT);
		}

		@Override
		public String value(Minecraft mc) {
			if (mc.level == null || mc.player == null) {
				return "-";
			}
			var key = mc.level.getBiome(mc.player.blockPosition()).unwrapKey();
			if (key.isEmpty()) {
				return "-";
			}
			return key.get().identifier().getPath().replace('_', ' ');
		}
	}

	private static final class Facing extends HudElementModule {
		Facing() {
			super("hud_facing", "FACING", ANCHOR_BOTTOM_LEFT);
		}

		@Override
		public String value(Minecraft mc) {
			LocalPlayer player = mc.player;
			if (player == null) {
				return "-";
			}
			var direction = player.getDirection();
			return direction == null ? "-" : direction.getName().toUpperCase(Locale.ROOT);
		}
	}

	/**
	 * Horizontal speed in blocks per second, smoothed so the number is
	 * readable while sprinting instead of flickering every tick.
	 */
	private static final class Speed extends HudElementModule implements Tickable {
		private double smoothed;
		private double lastX;
		private double lastZ;
		private boolean primed;

		Speed() {
			super("hud_speed", "SPEED", ANCHOR_BOTTOM_LEFT);
		}

		@Override
		public void onTick(Minecraft mc) {
			LocalPlayer player = mc.player;
			if (player == null) {
				primed = false;
				smoothed = 0;
				return;
			}
			double x = player.getX();
			double z = player.getZ();
			if (!primed) {
				lastX = x;
				lastZ = z;
				primed = true;
				return;
			}
			// One tick is a twentieth of a second.
			double perSecond = Math.hypot(x - lastX, z - lastZ) * 20.0;
			lastX = x;
			lastZ = z;
			smoothed += (perSecond - smoothed) * 0.25;
		}

		@Override
		public String value(Minecraft mc) {
			return String.format(Locale.ROOT, "%.1f b/s", smoothed);
		}
	}

	/** How long the client has been running this session. */
	private static final class Session extends HudElementModule {
		private long startMs = System.currentTimeMillis();

		Session() {
			super("hud_session", "TIME", ANCHOR_BOTTOM_RIGHT);
		}

		@Override
		protected void onToggle(boolean state) {
			if (state) {
				startMs = System.currentTimeMillis();
			}
		}

		@Override
		public String value(Minecraft mc) {
			long seconds = Math.max(0, (System.currentTimeMillis() - startMs) / 1000L);
			long hours = seconds / 3600L;
			if (hours > 0) {
				return String.format(Locale.ROOT, "%d:%02d:%02d", hours, (seconds / 60) % 60, seconds % 60);
			}
			return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
		}
	}

	/** The in-game day counter. */
	private static final class Day extends HudElementModule {
		Day() {
			super("hud_day", "DAY", ANCHOR_BOTTOM_RIGHT);
		}

		@Override
		public String value(Minecraft mc) {
			if (mc.level == null) {
				return "-";
			}
			return String.valueOf(mc.level.getDayTime() / 24000L + 1L);
		}
	}
}
