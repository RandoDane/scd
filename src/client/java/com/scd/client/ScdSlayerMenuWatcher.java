package com.scd.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passively scans whatever container screen just opened for RNG Meter data,
 * straight out of the real Slayer menu:
 *
 * 1. The general "Slayer" menu has an item named e.g. "Voidgloom Seraph RNG
 *    Meter" whose lore has a "Selected Drop" line followed by the drop's own
 *    name on the next line, and a "current/total" fraction (e.g.
 *    "2,200/885.6k"). AbstractContainerMenu.slots gives real ItemStacks the
 *    moment this screen is open, so every slot can be read directly without
 *    needing to actually hover each one.
 * 2. Clicking that item opens a screen titled the same "<Boss> RNG Meter",
 *    containing one item per possible drop. Each drop's own lore has the
 *    same "current/total" fraction (the total here is THAT drop's own
 *    required meter, which varies per drop - a rarer selected item needs a
 *    much bigger total) plus the line "Filling the meter increases the drop
 *    chance of this item." as a reliable anchor that this is a drop-detail
 *    item rather than something else in the same screen.
 */
public final class ScdSlayerMenuWatcher {
	private static final Pattern FORMATTING_CODE = Pattern.compile("§.");
	private static final Pattern RNG_METER_NAME = Pattern.compile("^(.+?) RNG Meter$");
	private static final Pattern PROGRESS_PERCENT = Pattern.compile("Progress:\\s*([\\d.]+)\\s*%", Pattern.CASE_INSENSITIVE);
	private static final Pattern PROGRESS_FRACTION = Pattern.compile(
			"([\\d,.]+)\\s*([kKmMbB]?)\\s*/\\s*([\\d,.]+)\\s*([kKmMbB]?)");
	private static final String FILLING_METER_ANCHOR = "filling the meter increases the drop chance";

	private final ScdSlayerRngMeter rngMeter;

	public ScdSlayerMenuWatcher(ScdSlayerRngMeter rngMeter) {
		this.rngMeter = rngMeter;
	}

	public void onScreenOpened(Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;

		String screenTitle = clean(screen.getTitle().getString());
		ScdSlayerType screenType = typeFromRngMeterName(screenTitle);

		for (var slot : containerScreen.getMenu().slots) {
			if (!slot.hasItem()) continue;
			ItemStack stack = slot.getItem();
			String itemName = clean(stack.getHoverName().getString());

			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) continue;
			List<String> lines = lore.lines().stream().map(line -> clean(line.getString())).toList();

			ScdSlayerType itemType = typeFromRngMeterName(itemName);
			if (itemType != null) {
				handleMainIcon(itemType, lines);
			} else if (screenType != null && containsFillingMeterAnchor(lines)) {
				handleDropDetail(screenType, itemName, lines);
			}
		}
	}

	/**
	 * The general "Slayer" menu's own "<Boss> RNG Meter" item: selected drop name + current progress.
	 * This icon's own tooltip shows the exact same "current/total" fraction as the selected drop's own
	 * detail page (see handleDropDetail) - recorded here too, keyed to the selected drop we just found
	 * on the same lore, so just hovering this one icon is enough on its own; opening the full drop
	 * grid screen isn't required just to learn the selected drop's required total.
	 */
	private void handleMainIcon(ScdSlayerType type, List<String> lines) {
		String selectedDropName = null;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).equalsIgnoreCase("Selected Drop") && i + 1 < lines.size()) {
				String dropName = lines.get(i + 1).trim();
				if (!dropName.isEmpty()) {
					selectedDropName = dropName;
					rngMeter.recordSelectedDrop(type, dropName);
				}
			}
		}
		recordProgress(type, lines);

		if (selectedDropName != null) {
			for (String line : lines) {
				Matcher fraction = PROGRESS_FRACTION.matcher(line);
				if (fraction.find()) {
					double total = toNumber(fraction.group(3), fraction.group(4));
					rngMeter.recordRequiredTotal(type, selectedDropName, Math.round(total));
					break;
				}
			}
		}
	}

	/** One specific drop's own page inside the "<Boss> RNG Meter" screen: that drop's required total. */
	private void handleDropDetail(ScdSlayerType type, String dropName, List<String> lines) {
		for (String line : lines) {
			Matcher fraction = PROGRESS_FRACTION.matcher(line);
			if (fraction.find()) {
				double total = toNumber(fraction.group(3), fraction.group(4));
				rngMeter.recordRequiredTotal(type, dropName, Math.round(total));
				break;
			}
		}
		recordProgress(type, lines);
		if (lines.stream().anyMatch(line -> line.equalsIgnoreCase("SELECTED"))) {
			rngMeter.recordSelectedDrop(type, dropName);
		}
	}

	private void recordProgress(ScdSlayerType type, List<String> lines) {
		for (String line : lines) {
			Matcher fraction = PROGRESS_FRACTION.matcher(line);
			if (fraction.find()) {
				double current = toNumber(fraction.group(1), fraction.group(2));
				rngMeter.recordStoredXp(type, Math.round(current));
			}
			Matcher percent = PROGRESS_PERCENT.matcher(line);
			if (percent.find()) {
				try {
					rngMeter.recordChancePercent(type, Double.parseDouble(percent.group(1)));
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	private boolean containsFillingMeterAnchor(List<String> lines) {
		for (String line : lines) {
			if (line.toLowerCase(Locale.ROOT).contains(FILLING_METER_ANCHOR)) return true;
		}
		return false;
	}

	private ScdSlayerType typeFromRngMeterName(String text) {
		Matcher m = RNG_METER_NAME.matcher(text);
		if (!m.matches()) return null;
		return ScdSlayerType.fromBossName(m.group(1));
	}

	private String clean(String text) {
		return FORMATTING_CODE.matcher(text).replaceAll("").trim();
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
