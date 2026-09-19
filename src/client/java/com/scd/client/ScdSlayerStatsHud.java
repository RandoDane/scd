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
 *
 * Redesigned (2026-09-19) around a "hero stat" pattern - a small caps label
 * over one big emphasized number for the single most useful stat
 * (kills/hour), with everything else in a two-column grid below - see
 * ScdTheme.panelRounded/heroNumber. Draws in two passes with the same
 * increment logic (measureOnly first to get the exact panel height, then
 * for real once the rounded background is in place) rather than tracking
 * height analytically by hand, so the layout can't silently drift out of
 * sync with what actually gets drawn.
 */
public class ScdSlayerStatsHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("scd", "slayer_stats_overlay");
	private static final int PADDING = 10;
	private static final int MIN_WIDTH = 200;
	private static final int COLUMN_GAP = 12;

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

	private record Stat(String label, String value) {
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
		} else if (stats.killCount() > 0 && isLastActiveTypeInAllowedArea()) {
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
		String subtitle = tier != null ? "Tier " + tier : "No active quest";
		String heroText = String.format(Locale.ROOT, "%.1f", perHour);

		List<Stat> grid = new ArrayList<>();
		grid.add(new Stat("Kills", String.valueOf(kills)));
		grid.add(new Stat("Avg Kill Time", ScdSlayerHud.formatElapsed(avgMs)));
		if (avgHuntMs > 0) grid.add(new Stat("Avg Spawn Time", ScdSlayerHud.formatElapsed(avgHuntMs)));
		grid.add(new Stat("XP Gained", xpGained > 0 ? ScdFormat.compactCount(xpGained) : "-"));
		boolean showBoost = xpBoostPercent > 0 && xpBoostMayor != null;
		String boostLine = showBoost ? String.format(Locale.ROOT, "+%.0f%% %s Slayer XP", xpBoostPercent, xpBoostMayor) : null;

		int panelX = config.slayer.statsHudPosition.x;
		int panelY = config.slayer.statsHudPosition.y;
		float scale = config.slayer.statsHudPosition.scale;
		int panelWidth = MIN_WIDTH;

		// Pass 1: measure only (at native, unscaled size), to get the exact height before drawing.
		int panelHeight = layout(null, font, panelWidth, subtitle, heroText, grid, boostLine);

		// Drawn in local (0,0)-relative coordinates, wrapped in a single translate+scale transform, so
		// the corner-drag resize handle in ScdHudEditScreen can grow/shrink the WHOLE box (panel, text,
		// hero number, everything) uniformly around its pinned top-left position instead of needing
		// every draw call in layout() to know about the scale individually.
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(panelX, panelY);
		pose.scale(scale, scale);
		ScdTheme.panelRounded(graphics, 0, 0, panelWidth, panelHeight);
		layout(graphics, font, panelWidth, subtitle, heroText, grid, boostLine);
		pose.popMatrix();

		return new ScdOverlayBox.Bounds(panelX, panelY, Math.round(panelWidth * scale), Math.round(panelHeight * scale));
	}

	/** graphics == null means measure only (return the final y, don't draw anything) - see renderContent's two-pass comment. Always local-origin (0,0) - renderContent applies the position/scale transform around this. */
	private int layout(GuiGraphicsExtractor graphics, Font font, int panelWidth,
			String subtitle, String heroText, List<Stat> grid, String boostLine) {
		int contentX = PADDING;
		int fieldWidth = panelWidth - PADDING * 2;
		int columnWidth = (fieldWidth - COLUMN_GAP) / 2;
		int lineH = ScdTheme.lineHeight(font);

		int y = PADDING;

		if (graphics != null) graphics.text(font, Component.literal("Slayer Session"), contentX, y, ScdTheme.ACCENT_SLAYER, true);
		y += font.lineHeight + 2;
		if (graphics != null) ScdTheme.label(graphics, font, subtitle, contentX, y, ScdTheme.TEXT_SECONDARY);
		y += lineH + 6;
		if (graphics != null) ScdTheme.divider(graphics, contentX, y, fieldWidth);
		y += 9;

		if (graphics != null) ScdTheme.sectionLabel(graphics, font, "Kills / Hour", contentX, y);
		y += lineH + 2;
		if (graphics != null) ScdTheme.heroNumber(graphics, font, heroText, contentX, y, ScdTheme.TEXT_PRIMARY);
		y += ScdTheme.heroLineHeight(font) + 8;
		if (graphics != null) ScdTheme.divider(graphics, contentX, y, fieldWidth);
		y += 9;

		for (int i = 0; i < grid.size(); i += 2) {
			Stat left = grid.get(i);
			if (graphics != null) {
				ScdTheme.sectionLabel(graphics, font, left.label(), contentX, y);
				ScdTheme.label(graphics, font, left.value(), contentX, y + lineH + 2, ScdTheme.TEXT_PRIMARY);
			}
			if (i + 1 < grid.size()) {
				Stat right = grid.get(i + 1);
				int colX2 = contentX + columnWidth + COLUMN_GAP;
				if (graphics != null) {
					ScdTheme.sectionLabel(graphics, font, right.label(), colX2, y);
					ScdTheme.label(graphics, font, right.value(), colX2, y + lineH + 2, ScdTheme.TEXT_PRIMARY);
				}
			}
			y += lineH * 2 + 8;
		}

		if (boostLine != null) {
			if (graphics != null) ScdTheme.label(graphics, font, boostLine, contentX, y, ScdTheme.TEXT_MUTED);
			y += lineH + 4;
		}

		return y + PADDING;
	}

	/**
	 * True for types with no location restriction, and for a restricted type (Enderman/Blaze/Spider)
	 * only while still standing in its designated area - same check the boss tracker itself already
	 * applies to `quest`, just re-run here against the last type tracked this session so the stats box
	 * doesn't keep showing forever after wandering off (unlike `quest`, session stats never reset).
	 */
	private boolean isLastActiveTypeInAllowedArea() {
		ScdSlayerType type = tracker.lastActiveTypeOrNull();
		return type == null || ScdSlayerScoreboard.isInAllowedArea(type);
	}
}
