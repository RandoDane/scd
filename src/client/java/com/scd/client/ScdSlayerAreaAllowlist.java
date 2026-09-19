package com.scd.client;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which sidebar-scoreboard area-name lines count as "actually somewhere this
 * Slayer type's content can happen", for gating its HUDs/ability
 * warnings/drop tracking off everywhere else. Hypixel's area line shows the
 * SPECIFIC sub-location (e.g. "Void Sepulture"), not a broader island
 * category the way SkyHanni's own IslandType enum groups things for its own
 * purposes - so this lists the actual sub-areas as they appear on the
 * scoreboard, not just the enclosing island name.
 *
 * Enderman's entries are verified against a real scoreboard reading; Blaze
 * and Spider's are best guesses (the broader island name, plus known points
 * of interest) - use /scd slayer debug's per-type area check while standing
 * in one of these if a HUD doesn't show up where it should.
 */
public final class ScdSlayerAreaAllowlist {
	private static final Map<ScdSlayerType, List<String>> AREAS = new EnumMap<>(ScdSlayerType.class);

	static {
		AREAS.put(ScdSlayerType.ENDERMAN, List.of("The End", "Void Sepulture", "Dragon's Nest", "Zealot Bruiser Hideout"));
		AREAS.put(ScdSlayerType.BLAZE, List.of("Crimson Isle", "Stronghold", "Smoldering Tomb"));
		// "Dragontail" confirmed live (2026-09-19): a Tarantula Broodfather V fight was running
		// there and got its whole quest nulled out by this allowlist missing it, breaking every
		// Spider feature (not just world-render) in that specific sub-area. Spider's Den almost
		// certainly has other named sub-areas beyond these three that haven't turned up yet.
		AREAS.put(ScdSlayerType.SPIDER, List.of("Spider's Den", "Burning Desert", "Dragontail"));
	}

	private ScdSlayerAreaAllowlist() {
	}

	/** False for most types (Zombie/Wolf/Vampire's host mobs aren't confined to one place) - true only for the three checked here. */
	public static boolean isRestricted(ScdSlayerType type) {
		return AREAS.containsKey(type);
	}

	public static boolean matches(ScdSlayerType type, String areaLine) {
		if (areaLine == null) return false;
		for (String area : AREAS.getOrDefault(type, List.of())) {
			if (areaLine.equalsIgnoreCase(area)) return true;
		}
		return false;
	}
}
