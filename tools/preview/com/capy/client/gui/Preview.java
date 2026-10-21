package com.capy.client.gui;

import com.capy.client.module.Category;
import com.capy.client.module.modules.antiadmin.AdminAlert;
import com.capy.client.module.modules.antiadmin.AdminDetector;
import com.capy.client.module.modules.antiadmin.AlertRenderer;
import com.capy.client.module.modules.antiadmin.Evidence;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.AntiAdminModule;
import com.capy.client.module.modules.SwingSpeedModule;
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
 * It runs the real {@link GuiFrame} and the real components against a
 * Java2D canvas and the bundled Inter files, so the result is what the client
 * draws - not a mock-up. The images are used to review proportions, spacing,
 * typography and contrast without a running game.
 *
 * Usage: java com.capy.client.gui.Preview <outputDir> <projectRoot>
 */
public final class Preview {
	private static final Path ROOT = Path.of(System.getProperty("capy.root", "."));
	private static final Path FONT_DIR = ROOT.resolve("src/client/resources/assets/capy/font");
	private static final Path LANG = ROOT.resolve("src/main/resources/assets/capy/lang/en_us.json");

	public static void main(String[] args) throws Exception {
		Path out = Path.of(args.length > 0 ? args[0] : "/tmp/kilo/preview");
		Files.createDirectories(out);

		loadLanguage();
		UiTheme.setPalette("sakura");

		ModuleManager modules = ModuleManager.get();
		modules.register(new SwingSpeedModule());
		modules.register(new AntiAdminModule());
		modules.register(new com.capy.client.module.modules.HudModule());

		SwingType type = new SwingType();

		// 1. The default view at a desktop resolution.
		GuiFrame frame = new GuiFrame();
		frame.resize(1920, 1080);
		settle(frame, type, 1920, 1080, 90);
		save(out.resolve("01-overview-1920x1080.png"), frame, type, 1920, 1080);

		// 2. The Anti Admin module with its settings open.
		GuiFrame anti = new GuiFrame();
		anti.resize(1920, 1080);
		anti.setCategory(Category.MISC.ordinal());
		anti.expandCard(Category.MISC, 0);
		settle(anti, type, 1920, 1080, 90);
		save(out.resolve("02-anti-admin-expanded.png"), anti, type, 1920, 1080);

		// 3. A smaller window.
		GuiFrame small = new GuiFrame();
		small.resize(1280, 720);
		settle(small, type, 1280, 720, 90);
		save(out.resolve("03-overview-1280x720.png"), small, type, 1280, 720);

		// 4. A very small window.
		GuiFrame tiny = new GuiFrame();
		tiny.resize(960, 540);
		settle(tiny, type, 960, 540, 90);
		save(out.resolve("04-overview-960x540.png"), tiny, type, 960, 540);

		// 5. An empty category.
		GuiFrame empty = new GuiFrame();
		empty.resize(1600, 900);
		empty.setCategory(Category.VISUAL.ordinal());
		settle(empty, type, 1600, 900, 60);
		save(out.resolve("05-empty-category.png"), empty, type, 1600, 900);

		// 6. The search state.
		GuiFrame search = new GuiFrame();
		search.resize(1600, 900);
		search.setSearch("swing");
		settle(search, type, 1600, 900, 60);
		save(out.resolve("06-search.png"), search, type, 1600, 900);

		// 7. The opening transition, a few frames in.
		GuiFrame opening = new GuiFrame();
		opening.resize(1600, 900);
		for (int i = 0; i < 8; i++) {
			opening.render(new SwingCanvas(1600, 900), type, 800, 400, 1f, 0.12f + i * 0.05f);
		}
		save(out.resolve("07-opening.png"), opening, type, 1600, 900);

		// 8. The Anti Admin alert at the different stages of its sequence.
		renderAlert(out.resolve("08-alert-stage1.png"), type, 1920, 1080, 120, Evidence.NEARBY);
		renderAlert(out.resolve("09-alert-stage2.png"), type, 1920, 1080, 420, Evidence.NEARBY);
		renderAlert(out.resolve("10-alert-hold.png"), type, 1920, 1080, 1600, Evidence.NEARBY);
		renderAlert(out.resolve("11-alert-exit.png"), type, 1920, 1080, 3600, Evidence.NEARBY);
		renderAlert(out.resolve("12-alert-online.png"), type, 1920, 1080, 1600, Evidence.ONLINE);

		System.out.println("preview written to " + out.toAbsolutePath());
	}

	/** Renders the alert at a given point of its timeline. */
	private static void renderAlert(Path file, SwingType type, int w, int h, long at, Evidence evidence)
			throws Exception {
		SwingCanvas canvas = new SwingCanvas(w, h);
		// Stand in for the game behind the HUD, so the translucent parts of
		// the alert can be judged.
		canvas.fill(0, 0, w, h, 0xFF2E3A44);
		canvas.fill(0, h / 2, w, h, 0xFF243026);
		AdminDetector.Match match = new AdminDetector.Match(
				java.util.UUID.nameUUIDFromBytes("preview".getBytes()),
				evidence == Evidence.NEARBY ? "Notch" : "CookieAdmin",
				evidence == Evidence.NEARBY ? "Notch" : "CookieAdmin",
				evidence == Evidence.NEARBY ? 12.4 : -1.0,
				null,
				evidence);
		long start = System.currentTimeMillis() - at;
		AdminAlert alert = new AdminAlert(match, start);
		float alpha = alert.alpha(System.currentTimeMillis());
		// Only the burst is alive: the module never fills the alert pool.
		com.capy.client.gui.UiPetals petals = new com.capy.client.gui.UiPetals();
		petals.burst(AlertRenderer.portraitCenterX(w), AlertRenderer.portraitCenterY(h), 9, 1.2f);
		for (int i = 0; i < Math.max(1, at / 20); i++) {
			petals.update(1f, w, h);
		}
		AlertRenderer.renderFaceAlert(canvas, type.regular(), type.medium(), type.display(), null,
				petals, w, h, alert, alpha);
		AlertRenderer.renderNotification(canvas, type.regular(), type.medium(), w, h, alert, alpha);
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

	/** Runs the frame for a while so the animations reach their resting state. */
	private static void settle(GuiFrame frame, SwingType type, int w, int h, int frames) {
		SwingCanvas canvas = new SwingCanvas(w, h);
		for (int i = 0; i < frames; i++) {
			frame.render(canvas, type, w / 2, h / 2, 1f, 1f);
		}
		SwingCanvas.fills = 0;
		frame.render(canvas, type, w / 2, h / 2, 1f, 1f);
		System.out.println("frame draw calls at " + w + "x" + h + ": " + SwingCanvas.fills);
	}

	private static void save(Path file, GuiFrame frame, SwingType type, int w, int h) throws Exception {
		SwingCanvas canvas = new SwingCanvas(w, h);
		frame.render(canvas, type, w / 2, h / 2, 1f, 1f);
		ImageIO.write(canvas.image(), "png", file.toFile());
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

		SwingType() throws Exception {
			BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = probe.createGraphics();
			this.regular = new SwingText(load("inter-400.ttf", 9f), g);
			this.medium = new SwingText(load("inter-500.ttf", 9f), g);
			this.bold = new SwingText(load("inter-600.ttf", 9f), g);
			this.display = new SwingText(load("inter-700.ttf", 12f), g);
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
	}

}
