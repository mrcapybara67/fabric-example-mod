package com.capy.client.module.modules.esp;

import java.util.Locale;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The kind of container a storage box wraps, and the colour that kind is drawn
 * with.
 *
 * A base full of chests next to a wall of shulker boxes is only useful if the
 * two read differently at a glance, so the classification is coarse on purpose:
 * nine kinds, each with its own colour row in the card, rather than a hue per
 * block id. Anything that is storage and is not one of the named kinds falls
 * into {@link #OTHER}, which is also where brewing stands, campfires and
 * chiseled bookshelves land.
 *
 * <p>A monster spawner is here even though it holds no items: it is the block a
 * mob farm is built around, and finding one is the reason the overlay is open.
 *
 * The block state is enough to tell the kinds apart: shulker boxes are the only
 * block whose class carries the whole family, ender chests are their own block,
 * and the rest are an explicit id list.
 */
public enum StorageKind {
	CHEST("color_chest", 0xFFF7A93B),
	SHULKER("color_shulker", 0xFFB07CF2),
	ENDER_CHEST("color_ender_chest", 0xFF7E6BFF),
	FURNACE("color_furnace", 0xFFE86A5C),
	HOPPER("color_hopper", 0xFF6FA8DC),
	SPAWNER("color_spawner", 0xFFE45CFF),
	PISTON("color_piston", 0xFF9FB3C8),
	STICKY_PISTON("color_sticky_piston", 0xFF9BD65B),
	OTHER("color_other", 0xFF5CE1E6);

	private final String settingKey;
	private final int defaultColor;

	StorageKind(String settingKey, int defaultColor) {
		this.settingKey = settingKey;
		this.defaultColor = defaultColor;
	}

	/** The setting key this kind's colour row is stored under. */
	public String settingKey() {
		return settingKey;
	}

	/** The colour this kind starts with; the user's own value replaces it. */
	public int defaultColor() {
		return defaultColor;
	}

	/**
	 * The kind a storage block belongs to.
	 *
	 * Only called for blocks that are already known to hold items, so every
	 * path that reaches here returns a real kind - {@link #OTHER} is the
	 * colour for "storage, but nothing worth naming", never for "not storage".
	 */
	public static StorageKind of(BlockState state) {
		if (state == null) {
			return OTHER;
		}
		if (state.getBlock() instanceof ShulkerBoxBlock) {
			return SHULKER;
		}
		if (state.is(Blocks.ENDER_CHEST)) {
			return ENDER_CHEST;
		}
		if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)) {
			return CHEST;
		}
		if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)) {
			return FURNACE;
		}
		if (state.is(Blocks.HOPPER) || state.is(Blocks.DISPENSER) || state.is(Blocks.DROPPER)) {
			return HOPPER;
		}
		if (state.is(Blocks.SPAWNER)) {
			return SPAWNER;
		}
		// Pistons hold no items, so the storage scan finds them by block state
		// rather than by block entity. They are here because a piston facing a
		// hidden chest is the single most useful thing to spot in a base.
		if (state.is(Blocks.STICKY_PISTON)) {
			return STICKY_PISTON;
		}
		if (state.is(Blocks.PISTON)) {
			return PISTON;
		}
		return OTHER;
	}

	/** Parsing is only needed by config readers; unknown text means other. */
	public static StorageKind parse(String name) {
		if (name == null) {
			return OTHER;
		}
		String key = name.toLowerCase(Locale.ROOT);
		for (StorageKind kind : values()) {
			if (kind.name().equalsIgnoreCase(key) || kind.settingKey.equalsIgnoreCase(key)) {
				return kind;
			}
		}
		return OTHER;
	}
}
