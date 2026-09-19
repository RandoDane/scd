package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.regex.Pattern;

/**
 * Live "Arrows Remaining" reading for Explosive Arrows specifically, read off
 * the Skyblock-menu hotbar slot (index 8) - but only ever while an Ender
 * Slayer (Voidgloom Seraph) quest is active, per explicit instruction: this
 * counter only matters for that fight (Explosive Arrows are the standard
 * Voidgloom weapon), so it's cleared the rest of the time even if the config
 * toggle is on or some other arrow type happens to be equipped.
 *
 * The slot only shows real quiver data while a bow is actually held, but the
 * box should stay up showing the last known count while you're mid-fight
 * swinging a sword or whatever else - so the reading is only ever REFRESHED
 * while holding a bow, and simply held onto (not cleared) whenever the bow
 * isn't in hand, for as long as the Ender Slayer quest stays active.
 */
public class ScdQuiverTracker {
	private static final int QUIVER_SLOT_INDEX = 8;
	private static final String REQUIRED_ARROW_TYPE = "Explosive Arrow";
	private static final Pattern FORMATTING_CODE = Pattern.compile("§.");

	private final ScdSlayerBossTracker slayerTracker;
	private ScdQuiverReading.Reading reading;

	public ScdQuiverTracker(ScdSlayerBossTracker slayerTracker) {
		this.slayerTracker = slayerTracker;
	}

	public void tick() {
		ScdSlayerQuest quest = slayerTracker.currentQuestOrNull();
		if (quest == null || quest.type() != ScdSlayerType.ENDERMAN) {
			reading = null;
			return;
		}

		var mc = Minecraft.getInstance();
		if (mc.player == null) return;

		boolean holdingBow = mc.player.getMainHandItem().is(Items.BOW) || mc.player.getOffhandItem().is(Items.BOW);
		if (!holdingBow) return;

		ItemStack quiverSlotStack = mc.player.getInventory().getItem(QUIVER_SLOT_INDEX);
		ScdQuiverReading.Reading candidate = ScdQuiverReading.parse(quiverSlotStack);
		if (candidate == null) return;

		String cleanName = FORMATTING_CODE.matcher(candidate.arrowTypeName()).replaceAll("").trim();
		// A genuine arrow-type switch (still holding a bow, but no longer Explosive Arrows) does
		// clear the reading - unlike simply not holding a bow, this is a real state change that
		// makes the last saved Explosive Arrow count stale/misleading.
		reading = REQUIRED_ARROW_TYPE.equalsIgnoreCase(cleanName) ? candidate : null;
	}

	public ScdQuiverReading.Reading currentOrNull() {
		return reading;
	}
}
