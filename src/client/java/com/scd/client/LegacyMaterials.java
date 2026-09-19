package com.scd.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * Hypixel's items resource still reports pre-flattening (Minecraft 1.8-era)
 * material names, sometimes with a numeric "durability" used as a sub-type
 * (e.g. INK_SACK:4 = lapis lazuli). This maps the common ones we actually see
 * on Bazaar-sellable items to a modern net.minecraft.world.item.Item.
 * Anything not covered here has no vanilla icon - better to show nothing
 * than a wrong one.
 */
public final class LegacyMaterials {
	private LegacyMaterials() {
	}

	private static final Map<String, String> DIRECT = new HashMap<>();
	private static final Map<String, String> WITH_DURABILITY = new HashMap<>();

	static {
		// Legacy name == modern id, just needs lowercasing.
		for (String name : new String[] {
				"DIAMOND", "EMERALD", "COAL", "IRON_INGOT", "GOLD_INGOT", "REDSTONE", "GLOWSTONE_DUST",
				"STRING", "FEATHER", "GUNPOWDER", "LEATHER", "PAPER", "BOOK", "BREAD", "APPLE", "CARROT",
				"POTATO", "MELON", "PUMPKIN", "CACTUS", "SUGAR_CANE", "NETHER_WART", "BLAZE_ROD", "BLAZE_POWDER",
				"GHAST_TEAR", "SLIME_BALL", "MAGMA_CREAM", "SPIDER_EYE", "FERMENTED_SPIDER_EYE", "BONE",
				"ROTTEN_FLESH", "ENDER_PEARL", "ENDER_EYE", "NETHER_STAR", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS",
				"CLAY_BALL", "CLAY", "SNOWBALL", "ICE", "OBSIDIAN", "GLASS", "SAND", "GRAVEL", "COBBLESTONE",
				"NETHERRACK", "SOUL_SAND", "MYCELIUM", "CHORUS_FRUIT", "POPPED_CHORUS_FRUIT", "DRAGON_BREATH",
				"SHULKER_SHELL", "TOTEM_OF_UNDYING", "PHANTOM_MEMBRANE", "HONEYCOMB", "HONEY_BOTTLE",
				"QUARTZ", "AMETHYST_SHARD", "COPPER_INGOT", "NETHERITE_SCRAP", "NETHERITE_INGOT",
		}) {
			DIRECT.put(name, name.toLowerCase());
		}

		// Legacy names with no numeric sub-type, but a different modern id.
		DIRECT.put("MUTTON", "mutton");
		DIRECT.put("SULPHUR", "gunpowder");
		DIRECT.put("SEEDS", "wheat_seeds");
		DIRECT.put("MELON_SEEDS", "melon_seeds");
		DIRECT.put("PUMPKIN_SEEDS", "pumpkin_seeds");
		DIRECT.put("SPECKLED_MELON", "glistering_melon_slice");
		DIRECT.put("MAGMA_CUBE_ITEM", "magma_cream");
		DIRECT.put("RAW_CHICKEN", "chicken");
		DIRECT.put("RAW_BEEF", "beef");
		DIRECT.put("PORK", "porkchop");
		DIRECT.put("HUGE_MUSHROOM_1", "red_mushroom_block");
		DIRECT.put("HUGE_MUSHROOM_2", "brown_mushroom_block");

		// Legacy numeric sub-types - key is "MATERIAL:durability".
		WITH_DURABILITY.put("INK_SACK:0", "ink_sac");
		WITH_DURABILITY.put("INK_SACK:3", "cocoa_beans");
		WITH_DURABILITY.put("INK_SACK:4", "lapis_lazuli");
		WITH_DURABILITY.put("RAW_FISH:0", "cod");
		WITH_DURABILITY.put("RAW_FISH:1", "salmon");
		WITH_DURABILITY.put("RAW_FISH:2", "tropical_fish");
		WITH_DURABILITY.put("RAW_FISH:3", "pufferfish");
	}

	public static Item resolve(String material, Integer durability) {
		if (material == null) return null;

		String key = durability != null ? material + ":" + durability : null;
		String modernId = key != null ? WITH_DURABILITY.get(key) : null;
		if (modernId == null) modernId = DIRECT.get(material);
		if (modernId == null) return null;

		return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft", modernId));
	}
}
