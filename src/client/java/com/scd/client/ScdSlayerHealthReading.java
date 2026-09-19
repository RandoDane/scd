package com.scd.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Slayer boss's live HP straight out of its nameplate text (e.g.
 * "Revenant Horror III 380,000/400,000❤" or the abbreviated "400k❤"). These
 * bosses' real HP pool is a Hypixel-side value only ever shown in the
 * nameplate - the correlated vanilla entity's own health attribute stays a
 * flat, unmoving 100% and isn't actually tied to it, so the nameplate text
 * is the only real source of truth.
 */
public final class ScdSlayerHealthReading {
	private static final Pattern CURRENT_OVER_MAX = Pattern.compile(
			"([\\d,.]+)\\s*([kKmMbB]?)\\s*/\\s*([\\d,.]+)\\s*([kKmMbB]?)\\s*❤");
	private static final Pattern CURRENT_ONLY = Pattern.compile("([\\d,.]+)\\s*([kKmMbB]?)\\s*❤");

	public record Reading(double current, Double max) {
	}

	private ScdSlayerHealthReading() {
	}

	public static Reading parse(String nameplateText) {
		Matcher both = CURRENT_OVER_MAX.matcher(nameplateText);
		if (both.find()) {
			return new Reading(toNumber(both.group(1), both.group(2)), toNumber(both.group(3), both.group(4)));
		}
		Matcher single = CURRENT_ONLY.matcher(nameplateText);
		if (single.find()) {
			return new Reading(toNumber(single.group(1), single.group(2)), null);
		}
		return null;
	}

	private static double toNumber(String digits, String suffix) {
		double value = Double.parseDouble(digits.replace(",", ""));
		return switch (suffix.toUpperCase(Locale.ROOT)) {
			case "K" -> value * 1_000;
			case "M" -> value * 1_000_000;
			case "B" -> value * 1_000_000_000;
			default -> value;
		};
	}
}
