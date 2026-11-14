package com.capy.client.module.modules.hud;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.Tickable;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

/**
 * The HUD elements that ship with the client.
 *
 * <p>Every value here is measured from information the client actually has, so
 * nothing on the HUD can be wrong: FPS is the client's own frame rate, ping is
 * the latency the server reports, coordinates and biome come from the world the
 * player is standing in, and the speed is the distance the player actually
 * moved.</p>
 *
 * <h2>What is not here, and why</h2>
 * <ul>
 *   <li><b>players</b> - the tab list already says who is online, and a chip
 *       that repeats it reads as a second, smaller tab list;</li>
 *   <li><b>facing</b> - the compass already answers it, and a four letter word
 *       that only ever changes when the player turns is the least useful thing
 *       a HUD can spend a chip on;</li>
 *   <li><b>session time</b> - nobody has ever made a decision with it. The day
 *       counter below is the number that actually matters in a world.</li>
 * </ul>
 *
 * <p>What replaces them is the pair of coordinate chips: the position the player
 * is standing at, and the same position converted into the other dimension. On
 * a server where the nether is used as a road, that second number is the one
 * that tells the player where the overworld portal will come out.</p>
 */
public final class HudElements {
	/** Render distance between the two dimensions: eight overworld blocks per nether block. */
	private static final double NETHER_RATIO = 8.0;

	private HudElements() {
	}

	/** Registers every HUD element. Order is the stacking order per cell. */
	public static void register(ModuleManager modules) {
		modules.register(new Watermark());
		modules.register(new Fps());
		modules.register(new Ping());
		modules.register(new Coordinates());
		modules.register(new OtherDimension());
		modules.register(new Biome());
		modules.register(new Speed());
		modules.register(new Day());
		FinderListHudModule finderList = new FinderListHudModule();
		modules.register(finderList);
		// The list is the one HUD element whose whole job is to exist when there
		// is a finding. It is on by default and hides itself while empty.
		finderList.setEnabled(true);
	}

	// ------------------------------------------------------------- elements

	/** The client name, top left. */
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
			if (info == null || info.getLatency() <= 0) {
				return "-";
			}
			return info.getLatency() + " ms";
		}
	}

	/** Where the player is standing, in the dimension they are in. */
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
			return format(player.getX(), player.getY(), player.getZ());
		}
	}

	/**
	 * The same position in the other dimension.
	 *
	 * <p>The label follows the dimension the player is in: standing in the
	 * overworld the chip is labelled NETHER and shows where the portal will
	 * place them; standing in the nether it is labelled OVERWORLD and shows where
	 * the return trip will surface. Y is not converted, because height is the
	 * same in both.</p>
	 */
	private static final class OtherDimension extends HudElementModule {
		OtherDimension() {
			super("hud_coords_other", "NETHER", ANCHOR_BOTTOM_LEFT);
		}

		@Override
		public String label() {
			return isNether(Minecraft.getInstance()) ? "OVERWORLD" : "NETHER";
		}

		@Override
		public String value(Minecraft mc) {
			LocalPlayer player = mc.player;
			if (player == null) {
				return "-";
			}
			double x = player.getX();
			double z = player.getZ();
			if (isNether(mc)) {
				x *= NETHER_RATIO;
				z *= NETHER_RATIO;
			} else {
				x /= NETHER_RATIO;
				z /= NETHER_RATIO;
			}
			return format(x, player.getY(), z);
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
			String path = key.get().identifier().getPath();
			// "cherry_grove" reads as a place name, "cherry grove" reads as a
			// description. The chip is a label, so it is capitalised like one.
			StringBuilder out = new StringBuilder(path.length());
			boolean wordStart = true;
			for (int i = 0; i < path.length(); i++) {
				char ch = path.charAt(i);
				if (ch == '_') {
					out.append(' ');
					wordStart = true;
					continue;
				}
				out.append(wordStart ? Character.toUpperCase(ch) : ch);
				wordStart = false;
			}
			return out.toString();
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
			// Nothing is measured while the element is off; it restarts from the
			// current position when it is switched on again.
			if (!isEnabled()) {
				primed = false;
				smoothed = 0;
				return;
			}
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

	/**
	 * The world's day counter and the in-game clock.
	 *
	 * <p>A day is 24000 ticks and starts at 06:00, which is why the offset is
	 * six hours and not zero: 0 is sunrise, not midnight. The clock is the number
	 * a player plans a night around, and the day count is how long the world has
	 * existed - one chip, both.</p>
	 */
	private static final class Day extends HudElementModule {
		Day() {
			super("hud_day", "DAY", ANCHOR_BOTTOM_RIGHT);
		}

		@Override
		public String value(Minecraft mc) {
			if (mc.level == null) {
				return "-";
			}
			// getDayTime can be negative in a custom world; a day below one would
			// read as a glitch.
			long day = Math.max(1L, mc.level.getDayTime() / 24000L + 1L);
			long ticks = mc.level.getDayTime() % 24000L;
			if (ticks < 0) {
				ticks += 24000L;
			}
			long minutes = (ticks * 1440L / 24000L + 6L * 60L) % 1440L;
			return String.format(Locale.ROOT, "%d \u00b7 %02d:%02d", day, minutes / 60L, minutes % 60L);
		}
	}

	// -------------------------------------------------------------- helpers

	/** Whole blocks, because a HUD reading a fraction of a block is noise. */
	private static String format(double x, double y, double z) {
		return String.format(Locale.ROOT, "%.0f %.0f %.0f", Math.floor(x), Math.floor(y), Math.floor(z));
	}

	private static boolean isNether(Minecraft mc) {
		return mc != null && mc.level != null && mc.level.dimension().equals(Level.NETHER);
	}
}
