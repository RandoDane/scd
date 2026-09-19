package com.scd.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the currently-active Slayer XP percentage boost from mayor/minister perks (e.g. Aatrox's
 * "Slayer XP Buff": "Earn 25% more Slayer XP."), fetched from the SCD server's own /api/mayor
 * endpoint (which already polls Hypixel's public election resource - see server/src/poller.js).
 *
 * Scans every currently-active perk's description for "Slayer" + "XP" + a percentage rather than
 * hardcoding Aatrox by name specifically, so this also picks up the same buff when granted via the
 * minister role instead of the mayor role, or a differently-worded Slayer XP perk from a future
 * mayor, without needing a code change either way.
 */
public class ScdMayorPerks {
	private static final Pattern FORMATTING_CODE = Pattern.compile("§.");
	private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

	private volatile double slayerXpBoostPercent;
	private volatile String sourceMayorName;

	public void update(ScdApiClient.MayorInfo info) {
		double boost = 0;
		for (var perk : info.perks()) {
			Double pct = slayerXpPercent(perk.description());
			if (pct != null) {
				boost = pct;
				break;
			}
		}
		slayerXpBoostPercent = boost;
		sourceMayorName = boost > 0 ? info.mayorName() : null;
	}

	/** Multiplier to apply to base Slayer XP - 1.25 for a +25% buff, 1.0 if none is currently active. */
	public double xpMultiplier() {
		return 1.0 + slayerXpBoostPercent / 100.0;
	}

	public double boostPercent() {
		return slayerXpBoostPercent;
	}

	/** Whichever mayor's own perks contain the active Slayer XP boost - not necessarily the elected mayor, since a minister can grant it too. Null if no boost is active. */
	public String sourceMayorNameOrNull() {
		return sourceMayorName;
	}

	private static Double slayerXpPercent(String description) {
		if (description == null) return null;
		String clean = FORMATTING_CODE.matcher(description).replaceAll("").toLowerCase(Locale.ROOT);
		if (!clean.contains("slayer") || !clean.contains("xp")) return null;
		Matcher m = PERCENT.matcher(clean);
		return m.find() ? Double.parseDouble(m.group(1)) : null;
	}
}
