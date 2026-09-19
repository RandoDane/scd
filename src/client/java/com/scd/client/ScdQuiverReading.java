package com.scd.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the "Arrows Remaining: N" count off whichever item currently occupies
 * the Skyblock-menu hotbar slot - that slot's icon swaps to show the equipped
 * arrow type (e.g. "Explosive Arrow") with a live remaining count in its Lore
 * whenever a bow is held.
 */
public final class ScdQuiverReading {
	private static final Pattern FORMATTING_CODE = Pattern.compile("§.");
	private static final Pattern ARROWS_REMAINING = Pattern.compile("Arrows Remaining:\\s*([\\d,]+)");

	public record Reading(String arrowTypeName, int remaining) {
	}

	private ScdQuiverReading() {
	}

	public static Reading parse(ItemStack stack) {
		if (stack.isEmpty()) return null;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return null;

		for (var line : lore.lines()) {
			String clean = FORMATTING_CODE.matcher(line.getString()).replaceAll("");
			Matcher matcher = ARROWS_REMAINING.matcher(clean);
			if (matcher.find()) {
				int remaining = Integer.parseInt(matcher.group(1).replace(",", ""));
				return new Reading(stack.getHoverName().getString(), remaining);
			}
		}
		return null;
	}
}
