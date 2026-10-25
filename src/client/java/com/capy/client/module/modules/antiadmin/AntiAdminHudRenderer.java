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
 * Three cards at most, newest first. More than three is not a warning, it is a
 * wall, and the oldest one is the least useful thing on screen.
 *
 * Nothing here allocates per frame beyond the strings the text faces need:
 * the cards are kept as records and the geometry is computed from the index.
 */
public final class AntiAdminHudRenderer {
	private static final int MAX_CARDS = 3;
	private static final int CARD_W = 228;
	private static final int CARD_H = 44;
	private static final int GAP = 4;
	private static final int MARGIN = 12;
	/** The corner notification the face renderer draws lives above this. */
	private static final int TOP = 66;
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

	/** Draws the stack in the top right corner, newest first. */
	public void render(GuiGraphics graphics, int screenW, int screenH) {
		long now = System.currentTimeMillis();
		prune(now);
		if (cards.isEmpty() || graphics == null) {
			return;
		}
		UiCanvas canvas = McCanvas.of(graphics);
		UiText medium = TYPE.medium();
		UiText regular = TYPE.regular();

		int index = 0;
		for (Card card : cards) {
			float alpha = alpha(card, now);
			if (alpha > 0.01f) {
				renderCard(canvas, medium, regular, card, now, alpha,
						screenW - MARGIN - CARD_W, TOP + index * (CARD_H + GAP));
			}
			index++;
		}
	}

	private void renderCard(UiCanvas c, UiText medium, UiText regular, Card card, long now,
			float alpha, int x, int y) {
		AlertRecord record = card.record;
		int accent = Ui.withAlpha(record.confidence().argb(), Math.round(255 * alpha));
		int panelTop = Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(242 * alpha));
		int panelBottom = Ui.withAlpha(UiTheme.PANEL_BOTTOM, Math.round(226 * alpha));

		Ui.shadow(c, x, y, CARD_W, CARD_H, UiTheme.RADIUS);
		Ui.surface(c, x, y, CARD_W, CARD_H, UiTheme.RADIUS, panelTop, panelBottom);
		Ui.outline(c, x, y, CARD_W, CARD_H, UiTheme.RADIUS, Ui.withAlpha(accent, Math.round(150 * alpha)));

		// A two pixel stripe on the leading edge carries the confidence at a
		// glance, the same way an enabled module card is marked.
		Ui.roundRect(c, x + 6, y + 8, 2, CARD_H - 16, 1, accent);

		int textX = x + 14;
		int textW = CARD_W - 22;
		int titleY = y + 7;

		String distance = record.distance() < 0
				? ""
				: String.format(Locale.ROOT, "%.0f m", record.distance());
		int distanceW = distance.isEmpty() ? 0 : regular.width(distance) + 8;
		Ui.drawEllipsized(c, medium, record.name(), textX, titleY, textW - distanceW - 48,
				Ui.withAlpha(UiTheme.TEXT, Math.round(245 * alpha)));
		if (!distance.isEmpty()) {
			Ui.drawRightAligned(c, regular, distance, x + CARD_W - 8, titleY + 2,
					Ui.withAlpha(UiTheme.TEXT_3, Math.round(220 * alpha)));
		}

		String level = record.confidence().label().toUpperCase(Locale.ROOT);
		int levelW = regular.width(level);
		Ui.drawRightAligned(c, regular, level, x + CARD_W - 8 - distanceW, titleY + 2, accent);

		// Confidence bar: a groove the width of the card, filled to the level.
		int barX = textX;
		int barY = y + 24;
		int barW = textW;
		Ui.roundRect(c, barX, barY, barW, 3, 2, Ui.withAlpha(UiTheme.TRACK_BOTTOM, Math.round(200 * alpha)));
		int fill = Math.max(3, Math.round(barW * fraction(record.confidence())));
		Ui.roundRect(c, barX, barY, fill, 3, 2, accent);

		String tags = card.tags();
		String when = ago(now - card.at);
		int whenW = regular.width(when);
		Ui.drawEllipsized(c, regular, tags, textX, y + 31, textW - whenW - 6,
				Ui.withAlpha(UiTheme.TEXT_2, Math.round(230 * alpha)));
		Ui.drawRightAligned(c, regular, when, x + CARD_W - 8, y + 31,
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
