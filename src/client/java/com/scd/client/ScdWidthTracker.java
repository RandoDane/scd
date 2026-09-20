package com.scd.client;

import net.minecraft.client.gui.Font;

/**
 * Records the widest line seen during a HUD's measure pass, so the panel can size itself to fit
 * whatever's actually being drawn instead of a fixed guess. Some Slayer ability call-outs (Enderman's
 * "Hitshield up (N hits left) - 0 dmg, don't waste hits" in particular) are long enough to run past a
 * fixed-width panel regardless of text-scale setting - see ScdSlayerHud's use of this for the fix.
 */
public class ScdWidthTracker {
	private int maxWidth = 0;

	/** Call once per line during the measure pass with the exact scale it will actually render at. */
	public void track(Font font, String text, float scale) {
		if (text == null || text.isEmpty()) return;
		maxWidth = Math.max(maxWidth, Math.round(font.width(text) * scale));
	}

	public int maxWidth() {
		return maxWidth;
	}
}
