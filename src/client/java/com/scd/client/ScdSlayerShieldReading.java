package com.scd.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Voidgloom Seraph's Malevolent Hitshield state directly out of
 * its own nameplate text - the same way ScdSlayerHealthReading reads HP,
 * since Hypixel appends this kind of state to the nameplate rather than
 * showing it anywhere else readable.
 *
 * The exact wording hasn't been verified against a live shielded nameplate,
 * so this tries a couple of plausible patterns. If it doesn't fire correctly
 * in practice, running /scd slayer nearby while the shield is up shows the
 * real text to match against instead of guessing further.
 */
public final class ScdSlayerShieldReading {
	private static final Pattern HITS_REMAINING = Pattern.compile("(\\d+)\\s*[Hh]its?\\b");
	private static final Pattern SHIELD_WORD = Pattern.compile("[Ss]hield(ed)?");

	public record Reading(boolean active, Integer hitsRemaining) {
	}

	private ScdSlayerShieldReading() {
	}

	public static Reading parse(String nameplateText) {
		Matcher hits = HITS_REMAINING.matcher(nameplateText);
		if (hits.find()) {
			return new Reading(true, Integer.parseInt(hits.group(1)));
		}
		if (SHIELD_WORD.matcher(nameplateText).find()) {
			return new Reading(true, null);
		}
		return new Reading(false, null);
	}
}
