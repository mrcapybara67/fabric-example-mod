package com.capy.client.finder.core;

/** Counts events per packet channel and per vertical band relative to the player, to show which channels a server strips underground. */
public final class ChannelStats {
	public enum Channel { SOUND, BLOCK_EVENT, LEVEL_EVENT, DESTRUCTION, BLOCK_UPDATE, PARTICLE, ITEM_ENTITY, XP_ORB }

	public enum Band { ABOVE, NEAR, MID, DEEP }

	private final int[][] counts = new int[Channel.values().length][Band.values().length];

	public static Band band(double eventY, double playerY) {
		double dy = eventY - playerY;
		if (dy > 8) {
			return Band.ABOVE;
		}
		if (dy >= -8) {
			return Band.NEAR;
		}
		if (dy >= -32) {
			return Band.MID;
		}
		return Band.DEEP;
	}

	public synchronized void count(Channel c, double eventY, double playerY) {
		counts[c.ordinal()][band(eventY, playerY).ordinal()]++;
	}

	public synchronized int total(Channel c) {
		int n = 0;
		for (int v : counts[c.ordinal()]) {
			n += v;
		}
		return n;
	}

	public synchronized int deep(Channel c) {
		return counts[c.ordinal()][Band.DEEP.ordinal()];
	}

	/** True when the channel delivers events at other heights but never from far below: the server probably culls it underground. */
	public synchronized boolean silentDeep(Channel c) {
		return total(c) - deep(c) >= 50 && deep(c) == 0;
	}

	public synchronized void reset() {
		for (int[] row : counts) {
			java.util.Arrays.fill(row, 0);
		}
	}

	public synchronized String line() {
		StringBuilder sb = new StringBuilder();
		for (Channel c : Channel.values()) {
			if (total(c) == 0) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(" | ");
			}
			sb.append(c.name().toLowerCase()).append(' ').append(total(c)).append(" (deep ").append(deep(c))
					.append(silentDeep(c) ? ", culled?" : "").append(')');
		}
		return sb.length() == 0 ? "no events yet" : sb.toString();
	}
}
