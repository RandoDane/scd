package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The combined, single-position Slayer HUD box: boss/quest info stacked
 * above session stats (ScdSlayerStatsHud provides that half's content) in
 * one rounded panel, sharing one position/size. Originally two independent
 * floating boxes; merged per explicit request instead of stacking two
 * separate panels. Each half is still independently toggleable
 * (bossTrackerEnabled/statsHudEnabled) - the box shrinks to whichever
 * half(s) are on, or disappears entirely if both are off.
 *
 * Two-pass layout throughout (graphics == null means measure only), same
 * reasoning as ScdSlayerStatsHud: the exact height has to be known before
 * the rounded panel background can be drawn, and re-running the identical
 * draw logic for the measure pass means the two can never drift out of sync.
 */
public class ScdSlayerHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("scd", "slayer_boss_overlay");
	private static final int BAR_BG_COLOR = 0x60000000;
	private static final int BAR_COLOR = 0xFFFF5555;
	private static final int WARNING_COLOR = 0xFFFF8855;
	private static final int INFO_COLOR = 0xFF55DDAA;
	private static final int PADDING = 10;
	private static final int PANEL_WIDTH = 210;
	private static final int BAR_HEIGHT = 6;

	private final ScdConfig config;
	private final ScdSlayerBossTracker tracker;
	private final ScdSlayerRecords records;
	private final ScdSlayerRngMeter rngMeter;
	private final ScdSlayerDrops drops;
	private final ScdSlayerStatsHud statsHud;
	// Proves whether the HUD callback is even being invoked at all, independent of whatever it then
	// does - so /scd slayer debug can tell "never called" apart from "called but throwing/blank".
	private final AtomicLong renderCallCount = new AtomicLong();

	public ScdSlayerHud(ScdConfig config, ScdSlayerBossTracker tracker, ScdSlayerRecords records,
			ScdSlayerRngMeter rngMeter, ScdSlayerDrops drops, ScdSlayerStatsHud statsHud) {
		this.config = config;
		this.tracker = tracker;
		this.records = records;
		this.rngMeter = rngMeter;
		this.drops = drops;
		this.statsHud = statsHud;
	}

	public void register() {
		// Ticks the tracker here too, not just via the separate ClientTickEvents.END_CLIENT_TICK
		// registration - an uncaught exception in some other mod's tick listener registered before
		// ours can silently stop every listener after it on that event for the rest of the session
		// (Fabric calls them all in one unbroken sequence, no per-listener isolation).
		// HudElementRegistry is a separate, more robust pipeline in a heavily modded environment.
		HudElementRegistry.addLast(ID, (graphics, deltaTracker) -> {
			renderCallCount.incrementAndGet();
			ScdLog.guard("slayer HUD", () -> {
				tracker.tick();
				renderContent(graphics, Minecraft.getInstance().font, false);
			});
		});
	}

	public long renderCallCount() {
		return renderCallCount.get();
	}

	public ScdOverlayBox.Bounds renderPreview(GuiGraphicsExtractor graphics, Font font) {
		return renderContent(graphics, font, true);
	}

	private ScdOverlayBox.Bounds renderContent(GuiGraphicsExtractor graphics, Font font, boolean preview) {
		boolean showBoss = config.slayer.bossTrackerEnabled;
		boolean showStats = config.slayer.statsHudEnabled;
		if (!showBoss && !showStats) return null;

		// Pass 1: measure only, to get the exact height before drawing the rounded background.
		Integer height = layoutCombined(null, font, showBoss, showStats, preview);
		if (height == null) return null;

		int panelX = config.slayer.bossTrackerPosition.x;
		int panelY = config.slayer.bossTrackerPosition.y;
		float scale = config.slayer.bossTrackerPosition.scale;

		// Drawn in local (0,0)-relative coordinates, wrapped in a single translate+scale transform -
		// try/finally so an exception partway through can never leave the pose stack unbalanced for
		// the rest of the frame's rendering (every other HUD element sharing it would inherit the
		// leftover transform otherwise).
		var pose = graphics.pose();
		pose.pushMatrix();
		try {
			pose.translate(panelX, panelY);
			pose.scale(scale, scale);
			ScdTheme.panelRounded(graphics, 0, 0, PANEL_WIDTH, height);
			layoutCombined(graphics, font, showBoss, showStats, preview);
		} finally {
			pose.popMatrix();
		}

		return new ScdOverlayBox.Bounds(panelX, panelY, Math.round(PANEL_WIDTH * scale), Math.round(height * scale));
	}

	/** graphics == null means measure only. Returns null if neither half has anything to show right now. */
	private Integer layoutCombined(GuiGraphicsExtractor graphics, Font font, boolean showBoss, boolean showStats, boolean preview) {
		int y = PADDING;
		boolean drewBoss = false;
		if (showBoss) {
			Integer afterBoss = layoutBossSection(graphics, font, y, preview);
			if (afterBoss != null) {
				y = afterBoss;
				drewBoss = true;
			}
		}
		boolean drewStats = false;
		if (showStats) {
			// Peek measure-only first, so the divider below is only ever drawn when the stats section
			// really does have something following it - otherwise a stats-has-nothing-yet frame would
			// leave a dangling divider line with nothing under it.
			Integer wouldShow = statsHud.layoutSection(null, font, PANEL_WIDTH, y, preview);
			if (wouldShow != null) {
				if (drewBoss) {
					y += 6;
					if (graphics != null) ScdTheme.divider(graphics, PADDING, y, PANEL_WIDTH - PADDING * 2);
					y += 9;
				}
				y = statsHud.layoutSection(graphics, font, PANEL_WIDTH, y, preview);
				drewStats = true;
			}
		}
		if (!drewBoss && !drewStats) return null;
		return y + PADDING;
	}

	/** graphics == null means measure only. Returns null if there's nothing boss/quest-related to show right now. */
	private Integer layoutBossSection(GuiGraphicsExtractor graphics, Font font, int startY, boolean preview) {
		LivingEntity boss = preview ? null : tracker.currentBossOrNull();
		ScdSlayerQuest quest = preview ? null : tracker.currentQuestOrNull();

		String title;
		String line2;
		List<String> extraLines = List.of();
		int extraLinesColor = WARNING_COLOR;
		Float healthFrac = null;

		if (!preview && tracker.isCocoonActive()) {
			title = "Boss Cocooned!";
			line2 = "Respawning in " + (tracker.cocoonRemainingMs() / 1000 + 1) + "s";
		} else if (boss != null) {
			Component custom = boss.getCustomName();
			title = custom != null ? custom.getString() : "Slayer Boss";

			Double currentHp = tracker.currentHpOrNull();
			Double maxHp = tracker.maxHpOrNull();
			String hpText;
			if (currentHp != null) {
				hpText = ScdFormat.coins(currentHp, 0) + (maxHp != null ? "/" + ScdFormat.coins(maxHp, 0) : "");
				if (maxHp != null && maxHp > 0) {
					healthFrac = (float) Math.max(0, Math.min(1, currentHp / maxHp));
				}
			} else {
				hpText = "?";
			}

			Long best = quest != null ? records.best(quest.type(), quest.tier()) : null;
			line2 = "Spawned - Time: " + formatElapsed(tracker.fightElapsedMs())
					+ "  HP: " + hpText + (best != null ? "  Best: " + formatElapsed(best) : "");

			extraLines = ScdSlayerAbilities.lines(config, quest, boss, tracker);
			extraLinesColor = WARNING_COLOR;
		} else if (quest != null) {
			// Boss hasn't spawned yet - the scoreboard already knows the quest is active
			// though, so this shows well before any boss entity exists to scan for. Good spot to
			// also show RNG meter progress and running drop totals, both keyed to this exact
			// type, since there's nothing more urgent competing for the box's space right now.
			title = quest.type().displayName() + " Slayer" + (quest.tier() != null ? " " + quest.tier() : "");
			line2 = "Spawning - Time: " + formatElapsed(tracker.huntElapsedMs()) + (tracker.isHuntPaused() ? " (paused)" : "");
			extraLines = huntingInfoLines(quest);
			extraLinesColor = INFO_COLOR;
		} else if (!preview && tracker.justEndedTypeOrNull() != null) {
			// Brief confirmation flash right after a kill, same window as the config screen's status
			// badge - otherwise the box just vanishes the instant the fight ends with no feedback.
			title = tracker.justEndedTypeOrNull().displayName() + " Slayer";
			line2 = "Killed";
		} else if (preview) {
			title = "Revenant Horror";
			healthFrac = 0.62f;
			line2 = "Fight time: 0:17   HP: 248K/400K";
			extraLines = List.of("Enrage ~23s");
		} else {
			return null;
		}

		int contentX = PADDING;
		int fieldWidth = PANEL_WIDTH - PADDING * 2;
		int lineH = ScdTheme.lineHeight(font);
		int y = startY;

		if (graphics != null) graphics.text(font, Component.literal(title), contentX, y, ScdTheme.ACCENT_SLAYER, true);
		y += font.lineHeight + 2;

		if (graphics != null) ScdTheme.label(graphics, font, line2, contentX, y, ScdTheme.TEXT_SECONDARY);
		y += lineH + 2;

		for (String extraLine : extraLines) {
			if (graphics != null) ScdTheme.label(graphics, font, extraLine, contentX, y, extraLinesColor);
			y += lineH + 2;
		}

		if (healthFrac != null) {
			if (graphics != null) {
				graphics.fill(contentX, y, contentX + fieldWidth, y + BAR_HEIGHT, BAR_BG_COLOR);
				graphics.fill(contentX, y, contentX + Math.round(fieldWidth * healthFrac), y + BAR_HEIGHT, BAR_COLOR);
			}
			y += BAR_HEIGHT + 4;
		}

		return y;
	}

	private List<String> huntingInfoLines(ScdSlayerQuest quest) {
		List<String> lines = new ArrayList<>();

		String selectedDrop = rngMeter.selectedDrop(quest.type());
		Long storedXp = rngMeter.storedXp(quest.type());
		Double chancePercent = rngMeter.chancePercent(quest.type());

		if (selectedDrop != null) {
			lines.add("RNG Meter: " + selectedDrop);
			Long requiredTotal = rngMeter.requiredTotal(quest.type(), selectedDrop);
			StringBuilder progress = new StringBuilder("  ");
			if (storedXp != null && requiredTotal != null) {
				progress.append(ScdFormat.compactCount(storedXp)).append("/").append(ScdFormat.compactCount(requiredTotal));
				if (chancePercent != null) progress.append(" (").append(String.format(Locale.ROOT, "%.1f", chancePercent)).append("%)");
			} else if (chancePercent != null) {
				progress.append(String.format(Locale.ROOT, "%.1f", chancePercent)).append("%");
			} else if (storedXp != null) {
				progress.append(ScdFormat.coins(storedXp, 0)).append(" stored XP");
			}
			if (progress.length() > 2) lines.add(progress.toString());
		} else if (storedXp != null || chancePercent != null) {
			// Menu hasn't been opened yet this session, so the selected drop's name isn't known -
			// falls back to a plain summary until the player opens the Slayer menu once.
			String rngLine = "RNG Meter:";
			if (chancePercent != null) rngLine += " " + String.format(Locale.ROOT, "%.1f", chancePercent) + "%";
			if (storedXp != null) rngLine += " (" + ScdFormat.coins(storedXp, 0) + " stored XP)";
			lines.add(rngLine);
		}

		var topDrops = drops.topForType(quest.type(), 3);
		if (!lines.isEmpty() && !topDrops.isEmpty()) lines.add("");
		for (var drop : topDrops) {
			lines.add(drop.displayName() + ": " + ScdFormat.compactCount(drop.count()));
		}
		return lines;
	}

	public static String formatElapsed(long ms) {
		long totalSeconds = ms / 1000;
		return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}
}
