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
 * A movable HUD box that appears whenever a Slayer boss is nearby, showing
 * its name, how long the fight has run, and a health-percentage bar.
 */
public class ScdSlayerHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("scd", "slayer_boss_overlay");
	private static final int BG_COLOR = 0x90000000;
	private static final int BAR_BG_COLOR = 0x60000000;
	private static final int BAR_COLOR = 0xFFFF5555;
	private static final int TITLE_COLOR = 0xFFFFAA00;
	private static final int LABEL_COLOR = 0xFFAAAAAA;
	private static final int WARNING_COLOR = 0xFFFF8855;
	private static final int INFO_COLOR = 0xFF55DDAA;
	private static final int PADDING = 4;
	private static final int MIN_WIDTH = 150;
	private static final int BAR_HEIGHT = 6;

	private final ScdConfig config;
	private final ScdSlayerBossTracker tracker;
	private final ScdSlayerRecords records;
	private final ScdSlayerRngMeter rngMeter;
	private final ScdSlayerDrops drops;
	// Proves whether the HUD callback is even being invoked at all, independent of whatever it then
	// does - so /scd slayer debug can tell "never called" apart from "called but throwing/blank".
	private final AtomicLong renderCallCount = new AtomicLong();

	public ScdSlayerHud(ScdConfig config, ScdSlayerBossTracker tracker, ScdSlayerRecords records,
			ScdSlayerRngMeter rngMeter, ScdSlayerDrops drops) {
		this.config = config;
		this.tracker = tracker;
		this.records = records;
		this.rngMeter = rngMeter;
		this.drops = drops;
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
				if (config.slayer.bossTrackerEnabled) renderContent(graphics, Minecraft.getInstance().font, false);
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

		int textWidth = font.width(title);
		textWidth = Math.max(textWidth, font.width(line2));
		for (String extraLine : extraLines) {
			textWidth = Math.max(textWidth, font.width(extraLine));
		}
		int width = Math.max(MIN_WIDTH, textWidth + PADDING * 2);

		int x = config.slayer.bossTrackerPosition.x + PADDING;
		int y = config.slayer.bossTrackerPosition.y + PADDING;
		int lineHeight = font.lineHeight + 2;

		int textLines = 2 + extraLines.size();
		int boxHeight = PADDING * 2 + lineHeight * textLines + (healthFrac != null ? 2 + BAR_HEIGHT : 0);
		graphics.fill(x - PADDING, y - PADDING, x - PADDING + width, y - PADDING + boxHeight, BG_COLOR);

		graphics.text(font, Component.literal(title), x, y, TITLE_COLOR, true);
		y += lineHeight;

		graphics.text(font, Component.literal(line2), x, y, LABEL_COLOR, true);
		y += lineHeight;

		for (String extraLine : extraLines) {
			graphics.text(font, Component.literal(extraLine), x, y, extraLinesColor, true);
			y += lineHeight;
		}

		if (healthFrac != null) {
			y += 2;
			int barWidth = width - PADDING * 2;
			graphics.fill(x, y, x + barWidth, y + BAR_HEIGHT, BAR_BG_COLOR);
			graphics.fill(x, y, x + Math.round(barWidth * healthFrac), y + BAR_HEIGHT, BAR_COLOR);
		}

		return new ScdOverlayBox.Bounds(x - PADDING, config.slayer.bossTrackerPosition.y, width, boxHeight);
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
