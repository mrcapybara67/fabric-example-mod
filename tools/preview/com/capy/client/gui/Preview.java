package com.capy.client.gui;

import com.capy.client.module.Category;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.ItemInspectModule;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
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
 * It runs the real {@link GuiFrame}, the real components and the bundled
 * Poppins files against a Java2D canvas. The images are used to review
 * proportions, spacing, typography and contrast without a running game.
 *
 * Usage: java com.capy.client.gui.Preview [outputDir]
 */
public final class Preview {
    private static final Path ROOT = Path.of(System.getProperty("capy.root", "."));
    private static final Path FONTS = ROOT.resolve("src/client/resources/assets/capy/font");
    private static final Path TEXT = ROOT.resolve("src/client/resources/assets/capy/text/en_us.json");

    private Preview() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "/tmp/capy-gui");
        Files.createDirectories(out);
        loadText();

        ItemInspectModule inspect = new ItemInspectModule();
        ModuleManager.get().register(inspect);
        register("freelook", Category.VISUAL);
        register("atmosphere", Category.VISUAL);
        register("custom_fov", Category.VISUAL);
        register("fullbright", Category.VISUAL);
        register("hand_aura", Category.VISUAL);
        register("smooth_camera", Category.VISUAL);
        register("block_overlay", Category.VISUAL);
        register("swing_speed", Category.VISUAL);
        register("amethyst_finder", Category.BASE_FINDER);
        register("chunk_reload", Category.BASE_FINDER);
        register("shaft_finder", Category.BASE_FINDER);
        register("fly", Category.WORLD);
        register("weather_notifier", Category.WORLD);
        register("boat_fly", Category.WORLD);
        register("player_esp", Category.DONUT_SMP);
        register("storage_esp", Category.DONUT_SMP);
        register("block_esp", Category.DONUT_SMP);
        register("freecam", Category.DONUT_SMP);
        register("anti_admin", Category.MISC);
        register("anti_vanish", Category.MISC);
        register("anticheat_notifier", Category.MISC);
        register("hud_fps", Category.HUD);
        register("hud_coords", Category.HUD);
        register("hud_biome", Category.HUD);
        inspect.setEnabled(true);
        setEnabled("player_esp", true);
        setEnabled("weather_notifier", true);
        setEnabled("anti_admin", true);
        setEnabled("hud_fps", true);

        SwingType type = new SwingType();
        save(out.resolve("01-overview-640x360.png"), type, 640, 360, null);
        save(out.resolve("02-settings-640x360.png"), type, 640, 360, f -> {
            f.setCategory(Category.VISUAL.ordinal());
            f.expandCard(Category.VISUAL, 0);
        });
        save(out.resolve("03-search-640x360.png"), type, 640, 360, f -> {
            f.setSearch("esp");
            searchFocused(f, true);
        });
        save(out.resolve("04-global-settings-640x360.png"), type, 640, 360,
                Preview::openGlobalSettings);
        save(out.resolve("05-overview-480x270.png"), type, 480, 270, null);
        save(out.resolve("06-overview-427x240.png"), type, 427, 240, null);
        save(out.resolve("08-settings-427x240.png"), type, 427, 240, f -> {
            f.setCategory(Category.VISUAL.ordinal());
            f.expandCard(Category.VISUAL, 0);
        });
        save(out.resolve("09-global-settings-427x240.png"), type, 427, 240,
                Preview::openGlobalSettings);
        System.out.println("wrote " + out.toAbsolutePath());
    }

    private static void searchFocused(GuiFrame frame, boolean focused) {
        try {
            java.lang.reflect.Field field = GuiFrame.class.getDeclaredField("search");
            field.setAccessible(true);
            ((UiSearch) field.get(frame)).setFocused(focused);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void openGlobalSettings(GuiFrame frame) {
        try {
            java.lang.reflect.Field field = GuiFrame.class.getDeclaredField("guiSettingsOpen");
            field.setAccessible(true);
            field.setBoolean(frame, true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setEnabled(String id, boolean enabled) {
        for (Module module : ModuleManager.get().modules()) {
            if (module.id().equals(id)) module.setEnabled(enabled);
        }
    }

    private static void register(String id, Category category) {
        ModuleManager.get().register(new PreviewModule(category, id));
    }

    private interface Setup { void apply(GuiFrame frame); }

    private static void save(Path file, SwingType type, int w, int h, Setup setup) throws Exception {
        GuiFrame frame = new GuiFrame();
        frame.resize(w, h);
        if (setup != null) setup.apply(frame);
        SwingCanvas warm = new SwingCanvas(w, h);
        float mx = w * 0.42f;
        float my = h * 0.42f;
        for (int i = 0; i < 120; i++) {
            frame.render(warm, type, mx, my, 1f, 1f);
        }
        // Put the pointer on the first card for the overview shots so hover is visible.
        if (setup == null) {
            var cards = frame.visibleCardsForTest();
            if (!cards.isEmpty()) {
                ModuleCard card = cards.get(0);
                mx = card.x() + card.width() * 0.5f;
                my = card.y() + card.height() * 0.5f;
                for (int i = 0; i < 90; i++) frame.render(warm, type, mx, my, 1f, 1f);
            }
        }
        SwingCanvas canvas = new SwingCanvas(w, h);
        canvas.world();
        frame.render(canvas, type, mx, my, 1f, 1f);
        ImageIO.write(canvas.image(), "png", file.toFile());
        System.out.println(file.getFileName() + ": " + canvas.draws + " fills");
    }

    private static void loadText() throws Exception {
        if (!Files.exists(TEXT)) return;
        try (Reader reader = Files.newBufferedReader(TEXT, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                UiLabels.override(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    private static final class PreviewModule extends Module {
        PreviewModule(Category category, String id) { super(category, id); }
    }

    static final class SwingCanvas implements UiCanvas {
        private final BufferedImage image;
        private final Graphics2D g;
        private final List<Shape> clips = new ArrayList<>();
        int draws;

        SwingCanvas(int width, int height) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        }

        BufferedImage image() { return image; }
        Graphics2D graphics() { return g; }

        void world() {
            g.setComposite(AlphaComposite.Src);
            g.setPaint(new GradientPaint(0, 0, new Color(0xFF7899B1), 0, image.getHeight(),
                    new Color(0xFF53694F)));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(new Color(0x557A684A, true));
            for (int i = 0; i < 24; i++) {
                int x = (i * 197) % (image.getWidth() + 200) - 100;
                int y = image.getHeight() / 2 + (i * 53) % Math.max(1, image.getHeight() / 2);
                g.fillRect(x, y, 150, 95);
            }
            g.setColor(new Color(0x33425238, true));
            g.fillRect(0, image.getHeight() / 2, image.getWidth(), image.getHeight() / 2);
        }

        @Override
        public void fill(int x1, int y1, int x2, int y2, int argb) {
            if (x2 <= x1 || y2 <= y1) return;
            draws++;
            g.setColor(new Color(argb, true));
            g.fillRect(x1, y1, x2 - x1, y2 - y1);
        }

        @Override
        public void pushClip(int x, int y, int width, int height) {
            Shape old = g.getClip();
            clips.add(old);
            Rectangle2D rect = new Rectangle2D.Float(x, y, Math.max(0, width), Math.max(0, height));
            g.setClip(old == null ? rect : old.getBounds2D().createIntersection(rect));
        }

        @Override
        public void popClip() {
            if (!clips.isEmpty()) g.setClip(clips.remove(clips.size() - 1));
        }

        @Override
        public int drawCalls() { return draws; }
    }

    static final class SwingText implements UiText {
        private final Font font;
        private final FontMetrics metrics;

        SwingText(Font font, Graphics2D g) {
            this.font = font;
            this.metrics = g.getFontMetrics(font);
        }

        @Override public int width(String text) { return text == null ? 0 : Math.round(metrics.stringWidth(text)); }
        @Override public int lineHeight() { return metrics.getHeight(); }

        @Override
        public void draw(UiCanvas canvas, String text, int x, int y, int argb) {
            if (text == null || text.isEmpty() || !(canvas instanceof SwingCanvas sc)) return;
            sc.g.setFont(font);
            sc.g.setColor(new Color(argb, true));
            sc.g.drawString(text, x, y + metrics.getAscent());
        }
    }

    static final class SwingType implements UiType {
        private final SwingText regular, medium, bold, display, headline;

        SwingType() throws Exception {
            BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = probe.createGraphics();
            regular = new SwingText(load("poppins-400.ttf", 8.5f), g);
            medium = new SwingText(load("poppins-500.ttf", 8.5f), g);
            bold = new SwingText(load("poppins-600.ttf", 8.5f), g);
            display = new SwingText(load("poppins-700.ttf", 11.5f), g);
            headline = new SwingText(load("poppins-700.ttf", 16f), g);
        }

        private static Font load(String name, float size) throws Exception {
            return Font.createFont(Font.TRUETYPE_FONT, FONTS.resolve(name).toFile()).deriveFont(size);
        }

        @Override public UiText regular() { return regular; }
        @Override public UiText medium() { return medium; }
        @Override public UiText bold() { return bold; }
        @Override public UiText display() { return display; }
        @Override public UiText headline() { return headline; }
    }
}
