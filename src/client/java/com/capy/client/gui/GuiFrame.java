package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.Category;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
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
	/** Below this content width, cards use one full-width column. */
	private static final int CARD_TWO_COLUMN_MIN_W = 330;
	/** Window width that gives the workspace room for two usable columns. */
	private static final int TWO_COLUMN_WINDOW_MIN_W = 624;

	/** Height of the theme picker button at the foot of the sidebar. */
	private static final int THEME_PICKER_H = 26;
	/** Height of the "THEME" caption above that button. */
	private static final int THEME_CAPTION_H = 14;

	/** Everything below the last category row, from the caption down. */
	private static final int SIDEBAR_FOOTER_H = UiTheme.SP_8 + THEME_PICKER_H
			+ UiTheme.SP_2 + THEME_CAPTION_H + UiTheme.SP_3;

	/** The smallest a category row may get before it stops being usable. */
	private static final int CATEGORY_H_MIN = 15;
	/** Width and compact row geometry of the settings popover. */
	private static final int SETTINGS_PANEL_W = 336;
	private static final int SETTINGS_PANEL_H = 272;
	private static final int SETTINGS_PAD = 16;
	private static final int SETTINGS_COMPACT_TOP = 55;
	private static final int SETTINGS_COMPACT_PITCH = 24;
	private static final int SETTINGS_ROW_H = 22;
	private static final int SETTINGS_SOUND_Y = 58;
	private static final int SETTINGS_VOLUME_Y = 88;
	private static final int SETTINGS_ANIM_Y = 120;
	private static final int SETTINGS_SIZE_Y = 148;
	private static final int SETTINGS_QUALITY_Y = 176;
	private static final int SETTINGS_STEALTH_Y = 204;
	private static final int SETTINGS_HUD_Y = 236;
	private static final int SETTINGS_VOLUME_LABEL_W = 78;
	private static final int SETTINGS_VOLUME_PCT_W = 30;

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
	private final UiToggle stealthToggle = new UiToggle();
	private final Anim tabIndicator = new Anim(0f, 16f);
	private final String[] categoryCounts = new String[CATEGORIES.length];
	private int cachedEnabledCount = -1;
	private String cachedEnabledText = "0 active";
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
	private int layoutColumns = -1;
	private int layoutSelected = -1;
	private String layoutQuery = "";
	private boolean layoutDirty = true;
	/** Cards that survived the last render's viewport culling. */
	private int lastVisibleCards;

	private int selected;
	private int lastSelected = -1;
	private float switchProgress = 1f;
	private String cachedQuery;
	private Object hintBinding;
	private String hintCache = "? / ESC to close";
	private ModuleCard settingsCard;
	private final UiScrollbar settingsScrollbar = new UiScrollbar();
	private int settingsBackX;
	private int settingsBackY;
	private int settingsBackW = 62;
	private int settingsBackH = 24;
	private int settingsBodyX;
	private int settingsBodyY;
	private int settingsBodyW;
	private int settingsBodyH;
	private int settingsBtnX;
	private int settingsBtnY;
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
		closeSettingsPage();
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
		settingsScrollbar.reset();
		layoutDirty = true;
		windowHeight.snap(targetWindowHeight());
	}

	/** Selects a category by index (used by the offline preview). */
	void setCategory(int index) {
		if (index >= 0 && index < CATEGORIES.length) {
			closeSettingsPage();
			selected = index;
			lastCategory = index;
			layoutDirty = true;
		}
	}

	/** Opens one card's settings page (used by the offline preview). */
	void expandCard(Category category, int cardIndex) {
		List<ModuleCard> list = cards.get(category);
		if (list != null && cardIndex >= 0 && cardIndex < list.size()) {
			openSettingsPage(list.get(cardIndex));
		}
	}

	/** Fills the search field (used by the offline preview). */
	void setSearch(String query) {
		search.setQuery(query);
		layoutDirty = true;
	}

	/** Releases transient picker textures when the screen finishes closing. */
	public void close() {
		closeSettingsPage();
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				card.closePickers();
			}
		}
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
		// Size is applied as a uniform pose scale by the owning screen, so this
		// is the virtual viewport's width. Scaling it a second time here made
		// the large preset wider without making anything else larger.
		return windowWidthFor(width);
	}

	/**
	 * The shell uses the available viewport instead of a narrow fixed column.
	 * At the common 640-pixel virtual width this lands on 624 pixels, which is
	 * exactly what the workspace needs for two cards per row. The old 58%
	 * preference produced a one-column list, so the lower rows fell outside
	 * the clipped content area and looked chopped off.
	 */
	static int windowWidthFor(int viewportWidth) {
		int maxAllowed = Math.max(1, Math.min(760, viewportWidth - 16));
		int minWanted = Math.min(TWO_COLUMN_WINDOW_MIN_W, maxAllowed);
		int preferred = Math.round(Math.max(0, viewportWidth) * 0.72f);
		return Math.min(maxAllowed, Math.max(minWanted, Math.min(680, preferred)));
	}

	/** Workspace width after the sidebar and both gutters. */
	static int contentWidthFor(int windowWidth) {
		return windowWidth - UiTheme.SIDEBAR_W - UiTheme.SP_6 * 2 - UiTheme.SP_5;
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
		List<ModuleCard> list = visibleCards();
		int rows = cardRows(list.size());
		int content = rows * UiTheme.CARD_H + Math.max(0, rows - 1) * UiTheme.CARD_GAP;
		int needed = UiTheme.HEADER_H + UiTheme.SP_6 + SECTION_HEADER_H
				+ content + UiTheme.SP_6;
		// Use the room that is actually there. The old 82% cap left the second
		// card row below the viewport on a short screen even when the window had
		// enough space; only the outer 4% is kept as breathing room now.
		int max = Math.min(520, Math.max(1, Math.round(height * 0.92f)));
		int min = Math.min(max, sidebarMinHeight());
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
		return contentWidthFor(windowWidth());
	}

	private int contentTop() {
		return windowY() + UiTheme.HEADER_H + UiTheme.SP_6;
	}

	private int contentBottom() {
		return windowY() + windowHeight() - UiTheme.SP_5;
	}

	/** The first card pixel, below the section header. */
	private int contentListTop() {
		return contentTop() + SECTION_HEADER_H;
	}

	/**
	 * The settings sheet must fit the viewport before it can be useful. It
	 * shrinks from its designed height on short screens instead of being
	 * clamped to y=8 and painting its last rows off-screen.
	 */
	static int settingsPanelHeightFor(int viewportHeight) {
		return Math.max(1, Math.min(SETTINGS_PANEL_H, viewportHeight - 16));
	}

	static int settingsPanelYFor(int preferredTop, int viewportHeight) {
		int panelHeight = settingsPanelHeightFor(viewportHeight);
		int lowest = Math.max(8, viewportHeight - panelHeight - 8);
		return Math.max(8, Math.min(lowest, preferredTop));
	}

	/**
	 * Row tops relative to the panel. The compact layout is used whenever the
	 * sheet is shorter than its full height; it keeps every row above the
	 * bottom edge at a 240-pixel viewport.
	 */
	static int settingsRowTopOffset(int index, int panelHeight) {
		if (panelHeight < SETTINGS_PANEL_H) {
			return SETTINGS_COMPACT_TOP + index * SETTINGS_COMPACT_PITCH;
		}
		return switch (index) {
			case 0 -> SETTINGS_SOUND_Y;
			case 1 -> SETTINGS_VOLUME_Y;
			case 2 -> SETTINGS_ANIM_Y;
			case 3 -> SETTINGS_SIZE_Y;
			case 4 -> SETTINGS_QUALITY_Y;
			case 5 -> SETTINGS_STEALTH_Y;
			case 6 -> SETTINGS_HUD_Y;
			default -> 0;
		};
	}

	private int settingsPanelHeight() {
		return settingsPanelHeightFor(height);
	}

	private int settingsRowTop(int index) {
		return settingsPanelY + settingsRowTopOffset(index, settingsPanelHeight());
	}

	private int settingsRowHeight(int index) {
		if (settingsPanelHeight() < SETTINGS_PANEL_H) {
			return SETTINGS_ROW_H;
		}
		return switch (index) {
			case 0, 2, 5 -> UiTheme.TOGGLE_H + 6;
			case 6 -> 26;
			default -> SETTINGS_ROW_H;
		};
	}

	private void openSettingsPage(ModuleCard card) {
		if (card == null || !card.hasSettings()) {
			return;
		}
		closeSettingsPage();
		settingsCard = card;
		settingsScrollbar.reset();
		search.setFocused(false);
	}

	private void closeSettingsPage() {
		if (settingsCard != null) {
			settingsCard.clearTextInput();
			// A colour picker can own a GL texture; releasing it on every page
			// exit keeps the old popup's cleanup behaviour intact.
			settingsCard.closePickers();
			settingsCard = null;
		}
		settingsScrollbar.reset();
	}

	// ---------------------------------------------------------------- render

	/**
	 * Draws the whole window.
	 *
	 * @param open 0..1, drives the opening and closing transition
	 */
	public void render(UiCanvas c, UiType type, float mouseX, float mouseY, float delta, float open) {
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
			// A new page starts at its first row, not wherever the previous
			// page happened to be scrolled to.
			scrollbar.reset();
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

		if (guiSettingsOpen) {
			renderGuiSettings(c, type, mouseX, mouseY, delta);
		}
		// Last, so a tooltip floats above the settings page it belongs to.
		tooltip.render(c, type.regular());
	}

	/** Slow drift of the specular band, so the window never looks static. */
	private float sheenPhase() {
		double t = (System.currentTimeMillis() % 11000L) / 11000.0;
		return (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2.0));
	}

	private void renderHeader(UiCanvas c, UiType type, int wx, int wy, int ww, float mouseX, float mouseY, float delta) {
		// Two custom weights lead the band: a small uppercase wordmark above
		// the large page title. It gives the header a typographic hierarchy
		// without adding another panel or another icon.
		UiText display = type.display();
		UiText overlineFont = type.bold();
		UiText pageFont = type.medium();
		String pageTitle = settingsCard != null ? "Module settings"
				: search.normalisedQuery().isEmpty() ? CATEGORIES[selected].title : "Search results";
		int titleX = wx + UiTheme.SP_6;
		overlineFont.draw(c, "CAPY / CLIENT", titleX, wy + 8,
				Ui.withAlpha(UiTheme.ACCENT_2, 0xC8));
		int titleY = wy + 20;
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
		// it follows a toggle immediately. It is status, not a button, so it
		// deliberately has no hover state.
		String count = enabledText();
		int pillW = pageFont.width(count) + UiTheme.SP_4;
		int pillH = 18;
		int pillX = titleX + titleW + UiTheme.SP_4;
		int pillY = wy + 20;
		if (!compactHeader() && pillX + pillW < settingsX(wx, ww) - UiTheme.SP_4) {
			Ui.surface(c, pillX, pillY, pillW, pillH, 3, UiTheme.TRACK_TOP, UiTheme.TRACK_BOTTOM);
			Ui.outline(c, pillX, pillY, pillW, pillH, 3, pillBorder());
			Ui.drawCentered(c, pageFont, count, pillX + pillW / 2,
					Ui.centerTextY(pillY, pillH, pageFont), UiTheme.ACCENT);
		}

		int fieldX = searchX(wx, ww);

		// The settings button sits between the page title and the search field.
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

	/** A quiet rim for the non-interactive status pill. */
	private static int pillBorder() {
		return Ui.withAlpha(UiTheme.BORDER, 0x70);
	}

	private String enabledText() {
		int count = enabledCount();
		if (count != cachedEnabledCount) {
			cachedEnabledCount = count;
			cachedEnabledText = count + " active";
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
		return compactHeader() ? 70 : 92;
	}

	/** The settings button: plain text, no branding mark. */
	private void renderSettingsButton(UiCanvas c, UiType type, int wx, int wy, int ww,
			float mouseX, float mouseY) {
		int x = settingsX(wx, ww);
		int y = wy + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2;
		int w = settingsButtonWidth();
		int h = UiTheme.SEARCH_H;
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		settingsBtnX = x;
		settingsBtnY = y;
		Ui.surface(c, x, y, w, h, UiTheme.RADIUS,
				hovered || guiSettingsOpen ? Ui.withAlpha(UiTheme.ACCENT, 0x2E) : UiTheme.TRACK_TOP,
				hovered || guiSettingsOpen ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x24) : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, x, y, w, h, UiTheme.RADIUS,
				hovered || guiSettingsOpen ? UiTheme.ACCENT : UiTheme.BORDER);
		int ink = hovered || guiSettingsOpen ? UiTheme.TEXT : UiTheme.TEXT_2;
		UiText regular = type.regular();
		Ui.drawCentered(c, regular, "Settings", x + w / 2,
				Ui.centerTextY(y, h, regular), ink);
	}

	private void renderGuiSettings(UiCanvas c, UiType type, float mouseX, float mouseY, float delta) {
		ClientConfig config = ClientConfig.get();
		int w = Math.min(SETTINGS_PANEL_W, width - 24);
		int h = settingsPanelHeight();
		settingsPanelX = settingsPanelX(w);
		settingsPanelY = settingsPanelYFor(settingsBtnY + UiTheme.SEARCH_H + UiTheme.SP_2, height);
		int x = settingsPanelX;
		int y = settingsPanelY;
		Ui.shadow(c, x, y, w, h, UiTheme.RADIUS_LG);
		// A floating menu has to win over the card text behind it. The rest of
		// the interface is deliberately glassy, but a control sheet at 70% alpha
		// let the module names read through every row and made the values hard
		// to scan. This is the same material, just opaque enough to be a menu.
		Ui.surface(c, x, y, w, h, UiTheme.RADIUS_LG,
				Ui.withAlpha(UiTheme.PANEL_TOP, 0xFF), Ui.withAlpha(UiTheme.PANEL_BOTTOM, 0xFF));
		Ui.outline(c, x, y, w, h, UiTheme.RADIUS_LG, UiTheme.BORDER);
		Ui.sheen(c, x, y, w, h, UiTheme.RADIUS_LG, sheenPhase());
		Ui.surface(c, x + UiTheme.RADIUS_LG, y + 1, w - UiTheme.RADIUS_LG * 2, 1, 0,
				Ui.withAlpha(UiTheme.ACCENT, 0x73));

		UiText display = type.display();
		UiText bold = type.bold();
		UiText regular = type.regular();
		display.draw(c, "INTERFACE", x + SETTINGS_PAD, y + 12, UiTheme.TEXT);
		regular.draw(c, "Changes save instantly", x + SETTINGS_PAD, y + 30, UiTheme.TEXT_3);
		Ui.drawRightAligned(c, regular, "ESC", x + w - SETTINGS_PAD, y + 30,
				Ui.withAlpha(UiTheme.TEXT_3, 0xB0));
		Ui.fadeLine(c, x + SETTINGS_PAD, y + 47, w - SETTINGS_PAD * 2,
				Ui.withAlpha(UiTheme.BORDER, 0x8C));

		int rowX = x + SETTINGS_PAD;
		int rowW = w - SETTINGS_PAD * 2;
		int toggleX = x + w - SETTINGS_PAD - UiTheme.TOGGLE_W;
		drawSettingToggle(c, type.medium(), "Sounds", soundsToggle, config.guiSounds(),
				rowX, settingsRowTop(0), rowW, toggleX, mouseX, mouseY, delta,
				settingsRowHeight(0));

		int volumeY = settingsRowTop(1);
		regular.draw(c, "Volume", rowX, Ui.centerTextY(volumeY, SETTINGS_ROW_H, regular), UiTheme.TEXT_2);
		int trackX = settingsVolumeTrackX(x);
		int trackY = volumeY + 11;
		int trackW = settingsVolumeTrackW(x, w);
		Ui.surface(c, trackX, trackY - 3, trackW, 6, 2, UiTheme.TRACK_TOP, UiTheme.TRACK_BOTTOM);
		int fill = Math.max(0, Math.min(trackW,
				Math.round(trackW * config.guiVolume() / 100f)));
		if (fill > 0) {
			Ui.surface(c, trackX, trackY - 3, fill, 6, 2, UiTheme.ACCENT, UiTheme.ACCENT_DEEP);
		}
		int knobX = trackX + Math.max(0, Math.min(trackW - 4, fill - 2));
		Ui.surface(c, knobX, trackY - 6, 4, 12, 1, UiTheme.ACCENT_2);
		Ui.drawRightAligned(c, regular, config.guiVolume() + "%", x + w - SETTINGS_PAD,
				Ui.centerTextY(volumeY, SETTINGS_ROW_H, regular), UiTheme.ACCENT_2);

		drawSettingToggle(c, type.medium(), "Reduce animations", animationsToggle,
				config.reduceAnimations(), rowX, settingsRowTop(2), rowW, toggleX,
				mouseX, mouseY, delta, settingsRowHeight(2));

		int pillW = 104;
		int pillX = x + w - SETTINGS_PAD - pillW;
		drawSettingsStepper(c, regular, "Window size", config.guiSize().label(), rowX,
				settingsRowTop(3), pillX, pillW, mouseX, mouseY);
		drawSettingsStepper(c, regular, "Quality", config.guiQuality().name(), rowX,
				settingsRowTop(4), pillX, pillW, mouseX, mouseY);
		drawSettingToggle(c, type.medium(), "Stealth mode", stealthToggle, config.stealth(),
				rowX, settingsRowTop(5), rowW, toggleX, mouseX, mouseY, delta,
				settingsRowHeight(5));

		int hudY = settingsRowTop(6);
		int hudH = settingsRowHeight(6);
		boolean hudHovered = mouseX >= rowX && mouseX < rowX + rowW
				&& mouseY >= hudY && mouseY < hudY + hudH;
		Ui.surface(c, rowX, hudY, rowW, hudH, 3,
				hudHovered ? Ui.withAlpha(UiTheme.ACCENT, 0x2E) : UiTheme.TRACK_TOP,
				hudHovered ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x22) : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, rowX, hudY, rowW, hudH, 3,
				hudHovered ? UiTheme.ACCENT : UiTheme.BORDER);
		Ui.drawCentered(c, bold, "Edit HUD", rowX + rowW / 2,
				Ui.centerTextY(hudY, hudH, bold), hudHovered ? UiTheme.TEXT : UiTheme.TEXT_2);
	}

	/**
	 * Anchors the sheet under the Settings button while it is wide enough to
	 * live in the workspace. Overlapping half the sidebar made the navigation
	 * look broken; when the content column is narrower than the sheet there is
	 * nothing to protect, so it centers in the viewport instead.
	 */
	private int settingsPanelX(int panelWidth) {
		if (contentW() < panelWidth) {
			return Math.max(8, (width - panelWidth) / 2);
		}
		int min = contentX();
		int max = min + contentW() - panelWidth;
		int anchored = settingsBtnX + settingsButtonWidth() - panelWidth;
		return Math.max(min, Math.min(max, anchored));
	}

	private void drawSettingToggle(UiCanvas c, UiText font, String label, UiToggle toggle,
			boolean value, int rowX, int rowY, int rowW, int toggleX,
			float mouseX, float mouseY, float delta, int rowHeight) {
		boolean hovered = mouseX >= rowX && mouseX < rowX + rowW
				&& mouseY >= rowY && mouseY < rowY + rowHeight;
		toggle.update(value, hovered, delta);
		toggle.render(c, toggleX, rowY, UiTheme.TOGGLE_W, UiTheme.TOGGLE_H);
		font.draw(c, label, rowX,
				Ui.centerTextY(rowY, UiTheme.TOGGLE_H, font),
				hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
	}

	private static int settingsVolumeTrackX(int x) {
		return x + SETTINGS_PAD + SETTINGS_VOLUME_LABEL_W;
	}

	private static int settingsVolumeTrackW(int x, int w) {
		int trackX = settingsVolumeTrackX(x);
		return Math.max(24, x + w - SETTINGS_PAD - SETTINGS_VOLUME_PCT_W - 8 - trackX);
	}

	/** One label plus a click-to-cycle control, using the same blocky radius. */
	private void drawSettingsStepper(UiCanvas c, UiText font, String label, String value,
			int labelX, int rowY, int x, int w, float mouseX, float mouseY) {
		font.draw(c, label, labelX, Ui.centerTextY(rowY, 22, font), UiTheme.TEXT_2);
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + 22;
		Ui.surface(c, x, rowY, w, 22, 3,
				hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				hovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, x, rowY, w, 22, 3, hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		Ui.drawCentered(c, font, value, x + w / 2, Ui.centerTextY(rowY, 22, font),
				hovered ? UiTheme.TEXT : UiTheme.ACCENT_2);
	}
	private void renderSidebar(UiCanvas c, UiType type, int wx, int wy, int ww, int wh, float mouseX, float mouseY,
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
				Ui.surface(c, bx, by, bw, 14, 3,
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
		Ui.drawEllipsized(c, type.regular(), hint, hintX, hintY,
				sw - UiTheme.SP_4, UiTheme.TEXT_3);
		boolean hintHovered = mouseX >= hintX && mouseX < hintX + sw - UiTheme.SP_4
				&& mouseY >= hintY - 2 && mouseY < hintY + type.regular().lineHeight() + 2;
		if (hintHovered) {
			tooltip.show("Esc closes the GUI. Rebind the key in Options - Controls.", hintX, hintY);
		} else {
			tooltip.hide();
		}
	}

	private void renderContent(UiCanvas c, UiType type, float mouseX, float mouseY, float delta, float slide,
			float reveal) {
		int cx = contentX();
		int top = contentTop() + Math.round(slide);
		int bottom = contentBottom();
		int cw = contentW();
		int viewportH = Math.max(0, bottom - top);

		List<ModuleCard> list = visibleCards();
		boolean showCategory = !search.normalisedQuery().isEmpty();
		boolean settingsOpen = settingsCard != null;

		if (settingsOpen) {
			// The window header already names this page, so a second
			// "MODULE SETTINGS" bar above it only steals a row from the
			// controls. The page starts directly under the header instead.
			renderSettingsPage(c, type, cx, top, cw, bottom, mouseX, mouseY, delta);
			return;
		}

		// The section header: what is shown and how much of it there is.
		int headerH = SECTION_HEADER_H;
		UiText bold = type.bold();
		UiText meta = type.regular();
		String title = showCategory ? "SEARCH RESULTS" : CATEGORIES[selected].title.toUpperCase(Locale.ROOT);
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
		Ui.surface(c, pillX, pillY, pillW, pillH, 3,
				Ui.withAlpha(UiTheme.PANEL_ALT, 0xB8), Ui.withAlpha(UiTheme.PANEL_TOP, 0x94));
		Ui.outline(c, pillX, pillY, pillW, pillH, 3, Ui.withAlpha(UiTheme.BORDER, 0x8C));
		Ui.drawCentered(c, meta, countLabel, pillX + pillW / 2,
				pillY + (pillH - meta.lineHeight()) / 2, UiTheme.TEXT_3);
		// A delicate line under the section header.
		Ui.fadeLine(c, cx, top + headerH - 6, cw, Ui.withAlpha(UiTheme.BORDER, 0xC0));

		int listTop = top + headerH;
		int listH = Math.max(0, bottom - listTop);

		// Content that moves between pages slides a little.
		float pageSlide = showCategory ? 0f : (1f - switchProgress) * 10f;

		ensureCardLayout(list);

		scrollbar.update(listTop, bottom, layoutContentH, delta);
		scrollbar.setRailX(cx + cw + UiTheme.SP_3);

		if (list.isEmpty()) {
			renderEmptyState(c, type, cx + cw / 2, listTop + listH / 2, showCategory);
			return;
		}

		c.pushClip(cx, listTop, cw, listH);
		int listY = listTop - (int) Math.round(scrollbar.scroll()) + Math.round(pageSlide);
		// Two cards per row only while each column can still show a full name;
		// below that the grid becomes one full-width column. The same helper is
		// used by the layout and the window-height calculation.
		int columns = cardColumns();
		int colW = Math.max(80, (cw - UiTheme.CARD_GAP * (columns - 1)) / columns);
		int drawn = 0;
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			int y = listY + layoutTops[i];
			if (VirtualList.intersects(y, UiTheme.CARD_H, listTop - 40, bottom)) {
				int cardX = cx + (i % columns) * (colW + UiTheme.CARD_GAP);
				card.setReveal(Motion.clamp01(reveal * 1.35f - Math.min(i / columns, 10) * 0.035f));
				card.render(c, type, cardX, y, colW, mouseX, mouseY, delta);
				GuiPerf.cardDrawn();
				drawn++;
			}
		}
		lastVisibleCards = drawn;
		c.popClip();

		scrollbar.render(c);
	}

	/** Test hook: the exact list object the next frame will render. */
	List<ModuleCard> visibleCardsForTest() {
		return visibleCards();
	}

	/** Test hook: a copy of the retained card rows. */
	int[] layoutTopsForTest() {
		ensureCardLayout(visibleCards());
		return layoutTops.clone();
	}

	/** Test hook: cards that survived the last frame's culling. */
	int visibleCardCountForTest() {
		return lastVisibleCards;
	}

	/** A full-workspace settings page, replacing the card grid instead of floating over it. */
	private void renderSettingsPage(UiCanvas c, UiType type, int x, int y, int w, int bottom,
			float mouseX, float mouseY, float delta) {
		int h = Math.max(0, bottom - y);
		Ui.shadow(c, x, y, w, h, UiTheme.RADIUS_LG);
		Ui.surface(c, x, y, w, h, UiTheme.RADIUS_LG, UiTheme.WELL_TOP, UiTheme.WELL_BOTTOM);
		Ui.surfaceEdge(c, x, y, w, h, UiTheme.RADIUS_LG,
				Ui.withAlpha(UiTheme.ACCENT, 0xB0), Ui.withAlpha(UiTheme.BORDER, 0x44));
		Ui.sheen(c, x, y, w, h, UiTheme.RADIUS_LG, sheenPhase());

		int pad = UiTheme.SP_6;
		settingsBackX = x + pad;
		settingsBackY = y + pad;
		settingsBackW = 62;
		settingsBackH = 24;
		boolean backHovered = mouseX >= settingsBackX && mouseX < settingsBackX + settingsBackW
				&& mouseY >= settingsBackY && mouseY < settingsBackY + settingsBackH;
		Ui.surface(c, settingsBackX, settingsBackY, settingsBackW, settingsBackH, 7,
				backHovered ? Ui.withAlpha(UiTheme.ACCENT, 0x36) : UiTheme.TRACK_TOP,
				backHovered ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x28) : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, settingsBackX, settingsBackY, settingsBackW, settingsBackH, 7,
				backHovered ? UiTheme.ACCENT : UiTheme.BORDER);
		UiIcons.draw(c, UiIcons.Icon.BACK, settingsBackX + 8,
				settingsBackY + (settingsBackH - 10) / 2, 10,
				backHovered ? UiTheme.TEXT : UiTheme.TEXT_2);
		Ui.drawEllipsized(c, type.regular(), "Back", settingsBackX + 21,
				Ui.centerTextY(settingsBackY, settingsBackH, type.regular()),
				settingsBackW - 25, backHovered ? UiTheme.TEXT : UiTheme.TEXT_2);

		UiText titleFont = type.bold();
		String title = settingsCard.title();
		int count = settingsCard.visibleSettingCount();
		String countLabel = count + (count == 1 ? " option" : " options");
		int countWidth = type.regular().width(countLabel);
		// Reserve the count column before measuring the title. Measuring only
		// against the full inner width let a long module name run under the
		// "N options" label on narrow pages.
		int titleRoom = Math.max(0, w - pad * 2 - countWidth - UiTheme.SP_4);
		if (titleFont.width(title) > titleRoom) {
			titleFont = type.medium();
		}
		int titleY = settingsBackY + settingsBackH + UiTheme.SP_3;
		if (!title.isEmpty() && titleRoom > 0) {
			Ui.drawEllipsized(c, titleFont, title, x + pad, titleY, titleRoom, UiTheme.TEXT);
		}
		Ui.drawRightAligned(c, type.regular(), countLabel,
				x + w - pad, titleY + (titleFont.lineHeight() - type.regular().lineHeight()) / 2,
				UiTheme.TEXT_3);

		int ruleY = titleY + titleFont.lineHeight() + UiTheme.SP_3;
		Ui.fadeLine(c, x + pad, ruleY, w - pad * 2, Ui.withAlpha(UiTheme.BORDER, 0xA0));
		settingsBodyX = x + 1;
		settingsBodyY = ruleY + UiTheme.SP_3;
		settingsBodyW = w - 2;
		settingsBodyH = Math.max(1, bottom - settingsBodyY - UiTheme.SP_3);

		settingsScrollbar.update(settingsBodyY, settingsBodyY + settingsBodyH,
				settingsCard.settingsHeight() + UiTheme.SP_4, delta);
		settingsScrollbar.setRailX(x + w - 14);
		c.pushClip(x + 1, settingsBodyY, w - 2, settingsBodyH);
		int contentW = Math.max(60, w - pad * 2 - 14);
		settingsCard.renderSettingsContent(c, type, x + pad, settingsBodyY, contentW,
				(int) Math.round(settingsScrollbar.scroll()), mouseX, mouseY, delta);
		// Soften the cut-off row at the bottom of the scroll viewport. Without
		// this, a toggle sliced by the clip reads as a stray accent line rather
		// than as content that continues below.
		int fadeH = Math.min(18, settingsBodyH / 4);
		if (fadeH > 0 && settingsScrollbar.usable()) {
			c.gradient(x + 1, settingsBodyY + settingsBodyH - fadeH,
					x + w - 1, settingsBodyY + settingsBodyH,
					Ui.withAlpha(UiTheme.WELL_BOTTOM, 0x00),
					Ui.withAlpha(UiTheme.WELL_BOTTOM, 0xE6));
		}
		c.popClip();
		String rowTip = settingsCard.hoveredTip();
		if (rowTip != null && !rowTip.isEmpty()) {
			tooltip.show(rowTip, settingsCard.hoveredTipX(), settingsCard.hoveredTipY());
		}
		settingsScrollbar.render(c);
	}

	private void ensureCardLayout(List<ModuleCard> list) {
		int columns = cardColumns();
		boolean sizeChanged = list.size() != layoutTops.length;
		if (!layoutDirty && !sizeChanged
				&& layoutWidth == width && layoutHeight == height
				&& layoutColumns == columns && layoutSelected == selected
				&& layoutQuery.equals(search.query())) {
			return;
		}
		if (sizeChanged) {
			layoutTops = new int[list.size()];
			layoutHeights = new int[list.size()];
		}
		int rows = cardRows(list.size());
		for (int i = 0; i < list.size(); i++) {
			layoutTops[i] = (i / columns) * (UiTheme.CARD_H + UiTheme.CARD_GAP);
			layoutHeights[i] = UiTheme.CARD_H;
		}
		layoutContentH = rows == 0 ? 0
				: rows * UiTheme.CARD_H + (rows - 1) * UiTheme.CARD_GAP;
		layoutWidth = width;
		layoutHeight = height;
		layoutColumns = columns;
		layoutSelected = selected;
		layoutQuery = search.query();
		layoutDirty = false;
	}

	/**
	 * Two columns only when each one leaves enough room for the longest module
	 * name plus the switch. The threshold is measured in content pixels, not
	 * window pixels, so it also holds while the shell is clamped to a small
	 * screen.
	 */
	private int cardColumns() {
		return cardColumnsFor(contentW());
	}

	static int cardColumnsFor(int contentWidth) {
		return contentWidth >= CARD_TWO_COLUMN_MIN_W ? 2 : 1;
	}

	private int cardRows(int cardCount) {
		int columns = cardColumns();
		return (cardCount + columns - 1) / columns;
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
			scrollbar.reset();
			cachedMatches.clear();
			for (Category category : CATEGORIES) {
				for (ModuleCard card : cards.get(category)) {
					if (card.title().toLowerCase(Locale.ROOT).contains(q)
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
	public boolean mouseClicked(MouseButtonEvent event, double mx, double my) {
		int button = event.button();

		int wx = windowX();
		int wy = windowY();
		int ww = windowWidth();
		int wh = windowHeight();
		if (mx < wx || mx >= wx + ww || my < wy || my >= wy + wh) {
			// Only a click on the scrim may use the mouse-bound GUI key. Doing
			// this before the bounds check meant a player who bound the GUI to
			// a mouse button closed the window on every card press.
			if (ClientKeybinds.openClickGui() != null
					&& ClientKeybinds.openClickGui().matchesMouse(event)) {
				closeRequested = true;
			}
			return true; // swallow clicks outside the window
		}

		// The header chips are checked before the search field because they sit
		// on the same row.
		if (button == 0) {
			int sy = windowY() + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2;
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

		// Close button. This is checked before the global settings sheet: the
		// sheet is an overlay, but the window's own close control stays live
		// while it is open. Previously the sheet swallowed the click first, so
		// its Close X only dismissed the overlay and never the GUI.
		if (button == 0) {
			int bx = wx + ww - UiTheme.SP_6 - UiTheme.CLOSE_BTN;
			int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
			if (mx >= bx && mx < bx + UiTheme.CLOSE_BTN && my >= by && my < by + UiTheme.CLOSE_BTN) {
				closeRequested = true;
				return true;
			}
		}

		if (guiSettingsOpen) {
			if (guiSettingsClicked(mx, my)) {
				return true;
			}
			// Clicking away closes the sheet without also toggling whatever
			// card happened to sit under the pointer.
			guiSettingsOpen = false;
			GuiSounds.close();
			return true;
		}

		// Search.
		if (button == 0 && search.contains(mx, my)) {
			GuiSounds.click();
			closeSettingsPage();
			if (search.isOnClear(mx, my)) {
				search.clear();
			} else {
				search.setFocused(true);
			}
			return true;
		}
		search.setFocused(false);
		clearCardTextFocus();

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
				closeSettingsPage();
				selected = index;
				lastCategory = index;
				search.clear();
				return true;
			}
		}

		// The settings page owns the workspace while it is open.
		if (settingsCard != null) {
			if (button == 0 && mx >= settingsBackX && mx < settingsBackX + settingsBackW
					&& my >= settingsBackY && my < settingsBackY + settingsBackH) {
				closeSettingsPage();
				GuiSounds.close();
				return true;
			}
			int settingsRailX = contentX() + contentW() - 14;
			if (button == 0 && mx >= settingsRailX - 3 && mx < settingsRailX + 7
					&& my >= settingsBodyY && my < settingsBodyY + settingsBodyH) {
				settingsScrollbar.pressed(my);
				return true;
			}
			// Only the left button operates a control. Forwarding every button
			// let a right-click toggle a switch or move a slider.
			if (button == 0 && my >= settingsBodyY && my < settingsBodyY + settingsBodyH) {
				settingsCard.settingsMouseClicked(mx, my);
			}
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
					if (card.consumeSettingsRequest()) {
						openSettingsPage(card);
					}
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
		if (settingsCard != null) {
			if (settingsScrollbar.isDragging()) {
				settingsScrollbar.dragged(my);
			} else {
				settingsCard.mouseDragged(mx, my);
			}
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
		if (settingsCard != null) {
			settingsScrollbar.released();
			settingsCard.mouseReleased(mx, my);
			return true;
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
		int w = Math.min(SETTINGS_PANEL_W, width - 24);
		int x = settingsPanelX;
		int y = settingsPanelY;
		int h = settingsPanelHeight();
		if (mx < x || mx >= x + w || my < y || my >= y + h) {
			return false;
		}
		ClientConfig config = ClientConfig.get();
		int rowX = x + SETTINGS_PAD;
		int rowW = w - SETTINGS_PAD * 2;
		int toggleX = x + w - SETTINGS_PAD - UiTheme.TOGGLE_W;
		if (rowHit(mx, my, rowX, settingsRowTop(0), rowW, settingsRowHeight(0))) {
			config.setGuiSounds(!config.guiSounds());
			ClientConfig.saveNow();
			GuiSounds.toggle(config.guiSounds());
			return true;
		}
		int volumeY = settingsRowTop(1);
		if (my >= volumeY && my < volumeY + settingsRowHeight(1)) {
			int trackX = settingsVolumeTrackX(x);
			if (mx >= trackX - 6 && mx < x + w - SETTINGS_PAD) {
				volumeDragging = true;
				setGuiVolume(mx);
				return true;
			}
		}
		if (rowHit(mx, my, rowX, settingsRowTop(2), rowW, settingsRowHeight(2))) {
			config.setReduceAnimations(!config.reduceAnimations());
			ClientConfig.saveNow();
			GuiSounds.toggle(config.reduceAnimations());
			return true;
		}
		int pillW = 104;
		int pillX = x + w - SETTINGS_PAD - pillW;
		int sizeY = settingsRowTop(3);
		if (rowHit(mx, my, pillX, sizeY, pillW, SETTINGS_ROW_H)) {
			GuiSize[] all = GuiSize.values();
			config.setGuiSize(all[(config.guiSize().ordinal() + 1) % all.length]);
			ClientConfig.saveNow();
			GuiSounds.tab();
			return true;
		}
		int qualityY = settingsRowTop(4);
		if (rowHit(mx, my, pillX, qualityY, pillW, SETTINGS_ROW_H)) {
			GuiQuality[] all = GuiQuality.values();
			config.setGuiQuality(all[(config.guiQuality().ordinal() + 1) % all.length]);
			ClientConfig.saveNow();
			GuiSounds.tab();
			return true;
		}
		if (rowHit(mx, my, rowX, settingsRowTop(5), rowW, settingsRowHeight(5))) {
			config.setStealth(!config.stealth());
			ClientConfig.saveNow();
			GuiSounds.toggle(config.stealth());
			return true;
		}
		int hudY = settingsRowTop(6);
		if (rowHit(mx, my, rowX, hudY, rowW, settingsRowHeight(6))) {
			guiSettingsOpen = false;
			GuiSounds.click();
			openHudEditor.run();
			return true;
		}
		return true;
	}

	private static boolean rowHit(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private void setGuiVolume(double mx) {
		int w = Math.min(SETTINGS_PANEL_W, width - 24);
		int trackX = settingsVolumeTrackX(settingsPanelX);
		int trackW = settingsVolumeTrackW(settingsPanelX, w);
		int value = Math.round((float) ((mx - trackX) / Math.max(1, trackW)) * 100f);
		ClientConfig.get().setGuiVolume(value);
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (guiSettingsOpen) {
			int w = Math.min(SETTINGS_PANEL_W, width - 24);
			if (mx >= settingsPanelX && mx < settingsPanelX + w
					&& my >= settingsPanelY && my < settingsPanelY + settingsPanelHeight()) {
				return true;
			}
		}
		if (settingsCard != null) {
			return my >= settingsBodyY && my < settingsBodyY + settingsBodyH
					&& settingsScrollbar.onScroll(amount);
		}
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
		if (settingsCard != null) {
			return settingsCard.charTyped(event);
		}
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
			// Escape backs out one layer at a time: the settings sheet, then a
			// module page, then the window.
			if (guiSettingsOpen) {
				guiSettingsOpen = false;
				GuiSounds.close();
				return true;
			}
			if (settingsCard != null) {
				closeSettingsPage();
				GuiSounds.close();
				return true;
			}
			clearAllTextFocus();
			closeRequested = true;
			return true;
		}
		if (!hasTextInput() && ClientKeybinds.openClickGui() != null
				&& ClientKeybinds.openClickGui().matches(event)) {
			closeRequested = true;
			return true;
		}
		if (settingsCard != null) {
			return settingsCard.keyPressed(event);
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
		if (settingsCard != null) {
			return settingsCard.hasTextInput();
		}
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
