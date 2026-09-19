package com.scd.client;

/** Coin-price formatting shared by the HUD, graph, tooltip, and chat commands. */
public final class ScdFormat {
	private ScdFormat() {
	}

	public static String coins(double n) {
		return coins(n, 1);
	}

	public static String coins(double n, int decimals) {
		if (n >= 1_000_000) return String.format("%." + decimals + "fM", n / 1_000_000);
		if (n >= 1_000) return String.format("%." + decimals + "fK", n / 1_000);
		return String.format("%." + decimals + "f", n);
	}

	/** Compact item-count formatting (2,541 -> "2.5K", 15,000,000 -> "15M") - trims a trailing ".0" that coins() always keeps. */
	public static String compactCount(long n) {
		if (n >= 1_000_000_000) return trimTrailingZero(n / 1_000_000_000.0) + "B";
		if (n >= 1_000_000) return trimTrailingZero(n / 1_000_000.0) + "M";
		if (n >= 1_000) return trimTrailingZero(n / 1_000.0) + "K";
		return String.valueOf(n);
	}

	private static String trimTrailingZero(double n) {
		String s = String.format(java.util.Locale.ROOT, "%.1f", n);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	private static final java.util.regex.Pattern COMPACT_NUMBER = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)([kKmMbB]?)$");

	/** Parses a coin amount that may use a k/m/b suffix ("1.3m" -> 1,300,000; "800k" -> 800,000; plain "50000" still works) - the inverse of coins() above, for typed price input. Returns null if the text isn't a valid number. */
	public static Long parseCompactLong(String text) {
		if (text == null) return null;
		String trimmed = text.trim();
		if (trimmed.isEmpty()) return null;
		var matcher = COMPACT_NUMBER.matcher(trimmed);
		if (!matcher.matches()) return null;

		double value;
		try {
			value = Double.parseDouble(matcher.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
		double multiplier = switch (matcher.group(2).toUpperCase(java.util.Locale.ROOT)) {
			case "K" -> 1_000.0;
			case "M" -> 1_000_000.0;
			case "B" -> 1_000_000_000.0;
			default -> 1.0;
		};
		return Math.round(value * multiplier);
	}
}
