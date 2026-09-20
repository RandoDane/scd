package com.scd.client;

import java.util.Map;

/**
 * One customizable color on a HUD - a stat number, a section background, whatever a HUD's own
 * enum (see ScdSlayerColorSlot for the pattern) declares as a distinct, independently-tintable
 * region. This is the reusable half of SCD's HUD appearance customization: any HUD that wants
 * player-editable colors defines its own enum implementing this interface (one constant per
 * customizable region, each with a stable id/label/default), stores overrides in a
 * {@code Map<String, Integer>} on its config section, resolves colors through
 * {@link #resolve(Map, ScdColorSlot)} at render time instead of a hardcoded ScdTheme constant, and
 * points a "Customize appearance..." button at ScdHudAppearanceScreen with that slot list and map.
 * See ScdSlayerColorSlot + ScdSlayerHud/ScdSlayerStatsHud for a complete worked example.
 */
public interface ScdColorSlot {
	/** Stable key this slot is saved under in config - must never change once shipped, or saved overrides silently stop applying. */
	String id();

	/** Human-readable name shown next to this slot's swatch in ScdHudAppearanceScreen. */
	String label();

	/** The color this slot renders with until the player overrides it. */
	int defaultColor();

	/** Looks up this slot's current color: the player's override if one is saved, otherwise its default. */
	static int resolve(Map<String, Integer> overrides, ScdColorSlot slot) {
		Integer override = overrides.get(slot.id());
		return override != null ? override : slot.defaultColor();
	}
}
