package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A movable HUD box summarizing this session's Slayer kills: count, average
 * kill time, an extrapolated kills/hour rate, and total Slayer XP gained.
 * Purely derived from fights ScdSlayerBossTracker has actually observed -
 * not persisted, resets on game restart, and never shown until at least one
 * kill has happened (there's nothing meaningful to summarize before that).
 */
public class ScdSlayerStatsHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("scd", "slayer_stats_overlay");
	private static final int BG_COLOR = 0x90000000;
	private static final int TITLE_COLOR = 0xFF55DDFF;
	private static final int LABEL_COLOR = 0xFFAAAAAA;
	private static final int PADDING = 4;
	private static final int MIN_WIDTH = 140;

	private final ScdConfig config;
	private final ScdSlayerSessionStats stats;
	private final ScdSlayerBossTracker tracker;
	private final ScdMayorPerks mayorPerks;

	public ScdSlayerStatsHud(ScdConfig config, ScdSlayerSessionStats stats, ScdSlayerBossTracker tracker, ScdMayorPerks mayorPerks) {
		this.config = config;
		this.stats = stats;
		this.tracker = tracker;
		this.mayorPerks = mayorPerks;
	}

	public void register() {
		HudElementRegistry.addLast(ID, (graphics, deltaTracker) -> ScdLog.guard("slayer stats HUD", () -> {
			var quest = tracker.currentQuestOrNull();
			if (quest != null) stats.setCurrentTier(quest.tier());
			if (config.slayer.statsHudEnabled) renderContent(graphics, Minecraft.getInstance().font, false);
		}));
	}

	public ScdOverlayBox.Bounds renderPreview(GuiGraphicsExtractor graphics, Font font) {
		return renderContent(graphics, font, true);
	}

	private ScdOverlayBox.Bounds renderContent(GuiGraphicsExtractor graphics, Font font, boolean preview) {
		int kills;
		long avgMs;
		double perHour;
		long avgHuntMs;
		long xpGained;
		double xpBoostPercent;
		String xpBoostMayor;
		if (preview) {
			kills = 4;
			avgMs = 47_000;
			perHour = 12.3;
			avgHuntMs = 95_000;
			xpGained = 6_250;
			xpBoostPercent = 25;
			xpBoostMayor = "Aatrox";
		} else if (stats.killCount() > 0) {
			kills = stats.killCount();
			avgMs = stats.averageKillMs();
			perHour = stats.killsPerHour();
			avgHuntMs = stats.averageHuntMs();
			xpGained = stats.totalXpGained();
			xpBoostPercent = mayorPerks.boostPercent();
			xpBoostMayor = mayorPerks.sourceMayorNameOrNull();
		} else {
			return null;
		}

		String tier = preview ? "IV" : stats.currentTierOrNull();
		String title = "Slayer Session" + (tier != null ? " (Tier " + tier + ")" : "");

		List<String> body = new ArrayList<>();
		body.add("Kills: " + kills);
		body.add("Avg: " + ScdSlayerHud.formatElapsed(avgMs) + "  " + String.format(Locale.ROOT, "%.1f", perHour) + "/hr");
		if (avgHuntMs > 0) body.add("Avg time to spawn boss: " + ScdSlayerHud.formatElapsed(avgHuntMs));
		if (xpGained > 0) {
			String xpLine = "XP: " + ScdFormat.compactCount(xpGained);
			if (xpBoostPercent > 0 && xpBoostMayor != null) {
				xpLine += String.format(Locale.ROOT, " (+%.0f%% %s)", xpBoostPercent, xpBoostMayor);
			}
			body.add(xpLine);
		}

		int textWidth = font.width(title);
		for (String line : body) textWidth = Math.max(textWidth, font.width(line));
		int width = Math.max(MIN_WIDTH, textWidth + PADDING * 2);

		int x = config.slayer.statsHudPosition.x + PADDING;
		int y = config.slayer.statsHudPosition.y + PADDING;
		int lineHeight = font.lineHeight + 2;

		int boxHeight = PADDING * 2 + lineHeight * (1 + body.size());
		graphics.fill(x - PADDING, y - PADDING, x - PADDING + width, y - PADDING + boxHeight, BG_COLOR);

		graphics.text(font, Component.literal(title), x, y, TITLE_COLOR, true);
		y += lineHeight;

		for (String line : body) {
			graphics.text(font, Component.literal(line), x, y, LABEL_COLOR, true);
			y += lineHeight;
		}

		return new ScdOverlayBox.Bounds(x - PADDING, config.slayer.statsHudPosition.y, width, boxHeight);
	}
}
