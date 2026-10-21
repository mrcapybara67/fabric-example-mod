package com.capy.client.gui;

import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Cherry blossom petals that drift across the screen while the GUI is open.
 *
 * They are drawn *behind* the window, so they can never cover a label or
 * intercept a click — they simply fall past the panel. The whole system lives
 * inside the screen instance, so it stops the moment the GUI closes and no
 * animation loop survives it.
 */
public final class UiPetals {
	private static final int MAX_PETALS = 22;

	private final Petal[] petals = new Petal[MAX_PETALS];
	private final Random random = new Random(0x5A4B1C);
	private int width;
	private int height;

	private static final class Petal {
		float x;
		float y;
		float vx;
		float vy;
		float rotation;
		float spin;
		float size;
		float life;
		int color;
	}

	/** (Re)seeds the petals for a screen of the given size. */
	public void reset(int width, int height) {
		this.width = width;
		this.height = height;
		for (int i = 0; i < petals.length; i++) {
			Petal petal = petals[i];
			if (petal == null) {
				petal = new Petal();
				petals[i] = petal;
			}
			respawn(petal, random.nextFloat() * height);
			petal.life = random.nextFloat();
		}
	}

	private void respawn(Petal petal, float y) {
		petal.x = random.nextFloat() * Math.max(1, width);
		petal.y = y;
		petal.vx = -6f - random.nextFloat() * 10f;
		petal.vy = 8f + random.nextFloat() * 16f;
		petal.rotation = random.nextFloat() * 6.28f;
		petal.spin = (random.nextFloat() - 0.5f) * 1.4f;
		petal.size = 1.6f + random.nextFloat() * 1.8f;
		petal.color = random.nextFloat() < 0.35f
				? Ui.withAlpha(UiTheme.ACCENT_2, 0x6E)
				: Ui.withAlpha(UiTheme.ACCENT, 0x59);
	}

	/** Advances the petals and recycles the ones that leave the screen. */
	public void update(float delta, int width, int height) {
		this.width = width;
		this.height = height;
		float dt = Math.min(0.1f, delta);
		for (Petal petal : petals) {
			if (petal == null) {
				continue;
			}
			petal.x += petal.vx * dt;
			petal.y += petal.vy * dt;
			petal.rotation += petal.spin * dt;
			petal.life += dt * 0.28f;
			if (petal.y > height + 12 || petal.x < -12) {
				respawn(petal, -8f);
				petal.life = 0f;
			}
		}
	}

	/** Draws every petal. */
	public void render(GuiGraphics g) {
		for (Petal petal : petals) {
			if (petal == null) {
				continue;
			}
			// Fade in and out over the petal's life so nothing pops.
			float fade = petal.life < 0.25f ? petal.life / 0.25f
					: (petal.life > 0.8f ? Math.max(0f, (1f - petal.life) / 0.2f) : 1f);
			if (fade <= 0.02f) {
				continue;
			}
			int color = Ui.withAlpha(petal.color, Math.round(((petal.color >>> 24) & 0xFF) * fade));
			UiIcons.petal(g, petal.x, petal.y, petal.size, petal.rotation, color);
		}
	}
}
