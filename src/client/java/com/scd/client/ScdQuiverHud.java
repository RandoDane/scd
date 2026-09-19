package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import java.util.Locale;

/**
 * A small movable HUD box showing the live "Arrows Remaining" count for
 * whatever arrow type is equipped, read via ScdQuiverTracker off the
 * Skyblock-menu hotbar slot. Only visible while a bow is actually held, since
 * that's the only time that slot carries quiver info at all.
 */
public class ScdQuiverHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("scd", "quiver_overlay");
	private static final int BG_COLOR = 0x90000000;
	private static final int TITLE_COLOR = 0xFF55DDFF;
	private static final int LABEL_COLOR = 0xFFAAAAAA;
	private static final int LOW_AMMO_COLOR = 0xFFFF5555;
	private static final int LOW_AMMO_THRESHOLD = 100;
	private static final int PADDING = 4;
	private static final int MIN_WIDTH = 120;

	private final ScdConfig config;
	private final ScdQuiverTracker tracker;

	public ScdQuiverHud(ScdConfig config, ScdQuiverTracker tracker) {
		this.config = config;
		this.tracker = tracker;
	}

	public void register() {
		// See ScdSlayerHud.register() for why the tracker is ticked here rather than relying
		// solely on ClientTickEvents.END_CLIENT_TICK.
		HudElementRegistry.addLast(ID, (graphics, deltaTracker) -> ScdLog.guard("quiver HUD", () -> {
			tracker.tick();
			if (config.slayer.enderman.explosiveArrowCounterEnabled) renderContent(graphics, Minecraft.getInstance().font, false);
		}));
	}

	public ScdOverlayBox.Bounds renderPreview(GuiGraphicsExtractor graphics, Font font) {
		return renderContent(graphics, font, true);
	}

	private ScdOverlayBox.Bounds renderContent(GuiGraphicsExtractor graphics, Font font, boolean preview) {
		ScdQuiverReading.Reading reading = preview ? null : tracker.currentOrNull();

		String title;
		String line2;
		if (reading != null) {
			title = reading.arrowTypeName();
			line2 = "Remaining: " + String.format(Locale.ROOT, "%,d", reading.remaining());
		} else if (preview) {
			title = "Explosive Arrow";
			line2 = "Remaining: 2,541";
		} else {
			return null;
		}

		int textWidth = Math.max(font.width(title), font.width(line2));
		int width = Math.max(MIN_WIDTH, textWidth + PADDING * 2);

		int x = config.slayer.enderman.explosiveArrowCounterPosition.x + PADDING;
		int y = config.slayer.enderman.explosiveArrowCounterPosition.y + PADDING;
		int lineHeight = font.lineHeight + 2;

		int boxHeight = PADDING * 2 + lineHeight * 2;
		graphics.fill(x - PADDING, y - PADDING, x - PADDING + width, y - PADDING + boxHeight, BG_COLOR);

		graphics.text(font, Component.literal(title), x, y, TITLE_COLOR, true);
		y += lineHeight;

		boolean lowAmmo = !preview && reading != null && reading.remaining() <= LOW_AMMO_THRESHOLD;
		graphics.text(font, Component.literal(line2), x, y, lowAmmo ? LOW_AMMO_COLOR : LABEL_COLOR, true);

		return new ScdOverlayBox.Bounds(x - PADDING, config.slayer.enderman.explosiveArrowCounterPosition.y, width, boxHeight);
	}
}
