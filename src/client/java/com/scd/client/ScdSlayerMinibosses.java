package com.scd.client;

import java.util.List;

/**
 * Every known Slayer miniboss nameplate, tagged with its parent Slayer type
 * and whether it's the rarer "stronger" variant. Vampire Slayer has no
 * minibosses at all, so it has no entries here.
 */
public final class ScdSlayerMinibosses {
	public record Entry(ScdSlayerType type, String name, boolean stronger) {
	}

	public static final List<Entry> ALL = List.of(
			new Entry(ScdSlayerType.ZOMBIE, "Revenant Sycophant", false),
			new Entry(ScdSlayerType.ZOMBIE, "Revenant Champion", false),
			new Entry(ScdSlayerType.ZOMBIE, "Deformed Revenant", true),
			new Entry(ScdSlayerType.ZOMBIE, "Atoned Champion", false),
			new Entry(ScdSlayerType.ZOMBIE, "Atoned Revenant", true),

			new Entry(ScdSlayerType.SPIDER, "Tarantula Vermin", false),
			new Entry(ScdSlayerType.SPIDER, "Tarantula Beast", false),
			new Entry(ScdSlayerType.SPIDER, "Mutant Tarantula", true),
			new Entry(ScdSlayerType.SPIDER, "Primordial Jockey", false),
			new Entry(ScdSlayerType.SPIDER, "Primordial Viscount", true),

			new Entry(ScdSlayerType.WOLF, "Pack Enforcer", false),
			new Entry(ScdSlayerType.WOLF, "Sven Follower", false),
			new Entry(ScdSlayerType.WOLF, "Sven Alpha", true),

			new Entry(ScdSlayerType.ENDERMAN, "Voidling Devotee", false),
			new Entry(ScdSlayerType.ENDERMAN, "Voidling Radical", false),
			new Entry(ScdSlayerType.ENDERMAN, "Voidcrazed Maniac", true),

			new Entry(ScdSlayerType.BLAZE, "Flare Demon", false),
			new Entry(ScdSlayerType.BLAZE, "Kindleheart Demon", false),
			new Entry(ScdSlayerType.BLAZE, "Burningsoul Demon", true));

	private ScdSlayerMinibosses() {
	}

	public static Entry match(String nameplate) {
		for (Entry entry : ALL) {
			if (nameplate.contains(entry.name())) return entry;
		}
		return null;
	}
}
