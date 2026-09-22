package com.scd.client;

import com.google.common.collect.HashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.UUID;

/** Best-effort ItemStack icon for a Bazaar product - see ScdApiClient.Icon for why this is best-effort. */
public final class ScdIcons {
	private ScdIcons() {
	}

	public static ItemStack resolve(ScdApiClient.Icon icon) {
		if (icon == null) return ItemStack.EMPTY;

		if (icon.skinValue() != null) {
			// Confirmed live 2026-09-22 (reproduced standalone against the real authlib jar, not
			// guessed): PropertyMap.put() itself always throws UnsupportedOperationException in this
			// authlib version, regardless of what backing Multimap it was constructed with - it must be
			// pre-populated before being wrapped, not written to afterward. This sat unnoticed until the
			// missing-accessories grid started using it, since Bazaar icons (the only prior caller)
			// rarely hit the skin-value branch at all.
			HashMultimap<String, Property> backing = HashMultimap.create();
			backing.put("textures", new Property("textures", icon.skinValue(), icon.skinSignature()));
			PropertyMap properties = new PropertyMap(backing);
			GameProfile profile = new GameProfile(UUID.randomUUID(), "scd_icon", properties);
			ItemStack head = new ItemStack(Items.PLAYER_HEAD);
			head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
			return head;
		}

		Item item = LegacyMaterials.resolve(icon.material(), icon.durability());
		return item != null ? new ItemStack(item) : ItemStack.EMPTY;
	}
}
