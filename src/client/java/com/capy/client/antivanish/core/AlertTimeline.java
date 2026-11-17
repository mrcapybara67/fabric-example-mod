package com.capy.client.antivanish.core;

/** Timing of the full-screen alert. Plain numbers: the mod draws it with the game's own fills and text. */
public final class AlertTimeline {
    private AlertTimeline() {}

    public static final double IN = 0.30, STAY = 3.40, OUT = 1.00, TOTAL = IN + STAY + OUT;

    private static double easeOutCubic(double x) { double v = 1 - Math.max(0, Math.min(1, x)); return 1 - v * v * v; }

    /** Returns {fade 0..1, pop 0.85..1, pulse 0..1}, or null when the alert is over. */
    public static double[] frame(double t) {
        if (t < 0 || t > TOTAL) return null;
        double fade = t < IN ? easeOutCubic(t / IN) : (t > IN + STAY ? 1 - easeOutCubic((t - IN - STAY) / OUT) : 1.0);
        double pop = t < IN ? 0.85 + 0.15 * easeOutCubic(t / IN) : 1.0;
        double pulse = 0.5 + 0.5 * Math.sin(t * 5.0);
        return new double[]{fade, pop, pulse};
    }
}
