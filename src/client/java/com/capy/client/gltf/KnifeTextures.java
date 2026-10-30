package com.capy.client.gltf;

import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Which texture each part of a knife mesh uses.
 *
 * <p>The blade is per material because that is the whole point of a sword
 * material: a netherite knife has to look like netherite. Everything else - the
 * grip wrap, the guard, the accent hardware and the bare steel edge - is shared,
 * so six materials cost six textures instead of thirty-six and a new material
 * needs no new art.
 *
 * <p>Lookups are total: an unknown material falls back to iron rather than
 * rendering an untextured mesh, because a wrong-coloured knife is a cosmetic
 * problem and an invisible one is a bug report.
 */
public final class KnifeTextures {
	private static final String NAMESPACE = "capy";
	private static final String DIR = "item/knife/";

	/** Blade face, per material. */
	private static final Map<String, Identifier> BLADE = Map.of(
			"wooden", texture("blade_wooden"),
			"stone", texture("blade_stone"),
			"iron", texture("blade_iron"),
			"golden", texture("blade_golden"),
			"diamond", texture("blade_diamond"),
			"netherite", texture("blade_netherite"));

	/** Honed cutting edge, per material. */
	private static final Map<String, Identifier> EDGE = Map.of(
			"wooden", texture("edge_wooden"),
			"stone", texture("edge_stone"),
			"iron", texture("edge_iron"),
			"golden", texture("edge_golden"),
			"diamond", texture("edge_diamond"),
			"netherite", texture("edge_netherite"));

	private static final Identifier GRIP_WRAPPED = texture("grip_wrapped");
	private static final Identifier GUARD = texture("guard");
	private static final Identifier ACCENT = texture("accent");
	private static final Identifier WRAP = texture("wrap");

	private KnifeTextures() {
	}

	private static Identifier texture(String name) {
		return Identifier.fromNamespaceAndPath(NAMESPACE, DIR + name);
	}

	/** The blade sheet for a material. */
	public static Identifier blade(String material) {
		Identifier id = material == null ? null : BLADE.get(material);
		return id != null ? id : BLADE.get("iron");
	}

	/** The edge sheet for a material. */
	public static Identifier edge(String material) {
		Identifier id = material == null ? null : EDGE.get(material);
		return id != null ? id : EDGE.get("iron");
	}

	/** The wrapped handle. */
	public static Identifier grip() {
		return GRIP_WRAPPED;
	}

	/** The guard, bolster or tsuba plate. */
	public static Identifier guard() {
		return GUARD;
	}

	/** Pins, collars, pommels: the small metal parts. */
	public static Identifier accent() {
		return ACCENT;
	}

	/** The cord wrap along a handle. */
	public static Identifier wrap() {
		return WRAP;
	}
}
