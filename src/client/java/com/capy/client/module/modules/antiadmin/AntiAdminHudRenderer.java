package com.capy.client.module.modules.antiadmin;

import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import com.capy.client.gui.UiType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The persistent half of the alerts: one card per recent detection.
 *
 * The face alert is a moment; this is the record. A card keeps the name, the
 * confidence bar, the signal tags and where it happened on screen for thirty
 * seconds, so a player who was looking at their hotbar when the overlay played
 * can still read what happened instead of guessing.
 *
 * Four cards at most, newest first. More than four is not a warning, it is a
 * wall, and the oldest one is the least useful thing on screen.
 *
 * The stack is anchored to the <b>bottom</b> right corner and grows upwards.
 * The corner notification the face renderer draws owns the top right, so
 * anchoring here keeps the two from ever sharing a row, and anchoring from the
 * bottom means a screen that is shorter than the full stack loses its oldest
 * card rather than pushing the newest one off the edge.
 *
 * Nothing here allocates per frame beyond the strings the text faces need:
 * the cards are kept as records and the geometry is computed from the index.
 */
public final class AntiAdminHudRenderer {
	private static final int MAX_CARDS = 4;
	private static final int CARD_W = 228;
	private static final int CARD_H = 44;
	/**
	 * Canvas height below which the cards shrink.
	 *
	 * The game really produces canvases this short - 1280x720 at GUI scale 3 is
	 * 427x240 - and there the full card is more than half the screen width and a
	 * fifth of its height, in the corner the player is trying to play in.
	 */
	private static final int COMPACT_BELOW = 400;
	private static final int COMPACT_CARD_W = 140;
	private static final int COMPACT_CARD_H = 36;
	private static final int GAP = 4;
	private static final int MARGIN = 12;
	private static final int FADE_MS = 2_000;
	private static final UiType TYPE = new McType();

	private final Deque<Card> cards = new ArrayDeque<>(MAX_CARDS);

	/** Adds a card, replacing an older card for the same account. */
	public void push(AlertRecord record, long now) {
		if (record == null) {
			return;
		}
		cards.removeIf(card -> card.record.uuid().equals(record.uuid()));
		cards.addFirst(new Card(record, now));
		while (cards.size() > MAX_CARDS) {
			cards.removeLast();
		}
	}

	/** Drops every card. Called on a world change or a toggle. */
	public void clear() {
		cards.clear();
	}

	/** How many cards are still inside their lifetime. */
	public int size(long now) {
		prune(now);
		return cards.size();
	}

	/** Draws the stack onto Minecraft's graphics, or nothing without a handle. */
	public void render(GuiGraphics graphics, int screenW, int screenH) {
		if (graphics == null) {
			return;
		}
		render(McCanvas.of(graphics), screenW, screenH);
	}

	/**
	 * Draws the stack in the bottom right corner, newest first, upwards.
	 *
	 * The layout only ever needs a {@link UiCanvas}, so that is the signature
	 * the drawing lives in: the same cards can be rasterised offline for review
	 * without a running game.
	 */
	public void render(UiCanvas canvas, int screenW, int screenH) {
		long now = System.currentTimeMillis();
		prune(now);
		if (cards.isEmpty() || canvas == null) {
			return;
		}
		UiText medium = TYPE.medium();
		UiText regular = TYPE.regular();

		// Both the margin and the card height come off the bottom edge, so the
		// newest card is always fully on screen: measuring the top edge from
		// screenH alone would hang the first card off the bottom of the screen.
		// A card that no longer fits above the bottom one is dropped by the
		// cardY test instead of being pushed over the top edge.
		boolean compact = screenH < COMPACT_BELOW;
		int cardW = compact ? COMPACT_CARD_W : CARD_W;
		int cardH = compact ? COMPACT_CARD_H : CARD_H;
		int cardX = screenW - MARGIN - cardW;
		int index = 0;
		for (Card card : cards) {
			int cardY = screenH - MARGIN - cardH - index * (cardH + GAP);
			float alpha = alpha(card, now);
			if (alpha > 0.01f && cardY >= 0) {
				renderCard(canvas, medium, regular, card, now, alpha, cardX, cardY, cardW, cardH, compact);
			}
			index++;
		}
	}

	private void renderCard(UiCanvas c, UiText medium, UiText regular, Card card, long now,
			float alpha, int x, int y, int cardW, int cardH, boolean compact) {
		AlertRecord record = card.record;
		int accent = Ui.withAlpha(record.confidence().argb(), Math.round(255 * alpha));
		int panelTop = Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(242 * alpha));
		int panelBottom = Ui.withAlpha(UiTheme.PANEL_BOTTOM, Math.round(226 * alpha));

		Ui.shadow(c, x, y, cardW, cardH, UiTheme.RADIUS);
		Ui.surface(c, x, y, cardW, cardH, UiTheme.RADIUS, panelTop, panelBottom);
		Ui.outline(c, x, y, cardW, cardH, UiTheme.RADIUS, Ui.withAlpha(accent, Math.round(150 * alpha)));

		// A two pixel stripe on the leading edge carries the confidence at a
		// glance, the same way an enabled module card is marked.
		int inset = compact ? 6 : 8;
		Ui.roundRect(c, x + 6, y + inset, 2, cardH - inset * 2, 1, accent);

		int textX = x + 14;
		int textW = cardW - 22;
		// The compact card gives its rows back, not its content: the name keeps
		// the whole line, and the two right hand columns it would have shared
		// the line with are the ones that go. The stripe still carries the
		// confidence, so nothing on the card becomes unreadable to save space.
		int titleY = compact ? y + 3 : y + 7;
		int reserve = compact ? 0 : 48;

		String distance = record.distance() < 0 || compact
				? ""
				: String.format(Locale.ROOT, "%.0f m", record.distance());
		int distanceW = distance.isEmpty() ? 0 : regular.width(distance) + 8;
		Ui.drawEllipsized(c, medium, record.name(), textX, titleY, textW - distanceW - reserve,
				Ui.withAlpha(UiTheme.TEXT, Math.round(245 * alpha)));
		if (!distance.isEmpty()) {
			Ui.drawRightAligned(c, regular, distance, x + cardW - 8, titleY + 2,
					Ui.withAlpha(UiTheme.TEXT_3, Math.round(220 * alpha)));
		}
		if (!compact) {
			String level = record.confidence().label().toUpperCase(Locale.ROOT);
			Ui.drawRightAligned(c, regular, level, x + cardW - 8 - distanceW, titleY + 2, accent);
		}

		// Confidence bar: a groove the width of the card, filled to the level.
		int barX = textX;
		int barY = compact ? y + 17 : y + 24;
		int barW = textW;
		Ui.roundRect(c, barX, barY, barW, 3, 2, Ui.withAlpha(UiTheme.TRACK_BOTTOM, Math.round(200 * alpha)));
		int fill = Math.max(3, Math.round(barW * fraction(record.confidence())));
		Ui.roundRect(c, barX, barY, fill, 3, 2, accent);

		String tags = card.tags();
		String when = ago(now - card.at);
		int whenW = regular.width(when);
		int tagY = compact ? y + 22 : y + 31;
		Ui.drawEllipsized(c, regular, tags, textX, tagY, textW - whenW - 6,
				Ui.withAlpha(UiTheme.TEXT_2, Math.round(230 * alpha)));
		Ui.drawRightAligned(c, regular, when, x + cardW - 8, tagY,
				Ui.withAlpha(UiTheme.TEXT_3, Math.round(200 * alpha)));
	}

	/** The filled fraction of the confidence bar. */
	private static float fraction(Confidence confidence) {
		return switch (confidence) {
			case LOW -> 0.25f;
			case MEDIUM -> 0.5f;
			case HIGH -> 0.78f;
			case CERTAIN -> 1f;
		};
	}

	/** Cards fade out over their last two seconds and then vanish. */
	private static float alpha(Card card, long now) {
		long age = now - card.at;
		if (age < 0) {
			return 0f;
		}
		long life = AlertManager.CARD_MS;
		if (age >= life) {
			return 0f;
		}
		long left = life - age;
		if (left >= FADE_MS) {
			return 1f;
		}
		return left / (float) FADE_MS;
	}

	private void prune(long now) {
		cards.removeIf(card -> now - card.at >= AlertManager.CARD_MS);
	}

	private static String ago(long millis) {
		long seconds = Math.max(0L, millis / 1000L);
		if (seconds < 60L) {
			return seconds + "s";
		}
		return (seconds / 60L) + "m";
	}

	/** One card: the record, when it arrived and its cached tag line. */
	private static final class Card {
		private final AlertRecord record;
		private final long at;
		private final String tags;

		private Card(AlertRecord record, long at) {
			this.record = record;
			this.at = at;
			String line = record.tagLine();
			this.tags = line.isEmpty() ? "no signal detail" : line;
		}

		private String tags() {
			return tags;
		}
	}
}
