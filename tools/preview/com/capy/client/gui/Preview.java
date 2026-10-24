package com.capy.client.gui;

import com.capy.client.module.Category;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.AntiAdminModule;
import com.capy.client.module.modules.CustomKnifeModule;
import com.capy.client.module.modules.SwingSpeedModule;
import com.capy.client.module.modules.WeatherNotifierModule;
import com.capy.client.module.modules.antiadmin.AdminAlert;
import com.capy.client.module.modules.antiadmin.AdminDetector;
import com.capy.client.module.modules.antiadmin.AlertRenderer;
import com.capy.client.module.modules.esp.PlayerEspModule;
import com.capy.client.module.modules.hud.HudElements;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Offline renderer for the Capy interface.
 *
 * It runs the real {@link GuiFrame}, the real components and the real alert
 * renderer against a Java2D canvas and the bundled Inter files, so the images
 * are what the client draws - not a mock-up. The images are used to review
 * proportions, spacing, typography, contrast and the alert timeline without a
 * running game.
 *
 * Usage: java com.capy.client.gui.Preview [outputDir]
 */
public final class Preview {
	private static final Path ROOT = Path.of(System.getProperty("capy.root", "."));
	private static final Path FONT_DIR = ROOT.resolve("src/client/resources/assets/capy/font");
	private static final Path LANG = ROOT.resolve("src/main/resources/assets/capy/lang/en_us.json");

	public static void main(String[] args) throws Exception {
		Path out = Path.of(args.length > 0 ? args[0] : "/tmp/capy-preview");
		Files.createDirectories(out);

		loadLanguage();
		UiTheme.setPalette("sakura");

		ModuleManager modules = ModuleManager.get();
		modules.register(new SwingSpeedModule());
		modules.register(new CustomKnifeModule());
		modules.register(new WeatherNotifierModule());
		modules.register(new AntiAdminModule());
		// Only the player overlay: the other two resolve block tags, which needs
		// the game's registries to be bootstrapped and this renderer runs without
		// them. The card, the settings and the layout are shared, so one overlay
		// is enough to review the whole group.
		modules.register(new PlayerEspModule());
		HudElements.register(modules);

		SwingType type = new SwingType();

		save(out.resolve("01-overview-1920x1080.png"), type, 1920, 1080);
		save(out.resolve("02-overview-1280x720.png"), type, 1280, 720);
		save(out.resolve("03-overview-960x540.png"), type, 960, 540);
		save(out.resolve("04-overview-2560x1440.png"), type, 2560, 1440);
		save(out.resolve("05-category-visual.png"), type, 1600, 900, Category.VISUAL.ordinal());
		// The Custom Knife card with its settings open: the choice row is the
		// only control in the interface that is neither a slider nor a switch.
		saveExpanded(out.resolve("06-visual-custom-knife.png"), type, 1600, 900,
				Category.VISUAL.ordinal(), 1);
		saveExpanded(out.resolve("07-world-weather.png"), type, 1600, 900,
				Category.WORLD.ordinal(), 0);
		// A world overlay with its settings open: colour, range, the two layers
		// and the through-walls switch all belong to the shared ESP base.
		saveExpanded(out.resolve("08-visual-player-esp.png"), type, 1600, 900,
				Category.VISUAL.ordinal(), 2);

		// The alert at the points of its timeline that matter: the face in,
		// the peak, the bottom of the travel, the hand over to the wording,
		// the two text lines, and the last frames before it is gone.
		for (long at : new long[] {60L, 300L, 700L, 1000L, 1200L, 1350L, 1450L, 1499L, 1600L, 2200L, 3800L, 4250L }) {
			renderAlert(out.resolve(String.format("alert-%04dms.png", at)), type, 1920, 1080, at);
		}

		System.out.println("preview written to " + out.toAbsolutePath());
	}

	/** Renders the whole window once it has settled. */
	private static void save(Path file, SwingType type, int w, int h) throws Exception {
		save(file, type, w, h, 0);
	}

	private static void save(Path file, SwingType type, int w, int h, int category) throws Exception {
		GuiFrame frame = new GuiFrame();
		frame.resize(w, h);
		frame.setCategory(category);
		SwingCanvas canvas = new SwingCanvas(w, h);
		for (int i = 0; i < 90; i++) {
			frame.render(canvas, type, w / 2, h / 2, 1f, 1f);
		}
		SwingCanvas.fills = 0;
		frame.render(canvas, type, w / 2, h / 2, 1f, 1f);
		System.out.println(w + "x" + h + ": " + SwingCanvas.fills + " draw calls");
		ImageIO.write(canvas.image(), "png", file.toFile());
	}

	/** Renders one category with one card's settings open. */
	private static void saveExpanded(Path file, SwingType type, int w, int h, int category, int card)
			throws Exception {
		GuiFrame frame = new GuiFrame();
		frame.resize(w, h);
		frame.setCategory(category);
		frame.expandCard(Category.values()[category], card);
		SwingCanvas canvas = new SwingCanvas(w, h);
		for (int i = 0; i < 90; i++) {
			frame.render(canvas, type, w / 2, h / 2, 1f, 1f);
		}
		ImageIO.write(canvas.image(), "png", file.toFile());
	}

	/** Renders the alert at a given point of its timeline over a stand-in world. */
	private static void renderAlert(Path file, SwingType type, int w, int h, long at) throws Exception {
		SwingCanvas canvas = new SwingCanvas(w, h);
		// A rough stand-in for the game behind the HUD, so the face can be
		// judged against something with structure instead of flat grey.
		canvas.fill(0, 0, w, h / 2, 0xFF6E8FA8);
		canvas.fill(0, h / 2, w, h, 0xFF4E6B45);
		for (int i = 0; i < 12; i++) {
			int bx = (i * 173) % w;
			int by = h / 2 + (i * 47) % (h / 2);
			canvas.fill(bx, by, bx + 120, by + 90, 0xFF7A6A4E);
		}
		AdminDetector.Match match = new AdminDetector.Match(
				java.util.UUID.nameUUIDFromBytes("preview".getBytes(StandardCharsets.UTF_8)),
				"CookieAdmin", "CookieAdmin", 12.4, 0L, null);
		AdminAlert alert = new AdminAlert(match, AdminAlert.Kind.NEARBY,
				System.currentTimeMillis() - at);
		AlertRenderer.renderFaceAlert(canvas, type, null, w, h, alert);
		AlertRenderer.renderNotification(canvas, type, w, h, alert,
				alert.alpha(System.currentTimeMillis()));
		ImageIO.write(canvas.image(), "png", file.toFile());
	}

	// ------------------------------------------------------------------ setup

	private static void loadLanguage() throws Exception {
		if (!Files.exists(LANG)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(LANG, StandardCharsets.UTF_8)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
				UiLabels.override(entry.getKey(), entry.getValue().getAsString());
			}
		}
	}

	// ------------------------------------------------------------- Java2D

	/** A {@link UiCanvas} that rasterises into a BufferedImage. */
	static final class SwingCanvas implements UiCanvas {
		/** Counts the draw calls, so the cost of a frame can be measured. */
		static long fills;
		private final BufferedImage image;
		final Graphics2D g;
		private final List<Shape> clips = new ArrayList<>();

		SwingCanvas(int width, int height) {
			this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			this.g = image.createGraphics();
			this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		}

		BufferedImage image() {
			return image;
		}

		@Override
		public void fill(int x1, int y1, int x2, int y2, int argb) {
			fills++;
			if (x2 <= x1 || y2 <= y1) {
				return;
			}
			g.setColor(new Color(argb, true));
			g.fillRect(x1, y1, x2 - x1, y2 - y1);
		}

		@Override
		public void pushClip(int x, int y, int width, int height) {
			Shape old = g.getClip();
			Rectangle rect = new Rectangle(x, y, Math.max(0, width), Math.max(0, height));
			Shape next = old == null ? rect : old.getBounds().createIntersection(rect);
			g.setClip(next);
			clips.add(old);
		}

		@Override
		public void popClip() {
			if (clips.isEmpty()) {
				return;
			}
			g.setClip(clips.remove(clips.size() - 1));
		}
	}

	/** A {@link UiText} backed by Java2D and the bundled Inter files. */
	static final class SwingText implements UiText {
		private final Font font;
		private final FontMetrics metrics;

		SwingText(Font font, Graphics2D g) {
			this.font = font;
			this.metrics = g.getFontMetrics(font);
		}

		@Override
		public int width(String text) {
			return text == null ? 0 : Math.round(metrics.stringWidth(text));
		}

		@Override
		public int lineHeight() {
			return metrics.getHeight();
		}

		@Override
		public void draw(UiCanvas canvas, String text, int x, int y, int argb) {
			if (text == null || text.isEmpty() || !(canvas instanceof SwingCanvas sc)) {
				return;
			}
			sc.g.setFont(font);
			sc.g.setColor(new Color(argb, true));
			sc.g.drawString(text, x, y + metrics.getAscent());
		}
	}

	/** The four Inter weights used by the interface. */
	static final class SwingType implements UiType {
		private final SwingText regular;
		private final SwingText medium;
		private final SwingText bold;
		private final SwingText display;
		private final SwingText headline;

		SwingType() throws Exception {
			BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = probe.createGraphics();
			this.regular = new SwingText(load("inter-400.ttf", 9f), g);
			this.medium = new SwingText(load("inter-500.ttf", 9f), g);
			this.bold = new SwingText(load("inter-600.ttf", 9f), g);
			this.display = new SwingText(load("inter-700.ttf", 12f), g);
			this.headline = new SwingText(load("inter-700.ttf", 17f), g);
		}

		private static Font load(String name, float size) throws Exception {
			File file = FONT_DIR.resolve(name).toFile();
			Font font = Font.createFont(Font.TRUETYPE_FONT, file);
			return font.deriveFont(size);
		}

		@Override
		public UiText regular() {
			return regular;
		}

		@Override
		public UiText medium() {
			return medium;
		}

		@Override
		public UiText bold() {
			return bold;
		}

		@Override
		public UiText display() {
			return display;
		}

		@Override
		public UiText headline() {
			return headline;
		}
	}
}
