package com.scd.client;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which items count as a Slayer "drop" for each type, checked against
 * ScdInventoryWatcher/sack-pickup gains alongside "is a matching quest
 * active" (see ScdClient) - together this is the same "state flag +
 * allowlist" approach SkyHanni's own Slayer profit tracker uses instead of
 * trying to correlate a pickup to one specific kill (Hypixel's Telekinesis
 * is mandatory, so most drops never spawn as a ground entity to correlate
 * against at all).
 *
 * ITEM_IDS and ENCHANTMENT_IDS are transcribed directly from SkyHanni's own
 * reference data (hannibal002/SkyHanni-REPO, constants/SlayerProfitTrackerItems.json)
 * rather than scraped from wiki prose - real internal ids an established,
 * tested mod actually uses for this exact purpose, matched against our own
 * resolved item id (SkyblockItems.getId()). That source file also lists
 * mob-tier references (e.g. "GHOUL;3", "TARANTULA;4", "HOUND;3", "ENDERMAN;0"-"4")
 * used for SkyHanni's own kill-based coin accounting - not items, so not
 * ported here - and ATTRIBUTE_SHARD_* entries, whose raw-attribute-key
 * naming doesn't line up with our own resolved shard-id scheme
 * (ScdAttributeShards) without more work; that's a known gap, not a guess.
 *
 * DISPLAY_NAMES is the original wiki-sourced fallback, kept only for the
 * sack-pickup chat path, which never has a resolved item id to check - only
 * a plain name parsed out of a hover tooltip.
 */
public final class ScdSlayerDropAllowlist {
	private static final Map<ScdSlayerType, List<String>> ITEM_IDS = new EnumMap<>(ScdSlayerType.class);
	private static final Map<ScdSlayerType, List<String>> ENCHANTMENT_IDS = new EnumMap<>(ScdSlayerType.class);
	private static final Map<ScdSlayerType, List<String>> DISPLAY_NAMES = new EnumMap<>(ScdSlayerType.class);

	static {
		ITEM_IDS.put(ScdSlayerType.ZOMBIE, List.of(
				"REVENANT_FLESH", "FOUL_FLESH", "ZOMBIE_SLAYER_RUNE", "UNDEAD_CATALYST", "BEHEADED_HORROR",
				"REVENANT_CATALYST", "SNAKE_RUNE", "REVENANT_VISCERA", "SCYTHE_BLADE", "SHARD_OF_THE_SHREDDED",
				"WARDEN_HEART", "ROTTEN_FLESH", "GOLD_INGOT", "GOLDEN_POWDER", "DYE_MATCHA",
				"FESTERING_MAGGOT", "SEVERED_HAND"));
		ENCHANTMENT_IDS.put(ScdSlayerType.ZOMBIE, List.of("ENCHANTMENT_SMITE_6", "ENCHANTMENT_SMITE_7"));
		DISPLAY_NAMES.put(ScdSlayerType.ZOMBIE, List.of(
				"Revenant Flesh", "Foul Flesh", "Beheaded Horror", "Revenant Viscera", "Scythe Blade",
				"Shard of the Shredded", "Warden Heart", "Rotten Flesh", "Matcha Dye", "Festering Maggot",
				"Severed Hand", "Smite"));

		ITEM_IDS.put(ScdSlayerType.SPIDER, List.of(
				"TARANTULA_WEB", "TOXIC_ARROW_POISON", "BITE_RUNE", "SPIDER_CATALYST", "FLY_SWATTER",
				"TARANTULA_TALISMAN", "DIGESTED_MOSQUITO", "SPIDER_EYE", "STRING", "BONE",
				"SPIDERS_DEN_TOP_TRAVEL_SCROLL", "ARACHNE_KEEPER_FRAGMENT", "BURNING_EYE", "DARKNESS_WITHIN_RUNE",
				"TARANTULA_CATALYST", "VIAL_OF_VENOM", "PRIMORDIAL_EYE", "ENSNARED_SNAIL", "SHRIVELED_WASP",
				"TARANTULA_SILK", "DYE_BRICK_RED"));
		ENCHANTMENT_IDS.put(ScdSlayerType.SPIDER, List.of("ENCHANTMENT_BANE_OF_ARTHROPODS_6"));
		DISPLAY_NAMES.put(ScdSlayerType.SPIDER, List.of(
				"Tarantula Web", "Toxic Arrow Poison", "Fly Swatter", "Digested Mosquito", "Spider Eye", "String",
				"Bone", "Burning Eye", "Vial of Venom", "Primordial Eye", "Ensnared Snail", "Shriveled Wasp",
				"Tarantula Silk", "Bane of Arthropods"));

		ITEM_IDS.put(ScdSlayerType.WOLF, List.of(
				"WOLF_TOOTH", "HAMSTER_WHEEL", "SPIRIT_RUNE", "FURBALL", "RED_CLAW_EGG", "COUTURE_RUNE",
				"OVERFLUX_CAPACITOR", "GRIZZLY_BAIT", "BONE", "WOLF_TALISMAN", "PARK_CAVE_TRAVEL_SCROLL",
				"WEAK_WOLF_CATALYST", "PET_ITEM_FORAGING_SKILL_BOOST_EPIC", "DYE_CELESTE"));
		ENCHANTMENT_IDS.put(ScdSlayerType.WOLF, List.of("ENCHANTMENT_CRITICAL_6"));
		DISPLAY_NAMES.put(ScdSlayerType.WOLF, List.of(
				"Wolf Tooth", "Hamster Wheel", "Furball", "Red Claw Egg", "Overflux Capacitor", "Grizzly Bait",
				"Bone", "Critical"));

		ITEM_IDS.put(ScdSlayerType.ENDERMAN, List.of(
				"NULL_SPHERE", "TWILIGHT_ARROW_POISON", "ENDERSNAKE_RUNE", "SUMMONING_EYE", "TRANSMISSION_TUNER",
				"NULL_ATOM", "HAZMAT_ENDERMAN", "POCKET_ESPRESSO_MACHINE", "DRAGON_RUNE", "HANDY_BLOOD_CHALICE",
				"SINFUL_DICE", "EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER", "PET_SKIN_ENDERMAN_SLAYER",
				"ETHERWARP_MERGER", "JUDGEMENT_CORE", "ENCHANTED_ENDER_PEARL", "ENDER_PEARL",
				"ENDERMAN_CORTEX_REWRITER", "DYE_BYZANTIUM", "ENDSTONE_IDOL"));
		ENCHANTMENT_IDS.put(ScdSlayerType.ENDERMAN, List.of(
				"ENCHANTMENT_MANA_STEAL_1", "ENCHANTMENT_SMARTY_PANTS_1", "ENCHANTMENT_ENDER_SLAYER_7"));
		DISPLAY_NAMES.put(ScdSlayerType.ENDERMAN, List.of(
				"Null Sphere", "Null Atom", "Twilight Arrow Poison", "Transmission Tuner", "Summoning Eye",
				"Hazmat Enderman", "Handy Blood Chalice", "Pocket Espresso Machine",
				"Exceedingly Rare Ender Artifact Upgrade", "Judgement Core", "Etherwarp Merger", "End Stone Idol",
				"Byzantium Dye", "Ender Pearl", "Mana Steal", "Smarty Pants", "Ender Slayer"));

		ITEM_IDS.put(ScdSlayerType.BLAZE, List.of(
				"DERELICT_ASHE", "LAVATEARS_RUNE", "WISP_POTION", "ARROW_BUNDLE_MAGMA", "MANA_DISINTEGRATOR",
				"SCORCHED_BOOKS", "KELVIN_INVERTER", "BLAZE_ROD_DISTILLATE", "GLOWSTONE_DUST_DISTILLATE",
				"MAGMA_CREAM_DISTILLATE", "NETHER_STALK_DISTILLATE", "CRUDE_GABAGOOL_DISTILLATE",
				"SCORCHED_POWER_CRYSTAL", "ARCHFIEND_DICE", "FIERY_BURST_RUNE", "FLAWED_OPAL_GEM",
				"HIGH_CLASS_ARCHFIEND_DICE", "WILSON_ENGINEERING_PLANS", "SUBZERO_INVERTER", "BLAZE_ASHES",
				"BLAZE_ROD", "ENCHANTED_BLAZE_POWDER", "NETHERRACK_LOOKING_SUNSHADE", "MILLENIA_OLD_BLAZE_ASHES",
				"SWORD_OF_BAD_HEALTH", "DYE_FLAME"));
		ENCHANTMENT_IDS.put(ScdSlayerType.BLAZE, List.of(
				"ENCHANTMENT_FIRE_ASPECT_3", "ENCHANTMENT_ULTIMATE_REITERATE_1", "ENCHANTMENT_SMOLDERING_1"));
		DISPLAY_NAMES.put(ScdSlayerType.BLAZE, List.of(
				"Derelict Ashe", "Kelvin Inverter", "Scorched Power Crystal", "Archfiend Dice",
				"Flawed Opal Gemstone", "High Class Archfiend Dice", "Wilson's Engineering Plans",
				"Subzero Inverter", "Blaze Ashes", "Blaze Rod", "Blaze Powder", "Flame Dye",
				"Fire Aspect", "Ultimate Reiterate", "Smoldering", "Scorched Books"));

		ITEM_IDS.put(ScdSlayerType.VAMPIRE, List.of(
				"COVEN_SEAL", "ENCHANTED_BOOK_BUNDLE_QUANTUM", "SOULTWIST_RUNE", "BUBBA_BLISTER", "CHOCOLATE_CHIP",
				"GUARDIAN_LUCKY_BLOCK", "MCGRUBBER_BURGER", "UNFANGED_VAMPIRE_PART", "ENCHANTED_BOOK_BUNDLE_THE_ONE",
				"HEMOVIBE", "VAMPIRIC_MELON", "DYE_SANGRIA"));
		DISPLAY_NAMES.put(ScdSlayerType.VAMPIRE, List.of(
				"Coven Seal", "Enchanted Book Bundle", "Bubba Blister", "Fang-tastic Chocolate Chip",
				"Guardian Lucky Block", "McGrubber's Burger", "Unfanged Vampire Part", "Hemovibe",
				"Vampiric Melon", "Sangria Dye"));
	}

	private ScdSlayerDropAllowlist() {
	}

	/**
	 * itemId is checked first (exact match against real internal ids, or against the specific
	 * enchantment+level ids this type can drop as a book) since it's authoritative when present. Falls
	 * back to a display-name substring check for the sack-pickup path, which only ever has a name
	 * parsed out of a chat hover tooltip, never a resolved id.
	 */
	public static boolean isKnownDrop(ScdSlayerType type, String itemId, String displayName) {
		if (itemId != null) {
			if (ITEM_IDS.getOrDefault(type, List.of()).contains(itemId)) return true;
			if (ENCHANTMENT_IDS.getOrDefault(type, List.of()).contains(itemId)) return true;
		}

		String needle = displayName.trim().toLowerCase(Locale.ROOT);
		for (String known : DISPLAY_NAMES.getOrDefault(type, List.of())) {
			if (needle.contains(known.toLowerCase(Locale.ROOT))) return true;
		}
		return false;
	}
}
