package com.capy.client.module.modules.antiadmin;

import net.minecraft.client.Minecraft;

/**
 * One detector of one signal.
 *
 * A detector is a state machine fed by three entry points: the packet it cares
 * about, the tick, and the enable/disable pair. It never renders and never
 * alerts — it pushes {@link Observation}s and the correlator decides what they
 * mean.
 *
 * Both defaults exist because most detectors only need one of them: a packet
 * detector ignores the tick, a scan detector ignores the queue.
 */
public interface SignalDetector {
	/** Stable name used in logs and by the per-signal settings. */
	String name();

	/** The single kind this detector emits. */
	DetectionKind kind();

	/** Called when the module is switched on. */
	void onEnable();

	/** Called when the module is switched off. */
	void onDisable();

	/** One packet the client received, already normalised. */
	default void onPacket(PacketSignals.Event event) {
	}

	/** One client tick. */
	default void onTick(Minecraft mc) {
	}

	/** Drops per-session state after a world change or a manual reset. */
	default void reset() {
	}
}
