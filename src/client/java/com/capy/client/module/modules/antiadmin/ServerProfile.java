package com.capy.client.module.modules.antiadmin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Per-server tuning.
 *
 * A profile can silence a signal that the server produces honestly, lower the
 * bar for a signal that only a vanished player can cause there, and raise the
 * alarm level inside a region. Everything else — the base thresholds — comes
 * from {@link DetectionKind} itself, so a profile only has to describe where it
 * differs from the default.
 */
public record ServerProfile(String id, String name, List<String> hostPatterns,
		Set<DetectionKind> disabledSignals, Map<DetectionKind, Integer> thresholdOverrides,
		List<RegionBoundary> regions) {

	public static final String AUTO = "auto";
	public static final String DEFAULT_ID = "default";
	public static final String DONUT_ID = "donut-family";
	public static final String ANARCHY_ID = "anarchy";

	public ServerProfile {
		hostPatterns = hostPatterns == null ? List.of() : List.copyOf(hostPatterns);
		disabledSignals = disabledSignals == null || disabledSignals.isEmpty()
				? Set.of()
				: Set.copyOf(disabledSignals);
		thresholdOverrides = thresholdOverrides == null || thresholdOverrides.isEmpty()
				? Map.of()
				: Map.copyOf(thresholdOverrides);
		regions = regions == null ? List.of() : List.copyOf(regions);
	}

	/**
	 * Everything on, stock thresholds.
	 *
	 * This is the profile a server with no entry uses, so it has to be the one
	 * that is right most of the time rather than the one that is loudest.
	 */
	public static final ServerProfile DEFAULT = new ServerProfile(
			DEFAULT_ID, "Default", List.of(), Set.of(), Map.of(), List.of());

	/**
	 * DonutSMP and its siblings.
	 *
	 * These servers run a vanish plugin that keeps the account in the player
	 * info map while hiding it from the tab list, so the weak evidence that a
	 * quieter profile would wait for is already conclusive there. The spawn box
	 * escalates anything found inside it, because that is where a staff member
	 * would be standing to watch new players.
	 */
	public static final ServerProfile DONUT = new ServerProfile(
			DONUT_ID,
			"DonutSMP family",
			List.of("donutsmp.com", "donutmc.com", "donutsmp.net", "donutmc.net"),
			EnumSet.noneOf(DetectionKind.class),
			Map.of(
					DetectionKind.ORPHAN_PARTICLE, 1,
					DetectionKind.ORPHAN_SOUND, 2,
					DetectionKind.SOURCELESS_EXPLOSION, 1,
					DetectionKind.SHADOW_MAP_DELTA, 1),
			List.of(new RegionBoundary("DonutSMP spawn",
					new BlockPos(-500, 40, -500), new BlockPos(500, 160, 500), Confidence.HIGH)));

	/**
	 * Anarchy servers.
	 *
	 * Chat there is a firehose of faked join and leave lines, so both chat based
	 * signals are off: they would fire on a troll typing "Player left the game"
	 * long before they fired on a vanish. Everything positional stays on.
	 */
	public static final ServerProfile ANARCHY = new ServerProfile(
			ANARCHY_ID,
			"Anarchy",
			List.of("2b2t.org", "constantiam.net", "9b9t.com", "2b2t.net"),
			EnumSet.of(DetectionKind.HEADLESS_CHAT, DetectionKind.VANISH_TEXT),
			Map.of(
					DetectionKind.ORPHAN_SOUND, 5,
					DetectionKind.ORPHAN_PARTICLE, 3,
					DetectionKind.SOURCELESS_EXPLOSION, 2),
			List.of());

	/** Every built-in profile, in the order the selector offers them. */
	public static List<ServerProfile> builtins() {
		return List.of(DEFAULT, DONUT, ANARCHY);
	}

	/** The built-in with this id, or null. */
	public static ServerProfile byId(String id) {
		if (id == null) {
			return null;
		}
		for (ServerProfile profile : builtins()) {
			if (profile.id.equals(id)) {
				return profile;
			}
		}
		return null;
	}

	/** Ids of the built-ins, for the selector setting. */
	public static String[] ids() {
		List<ServerProfile> all = builtins();
		String[] out = new String[all.size()];
		for (int i = 0; i < all.size(); i++) {
			out[i] = all.get(i).id;
		}
		return out;
	}

	public boolean isDisabled(DetectionKind kind) {
		return kind != null && disabledSignals.contains(kind);
	}

	/** The threshold for a kind, with this profile's override applied. */
	public int threshold(DetectionKind kind) {
		Integer override = thresholdOverrides.get(kind);
		return override == null ? kind.threshold() : Math.max(1, override);
	}

	/** The region containing the position, or null. */
	public RegionBoundary regionAt(BlockPos pos) {
		for (RegionBoundary region : regions) {
			if (region.contains(pos)) {
				return region;
			}
		}
		return null;
	}

	/** The confidence floor for a position inside a region, or null outside. */
	public Confidence regionFloor(BlockPos pos) {
		RegionBoundary region = regionAt(pos);
		return region == null ? null : region.alertOverride();
	}

	/**
	 * True when this profile belongs to the given host.
	 *
	 * Matching is on whole labels: {@code donutsmp.com} matches
	 * {@code play.donutsmp.com} but not {@code notdonutsmp.com}, which is the
	 * mistake a plain {@code contains} makes. A pattern containing {@code *}
	 * is treated as a glob for the cases the label rule cannot express.
	 */
	public boolean matchesHost(String hostname) {
		if (hostname == null || hostname.isBlank() || hostPatterns.isEmpty()) {
			return false;
		}
		String host = KnownPlayerStore.sanitizeHost(hostname);
		for (String pattern : hostPatterns) {
			String needle = pattern.toLowerCase(Locale.ROOT);
			if (needle.indexOf('*') >= 0) {
				if (glob(needle).matcher(host).matches()) {
					return true;
				}
				continue;
			}
			if (host.equals(needle) || host.endsWith("." + needle)) {
				return true;
			}
		}
		return false;
	}

	/** A copy with one more region, used by the region-aware profiles. */
	public ServerProfile withRegions(List<RegionBoundary> extra) {
		if (extra == null || extra.isEmpty()) {
			return this;
		}
		List<RegionBoundary> merged = new ArrayList<>(regions);
		merged.addAll(extra);
		return new ServerProfile(id, name, hostPatterns, disabledSignals, thresholdOverrides, merged);
	}

	private static java.util.regex.Pattern glob(String pattern) {
		StringBuilder regex = new StringBuilder();
		for (String part : pattern.split("\\*", -1)) {
			if (regex.length() > 0) {
				regex.append(".*");
			}
			regex.append(java.util.regex.Pattern.quote(part));
		}
		return java.util.regex.Pattern.compile(regex.toString());
	}

	/** Debug view of the overrides, so an empty profile is obvious in a log. */
	public String summary() {
		Map<String, Integer> overrides = new HashMap<>();
		for (Map.Entry<DetectionKind, Integer> entry : thresholdOverrides.entrySet()) {
			overrides.put(entry.getKey().name(), entry.getValue());
		}
		return name + " (off: " + disabledSignals.size() + ", overrides: " + overrides + ")";
	}
}
