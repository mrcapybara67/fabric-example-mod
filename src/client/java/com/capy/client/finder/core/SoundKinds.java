package com.capy.client.finder.core;

import java.util.Set;

/**
 * Classifies a sound event id (for example "minecraft:block.chest.open") into an activity kind with a weight.
 * Natural noise is IGNORE: falling gravity blocks, wooden doors (villagers and mobs use them), ambient liquid, dripstone,
 * sculk and note blocks. Sounds that only natural structures make are not activity either; isStructureSound() lets the
 * caller remember "a natural structure is here" so nearby redstone and storage noise can be discounted.
 */
public final class SoundKinds {
	private SoundKinds() {
	}

	public enum Kind { STORAGE, REDSTONE, MINING, BUILDING, MACHINE, EXPLOSION, IGNORE }

	public record Hit(Kind kind, double weight) {
	}

	private static final Hit IGNORE = new Hit(Kind.IGNORE, 0);
	private static final Set<String> STORAGE = Set.of("chest", "trapped_chest", "barrel", "ender_chest", "shulker_box");
	private static final Set<String> GRAVITY = Set.of("sand", "red_sand", "gravel", "anvil", "suspicious_sand", "suspicious_gravel",
			"pointed_dripstone", "dragon_egg", "scaffolding", "powder_snow", "snow");
	private static final Set<String> STONE_LIKE = Set.of("stone", "deepslate", "deepslate_bricks", "deepslate_tiles", "tuff",
			"calcite", "nether_ore", "ancient_debris", "netherrack", "basalt", "amethyst_block");
	private static final String[] STRUCTURE_PREFIXES = {
			"block.trial_spawner.", "block.ominous_trial_spawner.", "block.vault.", "block.ominous_vault.", "block.ominous_item_spawner.",
			"block.copper_bulb.", "block.copper_door.", "block.copper_trapdoor.", "block.heavy_core.", "block.decorated_pot.",
			"block.sculk", "entity.warden.", "block.reinforced_deepslate.", "block.bell.", "block.end_portal", "block.portal.",
			"block.spawner."};

	private static String strip(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	/** True for sounds that mean a natural structure (trial chamber, ancient city, village, portal room, spawner room) is here. */
	public static boolean isStructureSound(String soundId) {
		if (soundId == null) {
			return false;
		}
		String id = strip(soundId);
		for (String p : STRUCTURE_PREFIXES) {
			if (id.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	/** True for the primed-TNT sound: a player lit TNT, so a blast nearby is not a creeper. */
	public static boolean isTntPrimed(String soundId) {
		return soundId != null && strip(soundId).equals("entity.tnt.primed");
	}

	public static Hit classify(String soundId) {
		if (soundId == null) {
			return IGNORE;
		}
		String id = strip(soundId);
		if (id.equals("entity.generic.explode")) {
			return new Hit(Kind.EXPLOSION, 2.0);
		}
		if (!id.startsWith("block.") || isStructureSound(id)) {
			return IGNORE;
		}
		String rest = id.substring(6);
		int dot = rest.indexOf('.');
		if (dot < 0) {
			return IGNORE;
		}
		String block = rest.substring(0, dot), action = rest.substring(dot + 1);
		if (GRAVITY.contains(block)) {
			return IGNORE;
		}
		if (STORAGE.contains(block)) {
			return action.equals("open") || action.equals("close") ? new Hit(Kind.STORAGE, 3.0) : IGNORE;
		}
		switch (block) {
			case "piston":
				return action.equals("extend") || action.equals("contract") ? new Hit(Kind.REDSTONE, 2.0) : IGNORE;
			case "dispenser":
				return action.equals("dispense") || action.equals("launch") || action.equals("fail")
						? new Hit(Kind.REDSTONE, 2.0) : IGNORE;
			case "comparator":
			case "lever":
				return new Hit(Kind.REDSTONE, 1.0);
			case "furnace":
			case "blastfurnace":
			case "smoker":
				return new Hit(Kind.MACHINE, 1.0);
			case "beacon":
				return new Hit(Kind.MACHINE, 1.0);
			case "note_block":
				return IGNORE;
			case "iron_door":
			case "iron_trapdoor":
				return action.equals("open") || action.equals("close") ? new Hit(Kind.REDSTONE, 1.5) : IGNORE;
			default:
				break;
		}
		if (block.endsWith("door") || block.endsWith("trapdoor") || block.equals("fence_gate")) {
			return IGNORE; // wooden: villagers, mobs, everyone
		}
		if (block.endsWith("button") || block.contains("pressure_plate") || block.equals("tripwire")) {
			return new Hit(Kind.REDSTONE, 1.0);
		}
		boolean stone = STONE_LIKE.contains(block);
		return switch (action) {
			case "break" -> new Hit(Kind.MINING, stone ? 1.5 : 1.0);
			case "hit" -> new Hit(Kind.MINING, 0.4);
			case "place" -> new Hit(Kind.BUILDING, stone ? 1.5 : 1.0);
			default -> IGNORE;
		};
	}
}
