package com.capy.client.finder.core;

/**
 * "Owed growth": true when a block is immature AND could still grow right now, which means its chunk has not been
 * random-ticked for long enough. The mod reads block state values and passes them in. Every maximum and light need was
 * checked against the vanilla 1.21.11 block classes, except where the caller supplies the condition directly.
 */
public final class GrowthRules {
	private GrowthRules() {
	}

	public static boolean kelp(int age, boolean waterAbove) {
		return age < 25 && waterAbove;
	}

	public static boolean hangingVine(int age, boolean airBelow) {
		return age < 25 && airBelow;
	}

	public static boolean bamboo(int stage, boolean airAbove, int stackBelow, int lightAbove) {
		return stage == 0 && airAbove && stackBelow + 1 < 16 && lightAbove >= 9;
	}

	public static boolean sugarCane(boolean airAbove, int column) {
		return airAbove && column < 3;
	}

	public static boolean cactus(boolean airAbove, int age, int column) {
		return airAbove && (age < 15 || column < 3);
	}

	public static boolean cocoa(int age) {
		return age < 2;
	}

	public static boolean netherWart(int age) {
		return age < 3;
	}

	public static boolean sweetBerry(int age, int lightAbove) {
		return age < 3 && lightAbove >= 9;
	}

	public static boolean stem(int age, int light) {
		return age < 7 && light >= 9;
	}

	public static boolean crop(boolean maxAge, int light) {
		return !maxAge && light >= 9;
	}

	public static boolean vine(boolean airBelow, boolean anyFaceAttached) {
		return airBelow && anyFaceAttached;
	}

	public static boolean leaves(int distance, boolean persistent) {
		return distance == 7 && !persistent;
	}

	public static boolean propagule(boolean hanging, int age) {
		return hanging && age < 4;
	}

	public static boolean buddingAmethyst(boolean airNeighbour, boolean fullWaterNeighbour) {
		return airNeighbour || fullWaterNeighbour;
	}

	public static boolean amethystBud(boolean supportIsBudding, boolean fullCluster) {
		return supportIsBudding && !fullCluster;
	}

	public static boolean grassSmothered(boolean smotheredAbove) {
		return smotheredAbove;
	}

	public static boolean dirtUnspread(boolean smotheredAbove, boolean underWater, boolean litGrassNearby) {
		return !smotheredAbove && !underWater && litGrassNearby;
	}

	/** Folia-style servers: kelp counts only with water above and a kelp column shorter than 3. */
	public static boolean kelpFolia(boolean waterAbove, int column) {
		return waterAbove && column < 3;
	}

	/** Eyeblossoms: open from day tick 12600 to 23400, closed otherwise. TRUE open, FALSE closed, null = too soon to judge. */
	public static Boolean eyeblossomsShouldBeOpen(long dayTime) {
		int time = (int) Math.floorMod(dayTime, 24000L);
		boolean open = time >= 12600 && time < 23401;
		int since = open ? time - 12600 : Math.floorMod(time - 23401, 24000);
		return since >= 4000 ? open : null;
	}

	/** Count the wrong-state eyeblossoms only when none are in the right state and at least 3 are wrong. */
	public static int eyeblossomWeight(int wrong, int right) {
		return right == 0 && wrong >= 3 ? wrong : 0;
	}
}
