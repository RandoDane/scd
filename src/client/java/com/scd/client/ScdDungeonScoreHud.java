package com.scd.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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

	private ScdOverlayBox.Bounds renderContent(GuiGraphicsExtractor graphics, Font font, boolean preview) {
		ScdDungeonScore.ScoreBreakdown breakdown = preview ? null : ScdDungeonScore.computeOrNull(mayorPerks);

		String title;
		String line2;
		String line3;
		String line4;
		if (breakdown != null) {
			title = "Score: " + breakdown.total() + (breakdown.isEntrance() ? " (entrance x0.7)" : "");
			line2 = "Skill " + breakdown.skill() + "  Explore " + breakdown.explore()
					+ "  Speed " + breakdown.speed() + "  Bonus " + breakdown.bonus();
			line3 = "Rooms " + breakdown.completedRooms() + "/" + breakdown.totalRoomsEstimate()
					+ "  Secrets " + String.format(Locale.ROOT, "%.1f", breakdown.secretsPercent()) + "%";
			line4 = "Crypts " + breakdown.crypts() + "  Deaths " + breakdown.deaths()
					+ "  Puzzles failed " + breakdown.incompletePuzzles();
		} else if (preview) {
			title = "Score: 249";
			line2 = "Skill 78  Explore 93  Speed 100  Bonus 8";
			line3 = "Rooms 236/236  Secrets 100.0%";
			line4 = "Crypts 4  Deaths 0  Puzzles failed 0";
		} else {
			return null;
		}

		int textWidth = Math.max(font.width(title), Math.max(font.width(line2), Math.max(font.width(line3), font.width(line4))));
		int width = Math.max(MIN_WIDTH, textWidth + PADDING * 2);

		int x = config.dungeon.scoreHudPosition.x + PADDING;
		int y = config.dungeon.scoreHudPosition.y + PADDING;
		int lineHeight = font.lineHeight + 2;

		int boxHeight = PADDING * 2 + lineHeight * 4;
		graphics.fill(x - PADDING, y - PADDING, x - PADDING + width, y - PADDING + boxHeight, BG_COLOR);

		graphics.text(font, Component.literal(title), x, y, TITLE_COLOR, true);
		y += lineHeight;
		graphics.text(font, Component.literal(line2), x, y, ScdTheme.TEXT_SECONDARY, true);
		y += lineHeight;
		graphics.text(font, Component.literal(line3), x, y, ScdTheme.TEXT_SECONDARY, true);
		y += lineHeight;
		graphics.text(font, Component.literal(line4), x, y, ScdTheme.TEXT_SECONDARY, true);

		return new ScdOverlayBox.Bounds(x - PADDING, config.dungeon.scoreHudPosition.y, width, boxHeight);
	}
}
