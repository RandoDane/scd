package com.scd.client;

/**
 * Tracks whatever SkyBlock item the tooltip callback most recently saw. That
 * callback only fires while a tooltip is actually being shown, so "was it
 * called in roughly the last frame" is a reliable proxy for "the player is
 * currently hovering this item" - used to drive the graph HUD's visibility.
 */
public class ScdHoverState {
	private static final long FRESH_WINDOW_MS = 250;

	private volatile String itemId;
	private volatile long lastSeenMs;
	// Unlike itemId above, this deliberately never expires - /scd item nbt reads it well
	// after the tooltip closes (you can't type a command while a tooltip/inventory is open).
	private volatile String lastRawNbt;

	public void recordHover(String itemId, String rawNbt) {
		this.itemId = itemId;
		this.lastSeenMs = System.currentTimeMillis();
		this.lastRawNbt = rawNbt;
	}

	public String currentOrNull() {
		return (itemId != null && System.currentTimeMillis() - lastSeenMs <= FRESH_WINDOW_MS) ? itemId : null;
	}

	public String lastRawNbtOrNull() {
		return lastRawNbt;
	}
}
