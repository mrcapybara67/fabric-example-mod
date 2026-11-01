package com.capy.client.gui;

import com.capy.client.module.Category;
import java.util.ArrayList;
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
 * <p>Every animated value here is an {@link Anim} driven by {@link Motion},
 * and every one of them is advanced from the frame delta the screen passes in.
 * That delta is in ticks (there are twenty to a second), which is the unit
 * {@link Anim} expects and the unit the panel geometry is measured in; the two
 * places that need seconds - the hub's rotation and the orbit's drift -
 * convert it once, at the top of {@link #advance}.</p>
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
	/** Node radius at the reference canvas; the layout scales it to the screen. */
	private static final int NODE_RADIUS = 30;
	private static final int CLOSE_SIZE = 28;
	/** Gap from the top and right edges to the close disc, before scaling. */
	private static final int CLOSE_PAD = 8;
	private static final int STYLE_H = 28;
	private static final int STYLE_W = 104;
	/** Gap between the close disc and the style chip beneath it, before scaling. */
	private static final int STYLE_GAP = 6;
	private static final int BACK_SIZE = 22;
	/**
	 * The style the chip under the close disc switches to.
	 *
	 * The ring is only ever live while the radial style is active, so the style
	 * the chip offers is always the other one. Naming the target rather than the
	 * current style is what makes the chip read as a switch.
	 */
	private static final String NEXT_STYLE = GuiStyle.RADIAL.next().label();
	/** The canvas height the chrome is designed at. */
	private static final float REFERENCE_HEIGHT = 720f;

	/** How fast a node grows under the pointer, in e-folds per second. */
	private static final float HOVER_SPEED = 12f;
	/** How fast the click pulse rises and falls. */
	private static final float PULSE_SPEED = 8f;
	/** How fast a node lights up as the open category. */
	private static final float SELECT_SPEED = 12f;
	/** How fast the nodes orbit into place when the style is shown. */
	private static final float ORBIT_SPEED = 6f;
	/** How fast the sheet travels in and out. */
	private static final float PANEL_SPEED = 10f;
	/** How fast one card of the sheet lands. */
	private static final float CARD_SPEED = 14f;
	/**
	 * The delay between two neighbouring cards, in ticks.
	 *
	 * 0.6 ticks is 30 ms, which is short enough that a ten module sheet is
	 * fully in after a third of a second and long enough to read as a stagger
	 * rather than as one simultaneous fade.
	 */
	private static final float STAGGER_TICKS = 0.6f;
	/** The hub's rotating ring: degrees per second and how many segments. */
	private static final float HUB_DEGREES_PER_SECOND = 45f;
	private static final int HUB_SEGMENTS = 8;
	/** How far a card slides up while it lands, in pixels. */
	private static final int CARD_RISE = 8;

	private final CardSource source;
	private final UiAccentPanel accentPanel;
	private final Runnable closeAction;
	private final Runnable cycleStyleAction;
	private final UiScrollbar scrollbar = new UiScrollbar();

	/** One animation per category node. */
	private final Anim[] hoverScale = anims(0f, HOVER_SPEED);
	private final Anim[] selectPulse = anims(0f, PULSE_SPEED);
	private final Anim[] selectGlow = anims(0f, SELECT_SPEED);
	private final Anim[] orbitOffset = anims(0f, ORBIT_SPEED);
	/** Set while a node's click pulse is still rising. */
	private final boolean[] pulseArmed = new boolean[CATEGORIES.length];
	private final Anim panelReveal = new Anim(0f, PANEL_SPEED);
	private final Anim closeHover = new Anim(0f, HOVER_SPEED);
	/** One landing animation per card of the open sheet. */
	private final List<CardState> cardStates = new ArrayList<>();

	private int width;
	private int height;
	/** Chrome scale: 1 at the reference height, larger on a bigger canvas. */
	private float uiScale = 1f;
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
	/** The hub ring's angle in degrees. Continuous, so it is a plain float. */
	private float hubRotation;
	/** The category the card list currently belongs to, or -1. */
	private int revealCategory = -1;
	/** False until the ring has been placed once, so hover has a real target. */
	private boolean placed;
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

	private int selected = -1;

	public RadialView(CardSource source, UiAccentPanel accentPanel,
			Runnable closeAction, Runnable cycleStyleAction) {
		this.source = source;
		this.accentPanel = accentPanel;
		this.closeAction = closeAction;
		this.cycleStyleAction = cycleStyleAction;
	}

	/** A fresh animation for every category, at the same starting value. */
	private static Anim[] anims(float value, float speed) {
		Anim[] out = new Anim[CATEGORIES.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = new Anim(value, speed);
		}
		return out;
	}

	/** A pixel constant expressed in the current chrome scale. */
	private int scaled(int value) {
		return Math.round(value * uiScale);
	}

	public void resize(int width, int height) {
		this.width = width;
		this.height = height;
		// The chrome is designed against a 720 pixel tall canvas. A taller
		// canvas gets proportionally bigger controls rather than the same
		// thumb-sized ones floating in the middle of a lot of space; a shorter
		// one keeps 1:1, because shrinking a 28 pixel button further is how it
		// stops being clickable.
		this.uiScale = Math.max(1f, height / REFERENCE_HEIGHT);
	}

	/** Called when the style is switched to the ring, so the sheet starts closed. */
	public void onShown() {
		selected = -1;
		panelReveal.snap(0f);
		scrollbar.reset();
		clock = 0f;
		hubRotation = 0f;
		placed = false;
		cardStates.clear();
		revealCategory = -1;
		for (int i = 0; i < CATEGORIES.length; i++) {
			// The nodes orbit in from just inside the ring: every one of them
			// starts folded towards the centre and unwinds into its slot.
			orbitOffset[i].snap(0f);
			hoverScale[i].snap(0f);
			selectGlow[i].snap(0f);
			selectPulse[i].snap(0f);
			pulseArmed[i] = false;
		}
	}

	/** Selects a category without a click (used by the offline preview). */
	void select(int index) {
		selected = index >= 0 && index < CATEGORIES.length ? index : -1;
		scrollbar.reset();
		// The preview draws a single frame, so it has to be handed the settled
		// state: the first frame of an orbit is not what the style looks like.
		for (int i = 0; i < CATEGORIES.length; i++) {
			orbitOffset[i].snap(1f);
			hoverScale[i].snap(0f);
			selectGlow[i].snap(i == selected ? 1f : 0f);
			selectPulse[i].snap(0f);
			pulseArmed[i] = false;
		}
		if (selected < 0) {
			panelReveal.snap(0f);
			cardStates.clear();
			revealCategory = -1;
		} else {
			panelReveal.snap(1f);
			settleCards();
		}
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
		centerR = Math.max(60, Math.min(scaled(210), Math.round(min * 0.205f)));
		nodeR = Math.max(16, Math.min(scaled(NODE_RADIUS), Math.round(min * 0.052f)));
		int nodeGap = Math.max(38, Math.round(min * 0.105f));
		ringR = centerR + Math.min(scaled(112), nodeGap);

		// The two chrome controls stack in the top right corner, both a constant
		// pad off the edge: the close disc first, the style switch directly under
		// it, so the corner reads as one control group instead of one control in
		// each corner.
		int close = scaled(CLOSE_SIZE);
		int pad = scaled(CLOSE_PAD);
		closeX = width - pad - close;
		closeY = pad;
		styleW = Math.min(scaled(STYLE_W), width - pad * 2);
		styleX = width - pad - styleW;
		styleY = closeY + close + scaled(STYLE_GAP);
		paletteR = Math.max(scaled(15), Math.min(scaled(20), centerR / 8));
		paletteX = width - paletteR - scaled(26);
		paletteY = height - paletteR - scaled(24);

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
		panelW = Math.max(250, Math.min(scaled(420), Math.round(width * 0.34f)));
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
		//
		// The slide is eased with an overshoot, so the sheet arrives from the
		// right, passes its resting place by a couple of pixels and settles
		// back - the same spring the window styles use when they open.
		float reveal = Motion.easeOutBack(panelReveal.value(), 1.35f);
		int slide = selected >= 0 ? Math.round((1f - reveal) * 26f) : 0;
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
		panelH = selected < 0 ? scaled(190) : Math.max(148, contentH + 68);
		// Leave the bottom strip to the palette button: the sheet must never
		// grow over the one control that changes its colours.
		panelH = Math.min(panelH, Math.min(scaled(560), height - scaled(120)));
		panelY = (height - panelH) / 2;
		// The chrome owns the top right corner, so the sheet starts below it:
		// on a 427x240 canvas a centred sheet reaches up into the style chip and
		// the two would be drawn on top of each other. The height gives way
		// rather than the position, which keeps the whole sheet on screen.
		int chromeBottom = styleY + scaled(STYLE_H) + scaled(6);
		if (panelY < chromeBottom) {
			panelY = chromeBottom;
			panelH = Math.min(panelH, height - panelY - scaled(6));
		}
		listTop = panelY + scaled(56);
		listBottom = panelY + panelH - 12;
		railX = panelX + panelW - 10;
		backX = panelX + panelW - scaled(16) - scaled(BACK_SIZE);
		backY = panelY + scaled(18);

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
			// The orbit is read here rather than in the paint, because the hit
			// test measures against these same two numbers: a node that is
			// still travelling has to be clickable where it is drawn.
			float orb = Motion.easeOutCubic(orbitOffset[i].value());
			double angle = -Math.PI / 2 + step * i - (1f - orb) * 0.55;
			double radius = ringR * (0.70 + 0.30 * orb);
			nodeX[i] = centerX + (int) Math.round(Math.cos(angle) * radius);
			nodeY[i] = centerY + (int) Math.round(Math.sin(angle) * radius);
		}
		placed = true;
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

	// ------------------------------------------------------------ animation

	/**
	 * Advances the hub: the node animations, the drift clock and the ring's
	 * rotation.
	 *
	 * Hover is measured here rather than while painting the ring, so a node
	 * that is under the pointer but off screen for a frame still settles
	 * instead of freezing mid-inflation.
	 */
	private void advance(float delta, int mouseX, int mouseY) {
		// The delta arrives in ticks; the clock and the rotation are the two
		// quantities that are easier to read in seconds per second.
		float seconds = Math.min(0.25f, delta / 20f);
		clock += seconds;
		hubRotation = (hubRotation + seconds * HUB_DEGREES_PER_SECOND) % 360f;

		for (int i = 0; i < CATEGORIES.length; i++) {
			boolean hovered = hubVisible && placed
					&& distanceSqr(mouseX, mouseY, nodeX[i], nodeY[i]) <= (nodeR + 5L) * (nodeR + 5L);
			hoverScale[i].to(hovered ? 1f : 0f, delta);
			selectGlow[i].to(i == selected ? 1f : 0f, delta);
			// The pulse is one rise and one fall on the same animation: it is
			// aimed at 1, and as soon as it gets there it is aimed back at 0.
			selectPulse[i].to(pulseArmed[i] ? 1f : 0f, delta);
			if (pulseArmed[i] && selectPulse[i].value() >= 0.999f) {
				pulseArmed[i] = false;
			}
			orbitOffset[i].to(1f, delta);
		}
		closeHover.to(hubVisible && placed && overClose(mouseX, mouseY) ? 1f : 0f, delta);
	}

	/**
	 * Advances the card list of the open sheet exactly once.
	 *
	 * The module's own expand and press animations are stepped here too, so a
	 * card is never advanced twice in a frame - the ring is the only thing
	 * driving the sheet, and a second pass would double its speed.
	 */
	private void animateCards(float delta) {
		if (selected < 0) {
			if (revealCategory != -1) {
				cardStates.clear();
				revealCategory = -1;
			}
			return;
		}
		List<ModuleCard> list = source.cards(CATEGORIES[selected]);
		if (list == null) {
			return;
		}
		if (selected != revealCategory) {
			// The category changed: every card starts again from nothing, with
			// its own delay, so the sheet reads as a new column rather than as
			// the old one relabelled.
			revealCategory = selected;
			cardStates.clear();
			for (int i = 0; i < list.size(); i++) {
				CardState state = new CardState();
				state.wait = i * STAGGER_TICKS;
				cardStates.add(state);
			}
		}
		while (cardStates.size() < list.size()) {
			cardStates.add(new CardState());
		}
		while (cardStates.size() > list.size()) {
			cardStates.remove(cardStates.size() - 1);
		}
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			card.animate(delta);
			CardState state = cardStates.get(i);
			if (state.wait > 0f) {
				state.wait = Math.max(0f, state.wait - delta);
			}
			// Every card travels the same distance for the same time; only the
			// delay differs, which is what makes the column read as one gesture.
			state.reveal.to(state.wait > 0f ? 0f : 1f, delta);
		}
	}

	/** Snapshots the open sheet's cards as already landed (the preview). */
	private void settleCards() {
		cardStates.clear();
		List<ModuleCard> list = source.cards(CATEGORIES[selected]);
		int count = list == null ? 0 : list.size();
		for (int i = 0; i < count; i++) {
			CardState state = new CardState();
			state.reveal.snap(1f);
			cardStates.add(state);
		}
		revealCategory = selected;
	}

	// ---------------------------------------------------------------- render

	public void render(UiCanvas c, UiType type, int mouseX, int mouseY, float delta, float open) {
		float fade = Motion.clamp01(open);
		if (fade <= 0.02f) {
			return;
		}
		// The nodes move before the layout because the orbit is part of the
		// geometry, and the layout moves before the reveal because the sheet's
		// slide is measured from panelX: hit testing reads the same numbers the
		// paint uses, so the two can never disagree by a frame.
		advance(delta, mouseX, mouseY);
		animateCards(delta);
		panelReveal.to(selected >= 0 ? 1f : 0f, delta);
		layout();
		GuiColors.sync();

		int accent = GuiColors.ringArgb();
		int accentLight = Ui.lighten(accent, 0.34f);
		int accentDeep = Ui.darken(accent, 0.30f);
		int ink = Ui.luminance(accent) > 0.62f ? 0xFF35101F : 0xFFFFFFFF;

		c.fill(0, 0, width, height, Ui.lerpColor(0x00000000, UiTheme.SCRIM, fade));
		drawVignette(c, fade);

		float ease = Motion.easeOutQuint(fade);
		float pop = Motion.easeOutBack(fade, 2.0f);
		int r = Math.round(centerR * (0.90f + 0.10f * pop));

		if (hubVisible) {
			drawOrbit(c, fade, accent);
			drawHub(c, type, fade, ease, accent, accentLight, accentDeep, ink, r);
			renderRing(c, type, fade, accent);
		}

		if (selected >= 0 && panelReveal.value() > 0.02f) {
			renderPanel(c, type, mouseX, mouseY, delta, accent, ink);
		}

		// Chrome last so it is never covered by the ring or the sheet.
		renderStyleChip(c, type, mouseX, mouseY);
		renderCloseButton(c, mouseX, mouseY, ink);
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

	/**
	 * The dashed orbit outside the category ring, and the three motes riding it.
	 *
	 * Both are driven by {@link #clock}, which is wall time in seconds, so the
	 * motion is the same on every refresh rate and does not stop when the frame
	 * rate does.
	 */
	private void drawOrbit(UiCanvas c, float fade, int accent) {
		int orbit = labelR + 18;
		int ticks = 24;
		for (int i = 0; i < ticks; i++) {
			double angle = Math.PI * 2.0 * i / ticks + clock * 0.10;
			int x0 = centerX + (int) Math.round(Math.cos(angle) * orbit);
			int y0 = centerY + (int) Math.round(Math.sin(angle) * orbit);
			int x1 = centerX + (int) Math.round(Math.cos(angle) * (orbit + 6));
			int y1 = centerY + (int) Math.round(Math.sin(angle) * (orbit + 6));
			Ui.line(c, x0, y0, x1, y1, 1, Ui.withAlpha(accent, Math.round(0x2C * fade)));
		}
		for (int i = 0; i < 3; i++) {
			double angle = Math.PI * 2.0 * i / 3.0 + clock * 0.30;
			int mx = centerX + (int) Math.round(Math.cos(angle) * (orbit + 13));
			int my = centerY + (int) Math.round(Math.sin(angle) * (orbit + 13));
			Ui.circle(c, mx, my, Math.max(1, Math.round(nodeR * 0.09f)),
					Ui.withAlpha(Ui.lighten(accent, 0.55f), Math.round(0xB0 * fade)));
		}
	}

	/**
	 * The hub: the lit disc, the rotating ring around it and the wordmark.
	 *
	 * The rotating ring is the one piece of pure decoration in the style. It is
	 * drawn as {@link #HUB_SEGMENTS} arcs with a gap between each, turning at
	 * {@link #HUB_DEGREES_PER_SECOND} degrees a second, which is slow enough to
	 * read as machining rather than as a spinner.
	 */
	private void drawHub(UiCanvas c, UiType type, float fade, float ease,
			int accent, int accentLight, int accentDeep, int ink, int r) {
		// A soft halo behind the disc, so the disc reads as the light source of
		// the whole style rather than as a flat pink circle.
		Ui.discGlow(c, centerX, centerY, r + scaled(14), UiTheme.ACCENT_GLOW, 0.18f * fade);

		// The rotating segmented ring.
		int segR = r + Math.max(scaled(6), Math.round(centerR * 0.16f));
		int segThick = Math.max(2, scaled(2));
		for (int i = 0; i < HUB_SEGMENTS; i++) {
			double span = Math.PI * 2.0 / HUB_SEGMENTS;
			double start = Math.toRadians(hubRotation) + span * i;
			// The segments carry the palette's second accent rather than a
			// lightened copy of the first: two distinct pinks read as one ring
			// turning, one pink at two brightnesses reads as banding.
			drawArc(c, centerX, centerY, segR, start, start + span * 0.62, segThick,
					Ui.withAlpha(UiTheme.ACCENT_2, Math.round(0x99 * fade)));
		}

		// The disc itself: a soft shadow behind it and a light rim on it. Wide
		// halo rings were tried here, but at this size they read as banded grey
		// and cost thousands of fills; the sharp rim carries the same "lit"
		// idea for a fraction of the cost.
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

		// Wordmark group, measured as one block so the mark and the line share a
		// centre instead of each being nudged by eye. The hub names no second
		// line: the caption that used to sit under the wordmark either repeated
		// the style's own name or repeated the category the sheet header already
		// shows, and on the disc it only crowded the mark.
		UiText logo = type.headline();
		if (logo.width("Capy Client") > r * 1.55f) {
			logo = type.display();
		}
		int mark = Math.max(22, Math.min(44, Math.round(r * 0.22f)));
		int gap = Math.max(5, Math.round(mark * 0.22f));
		int groupH = mark + gap + logo.lineHeight();
		int groupTop = centerY - groupH / 2 - Math.round((1f - ease) * 10f);

		// A lit sphere instead of a flat glyph: two stacked discs, a rim and a
		// specular dot give the hub a light source of its own, which is the depth
		// the style was missing. The client's name stays under it.
		int orbR = Math.max(9, mark / 2);
		int orbAlpha = Math.round(0xF0 * fade);
		Ui.circle(c, centerX, groupTop + orbR + Math.max(1, orbR / 6), orbR,
				Ui.withAlpha(0x000000, Math.round(0x50 * fade)));
		Ui.circleGradient(c, centerX, groupTop + orbR, orbR,
				Ui.withAlpha(Ui.lighten(accent, 0.62f), orbAlpha),
				Ui.withAlpha(accentDeep, orbAlpha));
		Ui.ringFast(c, centerX, groupTop + orbR, Math.max(2, orbR - 1), 2,
				Ui.withAlpha(0xFFFFFFFF, Math.round(0x55 * fade)));
		Ui.circle(c, centerX - Math.round(orbR * 0.34f), groupTop + orbR - Math.round(orbR * 0.36f),
				Math.max(1, Math.round(orbR * 0.26f)), Ui.withAlpha(0xFFFFFFFF, Math.round(0xB0 * fade)));
		int logoY = groupTop + mark + gap;
		Ui.drawCentered(c, logo, "Capy Client", centerX, logoY, Ui.withAlpha(ink, Math.round(255 * fade)));
	}

	/**
	 * One arc of the hub ring, drawn as overlapping blocks along the curve.
	 *
	 * The samples are spaced at a pixel and a half, so consecutive blocks touch
	 * and the band has no gap, and the block count follows the radius so a
	 * segment is smooth at any size. Each chord used to be handed to
	 * {@link Ui#line}, which interpolated every pixel of it: at this radius that
	 * drew the same band three times over, and the eight segments were the most
	 * expensive decoration in the style.
	 */
	private static void drawArc(UiCanvas c, int cx, int cy, int radius,
			double from, double to, int thickness, int color) {
		int steps = Math.max(2, (int) Math.round((to - from) * Math.max(6, radius) / 1.5));
		for (int i = 0; i <= steps; i++) {
			double angle = from + (to - from) * i / steps;
			int x = cx + (int) Math.round(Math.cos(angle) * radius);
			int y = cy + (int) Math.round(Math.sin(angle) * radius);
			c.fill(x, y, x + thickness, y + thickness, color);
		}
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

	private void renderRing(UiCanvas c, UiType type, float fade, int accent) {
		// The ring is a 2 pixel band on a three hundred pixel circle: at that
		// radius the per row edge blending of ring() costs around six fills a
		// row - two thousand of the frame's submissions for one hairline - and
		// the stair it removes is smaller than a pixel. The fast path snaps the
		// band to whole pixels instead.
		Ui.ringFast(c, centerX, centerY, ringR, 2, Ui.withAlpha(accent, Math.round(0x80 * fade)));

		UiText label = type.medium();
		for (int i = 0; i < CATEGORIES.length; i++) {
			Category category = CATEGORIES[i];
			int nx = nodeX[i];
			int ny = nodeY[i];
			double angle = Math.atan2(ny - centerY, nx - centerX);
			float hv = hoverScale[i].value();
			float sv = selectGlow[i].value();
			float pulse = selectPulse[i].value();
			float emphasis = Math.max(hv, sv);
			int nodeColor = Ui.lerpColor(category.accent, 0xFFFFFFFF, 0.25f + hv * 0.25f);
			int radius = nodeR + Math.round(hv * nodeR * 0.15f) + Math.round(pulse * nodeR * 0.08f);

			// Spoke from the disc edge to the node, so the ring reads as one
			// instrument rather than as six unrelated buttons.
			int innerX = centerX + (int) Math.round(Math.cos(angle) * (centerR + 6));
			int innerY = centerY + (int) Math.round(Math.sin(angle) * (centerR + 6));
			int outerX = nx - (int) Math.round(Math.cos(angle) * (radius + 8));
			int outerY = ny - (int) Math.round(Math.sin(angle) * (radius + 8));
			Ui.line(c, innerX, innerY, outerX, outerY, 1,
					Ui.withAlpha(accent, Math.round((0x22 + sv * 0x33) * fade)));

			// The selected node carries an accent glow, so the open category is
			// identifiable from across the ring rather than only by its tint.
			if (sv > 0.02f) {
				Ui.discGlow(c, nx, ny, radius + scaled(10),
						Ui.withAlpha(UiTheme.ACCENT_GLOW, Math.round(0xD0 * sv)),
						0.30f * sv * fade);
			}
			if (emphasis > 0.02f || pulse > 0.02f) {
				Ui.circle(c, nx, ny, radius + 7, Ui.withAlpha(category.accent,
						Math.round((0x18 + 0x20 * emphasis + 0x28 * pulse) * fade)));
			}
			Ui.circle(c, nx, ny + 3, radius, Ui.withAlpha(0x000000, Math.round(0x40 * fade)));
			Ui.circleGradient(c, nx, ny, radius,
					Ui.lerpColor(UiTheme.PANEL_ALT, category.accent, 0.10f + 0.16f * emphasis + 0.26f * sv),
					Ui.lerpColor(UiTheme.PANEL_TOP, category.accent, 0.03f + 0.14f * sv));
			// Same trade as the category ring: six of these are drawn every
			// frame, and the smooth pass was costing more than the node itself.
			Ui.ringFast(c, nx, ny, radius, 2,
					Ui.withAlpha(Ui.lerpColor(category.accent, 0xFFFFFFFF, Math.max(hv, sv) * 0.25f),
							Math.round(0xB0 * fade)));

			int iconSize = Math.max(14, Math.round(radius * 0.80f));
			UiIcons.draw(c, icon(category), nx - iconSize / 2, ny - iconSize / 2, iconSize,
					Ui.withAlpha(nodeColor, Math.round(255 * fade)));

			// Label just outside the node, along the same spoke. It is centred
			// on the node's own axis and clamped into the screen, which keeps
			// the top and bottom labels from ever leaving the viewport - and is
			// why the label rides the spoke instead of being dropped straight
			// below the node, where the four side nodes would each land on top
			// of their neighbour.
			int lx = centerX + (int) Math.round(Math.cos(angle) * labelR);
			int ly = centerY + (int) Math.round(Math.sin(angle) * labelR);
			String text = category.title;
			int tw = label.width(text);
			lx = Math.max(8 + tw / 2, Math.min(width - 8 - tw / 2, lx));
			ly = Math.max(6, Math.min(height - 6 - label.lineHeight(), ly - label.lineHeight() / 2));
			// The top right corner belongs to the chrome. A label whose band
			// crosses the close disc or its style chip is pushed left of them
			// rather than drawn underneath: on a 427x240 canvas the upper right
			// node's label lands in exactly that strip. A label that clears the
			// controls vertically is left where the spoke puts it.
			int bandTop = ly - 2;
			int bandBottom = ly + label.lineHeight() + 2;
			if (bandBottom > closeY && bandTop < styleY + scaled(STYLE_H) && lx + tw / 2 > chromeLeft()) {
				lx = Math.max(8 + tw / 2, Math.min(lx, chromeLeft() - tw / 2 - 6));
			}
			Ui.drawCentered(c, label, text, lx, ly,
					Ui.withAlpha(Ui.lerpColor(UiTheme.TEXT_2, 0xFFFFFFFF, emphasis), Math.round(255 * fade)));
		}
	}

	private void renderPanel(UiCanvas c, UiType type, int mouseX, int mouseY, float delta,
			int accent, int ink) {
		Category category = CATEGORIES[selected];
		List<ModuleCard> list = source.cards(category);
		if (list == null) {
			list = List.of();
		}

		float reveal = Motion.easeOutBack(panelReveal.value(), 1.35f);
		// The opacity is the clamped curve, not the overshooting one: a colour
		// channel cannot be 108% opaque.
		float pr = Motion.clamp01(reveal);
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

		// Header: category mark, name and count. The name is a single line and
		// is centred in the header band, so it stays on the same axis whatever
		// the font resolves to.
		int mark = scaled(34);
		int markX = px + UiTheme.RADIUS_LG;
		int markY = py + (scaled(52) - mark) / 2;
		Ui.circleGradient(c, markX + mark / 2, markY + mark / 2, mark / 2,
				Ui.lerpColor(UiTheme.PANEL_ALT, category.accent, 0.30f),
				Ui.lerpColor(UiTheme.PANEL_TOP, category.accent, 0.10f));
		Ui.ring(c, markX + mark / 2, markY + mark / 2, mark / 2, 1,
				Ui.withAlpha(category.accent, Math.round(0xCC * pr)));
		UiIcons.draw(c, icon(category), markX + Math.round(mark * 0.24f), markY + Math.round(mark * 0.24f),
				Math.round(mark * 0.52f),
				Ui.withAlpha(Ui.lerpColor(category.accent, 0xFFFFFFFF, 0.22f), Math.round(255 * pr)));

		UiText bold = type.bold();
		UiText regular = type.regular();
		int titleX = markX + mark + UiTheme.SP_4;
		Ui.drawEllipsized(c, bold, category.title, titleX,
				Ui.centerTextY(py, scaled(52), bold),
				Math.max(0, backX - titleX - UiTheme.SP_3),
				Ui.withAlpha(UiTheme.TEXT, Math.round(255 * pr)));

		// Back arrow.
		boolean backHovered = overBack(mouseX, mouseY);
		Ui.surface(c, backX, backY, scaled(BACK_SIZE), scaled(BACK_SIZE), scaled(BACK_SIZE) / 2,
				Ui.withAlpha(backHovered ? accent : UiTheme.PANEL_ALT, Math.round(255 * pr)),
				Ui.withAlpha(backHovered ? accent : UiTheme.PANEL_TOP, Math.round(255 * pr)));
		UiIcons.draw(c, UiIcons.Icon.BACK, backX + scaled(6), backY + scaled(6), scaled(10),
				Ui.withAlpha(backHovered ? ink : UiTheme.TEXT_2, Math.round(255 * pr)));

		Ui.fadeLine(c, px + 12, py + scaled(52), pw - 24, Ui.withAlpha(UiTheme.BORDER, Math.round(0x9A * pr)));

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
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			int h = card.height();
			// Cards clear of the strip are skipped: a ten module HUD sheet used
			// to pay for every card every frame, most of them hidden under the
			// clip. The margin keeps the cached bounds of the cards just outside
			// the strip where they were last drawn - far enough out that the hit
			// test, which only ever asks about positions inside the strip, can
			// never reach them.
			if (y + h >= listTop - 40 && y <= listBottom + 40) {
				float landed = i < cardStates.size()
						? Motion.easeOutCubic(cardStates.get(i).reveal.value())
						: 1f;
				// The card slides the last few pixels up into its slot; the
				// reveal is measured from the slot, so the hit test follows it.
				int cardY = y + Math.round((1f - landed) * CARD_RISE);
				card.render(c, type, cardX, cardY, cardW, mouseX, mouseY, delta, false);
				if (landed < 0.999f) {
					// A card cannot be told to draw itself at partial alpha - it
					// owns dozens of colours - so the fade is a composite of the
					// surface it is arriving on. Laid over the card, it is
					// indistinguishable from the card itself fading in.
					fadeCardIn(c, cardX, cardY, cardW, h, cardY, 1f - landed);
				}
			}
			y += h + UiTheme.CARD_GAP;
		}
		c.popClip();
		scrollbar.setRailX(railX);
		scrollbar.render(c);
	}

	/**
	 * Hides part of a card behind the sheet's own surface.
	 *
	 * The colour is sampled down the panel rather than taken as one constant, so
	 * the composite matches the gradient underneath at any card position and no
	 * seam is left behind at the end of the stagger.
	 */
	private void fadeCardIn(UiCanvas c, int x, int y, int w, int h, int rowY, float amount) {
		int alpha = Math.round(Motion.clamp01(amount) * 255f);
		if (alpha <= 2) {
			return;
		}
		float rowT = Motion.clamp01((rowY - panelY) / (float) Math.max(1, panelH));
		int body = Ui.lerpColor(UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM, rowT);
		Ui.roundRect(c, x, y, w, h, UiTheme.RADIUS, Ui.withAlpha(body, alpha));
	}

	private boolean overClose(int mouseX, int mouseY) {
		int size = scaled(CLOSE_SIZE);
		return mouseX >= closeX && mouseX < closeX + size
				&& mouseY >= closeY && mouseY < closeY + size;
	}

	private boolean overBack(int mouseX, int mouseY) {
		int size = scaled(BACK_SIZE);
		return mouseX >= backX && mouseX < backX + size
				&& mouseY >= backY && mouseY < backY + size;
	}

	private boolean overStyle(int mouseX, int mouseY) {
		return mouseX >= styleX && mouseX < styleX + styleW
				&& mouseY >= styleY && mouseY < styleY + scaled(STYLE_H);
	}

	/**
	 * The style switch, directly under the close disc: it names the style it
	 * switches to, so the label is read as an instruction rather than as a
	 * status. Everything below it in the corner belongs to the frame.
	 */
	private void renderStyleChip(UiCanvas c, UiType type, int mouseX, int mouseY) {
		boolean hovered = overStyle(mouseX, mouseY);
		int h = scaled(STYLE_H);
		Ui.surface(c, styleX, styleY, styleW, h, UiTheme.RADIUS, UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
		Ui.outline(c, styleX, styleY, styleW, h, UiTheme.RADIUS,
				hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		UiIcons.draw(c, UiIcons.Icon.LAYOUT, styleX + scaled(8), styleY + scaled(8), scaled(12),
				hovered ? UiTheme.ACCENT : UiTheme.TEXT_3);
		Ui.drawEllipsized(c, type.regular(), NEXT_STYLE, styleX + scaled(25),
				Ui.centerTextY(styleY, h, type.regular()), styleW - scaled(42),
				hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
		UiIcons.draw(c, UiIcons.Icon.CHEVRON, styleX + styleW - scaled(15), styleY + h / 2 - scaled(3),
				scaled(8), hovered ? UiTheme.TEXT : UiTheme.TEXT_3);
	}

	/** The left edge of the top right chrome: the close disc and the style chip. */
	private int chromeLeft() {
		return Math.min(closeX, styleX);
	}

	private void renderCloseButton(UiCanvas c, int mouseX, int mouseY, int ink) {
		boolean hovered = overClose(mouseX, mouseY);
		// The button grows into the pointer, which is what makes it feel like a
		// control rather than a painted cross.
		int grow = Math.round(closeHover.value() * scaled(3));
		int size = scaled(CLOSE_SIZE) + grow;
		int x = closeX - grow / 2;
		int y = closeY - grow / 2;
		// A filled disc, not a well: the corner is chrome over the world, and a
		// control that is lit from the first frame reads as a button instead of
		// only becoming one when the pointer finds it.
		Ui.surface(c, x, y, size, size, size / 2, UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
		Ui.outline(c, x, y, size, size, size / 2,
				hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		if (closeHover.value() > 0.02f) {
			Ui.discGlow(c, x + size / 2, y + size / 2, size / 2 + scaled(4),
					UiTheme.ACCENT_GLOW, 0.22f * closeHover.value());
		}
		UiIcons.draw(c, UiIcons.Icon.CLOSE, x + size / 2 - scaled(4), y + size / 2 - scaled(4),
				scaled(8), hovered ? ink : UiTheme.TEXT_2);
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

		if (button == 0 && overClose((int) mx, (int) my)) {
			closeAction.run();
			return true;
		}
		if (button == 0 && overStyle((int) mx, (int) my)) {
			cycleStyleAction.run();
			return true;
		}
		if (button == 0 && distanceSqr(mx, my, paletteX, paletteY) <= (paletteR + 4) * (paletteR + 4)) {
			accentPanel.toggle();
			return true;
		}

		if (selected >= 0) {
			if (button == 0 && overBack((int) mx, (int) my)) {
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
						// The click pulse runs from here: the node swells once
						// as the sheet opens, so the two events are visibly one
						// gesture.
						selectPulse[i].snap(0f);
						pulseArmed[i] = true;
					}
					scrollbar.reset();
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

	private static long distanceSqr(double ax, double ay, double bx, double by) {
		double dx = ax - bx;
		double dy = ay - by;
		return Math.round(dx * dx + dy * dy);
	}

	/**
	 * One card's landing state: its own animation and the delay left before it
	 * is allowed to start.
	 */
	private static final class CardState {
		private final Anim reveal = new Anim(0f, CARD_SPEED);
		private float wait;
	}
}
