package com.capy.client.finder.core;

import java.util.ArrayList;
import java.util.List;

/** Holes in the lowest bedrock layer of a chunk. bedrock[z * 16 + x] tells whether the cell is bedrock. Returns local {x, z}. */
public final class BedrockVoid {
	private BedrockVoid() {
	}

	public static List<int[]> holes(boolean[] bedrock) {
		List<int[]> out = new ArrayList<>();
		for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (!bedrock[z * 16 + x]) out.add(new int[]{x, z});
		return out;
	}
}
