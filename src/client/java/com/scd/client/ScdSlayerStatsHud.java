package com.scd.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The session-stats SECTION of the combined Slayer HUD box (see ScdSlayerHud,
 * which owns the actual panel/position/registration): count, average kill
 * time, an extrapolated kills/hour rate, and total Slayer XP gained. Purely
 * derived from fights ScdSlayerBossTracker has actually observed - not
 * persisted, resets on game restart, and layoutSection() reports "nothing to
 * show" (null) until at least one kill has happened this session.
 *
 * Hero-stat pattern (2026-09-19): a small caps label over one big emphasized
 * number for the single most useful stat (kills/hour), with everything else
 * in a two-column grid below - see ScdTheme.heroNumber. Originally this
 * class owned its own rounded panel/position/HudElementRegistry
 * registration as a second, separate floating box; merged into
 * ScdSlayerHud's single combined box per explicit request instead of two
 * boxes side by side.
 */
public class ScdSlayerStatsHud {
	private static final int PADDING = 10;
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

	private record Stat(String label, String value) {
	}

	/**
	 * Draws this section at local (contentX=PADDING)-relative coordinates starting at startY, for
	 * ScdSlayerHud to stack under its own boss/quest section. graphics == null means measure only
	 * (used for ScdSlayerHud's own two-pass height calculation - see its class doc). Returns the y
	 * position immediately after the last line drawn (no bottom padding - the caller/combined box
	 * adds that once for the whole panel), or null if there's nothing to show at all (no kills yet
	 * this session and not a preview).
	 */
	public Integer layoutSection(GuiGraphicsExtractor graphics, Font font, int panelWidth, int startY, boolean preview) {
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
		// A section header rather than a repeat of the boss section's own title above it - this is
		// what visually marks "the stats half starts here" when both sections are stacked together.
		String sectionHeader = "Session Stats" + (tier != null ? " · Tier " + tier : "");
		String heroText = String.format(Locale.ROOT, "%.1f", perHour);

		List<Stat> grid = new ArrayList<>();
		grid.add(new Stat("Kills", String.valueOf(kills)));
		grid.add(new Stat("Avg Kill Time", ScdSlayerHud.formatElapsed(avgMs)));
		if (avgHuntMs > 0) grid.add(new Stat("Avg Spawn Time", ScdSlayerHud.formatElapsed(avgHuntMs)));
		grid.add(new Stat("XP Gained", xpGained > 0 ? ScdFormat.compactCount(xpGained) : "-"));
		boolean showBoost = xpBoostPercent > 0 && xpBoostMayor != null;
		String boostLine = showBoost ? String.format(Locale.ROOT, "+%.0f%% %s Slayer XP", xpBoostPercent, xpBoostMayor) : null;

		int contentX = PADDING;
		int fieldWidth = panelWidth - PADDING * 2;
		int columnWidth = (fieldWidth - COLUMN_GAP) / 2;
		int lineH = ScdTheme.lineHeight(font);

		int y = startY;

		if (graphics != null) ScdTheme.sectionLabel(graphics, font, sectionHeader, contentX, y);
		y += lineH + 8;

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

		return y;
	}

	/**
	 * True for types with no location restriction, and for a restricted type (Enderman/Blaze/Spider)
	 * only while still standing in its designated area - same check the boss tracker itself already
	 * applies to `quest`, just re-run here against the last type tracked this session so the stats
	 * section doesn't keep showing forever after wandering off (unlike `quest`, session stats never reset).
	 */
	private boolean isLastActiveTypeInAllowedArea() {
		ScdSlayerType type = tracker.lastActiveTypeOrNull();
		return type == null || ScdSlayerScoreboard.isInAllowedArea(type);
	}
}
