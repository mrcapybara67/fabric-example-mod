package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.Category;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.esp.FinderModule;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The Capy window: everything that is drawn, minus the Minecraft plumbing.
 *
 * The frame owns the layout, the animation state and the hit testing; the
 * screen only forwards Minecraft events to it. Keeping the two apart means
 * the whole interface can be rendered outside the game for visual review.
 *
 * Layout rules that are applied everywhere:
 *   <ul>
 *     <li>one spacing scale ({@link UiTheme#SP_1} .. {@link UiTheme#SP_8});</li>
 *     <li>one radius scale ({@link UiTheme#RADIUS} .. {@link UiTheme#RADIUS_XL});</li>
 *     <li>every surface is built from the same three steps: shadow, body,
 *         hairline - so the interface reads as one material.</li>
 *   </ul>
 */
public final class GuiFrame {
	private static final Category[] CATEGORIES = Category.values();
	/** Height of the section header above the card list. */
	private static final int SECTION_HEADER_H = 26;
	private static final int FINDER_STATUS_PAD = 8;
	private static final int FINDER_STATUS_HEADER_H = 24;
	private static final int FINDER_STATUS_ROW_H = 16;
	private static final int FINDER_STATUS_BUTTON_H = 18;

	/** Height of the theme picker button at the foot of the sidebar. */
	private static final int THEME_PICKER_H = 26;
	/** Height of the "THEME" caption above that button. */
	private static final int THEME_CAPTION_H = 14;

	/** Everything below the last category row, from the caption down. */
	private static final int SIDEBAR_FOOTER_H = UiTheme.SP_8 + THEME_PICKER_H
			+ UiTheme.SP_2 + THEME_CAPTION_H + UiTheme.SP_3;

	/** The smallest a category row may get before it stops being usable. */
	private static final int CATEGORY_H_MIN = 15;
	/** Width of the header's HUD editor chip when it carries its label. */
	private static final int HUD_BTN_W = 96;
	/** Height of the GUI settings flyout. */
	private static final int SETTINGS_PANEL_H = 280;
	private static final String[] SETTINGS_LABELS = {"Sounds", "Reduce animations", "Blur (optional)",
			"Glass debug", "GUI debug"};

	/**
	 * The page the window was last showing.
	 *
	 * The frame is rebuilt every time the screen opens, so without this the GUI
	 * always came back on the first category and the user had to re-navigate
	 * every single time.
	 */
	private static int lastCategory;

	/**
	 * The space the sidebar needs at its smallest usable row height. The window
	 * never gets shorter than this, so every category stays reachable.
	 */
	private static int sidebarMinHeight() {
		int label = UiTheme.SP_5 + UiTheme.SP_6;
		int rows = CATEGORIES.length * (CATEGORY_H_MIN + UiTheme.SP_1);
		return UiTheme.HEADER_H + label + rows + SIDEBAR_FOOTER_H;
	}

	private final Map<Category, List<ModuleCard>> cards = new EnumMap<>(Category.class);
	private final UiScrollbar scrollbar = new UiScrollbar();
	private final UiSearch search = new UiSearch();
	private final UiTooltip tooltip = new UiTooltip();
	private final UiToggle soundsToggle = new UiToggle();
	private final UiToggle animationsToggle = new UiToggle();
	private final UiToggle blurToggle = new UiToggle();
	private final UiToggle glassDebugToggle = new UiToggle();
	private final UiToggle debugToggle = new UiToggle();
	private final Anim tabIndicator = new Anim(0f, 16f);
	private final String[] categoryCounts = new String[CATEGORIES.length];
	private int cachedEnabledCount = -1;
	private String cachedEnabledText = "0 on";
	private int cachedListSize = -1;
	private String cachedListLabel = "0 modules";
	private boolean guiSettingsOpen;
	private boolean volumeDragging;
	private int settingsPanelX;
	private int settingsPanelY;

	private final Anim windowHeight = new Anim(0f, 12f);
	private final float[] select = new float[CATEGORIES.length];
	private final List<ModuleCard> cachedMatches = new ArrayList<>();
	/** Retained card positions; rebuilt only when the list or a card changes. */
	private int[] layoutTops = new int[0];
	private int[] layoutHeights = new int[0];
	private int layoutContentH;
	private int layoutWidth = -1;
	private int layoutHeight = -1;
	private int layoutSelected = -1;
	private String layoutQuery = "";
	private boolean layoutDirty = true;

	private int selected;
	private int lastSelected = -1;
	private float switchProgress = 1f;
	private String cachedQuery;
	private Object hintBinding;
	private String hintCache = "? / ESC to close";
	private int settingsBtnX;
	private int settingsBtnY;
	/** True while the header's HUD editor chip is under the pointer. */
	private boolean hudHovered;
	private int hudBtnX;
	private int hudBtnY;
	private int finderStatusButtonX;
	private int finderStatusButtonY;
	private int finderStatusButtonW;
	private int finderStatusButtonH;
	/**
	 * Opens the drag editor. Supplied by the screen, because the frame is also
	 * rendered outside Minecraft by the preview tool and must not depend on
	 * the game's classes.
	 */
	private Runnable openHudEditor = () -> {};
	private int width;
	private int height;
	private boolean closeRequested;

	public GuiFrame() {
		selected = lastCategory;
		rebuild();
	}

	/** Wires the header's HUD editor chip to the screen that owns Minecraft. */
	public void setOpenHudEditor(Runnable action) {
		openHudEditor = action == null ? () -> {} : action;
	}

	/** Rebuilds the card list from the registered modules. */
	public void rebuild() {
		int previous = selected;
		cards.clear();
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = new ArrayList<>();
			for (Module module : ModuleManager.get().byCategory(category)) {
				list.add(new ModuleCard(module));
			}
			cards.put(category, list);
			categoryCounts[category.ordinal()] = String.valueOf(list.size());
		}
		cachedEnabledCount = -1;
		cachedListSize = -1;
		layoutDirty = true;
		// Keep the current page when the list is rebuilt.
		selected = Math.max(0, Math.min(CATEGORIES.length - 1, previous));
		lastCategory = selected;
		lastSelected = -1;
		cachedQuery = null;
		cachedMatches.clear();
	}

	/** Updates the layout for a new screen size. */
	public void resize(int width, int height) {
		this.width = width;
		this.height = height;
		layoutDirty = true;
		windowHeight.snap(targetWindowHeight());
	}

	/** Selects a category by index (used by the offline preview). */
	void setCategory(int index) {
		if (index >= 0 && index < CATEGORIES.length) {
			selected = index;
			lastCategory = index;
			layoutDirty = true;
		}
	}

	/** Expands a module card (used by the offline preview). */
	void expandCard(Category category, int cardIndex) {
		List<ModuleCard> list = cards.get(category);
		if (list != null && cardIndex >= 0 && cardIndex < list.size()) {
			list.get(cardIndex).setExpanded(true);
		}
	}

	/** Fills the search field (used by the offline preview). */
	void setSearch(String query) {
		search.setQuery(query);
		layoutDirty = true;
	}

	/** True when the frame asked to be closed. */
	public boolean closeRequested() {
		return closeRequested;
	}

	// ---------------------------------------------------------------- layout

	private int windowHeight() {
		return Math.round(windowHeight.value());
	}

	private int windowWidth() {
		// 62% of the screen, but never wider than the screen. The old floor of
		// 520 was wider than a 480x270 GUI (the auto scale on a 1080p display),
		// so the whole window hung off both sides of it.
		int preferred = (int) (width * 0.62f);
		int fits = Math.min(760, width - 16);
		return Math.max(Math.min(520, fits), Math.min(760, preferred));
	}

	/** True when the header has to shed its labels to fit the window. */
	private boolean compactHeader() {
		// A 520 pixel window still fits the full chip and the 190 pixel search
		// field; only below that does the header need to shed the theme name.
		return windowWidth() < 540;
	}

	/** Search field width for the current window size. */
	private int searchWidth() {
		return compactHeader() ? 110 : UiTheme.SEARCH_W;
	}

	/**
	 * The height the window wants for its content. A window that is much
	 * taller than its content looks empty, so the shell follows the modules
	 * and grows only when the content needs the room.
	 */
	private int targetWindowHeight() {
		int content = 0;
		List<ModuleCard> list = visibleCards();
		for (int i = 0; i < list.size(); i++) {
			content += list.get(i).fullHeight();
			if (i < list.size() - 1) {
				content += UiTheme.CARD_GAP;
			}
		}
		int needed = UiTheme.HEADER_H + UiTheme.SP_6 + SECTION_HEADER_H + finderStatusHeight()
				+ content + UiTheme.SP_6;
		int max = Math.min(520, (int) (height * 0.82f));
		// The sidebar is allowed to push the window past the content cap, but
		// never off the screen: on a short window showing every category
		// matters more than the empty space the 82% rule would leave.
		int hardMax = Math.min(520, (int) (height * 0.94f));
		int min = Math.min(hardMax, sidebarMinHeight());
		return Math.max(min, Math.min(max, needed));
	}

	private int windowX() {
		return (width - windowWidth()) / 2;
	}

	private int windowY() {
		return (height - windowHeight()) / 2;
	}

	// ---- Sidebar geometry ------------------------------------------------
	// The renderer and the hit test both go through these, so a click can
	// never land on a row that was drawn somewhere else.

	private int sidebarX() {
		return windowX() + UiTheme.SP_4;
	}

	private int sidebarY() {
		return windowY() + UiTheme.HEADER_H - UiTheme.SP_1;
	}

	private int sidebarW() {
		return UiTheme.SIDEBAR_W - UiTheme.SP_4;
	}

	private int sidebarH() {
		return windowHeight() - UiTheme.HEADER_H + UiTheme.SP_1 - UiTheme.SP_4;
	}

	/**
	 * The height of one category row for the current window height.
	 *
	 * This is the fix for the "missing" HUD category. The rows used to have a
	 * fixed height, so on a short window the last categories were laid out
	 * below the bottom edge of the sidebar and were never drawn - the panel
	 * simply looked like it had fewer categories than it has. HUD is the last
	 * category, so it was the one that vanished. The rows now shrink to fit
	 * instead of running off the panel.
	 */
	private int categoryRowHeight() {
		int listTop = UiTheme.SP_5 + UiTheme.SP_6;
		int available = footerTop() - UiTheme.SP_3 - listTop;
		int perRow = available / CATEGORIES.length - UiTheme.SP_1;
		return Math.max(CATEGORY_H_MIN, Math.min(UiTheme.CATEGORY_H, perRow));
	}

	/**
	 * True when the sidebar is tall enough for the theme picker and the hint
	 * under the category list.
	 */
	private boolean footerFitted() {
		int listTop = UiTheme.SP_5 + UiTheme.SP_6;
		int needed = listTop + CATEGORIES.length * (CATEGORY_H_MIN + UiTheme.SP_1) + SIDEBAR_FOOTER_H;
		return needed <= sidebarH();
	}

	/**
	 * Where the footer begins, measured from the top of the sidebar: the rows
	 * must end above this, and the theme caption is drawn just below it.
	 *
	 * On a very short window the footer is dropped entirely rather than letting
	 * it eat the category list - a GUI that cannot show the categories is worse
	 * than one without the theme picker.
	 */
	private int footerTop() {
		if (!footerFitted()) {
			return sidebarH() - UiTheme.SP_2;
		}
		return sidebarH() - UiTheme.SP_8 - THEME_PICKER_H - UiTheme.SP_2 - THEME_CAPTION_H;
	}

	/** The y of the first category row. */
	private int categoryListY() {
		return sidebarY() + UiTheme.SP_5 + UiTheme.SP_6;
	}

	/** The x of the category rows, inside the panel padding. */
	private int categoryRowX() {
		return sidebarX() + UiTheme.SP_2;
	}

	private int categoryRowW() {
		return sidebarW() - UiTheme.SP_2 * 2;
	}

	private int contentX() {
		return windowX() + UiTheme.SIDEBAR_W + UiTheme.SP_6;
	}

	private int contentW() {
		return windowX() + windowWidth() - UiTheme.SP_6 - UiTheme.SP_5 - contentX();
	}

	private int contentTop() {
		return windowY() + UiTheme.HEADER_H + UiTheme.SP_6;
	}

	private int contentBottom() {
		return windowY() + windowHeight() - UiTheme.SP_5;
	}

	/** The first card pixel, below the section header and the finder status panel. */
	private int contentListTop() {
		return contentTop() + SECTION_HEADER_H + finderStatusHeight();
	}

	private boolean finderStatusVisible() {
		return search.normalisedQuery().isEmpty() && CATEGORIES[selected] == Category.BASE_FINDER;
	}

	private List<FinderModule> enabledFinders() {
		List<FinderModule> out = new ArrayList<>();
		for (Module module : ModuleManager.get().byCategory(Category.BASE_FINDER)) {
			if (module instanceof FinderModule finder && module.isEnabled()) {
				out.add(finder);
			}
		}
		return out;
	}

	/** Space taken by the status panel, including the gap below it; zero when hidden. */
	private int finderStatusHeight() {
		if (!finderStatusVisible()) {
			return 0;
		}
		int count = enabledFinders().size();
		if (count == 0) {
			return 0;
		}
		return FINDER_STATUS_PAD * 2 + FINDER_STATUS_HEADER_H + count * FINDER_STATUS_ROW_H
				+ UiTheme.SP_4;
	}

	// ---------------------------------------------------------------- render

	/**
	 * Draws the whole window.
	 *
	 * @param open 0..1, drives the opening and closing transition
	 */
	public void render(UiCanvas c, UiType type, int mouseX, int mouseY, float delta, float open) {
		float fade = Motion.clamp01(open);
		if (fade <= 0.02f) {
			return;
		}
		// The backdrop: a plum veil that is darkest at the edges, so the
		// window reads as the lit object in the middle.
		c.fill(0, 0, width, height, Ui.lerpColor(0x00000000, UiTheme.SCRIM, fade));
		Ui.vignette(c, width, height, fade);

		// The window follows its content: it grows when a module opens its
		// settings and shrinks back when it closes.
		windowHeight.to(targetWindowHeight(), delta);
		if (windowHeight.value() <= 1f) {
			windowHeight.snap(targetWindowHeight());
		}

		// Open from 94% over 8 px of upward travel, using ease-out-cubic. The
		// screen owns the 180 ms clock, so this reads the same at any frame rate.
		float ease = Motion.easeOutCubic(fade);
		int ww = Math.round(windowWidth() * (0.94f + 0.06f * ease));
		int wh = Math.round(windowHeight() * (0.94f + 0.06f * ease));
		int wx = (width - ww) / 2;
		int wy = (height - wh) / 2 + Math.round((1f - ease) * 8f);
		int r = UiTheme.RADIUS_XL;

		Ui.shadow(c, wx, wy, ww, wh, r);
		Ui.surface(c, wx, wy, ww, wh, r, UiTheme.SHELL_TOP, UiTheme.SHELL_BOTTOM);
		if (UiTheme.isGlass()) {
			// One specular band across the whole window, on the same clock as the
			// HUD chips, so the window and the chips read as the same pane of
			// glass rather than as two unrelated materials.
			Ui.sheen(c, wx, wy, ww, wh, r, sheenPhase());
		}
		// The pink sakura wash was a leftover from the old theme and made the
		// glass look white. The Glass surface uses only the cool accent.
		Ui.outline(c, wx, wy, ww, wh, r, UiTheme.BORDER);
		Ui.innerBevel(c, wx, wy, ww, wh, r, UiTheme.HIGHLIGHT, UiTheme.SHADE);
		// One accent hairline along the top edge. It is the only place the shell
		// itself is allowed to glow, which gives the window a lit edge instead
		// of a uniform frame.
		Ui.surface(c, wx + r, wy + 1, ww - r * 2, 1, 0, Ui.withAlpha(UiTheme.ACCENT, 0x4D));
		if (ClientConfig.get().glassDebug()) {
			// A fixed mid-grey backdrop removes the bright world/snow variable
			// that can make a translucent panel look washed out.
			c.fill(0, 0, width, height, 0xFF6B7078);
			Ui.surface(c, wx, wy, ww, wh, r, UiTheme.SHELL_TOP, UiTheme.SHELL_BOTTOM);
			Ui.outline(c, wx, wy, ww, wh, r, UiTheme.BORDER);
		}
		Ui.frost(c, wx + r / 2, wy + 2, ww - r, wh - 4, 0x08FFFFFF);

		// The accent reveal: a soft pink light sweeps down over the window
		// while it opens.
		if (fade < 1f) {
			int sweepH = Math.round(wh * 0.55f);
			int sweepY = wy + Math.round((1f - ease) * (wh - sweepH));
			Ui.glow(c, wx, wy, ww, sweepH, UiTheme.ACCENT_2, (1f - fade) * 0.16f);
			Ui.glow(c, wx, sweepY, ww, sweepH, UiTheme.ACCENT, (1f - fade) * 0.10f);
		}

		for (int i = 0; i < select.length; i++) {
			float target = i == selected ? 1f : 0f;
			if (Math.abs(select[i] - target) > 0.001f) {
				select[i] = Motion.approach(select[i], target, 14f, delta / 20f);
			} else {
				select[i] = target;
			}
		}
		tabIndicator.to(selected, delta);
		if (selected != lastSelected) {
			lastSelected = selected;
			switchProgress = 0f;
		}
		if (switchProgress < 1f) {
			switchProgress = Motion.approach(switchProgress, 1f, 12f, delta / 20f);
		} else {
			switchProgress = 1f;
		}

		renderHeader(c, type, wx, wy, ww, mouseX, mouseY, delta);
		// The sidebar arrives with the shell, the workspace a beat later, so
		// the eye is led from the frame to the content instead of being hit by
		// a whole finished window at once.
		float contentEase = Motion.easeOutCubic(Motion.clamp01(fade * 1.18f - 0.18f));
		renderSidebar(c, type, wx, wy, ww, wh, mouseX, mouseY, delta, (1f - ease) * 22f);
		renderContent(c, type, mouseX, mouseY, delta, (1f - contentEase) * 26f, contentEase);

		tooltip.render(c, type.regular());
		if (guiSettingsOpen) {
			renderGuiSettings(c, type, mouseX, mouseY, delta);
		}
	}

	/** Slow drift of the specular band, so the window never looks static. */
	private float sheenPhase() {
		double t = (System.currentTimeMillis() % 11000L) / 11000.0;
		return (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2.0));
	}

	private void renderHeader(UiCanvas c, UiType type, int wx, int wy, int ww, int mouseX, int mouseY, float delta) {
		int cy = wy + (UiTheme.HEADER_H - 34) / 2;

		// The page leads the band. The badge, the crest and the wordmark that
		// used to sit here were a logo inside a title bar that already carries
		// the window's name: they took the width the page title needs and told
		// the player nothing the sidebar does not. What replaces them is what a
		// header is for - where you are, and how much is switched on.
		UiText display = type.display();
		UiText pageFont = type.medium();
		String pageTitle = search.normalisedQuery().isEmpty() ? CATEGORIES[selected].title : "Search results";
		int titleX = wx + UiTheme.SP_6;
		int titleY = Ui.centerTextY(wy, UiTheme.HEADER_H, display);
		int titleMax = Math.max(0, searchX(wx, ww) - titleX - UiTheme.SP_4);
		int titleW = Math.min(display.width(pageTitle), titleMax);
		Ui.drawEllipsized(c, display, pageTitle, titleX, titleY, titleMax, UiTheme.TEXT);

		// An accent rule under the page name, opening as the page change
		// settles. It is what makes moving between pages read as a movement
		// rather than as the card list being replaced.
		float emphasis = Math.max(0f, Math.min(1f, select[selected]));
		int underlineW = Math.round(titleW * (0.35f + 0.65f * emphasis));
		if (underlineW > 0 && !compactHeader()) {
			Ui.surface(c, titleX, wy + UiTheme.HEADER_H - 8, underlineW, 2, 1, UiTheme.ACCENT);
		}

		// The live count: how many modules are on. It answers "is anything
		// even enabled" without opening a page, and it re-reads every frame so
		// it follows a toggle immediately.
		String count = enabledText();
		int pillW = pageFont.width(count) + UiTheme.SP_4;
		int pillH = 18;
		int pillX = titleX + titleW + UiTheme.SP_4;
		int pillY = wy + (UiTheme.HEADER_H - pillH) / 2;
		if (!compactHeader() && pillX + pillW < settingsX(wx, ww) - UiTheme.SP_4) {
			boolean hot = mouseX >= pillX && mouseX < pillX + pillW
					&& mouseY >= pillY && mouseY < pillY + pillH;
			Ui.surface(c, pillX, pillY, pillW, pillH, pillH / 2,
					hot ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
					hot ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
			Ui.outline(c, pillX, pillY, pillW, pillH, pillH / 2,
					hot ? UiTheme.ACCENT : UiTheme.BORDER);
			Ui.drawCentered(c, pageFont, count, pillX + pillW / 2, pillY + 5,
					hot ? UiTheme.TEXT : UiTheme.ACCENT);
		}

		int fieldX = searchX(wx, ww);

		// The editor and settings chips sit between the page title and the search field.
		renderHudButton(c, type, wx, wy, ww, mouseX, mouseY);
		renderSettingsButton(c, type, wx, wy, ww, mouseX, mouseY);

		// Search.
		search.bind(fieldX, wy + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2, searchWidth(), UiTheme.SEARCH_H);
		search.render(c, type.regular(), mouseX, mouseY, delta);

		// Close.
		int bx = wx + ww - UiTheme.SP_6 - UiTheme.CLOSE_BTN;
		int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
		boolean hovered = mouseX >= bx && mouseX < bx + UiTheme.CLOSE_BTN
				&& mouseY >= by && mouseY < by + UiTheme.CLOSE_BTN;
		Ui.surface(c, bx, by, UiTheme.CLOSE_BTN, UiTheme.CLOSE_BTN, UiTheme.RADIUS,
				hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				hovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, bx, by, UiTheme.CLOSE_BTN, UiTheme.CLOSE_BTN, UiTheme.RADIUS,
				hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		if (hovered) {
			Ui.glow(c, bx - 5, by - 5, UiTheme.CLOSE_BTN + 10, UiTheme.CLOSE_BTN + 10,
					UiTheme.ACCENT, 0.20f);
		}
		UiIcons.draw(c, UiIcons.Icon.CLOSE, bx + 9, by + 9, 8, hovered ? UiTheme.TEXT : UiTheme.TEXT_2);

		// A hairline closes the header band, so the wordmark reads as a title
		// bar rather than as text floating on the shell.
		Ui.fadeLine(c, wx + UiTheme.SP_5, wy + UiTheme.HEADER_H - 1,
				ww - UiTheme.SP_5 * 2, Ui.withAlpha(UiTheme.BORDER, 0xA6));
	}

	private String enabledText() {
		int count = enabledCount();
		if (count != cachedEnabledCount) {
			cachedEnabledCount = count;
			cachedEnabledText = count + " on";
		}
		return cachedEnabledText;
	}

	private String listLabel(int size) {
		if (size != cachedListSize) {
			cachedListSize = size;
			cachedListLabel = size + (size == 1 ? " module" : " modules");
		}
		return cachedListLabel;
	}

	/** How many modules are enabled right now, for the header's count pill. */
	private static int enabledCount() {
		int on = 0;
		for (Module module : ModuleManager.get().modules()) {
			if (module.isEnabled()) {
				on++;
			}
		}
		return on;
	}

	private int searchX(int wx, int ww) {
		return wx + ww - UiTheme.SP_6 - UiTheme.CLOSE_BTN - UiTheme.SP_3 - searchWidth();
	}	/** The fixed-width settings chip. */
	private int settingsX(int wx, int ww) {
		return searchX(wx, ww) - UiTheme.SP_3 - settingsButtonWidth();
	}

	private int settingsButtonWidth() {
		return compactHeader() ? UiTheme.SEARCH_H : 92;
	}

	/** The HUD editor chip sits one spacing step left of the settings chip. */
	private int hudX(int wx, int ww) {
		return settingsX(wx, ww) - UiTheme.SP_3 - hudButtonWidth();
	}


	/**
	 * The editor chip's width: its name while there is room, the layout icon
	 * when the header is too narrow to carry another label. The threshold is
	 * higher than the other chips' because this one is the newest addition and
	 * must not be the chip that pushes the page title off the band.
	 */
	private int hudButtonWidth() {
		return hudLabelled() ? HUD_BTN_W : UiTheme.SEARCH_H;
	}

	private boolean hudLabelled() {
		return !compactHeader() && windowWidth() >= 620;
	}

	/**
	 * The HUD editor chip: a labelled button that opens the drag editor.
	 *
	 * <p>The editor already had a keybind, but a keybind is only found by the
	 * player who reads the controls list. The window is where a HUD is thought
	 * about, so the door is here as well. The editor opens over the world, not
	 * inside the GUI: placing a chip means judging it against the scene it will
	 * sit on.</p>
	 */
	private void renderHudButton(UiCanvas c, UiType type, int wx, int wy, int ww,
			int mouseX, int mouseY) {
		int x = hudX(wx, ww);
		int y = wy + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2;
		int w = hudButtonWidth();
		int h = UiTheme.SEARCH_H;
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		hudHovered = hovered;
		hudBtnX = x;
		hudBtnY = y;

		Ui.surface(c, x, y, w, h, UiTheme.RADIUS,
				hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				hovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, x, y, w, h, UiTheme.RADIUS, hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		int ink = hovered ? UiTheme.TEXT : UiTheme.TEXT_2;

		if (!hudLabelled()) {
			UiIcons.draw(c, UiIcons.Icon.LAYOUT, x + (w - 8) / 2, y + (h - 8) / 2, 8, ink);
			return;
		}
		UiIcons.draw(c, UiIcons.Icon.LAYOUT, x + 9, y + (h - 8) / 2, 8, ink);
		UiText font = type.regular();
		font.draw(c, "Edit HUD", x + 22, Ui.centerTextY(y, h, font), ink);
	}

	/** The GUI settings chip: one fixed Glass look, no theme switching. */
	private void renderSettingsButton(UiCanvas c, UiType type, int wx, int wy, int ww,
			int mouseX, int mouseY) {
		int x = settingsX(wx, ww);
		int y = wy + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2;
		int w = settingsButtonWidth();
		int h = UiTheme.SEARCH_H;
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		settingsBtnX = x;
		settingsBtnY = y;
		Ui.surface(c, x, y, w, h, UiTheme.RADIUS,
				hovered || guiSettingsOpen ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				hovered || guiSettingsOpen ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, x, y, w, h, UiTheme.RADIUS,
				hovered || guiSettingsOpen ? UiTheme.ACCENT : UiTheme.BORDER);
		int ink = hovered || guiSettingsOpen ? UiTheme.TEXT : UiTheme.TEXT_2;
		UiIcons.draw(c, UiIcons.Icon.CREST, x + 9, y + (h - 8) / 2, 8, ink);
		if (!compactHeader()) {
			type.regular().draw(c, "GUI", x + 22, Ui.centerTextY(y, h, type.regular()), ink);
		}
	}

	private void renderGuiSettings(UiCanvas c, UiType type, int mouseX, int mouseY, float delta) {
		ClientConfig config = ClientConfig.get();
		int w = Math.min(300, width - 24);
		int h = SETTINGS_PANEL_H;
		settingsPanelX = Math.min(width - w - 8, settingsBtnX + settingsButtonWidth() - w);
		settingsPanelY = settingsBtnY + UiTheme.SEARCH_H + UiTheme.SP_2;
		int x = settingsPanelX;
		int y = settingsPanelY;
		Ui.shadow(c, x, y, w, h, UiTheme.RADIUS_LG);
		Ui.surface(c, x, y, w, h, UiTheme.RADIUS_LG, UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM);
		Ui.outline(c, x, y, w, h, UiTheme.RADIUS_LG, UiTheme.BORDER);
		Ui.sheen(c, x, y, w, h, UiTheme.RADIUS_LG, sheenPhase());
		type.medium().draw(c, "GUI SETTINGS", x + UiTheme.SP_5,
				y + UiTheme.SP_4, UiTheme.TEXT);

		int rowX = x + UiTheme.SP_5;
		int rowW = w - UiTheme.SP_5 * 2;
		int toggleX = x + w - UiTheme.SP_5 - UiTheme.TOGGLE_W;
		for (int i = 0; i < SETTINGS_LABELS.length; i++) {
			int rowY = y + 34 + i * 32;
			boolean hovered = mouseX >= rowX && mouseX < rowX + rowW
					&& mouseY >= rowY && mouseY < rowY + UiTheme.TOGGLE_H + 4;
			UiToggle toggle = i == 0 ? soundsToggle : i == 1 ? animationsToggle
					: i == 2 ? blurToggle : i == 3 ? glassDebugToggle : debugToggle;
			boolean value = i == 0 ? config.guiSounds() : i == 1 ? config.reduceAnimations()
					: i == 2 ? config.blur() : i == 3 ? config.glassDebug() : config.guiDebug();
			toggle.update(value, hovered, delta);
			toggle.render(c, toggleX, rowY, UiTheme.TOGGLE_W, UiTheme.TOGGLE_H);
			type.regular().draw(c, SETTINGS_LABELS[i], rowX,
					Ui.centerTextY(rowY, UiTheme.TOGGLE_H, type.regular()),
					hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
		}

		int qualityY = y + 198;
		type.regular().draw(c, "Quality", rowX, Ui.centerTextY(qualityY, 22, type.regular()), UiTheme.TEXT_2);
		int qw = 92;
		int qx = x + w - UiTheme.SP_5 - qw;
		boolean qualityHovered = mouseX >= qx && mouseX < qx + qw && mouseY >= qualityY && mouseY < qualityY + 22;
		Ui.surface(c, qx, qualityY, qw, 22, 8,
				qualityHovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				qualityHovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, qx, qualityY, qw, 22, 8, qualityHovered ? UiTheme.ACCENT : UiTheme.BORDER);
		Ui.drawCentered(c, type.regular(), config.guiQuality().name(), qx + qw / 2,
				Ui.centerTextY(qualityY, 22, type.regular()), qualityHovered ? UiTheme.TEXT : UiTheme.ACCENT_2);

		int volumeY = y + 238;
		type.regular().draw(c, "Volume", rowX, volumeY, UiTheme.TEXT_2);
		int trackX = rowX + 72;
		int trackY = volumeY + type.regular().lineHeight() / 2;
		int trackW = x + w - UiTheme.SP_5 - trackX;
		Ui.surface(c, trackX, trackY - 4, trackW, 8, 4, UiTheme.TRACK_TOP, UiTheme.TRACK_BOTTOM);
		int fill = Math.round(trackW * config.guiVolume() / 100f);
		Ui.surface(c, trackX, trackY - 4, fill, 8, 4, UiTheme.ACCENT, UiTheme.ACCENT_DEEP);
		Ui.circle(c, trackX + fill, trackY, 6, UiTheme.ACCENT_2);
	}
	private void renderSidebar(UiCanvas c, UiType type, int wx, int wy, int ww, int wh, int mouseX, int mouseY,
			float delta, float slide) {
		int px = sidebarX() - Math.round(slide);
		int py = sidebarY();
		int pw = sidebarW();
		int ph = sidebarH();

		// The sidebar is a panel set into the shell.
		Ui.surface(c, px, py, pw, ph, UiTheme.RADIUS_LG, UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM);
		Ui.outline(c, px, py, pw, ph, UiTheme.RADIUS_LG, UiTheme.BORDER);

		int sx0 = categoryRowX();
		int sw = categoryRowW();
		int y = categoryListY();
		// One row height for the whole list, taken from the same helper the
		// hit test uses: rows shrink instead of running past the panel.
		int rowH = categoryRowHeight();
		int rowPitch = rowH + UiTheme.SP_1;

		// The caption shares the row labels' inset rather than the content
		// header's: in the sidebar the rows are the alignment reference, and a
		// caption a couple of pixels off them reads as a mistake.
		Ui.drawEllipsized(c, type.regular(), "CATEGORIES", sx0 + UiTheme.SP_5, py + UiTheme.SP_5,
				sw - UiTheme.SP_6, UiTheme.TEXT_3);

		for (int i = 0; i < CATEGORIES.length; i++) {
			Category category = CATEGORIES[i];
			boolean hovered = mouseX >= sx0 && mouseX < sx0 + sw && mouseY >= y && mouseY < y + rowH;
			float sel = select[i];
			int rr = UiTheme.RADIUS;

			if (sel > 0.02f) {
				// The active row: a cool wash. The accent rail is one
				// indicator that slides between rows below this loop.
				Ui.surface(c, sx0, y, sw, rowH, rr,
						Ui.withAlpha(UiTheme.ACCENT, Math.round(30 * sel)),
						Ui.withAlpha(UiTheme.ACCENT_DEEP, Math.round(14 * sel)));
			} else if (hovered) {
				Ui.surface(c, sx0, y, sw, rowH, rr,
						Ui.withAlpha(UiTheme.PANEL_ALT, 0xB0),
						Ui.withAlpha(UiTheme.PANEL_TOP, 0x90));
			}

			// The category rows are typographic: no symbol on every entry. The
			// label is inset past the accent bar of the selected row, so the bar
			// never sits under the text.
			int textX = sx0 + UiTheme.SP_5;
			UiText medium = type.medium();
			int textY = Ui.centerTextY(y, rowH, medium);
			int nameColor = Ui.lerpColor(UiTheme.TEXT_2, UiTheme.TEXT, Math.max(sel * 0.9f, hovered ? 0.7f : 0f));
			Ui.drawEllipsized(c, medium, category.title, textX, textY, sx0 + sw - UiTheme.SP_5 - textX, nameColor);

			int count = cards.get(category).size();
			if (count > 0) {
				UiText regular = type.regular();
				String label = categoryCounts[i];
				int bw = regular.width(label) + 10;
				int bx = sx0 + sw - UiTheme.SP_4 - bw;
				int by = y + (rowH - 14) / 2;
				Ui.surface(c, bx, by, bw, 14, 7,
						Ui.lerpColor(Ui.withAlpha(UiTheme.PANEL_ALT, 0xD0), Ui.withAlpha(UiTheme.ACCENT, 0x59), sel),
						Ui.lerpColor(Ui.withAlpha(UiTheme.PANEL_TOP, 0xD0), Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x33), sel));
				Ui.drawCentered(c, regular, label, bx + bw / 2, Ui.centerTextY(by, 14, regular),
						Ui.lerpColor(UiTheme.TEXT_3, UiTheme.TEXT, sel));
			}

			y += rowPitch;
		}

		// One rail slides between category rows instead of seven different
		// bars appearing and disappearing. It settles to a fixed position on
		// a still screen, so it costs no animation work at rest.
		int railH = Math.max(8, rowH - 16);
		int railY = categoryListY() + Math.round(tabIndicator.value() * rowPitch) + (rowH - railH) / 2;
		Ui.surface(c, sx0 + 2, railY, 3, railH, 2, UiTheme.ACCENT, UiTheme.ACCENT_DEEP);

		if (!footerFitted()) {
			return;
		}

		// Theme switching and the old colour studio are gone; the footer keeps
		// only the keybind hint.
		// Keybind hint. Escape always works, so it is named next to the
		// player's own binding instead of being discoverable only by trying it.
		Object binding = ClientKeybinds.openClickGui();
		if (binding != hintBinding) {
			hintBinding = binding;
			String key = binding == null ? "?"
					: ClientKeybinds.openClickGui().getTranslatedKeyMessage().getString();
			hintCache = key + " / ESC to close";
		}
		String hint = hintCache;
		int hintY = Ui.textYFromBottom(py + ph, UiTheme.SP_5, type.regular());
		int hintX = sx0 + UiTheme.SP_3;
		Ui.drawEllipsized(c, type.regular(), hint + " / ESC to close", hintX, hintY,
				sw - UiTheme.SP_4, UiTheme.TEXT_3);
		boolean hintHovered = mouseX >= hintX && mouseX < hintX + sw - UiTheme.SP_4
				&& mouseY >= hintY - 2 && mouseY < hintY + type.regular().lineHeight() + 2;
		if (hintHovered) {
			tooltip.show("Esc closes the GUI. Rebind the key in Options - Controls.", hintX, hintY);
		} else if (hudHovered) {
			tooltip.show("HUD Editor - drag chips and boxes to place them.",
					hudBtnX, hudBtnY + UiTheme.SEARCH_H);
		} else {
			tooltip.hide();
		}
	}

	private void renderContent(UiCanvas c, UiType type, int mouseX, int mouseY, float delta, float slide,
			float reveal) {
		int cx = contentX();
		int top = contentTop() + Math.round(slide);
		int bottom = contentBottom();
		int cw = contentW();
		int viewportH = Math.max(0, bottom - top);

		List<ModuleCard> list = visibleCards();
		boolean showCategory = !search.normalisedQuery().isEmpty();

		// The section header: what is shown and how much of it there is.
		int headerH = SECTION_HEADER_H;
		UiText bold = type.bold();
		UiText meta = type.regular();
		String title = showCategory ? "Search results" : CATEGORIES[selected].title;
		// The module count is a pill, not loose text: it is metadata about the
		// page, and a small surface is what makes that read as metadata. It is
		// measured before the title is drawn, so the title is trimmed against
		// the pill instead of against a guessed width.
		String countLabel = listLabel(list.size());
		int pillH = Math.max(14, meta.lineHeight() + 6);
		int pillW = meta.width(countLabel) + 14;
		int pillX = cx + cw - pillW;
		int pillY = top + (headerH - pillH) / 2 - 2;
		// The section header is padded by the panel radius, so its first glyph
		// starts on the same curve the cards below it are cornered to.
		int titleX = cx + UiTheme.RADIUS_LG;
		int titleRoom = Math.max(0, pillX - titleX - UiTheme.SP_4);
		Ui.drawEllipsized(c, bold, title, titleX, Ui.centerTextY(top, headerH, bold), titleRoom, UiTheme.TEXT);
		Ui.surface(c, pillX, pillY, pillW, pillH, pillH / 2,
				Ui.withAlpha(UiTheme.PANEL_ALT, 0xB8), Ui.withAlpha(UiTheme.PANEL_TOP, 0x94));
		Ui.outline(c, pillX, pillY, pillW, pillH, pillH / 2, Ui.withAlpha(UiTheme.BORDER, 0x8C));
		Ui.drawCentered(c, meta, countLabel, pillX + pillW / 2,
				pillY + (pillH - meta.lineHeight()) / 2, UiTheme.TEXT_3);
		// A delicate line under the section header.
		Ui.fadeLine(c, cx, top + headerH - 6, cw, Ui.withAlpha(UiTheme.BORDER, 0xC0));

		int finderSpace = finderStatusHeight();
		int statusTop = top + headerH;
		if (finderSpace > 0) {
			renderFinderStatus(c, type, cx, statusTop, cw, finderSpace - UiTheme.SP_4, mouseX, mouseY);
		}
		int listTop = statusTop + finderSpace;
		int listH = Math.max(0, bottom - listTop);

		// Content that moves between pages slides a little.
		float pageSlide = showCategory ? 0f : (1f - switchProgress) * 10f;

		boolean cardsMoved = false;
		for (int i = 0; i < list.size(); i++) {
			cardsMoved |= list.get(i).animate(delta);
		}
		ensureCardLayout(list, cardsMoved);

		scrollbar.update(listTop, bottom, layoutContentH, delta);
		scrollbar.setRailX(cx + cw + UiTheme.SP_3);

		if (list.isEmpty()) {
			renderEmptyState(c, type, cx + cw / 2, listTop + listH / 2, showCategory);
			return;
		}

		// A very faint blossom in the corner of the workspace. It gives the
		// empty area a purpose without adding any content.

		c.pushClip(cx, listTop, cw, listH);
		int listY = listTop - (int) Math.round(scrollbar.scroll()) + Math.round(pageSlide);
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			int y = listY + layoutTops[i];
			if (VirtualList.intersects(y, layoutHeights[i], listTop - 40, bottom)) {
				card.setReveal(Motion.clamp01(reveal * 1.35f - Math.min(i, 10) * 0.035f));
				card.render(c, type, cx, y, cw, mouseX, mouseY, delta, showCategory);
			}
		}
		c.popClip();

		scrollbar.render(c);
	}

	/**
	 * The base-finder status strip: one live line per enabled finder, and the
	 * shared elimination-map switch. It is deliberately in the workspace and
	 * not in chat; the player opens the page to read a state, not to scroll
	 * back through messages.
	 */
	private void renderFinderStatus(UiCanvas c, UiType type, int x, int y, int w, int h,
			int mouseX, int mouseY) {
		List<FinderModule> finders = enabledFinders();
		if (finders.isEmpty() || w <= 0 || h <= 0) {
			return;
		}
		UiText regular = type.regular();
		UiText medium = type.medium();
		boolean mapVisible = FinderModule.eliminationMapVisible();
		int accent = mapVisible ? UiTheme.ACCENT_2 : UiTheme.ACCENT;

		Ui.roundRect(c, x + 1, y + 2, w, h, UiTheme.RADIUS_LG, 0x33000000);
		Ui.surface(c, x, y, w, h, UiTheme.RADIUS_LG, UiTheme.WELL_TOP, UiTheme.WELL_BOTTOM);
		Ui.surfaceEdge(c, x, y, w, h, UiTheme.RADIUS_LG,
				Ui.withAlpha(accent, 0xB0), Ui.withAlpha(UiTheme.BORDER, 0x44));
		Ui.sheen(c, x, y, w, h, UiTheme.RADIUS_LG, sheenPhase());

		int headerY = y + FINDER_STATUS_PAD;
		int titleX = x + FINDER_STATUS_PAD + 8;
		Ui.surface(c, x + FINDER_STATUS_PAD, headerY + 3, 2, 10, 1, accent, UiTheme.ACCENT_DEEP);
		medium.draw(c, "FINDER STATUS", titleX, headerY + Math.max(0, (FINDER_STATUS_HEADER_H - medium.lineHeight()) / 2),
				UiTheme.TEXT_2);

		String buttonLabel = UiLabels.translate("capy.finder.show_elimination", "show elimination map");
		int buttonW = Math.min(w / 2, regular.width(buttonLabel) + UiTheme.SP_4 * 2);
		int buttonX = x + w - FINDER_STATUS_PAD - buttonW;
		int buttonY = headerY + (FINDER_STATUS_HEADER_H - FINDER_STATUS_BUTTON_H) / 2;
		boolean hovered = mouseX >= buttonX && mouseX < buttonX + buttonW
				&& mouseY >= buttonY && mouseY < buttonY + FINDER_STATUS_BUTTON_H;
		finderStatusButtonX = buttonX;
		finderStatusButtonY = buttonY;
		finderStatusButtonW = buttonW;
		finderStatusButtonH = FINDER_STATUS_BUTTON_H;
		Ui.surface(c, buttonX, buttonY, buttonW, FINDER_STATUS_BUTTON_H, 6,
				mapVisible ? Ui.withAlpha(UiTheme.ACCENT, 0x42) : hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				mapVisible ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x32) : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, buttonX, buttonY, buttonW, FINDER_STATUS_BUTTON_H, 6,
				mapVisible || hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		Ui.drawEllipsized(c, regular, buttonLabel, buttonX + UiTheme.SP_3,
				Ui.centerTextY(buttonY, FINDER_STATUS_BUTTON_H, regular), buttonW - UiTheme.SP_3 * 2,
				mapVisible ? UiTheme.ACCENT_2 : hovered ? UiTheme.TEXT : UiTheme.TEXT_2);

		int rowY = headerY + FINDER_STATUS_HEADER_H;
		int nameX = x + FINDER_STATUS_PAD + 8;
		int nameW = Math.min(132, Math.max(80, w / 3));
		int statusX = nameX + nameW + UiTheme.SP_4;
		for (FinderModule finder : finders) {
			int lineY = rowY + Math.max(0, (FINDER_STATUS_ROW_H - medium.lineHeight()) / 2);
			Ui.drawEllipsized(c, medium, finder.displayName(), nameX, lineY, nameW,
					finder.colorArgb());
			int statusY = rowY + Math.max(0, (FINDER_STATUS_ROW_H - regular.lineHeight()) / 2);
			Ui.drawEllipsized(c, regular, finder.statusLine(), statusX, statusY,
					Math.max(0, x + w - FINDER_STATUS_PAD - statusX), UiTheme.TEXT_3);
			rowY += FINDER_STATUS_ROW_H;
		}
	}

	private void ensureCardLayout(List<ModuleCard> list, boolean cardsMoved) {
		boolean sizeChanged = list.size() != layoutTops.length;
		if (!layoutDirty && !cardsMoved && !sizeChanged
				&& layoutWidth == width && layoutHeight == height && layoutSelected == selected
				&& layoutQuery.equals(search.query())) {
			return;
		}
		if (sizeChanged) {
			layoutTops = new int[list.size()];
			layoutHeights = new int[list.size()];
		}
		int cursor = 0;
		for (int i = 0; i < list.size(); i++) {
			layoutTops[i] = cursor;
			layoutHeights[i] = list.get(i).height();
			cursor += layoutHeights[i] + (i < list.size() - 1 ? UiTheme.CARD_GAP : 0);
		}
		layoutContentH = cursor;
		layoutWidth = width;
		layoutHeight = height;
		layoutSelected = selected;
		layoutQuery = search.query();
		layoutDirty = false;
	}

	/**
	 * The cards to show. The search result is cached and only rebuilt when
	 * the query actually changes, so a frame never allocates a new list.
	 */
	private List<ModuleCard> visibleCards() {
		String q = search.normalisedQuery();
		if (q.isEmpty()) {
			cachedQuery = null;
			return cards.get(CATEGORIES[selected]);
		}
		if (!q.equals(cachedQuery)) {
			cachedQuery = q;
			cachedMatches.clear();
			for (Category category : CATEGORIES) {
				for (ModuleCard card : cards.get(category)) {
					if (card.title().toLowerCase(Locale.ROOT).contains(q)
							|| card.descriptionText().toLowerCase(Locale.ROOT).contains(q)
							|| card.module().id().replace('_', ' ').contains(q)) {
						cachedMatches.add(card);
					}
				}
			}
		}
		return cachedMatches;
	}

	private void renderEmptyState(UiCanvas c, UiType type, int centerX, int centerY, boolean searchEmpty) {
		UiText regular = type.regular();
		UiText medium = type.medium();
		String title = searchEmpty ? "No matches" : "Nothing here yet";
		String sub = searchEmpty
				? "No module matches your search."
				: "This category has no modules yet.";

		// A blossom in a soft disc, so the empty state still belongs to the
		// theme instead of looking like a missing screen.
		int orb = 46;
		int oy = centerY - 40;
		Ui.surface(c, centerX - orb / 2, oy, orb, orb, orb / 2, UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
		Ui.outline(c, centerX - orb / 2, oy, orb, orb, orb / 2, UiTheme.BORDER);
		UiIcons.draw(c, searchEmpty ? UiIcons.Icon.SEARCH : UiIcons.Icon.CREST,
				centerX - 10, oy + 10, 20, UiTheme.TEXT_3);

		Ui.drawCentered(c, medium, title, centerX, oy + orb + 14, UiTheme.TEXT_2);
		Ui.drawCentered(c, regular, sub, centerX, oy + orb + 30, UiTheme.TEXT_3);
	}

	// ----------------------------------------------------------------- input

	/** Handles a click. Returns true when the click was consumed. */
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();

		// A mouse button bound to the GUI closes it.
		if (ClientKeybinds.openClickGui() != null && ClientKeybinds.openClickGui().matchesMouse(event)) {
			closeRequested = true;
			return true;
		}

		int wx = windowX();
		int wy = windowY();
		int ww = windowWidth();
		int wh = windowHeight();
		if (mx < wx || mx >= wx + ww || my < wy || my >= wy + wh) {
			return true; // swallow clicks outside the window
		}

		// The header chips are checked before the search field because they sit
		// on the same row.
		if (button == 0) {
			int sy = windowY() + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2;
			int hx = hudX(windowX(), windowWidth());
			if (mx >= hx && mx < hx + hudButtonWidth() && my >= sy && my < sy + UiTheme.SEARCH_H) {
				// The screen closes through its own outro first; the editor is
				// opened when that finishes, so the player never sees two screens
				// fight over the mouse.
				openHudEditor.run();
				return true;
			}
			int gx = settingsX(windowX(), windowWidth());
			if (mx >= gx && mx < gx + settingsButtonWidth() && my >= sy && my < sy + UiTheme.SEARCH_H) {
				guiSettingsOpen = !guiSettingsOpen;
				if (guiSettingsOpen) {
					GuiSounds.open();
				} else {
					GuiSounds.close();
				}
				return true;
			}
		}

		if (guiSettingsOpen) {
			if (guiSettingsClicked(mx, my)) {
				return true;
			}
			guiSettingsOpen = false;
		}

		// Search.
		if (button == 0 && search.contains(mx, my)) {
			GuiSounds.click();
			if (search.isOnClear(mx, my)) {
				search.clear();
			} else {
				search.setFocused(true);
			}
			return true;
		}
		search.setFocused(false);
		clearCardTextFocus();

		// Close button.
		if (button == 0) {
			int bx = wx + ww - UiTheme.SP_6 - UiTheme.CLOSE_BTN;
			int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
			if (mx >= bx && mx < bx + UiTheme.CLOSE_BTN && my >= by && my < by + UiTheme.CLOSE_BTN) {
				closeRequested = true;
				return true;
			}
		}

		// Sidebar rows. The row height comes from the same helper the renderer
		// uses, so a click always lands on the row that was actually drawn.
		int rowH = categoryRowHeight();
		int rowPitch = rowH + UiTheme.SP_1;
		int sx0 = categoryRowX();
		int sw = categoryRowW();
		int rowY = categoryListY();
		if (mx >= sx0 && mx < sx0 + sw && my >= rowY) {
			int index = (int) ((my - rowY) / rowPitch);
			int rowTop = rowY + index * rowPitch;
			if (index >= 0 && index < CATEGORIES.length && my < rowTop + rowH) {
				if (selected != index) {
					GuiSounds.tab();
				}
				selected = index;
				lastCategory = index;
				search.clear();
				return true;
			}
		}

		// Finder status panel action.
		if (button == 0 && finderStatusVisible() && finderStatusHeight() > 0 && finderStatusButtonW > 0
				&& mx >= finderStatusButtonX && mx < finderStatusButtonX + finderStatusButtonW
				&& my >= finderStatusButtonY && my < finderStatusButtonY + finderStatusButtonH) {
			FinderModule.toggleEliminationMap();
			GuiSounds.click();
			return true;
		}

		// Scrollbar rail.
		int railX = contentX() + contentW() + UiTheme.SP_3;
		if (mx >= railX - 3 && mx < railX + 8 && my >= contentListTop() && my < contentBottom()) {
			scrollbar.pressed(my);
			return true;
		}

		// Cards.
		int cx = contentX();
		int cw = contentW();
		// The same header and status heights the renderer uses, so a click can
		// never land one row off when either changes.
		int listTop = contentListTop();
		if (mx >= cx && mx < cx + cw && my >= listTop && my < contentBottom()) {
			List<ModuleCard> list = visibleCards();
			for (int i = list.size() - 1; i >= 0; i--) {
				ModuleCard card = list.get(i);
				if (card.contains(mx, my) && card.mouseClicked(mx, my, button)) {
					return true;
				}
			}
		}
		return true;
	}

	public boolean mouseDragged(double mx, double my) {
		if (volumeDragging) {
			setGuiVolume(mx);
			return true;
		}
		if (scrollbar.isDragging()) {
			scrollbar.dragged(my);
			return true;
		}
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				card.mouseDragged(mx, my);
			}
		}
		return true;
	}

	public boolean mouseReleased(double mx, double my) {
		if (volumeDragging) {
			volumeDragging = false;
			ClientConfig.saveNow();
		}
		scrollbar.released();
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				card.mouseReleased(mx, my);
			}
		}
		return true;
	}

	private boolean guiSettingsClicked(double mx, double my) {
		int w = Math.min(300, width - 24);
		int x = settingsPanelX;
		int y = settingsPanelY;
		if (mx < x || mx >= x + w || my < y || my >= y + SETTINGS_PANEL_H) {
			return false;
		}
		ClientConfig config = ClientConfig.get();
		for (int i = 0; i < SETTINGS_LABELS.length; i++) {
			int rowY = y + 34 + i * 32;
			if (my >= rowY && my < rowY + UiTheme.TOGGLE_H + 4) {
				if (i == 0) {
					config.setGuiSounds(!config.guiSounds());
				} else if (i == 1) {
					config.setReduceAnimations(!config.reduceAnimations());
				} else if (i == 2) {
					config.setBlur(!config.blur());
				} else if (i == 3) {
					config.setGlassDebug(!config.glassDebug());
				} else {
					config.setGuiDebug(!config.guiDebug());
				}
				ClientConfig.saveNow();
				GuiSounds.click();
				return true;
			}
		}
		int qualityY = y + 198;
		int qualityX = x + w - UiTheme.SP_5 - 92;
		if (my >= qualityY && my < qualityY + 22 && mx >= qualityX && mx < qualityX + 92) {
			GuiQuality[] all = GuiQuality.values();
			config.setGuiQuality(all[(config.guiQuality().ordinal() + 1) % all.length]);
			ClientConfig.saveNow();
			GuiSounds.tab();
			return true;
		}
		int trackX = x + UiTheme.SP_5 + 72;
		int trackW = w - UiTheme.SP_5 * 2 - 72;
		if (my >= y + 230 && my < y + 262 && mx >= trackX - 8 && mx < trackX + trackW + 8) {
			volumeDragging = true;
			setGuiVolume(mx);
			return true;
		}
		return true;
	}

	private void setGuiVolume(double mx) {
		int w = Math.min(300, width - 24);
		int trackX = settingsPanelX + UiTheme.SP_5 + 72;
		int trackW = w - UiTheme.SP_5 * 2 - 72;
		int value = Math.round((float) ((mx - trackX) / Math.max(1, trackW)) * 100f);
		ClientConfig.get().setGuiVolume(value);
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		// Only the workspace scrolls. A wheel turn over the sidebar used to move
		// the module list, which made the category panel feel like it was
		// dragging the content with it.
		int cx = contentX();
		int cw = contentW() + UiTheme.SP_6;
		if (mx < cx || mx >= cx + cw || my < contentTop() || my >= contentBottom()) {
			return false;
		}
		return scrollbar.onScroll(amount);
	}

	public boolean charTyped(CharacterEvent event) {
		if (search.charTyped(event)) {
			return true;
		}
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				if (card.charTyped(event)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Handles a key press. The client's own binding is only honoured when no
	 * text field owns the keyboard.
	 */
	public boolean keyPressed(KeyEvent event) {
		// Escape always closes, even while the search field has the keyboard.
		// It used to be swallowed by the field first, so the first Escape only
		// blurred the field and the window stayed open over a frozen world -
		// which reads as "the GUI will not close".
		if (event.isEscape()) {
			clearAllTextFocus();
			closeRequested = true;
			return true;
		}
		if (!hasTextInput() && ClientKeybinds.openClickGui() != null
				&& ClientKeybinds.openClickGui().matches(event)) {
			closeRequested = true;
			return true;
		}
		if (search.keyPressed(event)) {
			return true;
		}
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				if (card.keyPressed(event)) {
					return true;
				}
			}
		}
		return false;
	}

	/** True while a text field owns the keyboard. */
	public boolean hasTextInput() {
		if (search.isFocused()) {
			return true;
		}
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				if (card.hasTextInput()) {
					return true;
				}
			}
		}
		return false;
	}

	private void clearCardTextFocus() {
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				card.clearTextInput();
			}
		}
	}

	/** Drops the keyboard focus of every field, including the search box. */
	private void clearAllTextFocus() {
		search.setFocused(false);
		clearCardTextFocus();
	}

	}
