package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import com.capy.client.gui.UiType;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * HUD — a small, optional information panel in a corner of the screen.
 *
 * Every line is computed from information the client really has:
 * <ul>
 *   <li><b>FPS</b> — the client's own frame rate;</li>
 *   <li><b>Ping</b> — the latency the server reports for the player;</li>
 *   <li><b>Coords</b> — the position and the direction the player faces;</li>
 *   <li><b>Biome</b> — the biome the player stands in;</li>
 *   <li><b>Cosmetics</b> — the counter the server itself puts on the
 *       scoreboard, if it has one. Nothing is invented: when the server shows
 *       no such line the field reads "-";</li>
 *   <li><b>Time</b> — how long the current session has been running.</li>
 * </ul>
 *
 * There is deliberately no armour display: the HUD reports what the client
 * can measure, it does not duplicate what the vanilla HUD already shows.
 */
public class HudModule extends Module implements Configurable {
	private final BooleanSetting fps = setting(new BooleanSetting("fps", true));
	private final BooleanSetting ping = setting(new BooleanSetting("ping", true));
	private final BooleanSetting coordinates = setting(new BooleanSetting("coordinates", false));
	private final BooleanSetting biome = setting(new BooleanSetting("biome", false));
	private final BooleanSetting cosmetics = setting(new BooleanSetting("cosmetics", false));
	private final BooleanSetting time = setting(new BooleanSetting("time", false));
	/** Which corner the panel sits in: 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
	private final SliderSetting position = setting(new SliderSetting("position", 0, 0, 3));

	private static final UiType TYPE = new McType();

	private long sessionStart = System.currentTimeMillis();
	/** Reused between frames so the panel does not allocate every tick. */
	private final java.util.List<String[]> lines = new java.util.ArrayList<>(6);

	public HudModule() {
		super(Category.HUD, "hud");
	}

	/** Draws the panel over the game. */
	public void onHudRender(GuiGraphics g, int screenW, int screenH) {
		if (!isEnabled()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		UiCanvas canvas = McCanvas.of(g);
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();

		String[][] lines = buildLines(mc);
		if (lines.length == 0) {
			return;
		}

		int rowH = 13;
		int pad = 8;
		int labelW = 0;
		int valueW = 0;
		for (String[] line : lines) {
			labelW = Math.max(labelW, regular.width(line[0]));
			valueW = Math.max(valueW, medium.width(line[1]));
		}
		int w = labelW + valueW + pad * 3;
		int h = lines.length * rowH + pad * 2 - 2;

		int margin = 10;
		int corner = position.get();
		int x = (corner == 1 || corner == 3) ? screenW - w - margin : margin;
		int y = (corner >= 2) ? screenH - h - margin : margin;

		Ui.shadow(canvas, x, y, w, h, UiTheme.RADIUS);
		Ui.surface(canvas, x, y, w, h, UiTheme.RADIUS,
				Ui.withAlpha(UiTheme.PANEL_ALT, 0xE0), Ui.withAlpha(UiTheme.PANEL_TOP, 0xE0));
		Ui.outline(canvas, x, y, w, h, UiTheme.RADIUS, Ui.withAlpha(UiTheme.BORDER, 0xB0));

		for (int i = 0; i < lines.length; i++) {
			int ly = y + pad + i * rowH;
			regular.draw(canvas, lines[i][0], x + pad, ly, UiTheme.TEXT_3);
			medium.draw(canvas, lines[i][1], x + w - pad - medium.width(lines[i][1]), ly, UiTheme.TEXT);
		}
	}

	/** Collects the enabled lines. */
	private String[][] buildLines(Minecraft mc) {
		lines.clear();
		if (fps.value()) {
			lines.add(new String[]{"FPS", String.valueOf(mc.getFps())});
		}
		if (ping.value()) {
			lines.add(new String[]{"PING", latency(mc) + " ms"});
		}
		if (coordinates.value()) {
			lines.add(new String[]{"XYZ", coordinates(mc)});
		}
		if (biome.value()) {
			lines.add(new String[]{"BIOME", biome(mc)});
		}
		if (cosmetics.value()) {
			lines.add(new String[]{"COSMETICS", cosmetics(mc)});
		}
		if (time.value()) {
			lines.add(new String[]{"TIME", sessionTime()});
		}
		return lines.toArray(new String[0][]);
	}

	/** The latency the server reports for the local player. */
	private static String latency(Minecraft mc) {
		if (mc.getConnection() == null || mc.player == null) {
			return "-";
		}
		PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return info == null ? "-" : String.valueOf(info.getLatency());
	}

	/** The position and the direction the player faces. */
	private static String coordinates(Minecraft mc) {
		Player player = mc.player;
		if (player == null) {
			return "-";
		}
		return String.format(Locale.ROOT, "%.0f %.0f %.0f %s",
				player.getX(), player.getY(), player.getZ(), facing(player));
	}

	private static String facing(Player player) {
		var dir = player.getDirection();
		return dir == null ? "" : dir.getName();
	}

	/** The biome the player stands in. */
	private static String biome(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return "-";
		}
		var holder = mc.level.getBiome(mc.player.blockPosition());
		var key = holder.unwrapKey();
		if (key == null) {
			return "-";
		}
		String name = key.get().identifier().getPath();
		return name.replace('_', ' ');
	}

	/**
	 * The cosmetics counter the server publishes on the scoreboard. The server
	 * owns that number; the client only reads the line it already renders, and
	 * shows "-" when there is none.
	 */
	private static String cosmetics(Minecraft mc) {
		String line = scoreboardLine(mc, "cosmetic");
		if (line == null) {
			line = scoreboardLine(mc, "costume");
		}
		if (line == null) {
			return "-";
		}
		for (int i = 0; i < line.length(); i++) {
			if (Character.isDigit(line.charAt(i))) {
				int end = i;
				while (end < line.length() && Character.isDigit(line.charAt(end))) {
					end++;
				}
				return line.substring(i, end);
			}
		}
		return "-";
	}

	/** Finds the scoreboard line that contains the given word. */
	private static String scoreboardLine(Minecraft mc, String keyword) {
		if (mc.level == null) {
			return null;
		}
		Scoreboard board = mc.level.getScoreboard();
		if (board == null) {
			return null;
		}
		Objective objective = board.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) {
			return null;
		}
		for (var score : board.listPlayerScores(objective)) {
			String owner = score.owner();
			PlayerTeam team = board.getPlayersTeam(owner);
			String text = team != null && team.getDisplayName() != null
					? team.getDisplayName().getString()
					: owner;
			if (text.toLowerCase(Locale.ROOT).contains(keyword)) {
				return text;
			}
		}
		return null;
	}

	/** How long the current session has been running. */
	private String sessionTime() {
		long seconds = Math.max(0, (System.currentTimeMillis() - sessionStart) / 1000);
		return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
	}

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			sessionStart = System.currentTimeMillis();
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		fps.set(ConfigValues.boolValue(json, "fps", true));
		ping.set(ConfigValues.boolValue(json, "ping", true));
		coordinates.set(ConfigValues.boolValue(json, "coordinates", false));
		biome.set(ConfigValues.boolValue(json, "biome", false));
		cosmetics.set(ConfigValues.boolValue(json, "cosmetics", false));
		time.set(ConfigValues.boolValue(json, "time", false));
		position.set(Math.max(0, Math.min(3, ConfigValues.intValue(json, "position", 0))));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("fps", fps.value());
		json.addProperty("ping", ping.value());
		json.addProperty("coordinates", coordinates.value());
		json.addProperty("biome", biome.value());
		json.addProperty("cosmetics", cosmetics.value());
		json.addProperty("time", time.value());
		json.addProperty("position", position.get());
	}
}
