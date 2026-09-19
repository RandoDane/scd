package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Detects newly-received items by diffing the player's own inventory
 * contents tick to tick - the same mechanism other Skyblock item trackers
 * (e.g. SkyHanni's ItemPickupLog/ItemAddManager) use, rather than
 * entity/packet-level pickup detection. Hypixel's Telekinesis feature
 * (mandatory, made permanent years ago to curb item-on-floor lag) sends
 * basically everything straight to inventory with no ground item entity ever
 * spawning, so there's nothing at the entity/packet level left to hook for
 * most drops anyway. Reading your own inventory needs no open screen the way
 * reading a chest would.
 */
public class ScdInventoryWatcher {
	// The Skyblock menu slot - always a compass, except it swaps to show quiver/arrow-type info while
	// a bow is held (see ScdQuiverTracker). Never a real item you picked up, and its identity/count
	// can change from one tick to the next for reasons unrelated to loot (menu icon swaps, arrow count
	// ticking down as you shoot) - excluded so those changes don't get misread as gained drops.
	private static final int MENU_SLOT_INDEX = 8;

	public interface Listener {
		void onItemGained(String itemId, String displayName, int amount);
	}

	private Map<String, Integer> lastCounts = new HashMap<>();
	private Listener listener;

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	public void tick(ScdAttributeShards attributeShards) {
		var mc = Minecraft.getInstance();
		if (mc.player == null) {
			lastCounts = new HashMap<>();
			return;
		}

		Map<String, Integer> counts = new HashMap<>();
		Map<String, String> displayNames = new HashMap<>();
		var inventory = mc.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (i == MENU_SLOT_INDEX) continue;
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			String id = SkyblockItems.getId(stack, attributeShards);
			if (id == null) id = stack.getHoverName().getString();
			counts.merge(id, stack.getCount(), Integer::sum);
			displayNames.putIfAbsent(id, stack.getHoverName().getString());
		}

		// Skip the very first read (lastCounts starts empty) so joining a world with a full
		// inventory doesn't get reported as "gaining" everything already in it.
		if (!lastCounts.isEmpty() && listener != null) {
			for (var entry : counts.entrySet()) {
				int before = lastCounts.getOrDefault(entry.getKey(), 0);
				int gained = entry.getValue() - before;
				if (gained > 0) {
					listener.onItemGained(entry.getKey(), displayNames.get(entry.getKey()), gained);
				}
			}
		}
		lastCounts = counts;
	}
}
