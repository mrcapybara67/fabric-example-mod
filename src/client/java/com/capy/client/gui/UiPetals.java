package com.capy.client.gui;

import java.util.Random;

/**
 * Cherry blossom petals.
 *
 * The system is a fixed pool of petal objects that are recycled for the whole
 * lifetime of the owner — nothing is allocated per frame. Each petal carries
 * its own position, drift, fall speed, rotation, rotation speed, scale, shape
 * variant, colour and sway, so the motion looks organic but stays
 * deterministic (the pool is seeded, so a session always looks the same).
 *
 * Three depth layers give the scene its depth:
 * <ul>
 *   <li>{@code BACK} — small, faint petals far behind the interface;</li>
 *   <li>{@code MID} — medium petals drifting through the workspace;</li>
 *   <li>{@code FRONT} — a few larger, softer petals that pass in front of
 *       the panels.</li>
 * </ul>
 *
 * The system is inert unless it is updated, so a closed screen costs nothing.
 */
public final class UiPetals {
	/** Maximum number of live petals. */
	public static final int CAPACITY = 64;

	/** Depth layer of a petal. */
	public enum Layer {
		BACK(0.75f, 0.58f, 2.0f, 4.5f, 0.34f),
		MID(1.0f, 0.72f, 3.5f, 7.0f, 0.55f),
		FRONT(1.3f, 0.46f, 7.0f, 13.0f, 0.40f);

		final float scale;
		final float alpha;
		final float sizeMin;
		final float sizeMax;
		final float speed;

		Layer(float scale, float alpha, float sizeMin, float sizeMax, float speed) {
			this.scale = scale;
			this.alpha = alpha;
			this.sizeMin = sizeMin;
			this.sizeMax = sizeMax;
			this.speed = speed;
		}
	}

	/** Petal silhouette variants. */
	public enum Shape {
		/** Wide, notched petal - the classic cherry blossom petal. */
		NOTCHED,
		/** Narrow teardrop. */
		SLIM,
		/** Rounded, almost circular petal. */
		ROUND
	}

	private static final class Petal {
		float x;
		float y;
		float vx;
		float vy;
		float rotation;
		float spin;
		float scale;
		float size;
		float alpha;
		float swayPhase;
		float swayAmount;
		float life;
		float maxLife;
		int color;
		Layer layer;
		Shape shape;
	}

	private final Petal[] pool = new Petal[CAPACITY];
	private final Random random = new Random(0x5A4B1C0L);
	private int live;
	private float width;
	private float height;

	public UiPetals() {
		for (int i = 0; i < pool.length; i++) {
			pool[i] = new Petal();
		}
	}

	/**
	 * Prepares the pool for a screen of the given size and fills it with
	 * drifting petals.
	 */
	public void reset(int width, int height) {
		this.width = width;
		this.height = height;
		this.live = 0;
		for (int i = 0; i < pool.length; i++) {
			Layer layer = layerFor(i);
			spawn(pool[i], layer, random.nextFloat() * height, true);
		}
		this.live = pool.length;
	}

	/** Advances every petal. */
	public void update(float delta, int width, int height) {
		this.width = width;
		this.height = height;
		float dt = Math.min(0.1f, delta);
		for (Petal petal : pool) {
			if (petal == null || petal.maxLife <= 0) {
				continue;
			}
			petal.life += dt;
			if (petal.life >= petal.maxLife) {
				// The petal has settled: recycle it at the top.
				spawn(petal, petal.layer, -6f, false);
				continue;
			}
			petal.swayPhase += dt * petal.swayAmount;
			float sway = (float) Math.sin(petal.swayPhase) * 9.0f;
			petal.x += (petal.vx + sway) * dt;
			petal.y += petal.vy * dt;
			petal.rotation += petal.spin * dt;
			if (petal.y > height + 20 || petal.x < -30 || petal.x > width + 30) {
				spawn(petal, petal.layer, -8f, false);
			}
		}
	}

	/** Draws every petal of the given layer. */
	public void render(UiCanvas canvas, Layer layer, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		for (Petal petal : pool) {
			if (petal == null || petal.layer != layer || petal.maxLife <= 0) {
				continue;
			}
			float fade = fade(petal);
			if (fade <= 0.02f) {
				continue;
			}
			int color = Ui.fadeAlpha(petal.color, fade * alpha);
			drawPetal(canvas, petal, color);
		}
	}

	/** Draws every layer, back to front. */
		/**
	 * Releases a burst of petals from a point, used when the alert portrait
	 * appears. The burst reuses pooled objects, so it never allocates.
	 */
		// --------------------------------------------------------------- internals

	private Layer layerFor(int index) {
		if (index < CAPACITY * 0.47f) {
			return Layer.BACK;
		}
		if (index < CAPACITY * 0.85f) {
			return Layer.MID;
		}
		return Layer.FRONT;
	}

	private void spawn(Petal petal, Layer layer, float y, boolean scattered) {
		petal.layer = layer;
		petal.shape = Shape.values()[random.nextInt(Shape.values().length)];
		petal.scale = layer.scale * (0.8f + random.nextFloat() * 0.5f);
		petal.size = layer.sizeMin + random.nextFloat() * (layer.sizeMax - layer.sizeMin);
		petal.x = random.nextFloat() * Math.max(1f, width);
		petal.y = y;
		petal.vx = (-6f - random.nextFloat() * 10f) * layer.speed;
		petal.vy = (10f + random.nextFloat() * 16f) * layer.speed;
		petal.rotation = random.nextFloat() * 6.283f;
		petal.spin = (random.nextFloat() - 0.5f) * 1.6f;
		petal.swayPhase = random.nextFloat() * 6.283f;
		petal.swayAmount = 0.5f + random.nextFloat() * 1.4f;
		petal.life = 0f;
		petal.maxLife = scattered ? 6f + random.nextFloat() * 8f : 5f + random.nextFloat() * 7f;
		petal.color = random.nextFloat() < 0.4f
				? UiTheme.ACCENT_2
				: UiTheme.ACCENT;
		petal.alpha = layer.alpha;
	}

	/** Fade in at the start of a petal's life, fade out at the end. */
	private float fade(Petal petal) {
		float in = Math.min(1f, petal.life / 0.8f);
		float out = Math.min(1f, (petal.maxLife - petal.life) / 1.4f);
		return Math.max(0f, Math.min(in, out)) * petal.alpha;
	}

	/**
	 * Draws one petal.
	 *
	 * A petal is a tapered almond: three overlapping discs along the major
	 * axis, the outer ones smaller so the shape comes to a point. Building it
	 * from discs keeps the edge smooth at any rotation, which a per row
	 * ellipse cannot do.
	 */
	private void drawPetal(UiCanvas c, Petal petal, int color) {
		float size = petal.size;
		float aspect = petal.shape == Shape.SLIM ? 0.42f : (petal.shape == Shape.ROUND ? 0.86f : 0.62f);
		float minor = size * aspect;
		float rr = minor * 0.5f;
		double angle = petal.rotation;

		if (rr < 0.7f) {
			// Too small for a shape: a single soft dot.
			c.fill((int) petal.x, (int) petal.y, (int) petal.x + 2, (int) petal.y + 2, color);
			return;
		}
		float major = size;
		float reach = Math.max(0f, major * 0.5f - rr);
		// Small petals need one disc, big ones three.
		final int steps = rr < 1.6f ? 1 : (rr < 2.6f ? 2 : 3);
		for (int i = 0; i < steps; i++) {
			float t = steps == 1 ? 0f : (i / (float) (steps - 1)) * 2f - 1f;
			float ox = t * reach;
			float px = petal.x + (float) (ox * Math.cos(angle));
			float py = petal.y + (float) (ox * Math.sin(angle));
			int rad = Math.max(1, Math.round(rr * (1f - 0.30f * Math.abs(t))));
			Ui.circle(c, (int) Math.round(px), (int) Math.round(py), rad, color);
		}
	}
}
