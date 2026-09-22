package com.scd.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passively scans the vanilla Accessory Bag menu page by page as the player browses it normally -
 * no auto-clicking/paging on our behalf, just reading whatever page is already on screen, the same
 * passive-GUI-reading technique already proven for the Slayer RNG Meter menu
 * (ScdSlayerMenuWatcher).
 *
 * Built this way specifically to replace the backend's peakMagicalPower field for a "how much
 * Accessory Power do I actually have right now" number, after that field turned out to be
 * Hypixel's own lifetime PEAK rather than the current total (confirmed live 2026-09-21 - see
 * ScdApiClient.fetchAccessories's doc comment). This scanner reads the exact number the game itself
 * shows per item, so once a full scan completes it should match the in-game total exactly - no
 * estimating.
 *
 * Confirmed live 2026-09-21 from a real "/scd slayer debug menu dump" while the bag was open: the
 * screen's own title is literally "Accessory Bag (X/Y)" (current/total page), and every real
 * accessory - regardless of which slot it's in, including ones incidentally sitting in the player's
 * own inventory below the custom grid rather than filed into a bag page - has an
 * "Accessory Power: +N" lore line that nothing else in the menu has (not the page-nav buttons, not
 * the border filler glass panes, not unrelated inventory items). Filtering on that line's presence,
 * rather than a hardcoded slot range, is what makes this robust to page-layout details this project
 * hasn't verified (exact slot ranges on page 2/3, whether a "Previous Page" button exists, etc.) -
 * it simply doesn't need to know any of that.
 */
public class ScdAccessoryBagWatcher {
	private static final Pattern TITLE_PATTERN = Pattern.compile("^Accessory Bag \\((\\d+)/(\\d+)\\)$");
	private static final Pattern POWER_PATTERN = Pattern.compile("Accessory Power:\\s*\\+([0-9,]+)");
	private static final Pattern RARITY_PATTERN = Pattern.compile(
			"ADMIN|DIVINE|VERY SPECIAL|SPECIAL|MYTHIC|LEGENDARY|EPIC|RARE|UNCOMMON|COMMON");

	public record ScannedAccessory(String name, String rarity, int accessoryPower) {
	}

	// Keyed by display name rather than a stable id (not available from lore alone) - two slots
	// showing the exact same name/lore are the same accessory held twice, which should only count
	// once, the same way Hypixel's own Accessory Power only counts one of each. Different upgrade
	// tiers of the same "family" (Speed Talisman vs Speed Ring) have different names, so this does
	// NOT collapse those - that needs a maintained accessory-family list this project doesn't have
	// yet (see FEATURE_ROADMAP.md §13), same gap as the missing-accessories checklist itself.
	private final Map<String, ScannedAccessory> scanned = new LinkedHashMap<>();
	private final Set<Integer> pagesSeen = new HashSet<>();
	private int totalPages = 0;

	public static boolean isAccessoryBagScreen(Screen screen) {
		return TITLE_PATTERN.matcher(screen.getTitle().getString()).find();
	}

	/** Call every tick the Accessory Bag screen is open - re-scanning an already-seen page is harmless, just redundant. */
	public void onScreenOpened(Screen screen) {
		Matcher titleMatch = TITLE_PATTERN.matcher(screen.getTitle().getString());
		if (!titleMatch.find() || !(screen instanceof AbstractContainerScreen<?> containerScreen)) return;

		int page = Integer.parseInt(titleMatch.group(1));
		int total = Integer.parseInt(titleMatch.group(2));
		totalPages = Math.max(totalPages, total);
		pagesSeen.add(page);

		for (var slot : containerScreen.getMenu().slots) {
			if (!slot.hasItem()) continue;
			var stack = slot.getItem();
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) continue;

			Integer power = null;
			String rarity = null;
			for (var line : lore.lines()) {
				String text = line.getString();
				Matcher powerMatch = POWER_PATTERN.matcher(text);
				if (powerMatch.find()) power = Integer.parseInt(powerMatch.group(1).replace(",", ""));
				Matcher rarityMatch = RARITY_PATTERN.matcher(text);
				if (rarityMatch.find()) rarity = rarityMatch.group();
			}
			// The one filter that matters: no nav button, filler pane, or unrelated inventory item
			// has an "Accessory Power" lore line, so this alone separates real accessories from
			// everything else on screen without needing to know which slots are which.
			if (power == null) continue;

			String name = stack.getHoverName().getString();
			scanned.put(name, new ScannedAccessory(name, rarity, power));
		}
	}

	/** Resets to a blank scan - ScdClient calls this once per fresh visit to the menu (transitioning in from a non-bag screen, tracked in ScdClient itself since it can't be inferred from this class's own state), so a stale prior scan can't silently mix with accessories removed/changed since, and so paging between pages 1/2/3 of an already-open bag never wipes progress. */
	public void reset() {
		scanned.clear();
		pagesSeen.clear();
		totalPages = 0;
	}

	public boolean isComplete() {
		return totalPages > 0 && pagesSeen.size() >= totalPages;
	}

	public int pagesScanned() {
		return pagesSeen.size();
	}

	public int totalPages() {
		return totalPages;
	}

	public int accessoryCount() {
		return scanned.size();
	}

	/** Sum of every distinct accessory's own Accessory Power - see the class doc for why this should match Hypixel's own total once isComplete(). */
	public int totalAccessoryPower() {
		return scanned.values().stream().mapToInt(ScannedAccessory::accessoryPower).sum();
	}

	public Collection<ScannedAccessory> accessories() {
		return scanned.values();
	}
}
