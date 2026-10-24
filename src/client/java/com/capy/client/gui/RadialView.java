package com.capy.client.gui;

import com.capy.client.module.Category;
import java.util.List;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * The radial GUI style: a lit accent disc with the Capy wordmark, a ring of
 * category nodes around it, and a module sheet beside the ring.
 *
 * Navigation is deliberately two-step, matching the way the style reads:
 *
 *   <ol>
 *     <li>the hub shows one node per category, each with its own mark and its
 *         own tint, so a category can be recognised before its label is read;</li>
 *     <li>pressing a node opens the category's module sheet. The hub stays in
 *         place, so the user can switch categories without backing out first;
 *         pressing the centre (or the back arrow) returns to the hub.</li>
 *   </ol>
 *
 * The sheet is the ordinary {@link ModuleCard} list, not a second kind of
 * module control. Toggling, expanding, colour pickers, watchlists and text
 * fields all therefore behave exactly like they do in the window styles; the
 * radial view only changes how a category is chosen.
 *
 * Every colour that belongs to the ring comes from {@link GuiColors}. The
 * category nodes additionally use their category tint ({@link Category#accent})
 * so the ring is not monochrome.
 */
public final class RadialView {
	/** Supplies the card list for a category; the frame owns the cards. */
	public interface CardSource {
		List<ModuleCard> cards(Category category);
	}

	private static final Category[] CATEGORIES = Category.values();
	/** Node radius at a 1080p sized canvas; scaled for smaller screens. */
	private static final int NODE_RADIUS = 30;
	private static final int CLOSE_SIZE = 28;
	private static final int STYLE_H = 28;

	private final CardSource source;
	private final UiAccentPanel accentPanel;
	private final Runnable closeAction;
	private final Runnable cycleStyleAction;
	private final UiScrollbar scrollbar = new UiScrollbar();
	private final float[] hover = new float[CATEGORIES.length];
	private final float[] active = new float[CATEGORIES.length];
	private final Anim panelReveal = new Anim(0f, 13f);

	private int width;
	private int height;
	private int centerX;
	private int centerY;
	private int centerR;
	private int ringR;
	private int nodeR = 27;
	private int labelR;
	/**
	 * False when the sheet leaves too little room for the ring. On a very
	 * narrow canvas the sheet becomes the whole view instead of covering half
	 * of a ring that cannot fit beside it.
	 */
	private boolean hubVisible = true;
	/** Seconds since the style was shown, for the slow orbit motion. */
	private float clock;
	private final int[] nodeX = new int[CATEGORIES.length];
	private final int[] nodeY = new int[CATEGORIES.length];

	private int closeX;
	private int closeY;
	private int styleX;
	private int styleY;
	private int styleW;
	private int paletteX;
	private int paletteY;
	private int paletteR = 17;

	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int listTop;
	private int listBottom;
	private int railX;
	private int backX;
	private int backY;
	private static final int BACK_SIZE = 22;

	private int selected = -1;

	public RadialView(CardSource source, UiAccentPanel accentPanel,
			Runnable closeAction, Runnable cycleStyleAction) {
		this.source = source;
		this.accentPanel = accentPanel;
		this.closeAction = closeAction;
		this.cycleStyleAction = cycleStyleAction;
	}

	public void resize(int width, int height) {
		this.width = width;
		this.height = height;
	}

	/** Called when the style is switched to the ring, so the sheet starts closed. */
	public void onShown() {
		selected = -1;
		panelReveal.snap(0f);
		scrollbar.reset();
		clock = 0f;
	}

	/** Selects a category without a click (used by the offline preview). */
	void select(int index) {
		selected = index >= 0 && index < CATEGORIES.length ? index : -1;
		scrollbar.reset();
	}

	/** Expands one card of the open sheet (used by the offline preview). */
	void expand(int cardIndex) {
		if (selected < 0) {
			return;
		}
		List<ModuleCard> list = source.cards(CATEGORIES[selected]);
		if (list != null && cardIndex >= 0 && cardIndex < list.size()) {
			list.get(cardIndex).setExpanded(true);
		}
	}

	// ---------------------------------------------------------------- layout

	/** How far a category label sits outside its own node. */
	private int labelGap() {
		return Math.max(12, Math.round(nodeR * 0.70f));
	}

	private void layout() {
		int min = Math.min(width, height);
		centerY = height / 2;
		// Everything in the ring scales with the short side. The old floor of
		// 92 pixels was larger than the whole height at a 640x360 GUI scale /
		// 3 canvas, which pushed the top and bottom labels off screen.
		centerR = Math.max(60, Math.min(210, Math.round(min * 0.205f)));
		nodeR = Math.max(16, Math.min(NODE_RADIUS, Math.round(min * 0.052f)));
		int nodeGap = Math.max(38, Math.round(min * 0.105f));
		ringR = centerR + Math.min(112, nodeGap);

		closeX = width - 22 - CLOSE_SIZE;
		closeY = 18;
		styleX = 22;
		styleY = 18;
		styleW = 104;
		paletteR = Math.max(15, Math.min(20, centerR / 8));
		paletteX = width - paletteR - 26;
		paletteY = height - paletteR - 24;

		labelR = ringR + nodeR + labelGap();
		// The labels sit outside the ring, so the whole ring has to fit in
		// half the screen with room for the label line above it. On a
		// 427x240 canvas (1280x720 at GUI scale 3) the floors above push
		// labelR past the edge, and the clamp in renderRing then dropped the
		// top label straight onto its own node and the bottom label across
		// its own. The ring gives way instead of the label.
		int maxLabelR = height / 2 - 13;
		if (labelR > maxLabelR) {
			int overflow = labelR - maxLabelR;
			ringR = Math.max(24, ringR - overflow);
			// The disc must stay clear of the nodes that ride on the ring.
			centerR = Math.min(centerR, Math.max(24, ringR - nodeR - 4));
			labelR = ringR + nodeR + labelGap();
		}
		panelW = Math.max(250, Math.min(420, Math.round(width * 0.34f)));
		panelW = Math.min(panelW, width - 32);
		int restX = width - panelW - Math.max(14, Math.round(width * 0.02f));
		// When the ring cannot fit beside the sheet, the sheet takes the centre
		// instead of hugging the right edge with an empty screen next to it.
		hubVisible = selected < 0 || restX - 28 >= labelR * 2;
		if (selected >= 0 && !hubVisible) {
			restX = (width - panelW) / 2;
		}
		// The arrival slide belongs to the geometry, not only to the paint:
		// the back arrow, the rail, the card strip and the click box are all
		// measured from panelX, so they travel with the surface instead of
		// standing still while it slides in underneath them.
		int slide = selected >= 0
				? Math.round((1f - Motion.easeOutCubic(panelReveal.value())) * 26f)
				: 0;
		panelX = restX + slide;

		// Size the sheet to its content. A one-module category in a half-screen
		// tall sheet looked like an empty page; a fully expanded one still needs
		// the scrollbar, so the cap stays.
		int contentH = 0;
		if (selected >= 0) {
			List<ModuleCard> list = source.cards(CATEGORIES[selected]);
			if (list != null) {
				for (int i = 0; i < list.size(); i++) {
					// The *current* height, so a collapsed sheet is not padded out
					// to the height it would have with every card expanded.
					contentH += list.get(i).height();
					if (i < list.size() - 1) {
						contentH += UiTheme.CARD_GAP;
					}
				}
			}
		}
		// The sheet is sized to its content, with a little air above and below.
		// The old +74 left a one-card category with a third of the sheet empty.
		panelH = selected < 0 ? 190 : Math.max(148, contentH + 68);
		// Leave the bottom strip to the palette button: the sheet must never
		// grow over the one control that changes its colours.
		panelH = Math.min(panelH, Math.min(560, height - 120));
		panelY = (height - panelH) / 2;
		listTop = panelY + 56;
		listBottom = panelY + panelH - 12;
		railX = panelX + panelW - 10;
		backX = panelX + panelW - 16 - BACK_SIZE;
		backY = panelY + 18;

		// The hub slides left to make room for the sheet when one is open. The
		// clearance follows the outside label radius, not just the node, so the
		// "World" label can never sit under the sheet at any GUI scale. On a
		// narrow canvas the ring is what gives: the sheet keeps its readable
		// width and the ring is clamped against it.
		centerX = width / 2;
		if (selected >= 0 && hubVisible) {
			int clearance = labelR + 4;
			// The *resting* position, so the hub settles once instead of
			// chasing the sheet while it slides in.
			int maxCenter = restX - 14 - clearance;
			int minCenter = 14 + clearance;
			centerX = Math.max(minCenter, Math.min(centerX, maxCenter));
		}

		double step = Math.PI * 2.0 / CATEGORIES.length;
		for (int i = 0; i < CATEGORIES.length; i++) {
			double angle = -Math.PI / 2 + step * i;
			nodeX[i] = centerX + (int) Math.round(Math.cos(angle) * ringR);
			nodeY[i] = centerY + (int) Math.round(Math.sin(angle) * ringR);
		}
	}

	private UiIcons.Icon icon(Category category) {
		return switch (category) {
			case BASE_FINDER -> UiIcons.Icon.COMPASS;
			case WORLD -> UiIcons.Icon.GLOBE;
			case VISUAL -> UiIcons.Icon.EYE;
			case DONUT_SMP -> UiIcons.Icon.DONUT;
			case MISC -> UiIcons.Icon.SLIDERS;
			case HUD -> UiIcons.Icon.LAYOUT;
		};
	}

	// ---------------------------------------------------------------- render

	public void render(UiCanvas c, UiType type, int mouseX, int mouseY, float delta, float open) {
		float fade = Motion.clamp01(open);
		if (fade <= 0.02f) {
			return;
		}
		animateCards(delta);
		// The reveal advances before the layout because the sheet's slide is
		// part of the geometry now: hit testing reads the same panelX the
		// paint uses, so the two can never disagree by a frame.
		panelReveal.to(selected >= 0 ? 1f : 0f, delta);
		layout();
		GuiColors.sync();

		int accent = GuiColors.ringArgb();
		int accentLight = Ui.lighten(accent, 0.34f);
		int accentDeep = Ui.darken(accent, 0.30f);
		int ink = Ui.luminance(accent) > 0.62f ? 0xFF35101F : 0xFFFFFFFF;
		int inkSoft = Ui.withAlpha(ink, 0xC0);

		c.fill(0, 0, width, height, Ui.lerpColor(0x00000000, UiTheme.SCRIM, fade));
		drawVignette(c, fade);

		clock += Math.min(0.1f, delta / 1000f);
		float ease = Motion.easeOutQuint(fade);
		float pop = Motion.easeOutBack(fade, 2.0f);
		int r = Math.round(centerR * (0.90f + 0.10f * pop));

		if (hubVisible) {

		// A dashed orbit sits outside the category ring and turns almost
		// imperceptibly. It frames the hub without the full-screen spokes the
		// first draft used, which were both noisy and expensive.
		int orbit = labelR + 18;
		int ticks = 24;
		for (int i = 0; i < ticks; i++) {
			double angle = Math.PI * 2.0 * i / ticks + clock * 0.035;
			int x0 = centerX + (int) Math.round(Math.cos(angle) * orbit);
			int y0 = centerY + (int) Math.round(Math.sin(angle) * orbit);
			int x1 = centerX + (int) Math.round(Math.cos(angle) * (orbit + 6));
			int y1 = centerY + (int) Math.round(Math.sin(angle) * (orbit + 6));
			Ui.line(c, x0, y0, x1, y1, 1, Ui.withAlpha(accent, Math.round(0x2C * fade)));
		}
		// Two drifting motes on that orbit. They are the only moving thing in
		// the style, which is what keeps it calm while still feeling alive.
		for (int i = 0; i < 3; i++) {
			double angle = Math.PI * 2.0 * i / 3.0 + clock * 0.12;
			int mx = centerX + (int) Math.round(Math.cos(angle) * (orbit + 13));
			int my = centerY + (int) Math.round(Math.sin(angle) * (orbit + 13));
			Ui.circle(c, mx, my, Math.max(1, Math.round(nodeR * 0.09f)),
					Ui.withAlpha(Ui.lighten(accent, 0.55f), Math.round(0xB0 * fade)));
		}

		// The disc is the whole light source: a soft shadow behind it and a
		// light rim on it. Wide halo rings were tried here, but at this size
		// they read as banded grey and cost thousands of fills; the sharp rim
		// carries the same "lit" idea for a fraction of the cost.
		Ui.circle(c, centerX, centerY + Math.round(8 * ease), r + 2, Ui.withAlpha(0x000000, 0x55));
		Ui.circleGradient(c, centerX, centerY, r, accentLight, accentDeep);
		Ui.ringFast(c, centerX, centerY, r - 1, 3,
				Ui.withAlpha(Ui.lighten(accent, 0.66f), Math.round(0x99 * fade)));
		Ui.ringFast(c, centerX, centerY, Math.round(r * 0.86f), 1,
				Ui.withAlpha(accentLight, Math.round(0x58 * fade)));

		// A short specular arc at the upper left, drawn as a run of tiny
		// segments rather than another ring: a highlight only exists where the
		// light hits, and a full ring reads as a second border.
		int glossR = r - Math.max(6, r / 12);
		for (int i = 0; i < 7; i++) {
			double a0 = Math.toRadians(196 + i * 5);
			double a1 = Math.toRadians(196 + (i + 1) * 5 - 1);
			Ui.line(c,
					centerX + (int) Math.round(Math.cos(a0) * glossR),
					centerY + (int) Math.round(Math.sin(a0) * glossR),
					centerX + (int) Math.round(Math.cos(a1) * glossR),
					centerY + (int) Math.round(Math.sin(a1) * glossR), 2,
					Ui.withAlpha(0xFFFFFFFF, Math.round(0x3C * fade)));
		}

		// Wordmark group, measured as one block so the mark and the two lines
		// share a centre instead of each being nudged by eye.
		UiText logo = type.headline();
		if (logo.width("Capy Client") > r * 1.55f) {
			logo = type.display();
		}
		UiText caption = type.regular();
		int mark = Math.max(22, Math.min(44, Math.round(r * 0.22f)));
		int gap = Math.max(5, Math.round(mark * 0.22f));
		int groupH = mark + gap + logo.lineHeight() + 4 + caption.lineHeight();
		int groupTop = centerY - groupH / 2 - Math.round((1f - ease) * 10f);

		// The seal, not a bare flower: the ring around the blossom is what makes
		// this mark the client's own at a glance.
		UiIcons.draw(c, UiIcons.Icon.CREST, centerX - mark / 2, groupTop, mark,
				Ui.withAlpha(ink, Math.round(0xF0 * fade)));
		int logoY = groupTop + mark + gap;
		Ui.drawCentered(c, logo, "Capy Client", centerX, logoY, Ui.withAlpha(ink, Math.round(255 * fade)));
		String sub = selected >= 0 ? CATEGORIES[selected].title.toUpperCase() : "SAKURA CLIENT";
		Ui.drawCentered(c, caption, sub, centerX, logoY + logo.lineHeight() + 3, inkSoft);

		renderRing(c, type, mouseX, mouseY, delta, fade, accent, ink);
		}

		if (selected >= 0 && panelReveal.value() > 0.02f) {
			renderPanel(c, type, mouseX, mouseY, delta, accent, ink);
		}

		// Chrome last so it is never covered by the ring or the sheet.
		renderStyleChip(c, type, mouseX, mouseY);
		renderCloseButton(c, mouseX, mouseY);
		renderPaletteButton(c, type, mouseX, mouseY, accent, ink);

		// On a 480x270 canvas the bottom label of the ring is already at the
		// edge; a second line there only collides with it. Drop the hint rather
		// than letting the two overlay each other.
		if (height >= 340) {
			UiText hint = type.regular();
			String hintText = selected < 0
					? "Select a category  ·  Esc closes"
					: "Centre or the back arrow returns";
			Ui.drawCentered(c, hint, hintText, width / 2,
					Ui.textYFromBottom(height, 10, hint),
					Ui.withAlpha(UiTheme.TEXT_3, Math.round(0xD0 * fade)));
		}

		accentPanel.render(c, type, width, height, mouseX, mouseY, delta);
	}

	private void drawVignette(UiCanvas c, float fade) {
		int steps = 14;
		for (int i = 0; i < steps; i++) {
			float t = i / (float) steps;
			int a = Math.round(80 * fade * (1f - t));
			if (a <= 0) {
				continue;
			}
			int shade = (a << 24);
			int inset = i * 6;
			c.fill(inset, inset, width - inset, inset + 6, shade);
			c.fill(inset, height - inset - 6, width - inset, height - inset, shade);
			c.fill(inset, inset, inset + 6, height - inset, shade);
			c.fill(width - inset - 6, inset, width - inset, height - inset, shade);
		}
	}

	private void renderRing(UiCanvas c, UiType type, int mouseX, int mouseY, float delta,
			float fade, int accent, int ink) {
		// The category ring is the signature of the style, so it keeps the
		// smooth edge pass; the decorative rims inside the disc can afford the
		// faster path.
		Ui.ring(c, centerX, centerY, ringR, 2, Ui.withAlpha(accent, Math.round(0x80 * fade)));

		UiText label = type.medium();
		for (int i = 0; i < CATEGORIES.length; i++) {
			Category category = CATEGORIES[i];
			int nx = nodeX[i];
			int ny = nodeY[i];
			double angle = Math.atan2(ny - centerY, nx - centerX);
			boolean hovered = distanceSqr(mouseX, mouseY, nx, ny) <= (nodeR + 5) * (nodeR + 5);
			hover[i] = Motion.approach(hover[i], hovered ? 1f : 0f, 15f, delta / 20f);
			active[i] = Motion.approach(active[i], i == selected ? 1f : 0f, 14f, delta / 20f);
			float hv = hover[i];
			float av = active[i];
			int nodeColor = Ui.lerpColor(category.accent, 0xFFFFFFFF, 0.25f + hv * 0.25f);
			int radius = nodeR + Math.round(hv * 2f) + Math.round(av * 1f);

			// Spoke from the disc edge to the node, so the ring reads as one
			// instrument rather than as six unrelated buttons.
			int innerX = centerX + (int) Math.round(Math.cos(angle) * (centerR + 6));
			int innerY = centerY + (int) Math.round(Math.sin(angle) * (centerR + 6));
			int outerX = nx - (int) Math.round(Math.cos(angle) * (radius + 8));
			int outerY = ny - (int) Math.round(Math.sin(angle) * (radius + 8));
			Ui.line(c, innerX, innerY, outerX, outerY, 1,
					Ui.withAlpha(accent, Math.round((0x22 + av * 0x33) * fade)));

			if (hv > 0.02f || av > 0.02f) {
				Ui.circle(c, nx, ny, radius + 7, Ui.withAlpha(category.accent,
						Math.round((0x18 + 0x20 * Math.max(hv, av)) * fade)));
			}
			Ui.circle(c, nx, ny + 3, radius, Ui.withAlpha(0x000000, Math.round(0x40 * fade)));
			Ui.circleGradient(c, nx, ny, radius,
					Ui.lerpColor(UiTheme.PANEL_ALT, category.accent, 0.10f + 0.16f * Math.max(hv, av) + 0.18f * av),
					Ui.lerpColor(UiTheme.PANEL_TOP, category.accent, 0.03f + 0.08f * av));
			Ui.ring(c, nx, ny, radius, 2,
					Ui.withAlpha(Ui.lerpColor(category.accent, 0xFFFFFFFF, hv * 0.25f), Math.round(0xB0 * fade)));

			int iconSize = Math.max(14, Math.round(radius * 0.80f));
			UiIcons.draw(c, icon(category), nx - iconSize / 2, ny - iconSize / 2, iconSize,
					Ui.withAlpha(nodeColor, Math.round(255 * fade)));

			// Label just outside the node, along the same spoke. It is centred
			// on the node's axis and clamped into the screen, which keeps the
			// top and bottom labels from ever leaving the viewport.
			int lx = centerX + (int) Math.round(Math.cos(angle) * labelR);
			int ly = centerY + (int) Math.round(Math.sin(angle) * labelR);
			String text = category.title;
			int tw = label.width(text);
			lx = Math.max(8 + tw / 2, Math.min(width - 8 - tw / 2, lx));
			ly = Math.max(6, Math.min(height - 6 - label.lineHeight(), ly - label.lineHeight() / 2));
			Ui.drawCentered(c, label, text, lx, ly,
					Ui.withAlpha(Ui.lerpColor(UiTheme.TEXT_2, 0xFFFFFFFF, Math.max(hv, av)), Math.round(255 * fade)));
		}
	}

	private void renderPanel(UiCanvas c, UiType type, int mouseX, int mouseY, float delta,
			int accent, int ink) {
		Category category = CATEGORIES[selected];
		List<ModuleCard> list = source.cards(category);
		if (list == null) {
			list = List.of();
		}

		float pr = Motion.easeOutCubic(panelReveal.value());
		// panelX already carries the arrival slide; the whole sheet, chrome
		// included, is drawn from that one number.
		int px = panelX;
		int py = panelY;
		int pw = panelW;
		int ph = panelH;

		Ui.shadow(c, px, py, pw, ph, UiTheme.RADIUS_LG);
		Ui.surface(c, px, py, pw, ph, UiTheme.RADIUS_LG,
				Ui.withAlpha(UiTheme.PANEL_TOP, Math.round(248 * pr)),
				Ui.withAlpha(UiTheme.PANEL_BOTTOM, Math.round(248 * pr)));
		Ui.outline(c, px, py, pw, ph, UiTheme.RADIUS_LG,
				Ui.withAlpha(UiTheme.BORDER, Math.round(255 * pr)));
		Ui.innerBevel(c, px, py, pw, ph, UiTheme.RADIUS_LG, 0x12FFFFFF, 0x22000000);
		Ui.surface(c, px + UiTheme.RADIUS_LG, py + 1, pw - UiTheme.RADIUS_LG * 2, 1, 0,
				Ui.withAlpha(accent, Math.round(0xB0 * pr)));

		// Header: category mark, name and count.
		int mark = 34;
		int markX = px + 14;
		int markY = py + 12;
		Ui.circleGradient(c, markX + mark / 2, markY + mark / 2, mark / 2,
				Ui.lerpColor(UiTheme.PANEL_ALT, category.accent, 0.30f),
				Ui.lerpColor(UiTheme.PANEL_TOP, category.accent, 0.10f));
		Ui.ring(c, markX + mark / 2, markY + mark / 2, mark / 2, 1,
				Ui.withAlpha(category.accent, Math.round(0xCC * pr)));
		UiIcons.draw(c, icon(category), markX + 8, markY + 8, 18,
				Ui.withAlpha(Ui.lerpColor(category.accent, 0xFFFFFFFF, 0.22f), Math.round(255 * pr)));

		UiText bold = type.bold();
		UiText regular = type.regular();
		int titleX = markX + mark + 12;
		bold.draw(c, category.title, titleX, py + 15, Ui.withAlpha(UiTheme.TEXT, Math.round(255 * pr)));
		regular.draw(c, list.size() + (list.size() == 1 ? " module" : " modules"), titleX, py + 31,
				Ui.withAlpha(UiTheme.TEXT_3, Math.round(255 * pr)));

		// Back arrow.
		boolean backHovered = mouseX >= backX && mouseX < backX + BACK_SIZE
				&& mouseY >= backY && mouseY < backY + BACK_SIZE;
		Ui.surface(c, backX, backY, BACK_SIZE, BACK_SIZE, BACK_SIZE / 2,
				Ui.withAlpha(backHovered ? accent : UiTheme.PANEL_ALT, Math.round(255 * pr)),
				Ui.withAlpha(backHovered ? accent : UiTheme.PANEL_TOP, Math.round(255 * pr)));
		UiIcons.draw(c, UiIcons.Icon.BACK, backX + 6, backY + 6, 10,
				Ui.withAlpha(backHovered ? ink : UiTheme.TEXT_2, Math.round(255 * pr)));

		Ui.fadeLine(c, px + 12, py + 52, pw - 24, Ui.withAlpha(UiTheme.BORDER, Math.round(0x9A * pr)));

		if (list.isEmpty()) {
			UiIcons.draw(c, UiIcons.Icon.CREST, px + pw / 2 - 18, py + ph / 2 - 32, 36,
					Ui.withAlpha(category.accent, 0x50));
			Ui.drawCentered(c, type.medium(), "Nothing here yet", px + pw / 2, py + ph / 2 + 10,
					Ui.withAlpha(UiTheme.TEXT_2, Math.round(255 * pr)));
			Ui.drawCentered(c, regular, "This category has no modules yet.", px + pw / 2, py + ph / 2 + 26,
					Ui.withAlpha(UiTheme.TEXT_3, Math.round(255 * pr)));
			return;
		}

		int contentH = 0;
		for (int i = 0; i < list.size(); i++) {
			contentH += list.get(i).height();
			if (i < list.size() - 1) {
				contentH += UiTheme.CARD_GAP;
			}
		}
		scrollbar.update(listTop, listBottom, contentH, delta);
		int cardX = px + 12;
		int cardW = pw - 24 - 8;
		int y = listTop - (int) Math.round(scrollbar.scroll());
		c.pushClip(px + 6, listTop, pw - 12, listBottom - listTop);
		for (ModuleCard card : list) {
			int h = card.height();
			// Cards clear of the strip are skipped: a ten module HUD sheet used
			// to pay for every card every frame, most of them hidden under the
			// clip. The margin keeps the cached bounds of the cards just outside
			// the strip where they were last drawn - far enough out that the hit
			// test, which only ever asks about positions inside the strip, can
			// never reach them.
			if (y + h >= listTop - 40 && y <= listBottom + 40) {
				card.render(c, type, cardX, y, cardW, mouseX, mouseY, delta, false);
			}
			y += h + UiTheme.CARD_GAP;
		}
		c.popClip();
		scrollbar.setRailX(railX);
		scrollbar.render(c);
	}

	private void renderStyleChip(UiCanvas c, UiType type, int mouseX, int mouseY) {
		boolean hovered = mouseX >= styleX && mouseX < styleX + styleW
				&& mouseY >= styleY && mouseY < styleY + STYLE_H;
		Ui.surface(c, styleX, styleY, styleW, STYLE_H, UiTheme.RADIUS,
				hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				hovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, styleX, styleY, styleW, STYLE_H, UiTheme.RADIUS,
				hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		UiIcons.draw(c, UiIcons.Icon.LAYOUT, styleX + 8, styleY + 8, 12,
				hovered ? UiTheme.ACCENT : UiTheme.TEXT_3);
		Ui.drawEllipsized(c, type.regular(), "Radial", styleX + 25,
				Ui.centerTextY(styleY, STYLE_H, type.regular()), styleW - 42,
				hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
		UiIcons.draw(c, UiIcons.Icon.CHEVRON, styleX + styleW - 15, styleY + STYLE_H / 2 - 3, 8,
				hovered ? UiTheme.TEXT : UiTheme.TEXT_3);
	}

	private void renderCloseButton(UiCanvas c, int mouseX, int mouseY) {
		boolean hovered = mouseX >= closeX && mouseX < closeX + CLOSE_SIZE
				&& mouseY >= closeY && mouseY < closeY + CLOSE_SIZE;
		Ui.surface(c, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, CLOSE_SIZE / 2,
				hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				hovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, CLOSE_SIZE / 2,
				hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		UiIcons.draw(c, UiIcons.Icon.CLOSE, closeX + 10, closeY + 10, 8,
				hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
	}

	private void renderPaletteButton(UiCanvas c, UiType type, int mouseX, int mouseY, int accent, int ink) {
		boolean hovered = distanceSqr(mouseX, mouseY, paletteX, paletteY) <= (paletteR + 4) * (paletteR + 4);
		// The label is to the left, right-aligned against the button, so the
		// control explains itself without a tooltip.
		UiText label = type.regular();
		int lx = paletteX - paletteR - 9 - label.width("Colours");
		Ui.drawCentered(c, label, "Colours", lx + label.width("Colours") / 2,
				Ui.centerTextY(paletteY - paletteR, paletteR * 2, label),
				Ui.withAlpha(UiTheme.TEXT_3, hovered ? 0xFF : 0xC0));
		Ui.discGlow(c, paletteX, paletteY, paletteR, Ui.withAlpha(accent, 0xC0), hovered ? 0.32f : 0.20f);
		Ui.circleGradient(c, paletteX, paletteY, paletteR,
				Ui.lighten(accent, 0.30f), Ui.darken(accent, 0.28f));
		Ui.ring(c, paletteX, paletteY, paletteR, 1, Ui.withAlpha(0xFFFFFFFF, 0x4D));
		int iconSize = Math.max(12, paletteR - 4);
		UiIcons.draw(c, UiIcons.Icon.PALETTE, paletteX - iconSize / 2, paletteY - iconSize / 2, iconSize, ink);
	}

	// ----------------------------------------------------------------- input

	public boolean mouseClicked(double mx, double my, int button) {
		if (accentPanel.mouseClicked(mx, my)) {
			return true;
		}
		layout();
		clearCardTextFocus();

		if (button == 0 && mx >= closeX && mx < closeX + CLOSE_SIZE
				&& my >= closeY && my < closeY + CLOSE_SIZE) {
			closeAction.run();
			return true;
		}
		if (button == 0 && mx >= styleX && mx < styleX + styleW
				&& my >= styleY && my < styleY + STYLE_H) {
			cycleStyleAction.run();
			return true;
		}
		if (button == 0 && distanceSqr(mx, my, paletteX, paletteY) <= (paletteR + 4) * (paletteR + 4)) {
			accentPanel.toggle();
			return true;
		}

		if (selected >= 0) {
			if (button == 0 && mx >= backX && mx < backX + BACK_SIZE
					&& my >= backY && my < backY + BACK_SIZE) {
				selected = -1;
				scrollbar.reset();
				return true;
			}
			if (mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH) {
				if (button == 0 && mx >= railX - 4 && mx < railX + 8
						&& my >= listTop && my < listBottom && scrollbar.pressed(my)) {
					return true;
				}
				List<ModuleCard> list = source.cards(CATEGORIES[selected]);
				// Only the strip that is actually drawn: a card whose body is cut
				// at listBottom must not answer for the sheet's padding below its
				// cut, and a card scrolled out of view must not answer from a
				// position it no longer occupies.
				if (list != null && my >= listTop && my < listBottom) {
					for (int i = list.size() - 1; i >= 0; i--) {
						ModuleCard card = list.get(i);
						if (card.contains(mx, my) && card.mouseClicked(mx, my, button)) {
							return true;
						}
					}
				}
				return true;
			}
		}

		// The hub: pressing the disc goes home; pressing a node opens it. When
		// the sheet fills the view there is no ring to hit, only the back arrow.
		if (hubVisible) {
			if (distanceSqr(mx, my, centerX, centerY) <= (long) centerR * centerR) {
				selected = -1;
				scrollbar.reset();
				return true;
			}
			for (int i = 0; i < CATEGORIES.length; i++) {
				if (distanceSqr(mx, my, nodeX[i], nodeY[i]) <= (nodeR + 5L) * (nodeR + 5L)) {
					if (selected == i) {
						selected = -1;
					} else {
						selected = i;
						scrollbar.reset();
					}
					return true;
				}
			}
		}
		return true;
	}

	public boolean mouseDragged(double mx, double my) {
		if (accentPanel.mouseDragged(mx, my)) {
			return true;
		}
		if (scrollbar.isDragging()) {
			scrollbar.dragged(my);
			return true;
		}
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = source.cards(category);
			if (list == null) {
				continue;
			}
			for (ModuleCard card : list) {
				card.mouseDragged(mx, my);
			}
		}
		return true;
	}

	public boolean mouseReleased(double mx, double my) {
		boolean was = accentPanel.mouseReleased();
		scrollbar.released();
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = source.cards(category);
			if (list == null) {
				continue;
			}
			for (ModuleCard card : list) {
				card.mouseReleased(mx, my);
			}
		}
		return was;
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (accentPanel.isOpen()) {
			return true;
		}
		if (selected >= 0 && mx >= panelX - 8 && mx < panelX + panelW
				&& my >= listTop && my < listBottom) {
			return scrollbar.onScroll(amount);
		}
		return false;
	}

	public boolean charTyped(CharacterEvent event) {
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = source.cards(category);
			if (list == null) {
				continue;
			}
			for (ModuleCard card : list) {
				if (card.charTyped(event)) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean keyPressed(KeyEvent event) {
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = source.cards(category);
			if (list == null) {
				continue;
			}
			for (ModuleCard card : list) {
				if (card.keyPressed(event)) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean hasTextInput() {
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = source.cards(category);
			if (list == null) {
				continue;
			}
			for (ModuleCard card : list) {
				if (card.hasTextInput()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Drops any focused module field, e.g. when the style changes. */
	public void clearTextInput() {
		clearCardTextFocus();
	}

	private void clearCardTextFocus() {
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = source.cards(category);
			if (list == null) {
				continue;
			}
			for (ModuleCard card : list) {
				card.clearTextInput();
			}
		}
	}

	/** Advances the visible sheet's expand/hover animation exactly once. */
	private void animateCards(float delta) {
		if (selected < 0) {
			return;
		}
		List<ModuleCard> list = source.cards(CATEGORIES[selected]);
		if (list == null) {
			return;
		}
		for (ModuleCard card : list) {
			card.animate(delta);
		}
	}

	private static long distanceSqr(double ax, double ay, double bx, double by) {
		double dx = ax - bx;
		double dy = ay - by;
		return Math.round(dx * dx + dy * dy);
	}
}
