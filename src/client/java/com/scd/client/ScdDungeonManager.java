package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the SkyBlock sidebar scoreboard to tell whether the player is currently inside a
 * Catacombs dungeon run, and which floor - the same mechanism ScdSlayerScoreboard already uses
 * for Slayer quest state, since Hypixel drives both off the same sidebar objective.
 *
 * Sidebar wording confirmed live 2026-09-22 via a real `/scd dungeon debug capture` run (Floor 6,
 * Sadan): the area line reads exactly `"The Catacombs (F6)"` (Master Mode presumably
 * `"The Catacombs (M6)"`, entrance presumably `"The Catacombs (Entrance)"` - those two variants
 * are still unconfirmed). The original guess here matched on a literal word "Floor" which never
 * appears at all - fixed to match the real `(F6)`-style suffix instead.
 *
 * Also confirmed from that same run: the sidebar's "Cleared: X% (N)" line - the parenthetical N
 * is the same number the end-of-run summary calls Score (it read exactly "Score: 273 (S)" and
 * the sidebar's last value before the kill was also 273). **Correction per direct account-owner
 * knowledge (2026-09-22): this number is only accurate once inside the boss room - before that
 * it reads wrong/incomplete.** So it's NOT a trustworthy live score for the whole run the way
 * this doc first assumed from watching it climb - only useful once the boss room is reached, and
 * the real formula (§3's exact-formula research) is still what a true live estimate needs for
 * every point before then.
 */
public final class ScdDungeonManager {
	private static final Pattern FORMATTING_CODE = Pattern.compile("§.");
	private static final Pattern PRIVATE_USE_AREA = Pattern.compile("[-]");
	private static final Pattern FLOOR_LINE = Pattern.compile("Catacombs\\s*\\((Entrance|[MF]\\d+)\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SCORE_LINE = Pattern.compile("Cleared:\\s*\\d+%\\s*\\((\\d+)\\)");

	private ScdDungeonManager() {
	}

	public record DungeonState(boolean inDungeon, String floor, Integer liveScore) {
		public static final DungeonState NONE = new DungeonState(false, null, null);
	}

	public static DungeonState read() {
		var mc = Minecraft.getInstance();
		if (mc.level == null) return DungeonState.NONE;

		Scoreboard scoreboard = mc.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return DungeonState.NONE;

		boolean inDungeon = false;
		String floor = null;
		Integer liveScore = null;
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			String line = resolveLine(scoreboard, entry);
			if (line.isEmpty()) continue;
			if (line.contains("Catacombs")) inDungeon = true;
			Matcher floorMatch = FLOOR_LINE.matcher(line);
			if (floorMatch.find()) floor = floorMatch.group(1);
			Matcher scoreMatch = SCORE_LINE.matcher(line);
			if (scoreMatch.find()) liveScore = Integer.parseInt(scoreMatch.group(1));
		}
		return inDungeon ? new DungeonState(true, floor, liveScore) : DungeonState.NONE;
	}

	/** Same raw-line dump style as ScdSlayerScoreboard.describeRaw() - used by the dungeon debug commands. */
	private static String resolveLine(Scoreboard scoreboard, PlayerScoreEntry entry) {
		String rawOwner = entry.owner();
		var team = scoreboard.getPlayersTeam(rawOwner);
		Component text = team != null ? team.getFormattedName(Component.literal(rawOwner)) : entry.ownerName();
		String clean = FORMATTING_CODE.matcher(text.getString()).replaceAll("");
		clean = PRIVATE_USE_AREA.matcher(clean).replaceAll("");
		return clean.replace(' ', ' ').trim();
	}
}
