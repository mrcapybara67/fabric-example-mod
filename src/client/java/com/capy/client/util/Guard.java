package com.capy.client.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The last line between a broken feature and a crashed game.
 *
 * <p>Every hook this client installs runs <em>inside</em> vanilla's own call
 * stack: a packet handler, the hand renderer, the HUD pass, the world pass.
 * An exception thrown there is not "our module failed", it is the game dying
 * with a stack trace the player cannot act on — and, because the join path is
 * where most of these run for the first time, it reads as "the client crashes
 * when I join a server".</p>
 *
 * <p>So the entry points are wrapped: the feature is skipped and the failure is
 * logged once per label, which is enough to diagnose it without filling the log
 * with sixty copies a second.</p>
 */
public final class Guard {
	private static final Logger LOGGER = LoggerFactory.getLogger("capy");
	/** Labels already reported, so a failure every frame logs once. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private Guard() {
	}

	/** Runs {@code body}, swallowing (and reporting) anything it throws. */
	public static void run(String label, Runnable body) {
		try {
			body.run();
		} catch (Throwable failure) {
			report(label, failure);
		}
	}

	/** Logs a failure once per label. */
	public static void report(String label, Throwable failure) {
		if (REPORTED.add(label)) {
			LOGGER.error("Capy: '{}' failed; the feature is skipped so the game keeps running.", label, failure);
		}
	}
}
