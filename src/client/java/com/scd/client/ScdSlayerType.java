package com.scd.client;

import java.util.List;

/**
 * The six Slayer types, each with every nameplate string its boss can show -
 * including the renamed forms at higher tiers (e.g. Revenant Horror becomes
 * Atoned Horror). Sourced from cross-checking Skyblocker's and SkyHanni's
 * (both LGPL, public) boss-name tables rather than guessed.
 */
public enum ScdSlayerType {
	ZOMBIE("Zombie", "Revenant Horror", "Atoned Horror"),
	SPIDER("Spider", "Tarantula Broodfather", "Conjoined Brood"),
	WOLF("Wolf", "Sven Packmaster"),
	ENDERMAN("Enderman", "Voidgloom Seraph"),
	BLAZE("Blaze", "Inferno Demonlord"),
	VAMPIRE("Vampire", "Riftstalker Bloodfiend", "Bloodfiend");

	private final String displayName;
	private final List<String> bossNames;

	ScdSlayerType(String displayName, String... bossNames) {
		this.displayName = displayName;
		this.bossNames = List.of(bossNames);
	}

	public String displayName() {
		return displayName;
	}

	public List<String> bossNames() {
		return bossNames;
	}

	/** Matches a scoreboard line or entity nameplate against every type's known boss-name aliases. */
	public static ScdSlayerType fromBossName(String text) {
		for (ScdSlayerType type : values()) {
			for (String bossName : type.bossNames) {
				if (text.contains(bossName)) return type;
			}
		}
		return null;
	}
}
