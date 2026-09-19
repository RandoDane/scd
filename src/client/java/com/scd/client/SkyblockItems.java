package com.scd.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;
import java.util.Optional;

/**
 * Every item on Hypixel SkyBlock carries a custom NBT tag with the internal
 * product id used by the Bazaar/Auction APIs. On this game version it's a
 * flat {id:"..."} tag on minecraft:custom_data, rather than the classic
 * pre-component "ExtraAttributes.id" nesting older SkyBlock mod guides
 * describe. Falls back to that nested form too, in case some item categories
 * still use it.
 */
public final class SkyblockItems {
	private SkyblockItems() {
	}

	public static String getId(ItemStack stack, ScdAttributeShards attributeShards) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;

		CompoundTag tag = data.copyTag();
		String id = tag.getString("id")
				.or(() -> tag.getCompound("ExtraAttributes").flatMap(extra -> extra.getString("id")))
				.orElse(null);
		if ("ENCHANTED_BOOK".equals(id)) {
			return enchantedBookBazaarId(tag).orElse(id);
		}
		if ("ATTRIBUTE_SHARD".equals(id)) {
			return attributeShardBazaarId(tag, attributeShards).orElse(id);
		}
		return id;
	}

	/**
	 * Every enchanted book shares id=ENCHANTED_BOOK regardless of which
	 * enchantment it holds - the actual enchant lives in a separate top-level
	 * compound: enchantments:{<lowercase name>:<level>}. Hypixel's Bazaar
	 * product id for these is ENCHANTMENT_<NAME>_<LEVEL>. Falls back to empty
	 * (leaving the plain ENCHANTED_BOOK id, which won't match a price) if the
	 * book doesn't have exactly one enchantment - safer than guessing which
	 * one to price.
	 */
	private static Optional<String> enchantedBookBazaarId(CompoundTag tag) {
		return tag.getCompound("enchantments").flatMap(enchantments -> {
			var keys = enchantments.keySet();
			if (keys.size() != 1) return Optional.<String>empty();
			String name = keys.iterator().next();
			return enchantments.getInt(name).map(level -> "ENCHANTMENT_" + name.toUpperCase(Locale.ROOT) + "_" + level);
		});
	}

	/**
	 * Every attribute shard shares id=ATTRIBUTE_SHARD regardless of which
	 * attribute it grants - the NBT only carries the raw attribute key (e.g.
	 * {attributes:{undead_resistance:1}}), with no mention of the shard's own
	 * display name. The attribute name and the shard's own name are
	 * frequently unrelated words (Hypixel's own naming), so unlike enchanted
	 * books this can't be resolved from the item alone - it needs the
	 * server's wiki-sourced attribute-to-shard table.
	 */
	private static Optional<String> attributeShardBazaarId(CompoundTag tag, ScdAttributeShards attributeShards) {
		return tag.getCompound("attributes").flatMap(attributes -> {
			var keys = attributes.keySet();
			if (keys.size() != 1) return Optional.<String>empty();
			String attributeKey = keys.iterator().next();
			return Optional.ofNullable(attributeShards.shardIdFor(attributeKey));
		});
	}

	/** Full custom_data NBT as text, for diagnosing item categories (enchanted books, pets, ...) whose price/identity needs more than the flat "id" field. */
	public static String getRawCustomDataOrNull(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null ? data.copyTag().toString() : null;
	}
}
