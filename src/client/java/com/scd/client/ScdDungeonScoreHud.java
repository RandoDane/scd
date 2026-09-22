package com.scd.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small movable HUD box showing ScdDungeonScore's live breakdown - built specifically so it can
 * be watched side-by-side against Odin's own dungeon score display for a real live accuracy
 * check (see FEATURE_ROADMAP.md §3 - the tab-list-derived fields are still unconfirmed). Only
 * renders while ScdDungeonScore.computeOrNull() actually returns something (in a dungeon, on a
 * recognized floor).
 */
public class ScdDungeonScoreHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("scd", "dungeon_score_overlay");
	private static final int BG_COLOR = 0x90000000;
	private static final int TITLE_COLOR = 0xFF55DDFF;
	private static final int PADDING = 4;
	private static final int MIN_WIDTH = 170;

	private final ScdConfig config;
	private final ScdMayorPerks mayorPerks;

	public ScdDungeonScoreHud(ScdConfig config, ScdMayorPerks mayorPerks) {
		this.config = config;
		this.mayorPerks = mayorPerks;
	}

	public void register() {
		HudElementRegistry.addLast(ID, (graphics, deltaTracker) -> ScdLog.guard("dungeon score HUD", () -> {
			if (config.dungeon.scoreHudEnabled) renderContent(graphics, Minecraft.getInstance().font, false);
		}));
	}

	public ScdOverlayBox.Bounds renderPreview(GuiGraphicsExtractor graphics, Font font) {
		return renderContent(graphics, font, true);
	}

	/**
	 * Every line past the title is individually toggleable (config.dungeon.scoreHud*) - built the
	 * line list dynamically so the box only ever takes up as much room as what's actually enabled.
	 * The title/total line itself isn't toggleable (matches every other HUD in this project - the
	 * headline number is the one thing that's always shown when the box is on at all).
	 */
	private ScdOverlayBox.Bounds renderContent(GuiGraphicsExtractor graphics, Font font, boolean preview) {
		ScdDungeonScore.ScoreBreakdown breakdown = preview ? null : ScdDungeonScore.computeOrNull(mayorPerks);
		if (breakdown == null && !preview) return null;

		List<String> lines = new ArrayList<>();
		if (breakdown != null) {
			lines.add("Score: " + breakdown.total() + (breakdown.isEntrance() ? " (entrance x0.7)" : ""));
			if (config.dungeon.scoreHudShowBreakdown) {
				lines.add("Skill " + breakdown.skill() + "  Explore " + breakdown.explore()
						+ "  Speed " + breakdown.speed() + "  Bonus " + breakdown.bonus());
			}
			if (config.dungeon.scoreHudShowRoomsSecrets) {
				lines.add("Rooms " + breakdown.completedRooms() + "/" + breakdown.totalRoomsEstimate()
						+ "  Secrets " + String.format(Locale.ROOT, "%.1f", breakdown.secretsPercent()) + "%");
			}
			if (config.dungeon.scoreHudShowCryptsDeathsPuzzles) {
				lines.add("Crypts " + breakdown.crypts() + "  Deaths " + breakdown.deaths()
						+ "  Puzzles incomplete " + breakdown.incompletePuzzles());
			}
		} else {
			lines.add("Score: 249");
			if (config.dungeon.scoreHudShowBreakdown) lines.add("Skill 78  Explore 93  Speed 100  Bonus 8");
			if (config.dungeon.scoreHudShowRoomsSecrets) lines.add("Rooms 236/236  Secrets 100.0%");
			if (config.dungeon.scoreHudShowCryptsDeathsPuzzles) lines.add("Crypts 4  Deaths 0  Puzzles incomplete 0");
		}

		int textWidth = 0;
		for (String line : lines) textWidth = Math.max(textWidth, font.width(line));
		int width = Math.max(MIN_WIDTH, textWidth + PADDING * 2);

		int x = config.dungeon.scoreHudPosition.x + PADDING;
		int y = config.dungeon.scoreHudPosition.y + PADDING;
		int lineHeight = font.lineHeight + 2;

		int boxHeight = PADDING * 2 + lineHeight * lines.size();
		graphics.fill(x - PADDING, y - PADDING, x - PADDING + width, y - PADDING + boxHeight, BG_COLOR);

		for (int i = 0; i < lines.size(); i++) {
			int color = i == 0 ? TITLE_COLOR : ScdTheme.TEXT_SECONDARY;
			graphics.text(font, Component.literal(lines.get(i)), x, y, color, true);
			y += lineHeight;
		}

		return new ScdOverlayBox.Bounds(x - PADDING, config.dungeon.scoreHudPosition.y, width, boxHeight);
	}
}
