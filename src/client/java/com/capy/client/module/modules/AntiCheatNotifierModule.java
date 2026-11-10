package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.anticheat.AntiCheatSignatures;
import com.capy.client.module.modules.antiadmin.PacketSignals;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Anticheat Notifier — names the anticheat when it can, and says so when it
 * cannot find one at all.
 *
 * <h2>What it can and cannot know</h2>
 * An anticheat never announces itself: there is no "Grim is running" packet, and
 * a server whose checks are switched off looks exactly like one whose checks are
 * running. What the client <em>can</em> read is everything the server puts its
 * own name in:
 *
 * <ul>
 *   <li>the address typed into the multiplayer list, which for most networks
 *       carries the anticheat's name ({@code grim.example.com});</li>
 *   <li>the display name of that list entry, which is free text;</li>
 *   <li>the server's own chat, where a plugin that broadcasts under its own
 *       name ({@code "Vulcan » ..."}, {@code "Grim » ..."}) names itself;</li>
 *   <li>the manual override, for servers where none of the above does.</li>
 * </ul>
 *
 * <h2>Why a verdict is retracted, not repeated</h2>
 * The failure this module must not have is telling a player there is nothing
 * watching and then being wrong: the player acts on it and gets removed. So the
 * verdict is not a one-way announcement. The evidence stream is re-read every
 * {@value #CHECK_INTERVAL} ticks, and the first piece of evidence that the
 * server does enforce - an anticheat naming itself in chat, or the server's own
 * movement kick - immediately withdraws the banner and marks the address as
 * protected for the rest of the session. Rejoining the same server therefore
 * never claims "off" twice.
 *
 * <p>A kick is also the one piece of proof the player themselves supplied: the
 * server refused something, which means something checked it. The movement
 * words in {@link #MOVEMENT_WORDS} are exactly the messages vanilla and the
 * common anticheats use for that.
 *
 * <h2>Why a module of its own</h2>
 * It is a notifier, not a detector of players: it shares the Anti Admin category
 * because both answer a question about the server rather than about the world,
 * but it has no watchlist, no alerts and no packet layer of its own.
 */
public class AntiCheatNotifierModule extends Module implements Configurable, Tickable {
	/** How often the evidence is re-read, in ticks. */
	private static final int CHECK_INTERVAL = 40;
	/**
	 * How long the module has to have been on before it judges a server.
	 *
	 * <p>Three seconds. The evidence it reads - the server address, the list
	 * entry, the server's own chat - is all available immediately, but a module
	 * that is switched on and off while the user is arranging their settings
	 * used to announce a verdict on every enable, because the first evaluation
	 * ran one tick after the toggle with no evidence read yet and concluded
	 * "nothing found". Nothing found is not the same as proven absent, so the
	 * judgement now waits for the module to have actually been running.</p>
	 */
	private static final int SETTLE_TICKS = 60;

	/** The two banner lines, in Minecraft's own font. */
	private static final String LINE_TITLE = "the anticheat is off";
	private static final String LINE_SUB = "Have fun";

	/** Name recorded when the server enforced movement itself. */
	public static final String MOVEMENT_CHECK = "server movement check";

	/**
	 * What a server says when it refuses to let the client move where it asked.
	 *
	 * <p>These are the vanilla server's own kick and correction messages plus the
	 * ones the common anticheats print. Seeing any of them means something is
	 * enforcing movement, whether or not it ever names itself.
	 */
	private static final String[] MOVEMENT_WORDS = {
			"flying is not enabled", "flying is disabled", "kicked for flying",
			"moved too quickly", "moved wrongly", "invalid move", "illegal move",
			"failed movement", "movement check"
	};

	/** Most addresses whose verdict has been learned in one session. */
	private static final int MAX_LEARNED = 64;

	/**
	 * What the server has already proven about itself, per address.
	 *
	 * <p>Static on purpose: it has to survive the player being kicked and
	 * rejoining, which is the exact moment the memory is worth anything.
	 */
	private static final Map<String, String> LEARNED = new ConcurrentHashMap<>();
	/**
	 * Addresses whose verdict has already been announced this session.
	 *
	 * <p>Keyed by address, not by module state: switching the module off and on
	 * again is the same server and the same verdict, and telling the player a
	 * second time - which is what a state-only edge trigger did - reads as a
	 * fresh detection. Static so the memory survives a kick and a rejoin too.</p>
	 */
	private static final java.util.Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

	private final ChoiceSetting force = setting(new ChoiceSetting("force", 0, "auto", "off", "on"));
	private final BooleanSetting unknownServers = setting(new BooleanSetting("unknown_servers", true));
	private final BooleanSetting chatEvidence = setting(new BooleanSetting("chat_evidence", true));
	private final BooleanSetting sound = setting(new BooleanSetting("sound", true));
	private final SliderSetting volume = setting(new SliderSetting("volume", 70, 10, 100));
	private final BooleanSetting banner = setting(new BooleanSetting("banner", true));
	private final SliderSetting hold = setting(new SliderSetting("hold", 5, 2, 12));
	private final ColorSetting color = setting(new ColorSetting("color", 0xFF7CF0C0));
	/** Banner size as a percentage of vanilla's own font size. */
	private final SliderSetting textSize = setting(new SliderSetting("text_size", 200, 100, 400));

	/** Ticks until the next evaluation. */
	private int countdown;
	/** The host the current verdict belongs to, so a new server re-announces. */
	private String judgedHost = "";
	/** The anticheat that was found, or "" when none was. */
	private String fingerprint = "";
	/** How many chat lines have been read. */
	private long textSeq;
	/** When the banner was raised, in {@link System#currentTimeMillis()} units. */
	private long shownAt;
	/** How long the banner stays up, in milliseconds. */
	private long holdMs = 5000L;
	/** The verdict: true when the server looks unprotected. */
	private boolean unprotected;
	/** Ticks the module has been continuously enabled. */
	private int enabledTicks;

	public AntiCheatNotifierModule() {
		super(Category.MISC, "anticheat_notifier");
	}

	@Override
	protected void onToggle(boolean state) {
		countdown = 0;
		enabledTicks = 0;
		judgedHost = "";
		fingerprint = "";
		shownAt = 0L;
		unprotected = false;
		// Chat that arrived before the module was switched on is not evidence
		// about anything this session did, and replaying it would announce a
		// verdict the player never saw the cause of.
		textSeq = PacketSignals.textSequence();
	}

	// ------------------------------------------------------------------ tick

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		if (mc == null || mc.player == null || mc.level == null) {
			// Leaving a world drops the verdict: the next server is a different
			// server and must be judged on its own evidence. What was learned is
			// kept, because rejoining the same address is the same server.
			judgedHost = "";
			shownAt = 0L;
			return;
		}
		if (enabledTicks < Integer.MAX_VALUE) {
			enabledTicks++;
		}
		if (--countdown > 0) {
			return;
		}
		countdown = CHECK_INTERVAL;
		judge(mc);
	}

	/**
	 * Reads the server's own strings and decides whether to announce.
	 *
	 * <p>The announcement is edge triggered on the host: joining the same server
	 * twice in a session says it twice (the second join is a new connection and
	 * the user may well have forgotten), but staying on it does not repeat.
	 */
	private void judge(Minecraft mc) {
		String host = hostOf(mc);
		String listName = nameOf(mc);
		readChat(host);

		String fromName = AntiCheatSignatures.match(host);
		if (fromName == null) {
			fromName = AntiCheatSignatures.match(listName);
		}
		if (fromName != null) {
			learn(host, fromName);
		}
		String learned = host.isEmpty() ? null : LEARNED.get(host);
		String found = fromName != null ? fromName : learned;
		fingerprint = found == null ? "" : found;

		String mode = force.option();
		boolean next;
		if ("on".equals(mode)) {
			// Forced on means "this server does have one".
			next = false;
		} else if ("off".equals(mode)) {
			next = true;
		} else if (found != null) {
			next = false;
		} else {
			// No evidence: either it is anarchy, or the network does not name its
			// anticheat anywhere the client can see. The setting decides which of
			// those the user wants to hear about.
			next = unknownServers.value();
		}

		boolean withdrawn = unprotected && !next;
		unprotected = next;
		if (withdrawn) {
			// Something proved the earlier verdict wrong. Take the banner down
			// instead of leaving a wrong claim on screen.
			shownAt = 0L;
		}
		if (!unprotected) {
			judgedHost = host;
			return;
		}
		if (enabledTicks < SETTLE_TICKS) {
			// Not judged yet: the module has only just been switched on, and a
			// verdict reached before it has read anything is a guess.
			return;
		}
		if (host.equals(judgedHost)) {
			return;
		}
		judgedHost = host;
		// Once per address per session. A toggle, a rejoin or a second world
		// load is not new evidence, and re-announcing on one is what made
		// spamming the module's own switch look like a detection.
		if (!ANNOUNCED.add(host)) {
			return;
		}
		announce();
	}

	/**
	 * Reads chat lines the module has not seen yet and keeps what they prove.
	 *
	 * <p>The lines are read, never consumed: Anti Admin drains its own packet
	 * queue and would lose its input if this shared it.
	 */
	private void readChat(String host) {
		if (!chatEvidence.value()) {
			// Skip what arrived while the setting was off, so switching it back
			// on cannot announce a verdict about an old message.
			textSeq = PacketSignals.textSequence();
			return;
		}
		for (PacketSignals.TextLine line : PacketSignals.textAfter(textSeq)) {
			textSeq = Math.max(textSeq, line.seq());
			if (!line.system()) {
				// Only the server's own text counts. A player typing the name of
				// an anticheat is not evidence that it is installed, and treating
				// it as such would let one person in chat silence the module.
				continue;
			}
			String text = line.text() == null ? "" : line.text();
			String name = AntiCheatSignatures.match(text);
			if (name != null) {
				learn(host, name);
				return;
			}
			if (enforcesMovement(text)) {
				learn(host, MOVEMENT_CHECK);
				return;
			}
		}
	}

	/** True when the line is a server refusing a movement. */
	private static boolean enforcesMovement(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (String word : MOVEMENT_WORDS) {
			if (lower.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/** Records what a server proved about itself, bounding the map. */
	private static void learn(String host, String name) {
		if (host == null || host.isEmpty() || name == null || name.isEmpty()) {
			return;
		}
		if (LEARNED.size() >= MAX_LEARNED && !LEARNED.containsKey(host)) {
			LEARNED.clear();
		}
		LEARNED.put(host, name);
	}

	/** The banner and the chime, once. */
	private void announce() {
		shownAt = System.currentTimeMillis();
		holdMs = Math.max(2000L, Math.round(hold.real() * 1000.0));
		if (!sound.value()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.getSoundManager() == null) {
				return;
			}
			// One bright chime at full pitch. It is the challenge-complete
			// sound because it is the one short, clean, unmistakably positive
			// note in the game; anything longer competes with the world.
			float gain = (float) (volume.real() / 100.0);
			mc.getSoundManager().play(SimpleSoundInstance.forUI(
					SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, Math.max(0.05f, gain)));
		} catch (RuntimeException | LinkageError failure) {
			com.capy.client.util.Guard.report("anticheat sound", failure);
			// A missing sound must not cost the banner.
		}
	}

	// ------------------------------------------------------------------- hud

	/**
	 * Draws the banner in Minecraft's own font, in the middle of the screen.
	 *
	 * <p>Deliberately not the client's bundled typefaces: this is a note about
	 * the server, and the request was for the vanilla look, so it is drawn with
	 * {@code mc.font} at the default GUI scale, with the drop shadow vanilla
	 * uses.
	 */
	public void onHudRender(GuiGraphics graphics, int screenW, int screenH) {
		if (!isEnabled() || !banner.value() || shownAt == 0L) {
			return;
		}
		long elapsed = System.currentTimeMillis() - shownAt;
		if (elapsed < 0 || elapsed >= holdMs) {
			shownAt = 0L;
			return;
		}
		float fadeIn = Math.min(1f, elapsed / 180f);
		float fadeOut = Math.min(1f, (holdMs - elapsed) / 320f);
		float alpha = Math.max(0f, Math.min(fadeIn, fadeOut));
		if (alpha <= 0.01f) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.font == null || graphics == null) {
			return;
		}
		Font font = mc.font;
		// The banner is the whole announcement, so it is drawn at twice
		// vanilla's size by default: at the game's own font size the wording is
		// the same size as the chat line beside it and reads as a footnote.
		float scale = Math.max(1f, Math.min(4f, (float) textSize.real() / 100f));
		int title = withAlpha(color.argb(), alpha);
		int sub = withAlpha(0xFFFFFFFF, alpha);
		int centerX = screenW / 2;
		int y = Math.round(screenH * 0.30f);
		int titleW = Math.round(font.width(LINE_TITLE) * scale);
		int subW = Math.round(font.width(LINE_SUB) * scale);
		int lineH = Math.round(font.lineHeight * scale);
		int gap = Math.round(3f * scale);
		int pad = Math.round(10f * scale);
		// One dark plate behind the two lines, so the wording survives a bright
		// sky as well as a cave.
		int plateH = lineH * 2 + gap + pad;
		int plateW = Math.max(titleW, subW) + pad * 2;
		int plate = withAlpha(0x000000, alpha * 0.42f);
		int plateTop = y - pad / 2;
		graphics.fill(centerX - plateW / 2, plateTop, centerX + plateW / 2, plateTop + plateH, plate);
		drawScaled(graphics, font, LINE_TITLE, centerX - titleW / 2f, y, title, scale);
		drawScaled(graphics, font, LINE_SUB, centerX - subW / 2f, y + lineH + gap, sub, scale);
	}

	/**
	 * Draws one line of vanilla text at a multiple of its own size.
	 *
	 * <p>Vanilla's font has a single size, so a larger banner can only be made
	 * by scaling the GUI matrix around the line. The matrix is pushed and popped
	 * around the call, so nothing else on screen is affected, and the anchor is
	 * the line's own top-left corner, which keeps the centring maths identical
	 * to an unscaled draw.</p>
	 */
	private static void drawScaled(GuiGraphics graphics, Font font, String text,
			float x, float y, int argb, float scale) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.drawString(font, text, 0, 0, argb, true);
		pose.popMatrix();
	}

	// -------------------------------------------------------------- readings

	/** The address of the server being played, lower case, or "". */
	private static String hostOf(Minecraft mc) {
		try {
			ServerData server = mc.getCurrentServer();
			if (server != null && server.ip != null && !server.ip.isBlank()) {
				return server.ip.trim().toLowerCase(Locale.ROOT);
			}
		} catch (RuntimeException | LinkageError failure) {
			com.capy.client.util.Guard.report("anticheat host", failure);
			// Single player, a replaced server list, an old build: no host.
		}
		return "";
	}

	/** The display name of the server list entry, or "". */
	private static String nameOf(Minecraft mc) {
		try {
			ServerData server = mc.getCurrentServer();
			if (server != null && server.name != null) {
				return server.name.trim();
			}
		} catch (RuntimeException | LinkageError failure) {
			com.capy.client.util.Guard.report("anticheat name", failure);
			// Nothing to read.
		}
		return "";
	}

	/** The anticheat that was found for the current server, or "". */
	public String fingerprint() {
		return fingerprint;
	}

	/** True when the current server is judged to have no anticheat running. */
	public boolean unprotected() {
		return unprotected;
	}

	/**
	 * What the module currently believes about the server, for a HUD line or a
	 * debug read: an anticheat name, {@link #MOVEMENT_CHECK}, or "" when the
	 * server shows nothing.
	 */
	public String verdict() {
		return unprotected ? "" : fingerprint;
	}

	private static int withAlpha(int argb, float factor) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		force.select(ConfigValues.stringValue(json, "force", "auto"));
		unknownServers.set(ConfigValues.boolValue(json, "unknown_servers", true));
		chatEvidence.set(ConfigValues.boolValue(json, "chat_evidence", true));
		sound.set(ConfigValues.boolValue(json, "sound", true));
		volume.set(ConfigValues.intValue(json, "volume", 70));
		banner.set(ConfigValues.boolValue(json, "banner", true));
		hold.set(ConfigValues.intValue(json, "hold", 5));
		color.set(ConfigValues.intValue(json, "color", 0xFF7CF0C0));
		textSize.set(ConfigValues.intValue(json, "text_size", 200));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("force", force.option());
		json.addProperty("unknown_servers", unknownServers.value());
		json.addProperty("chat_evidence", chatEvidence.value());
		json.addProperty("sound", sound.value());
		json.addProperty("volume", volume.get());
		json.addProperty("banner", banner.value());
		json.addProperty("hold", hold.get());
		json.addProperty("color", color.argb());
		json.addProperty("text_size", textSize.get());
	}
}
