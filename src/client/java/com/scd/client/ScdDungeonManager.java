package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.Comparator;
import java.util.List;
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
 * the real formula (§3's exact-formula research, now implemented in ScdDungeonScore) is what a
 * true live estimate needs for every point before then.
 */
public final class ScdDungeonManager {
	private static final Pattern FORMATTING_CODE = Pattern.compile("§.");
	private static final Pattern PRIVATE_USE_AREA = Pattern.compile("[-]");
	private static final Pattern FLOOR_LINE = Pattern.compile("Catacombs\\s*\\((Entrance|[MF]\\d+)\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SCORE_LINE = Pattern.compile("Cleared:\\s*(\\d+)%\\s*\\((\\d+)\\)");
	private static final Pattern TIME_LINE = Pattern.compile("Time Elapsed:\\s*(?:(\\d+)m\\s*)?(\\d+)s");

	private ScdDungeonManager() {
	}

	public record DungeonState(boolean inDungeon, String floor, Integer liveScore, Double clearPercent, Integer timeElapsedSeconds) {
		public static final DungeonState NONE = new DungeonState(false, null, null, null, null);
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
		Double clearPercent = null;
		Integer timeElapsedSeconds = null;
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			String line = resolveLine(scoreboard, entry);
			if (line.isEmpty()) continue;
			if (line.contains("Catacombs")) inDungeon = true;
			Matcher floorMatch = FLOOR_LINE.matcher(line);
			if (floorMatch.find()) floor = floorMatch.group(1);
			Matcher scoreMatch = SCORE_LINE.matcher(line);
			if (scoreMatch.find()) {
				clearPercent = Double.parseDouble(scoreMatch.group(1));
				liveScore = Integer.parseInt(scoreMatch.group(2));
			}
			Matcher timeMatch = TIME_LINE.matcher(line);
			if (timeMatch.find()) {
				int minutes = timeMatch.group(1) != null ? Integer.parseInt(timeMatch.group(1)) : 0;
				int seconds = Integer.parseInt(timeMatch.group(2));
				timeElapsedSeconds = minutes * 60 + seconds;
			}
		}
		return inDungeon ? new DungeonState(true, floor, liveScore, clearPercent, timeElapsedSeconds) : DungeonState.NONE;
	}

	/**
	 * The vanilla tab list (hold Tab), which Hypixel repurposes for dungeon run stats (Completed
	 * Rooms, Secrets Found %, Crypts, Puzzles) the same way the sidebar is repurposed - a
	 * genuinely new data source for this project (only the sidebar and chat/packets were read
	 * before this). Deliberately scans every entry and pattern-matches content, the same way the
	 * sidebar is read here and in ScdSlayerScoreboard, rather than trusting a fixed row
	 * index/position - a real reference mod (Skyblocker) reads specific hardcoded row numbers,
	 * which this project has no confirmation actually matches our own party-size/layout, so
	 * scanning is the safer choice even though it's a little more work. NOT yet confirmed live -
	 * the tab list has never been read by this project before.
	 */
	public static List<String> readTabList() {
		var player = Minecraft.getInstance().player;
		if (player == null || player.connection == null) return List.of();

		return player.connection.getListedOnlinePlayers().stream()
				.sorted(Comparator.comparingInt(PlayerInfo::getTabListOrder))
				.map(ScdDungeonManager::resolveTabListLine)
				.toList();
	}

	private static String resolveTabListLine(PlayerInfo info) {
		Component text = info.getTabListDisplayName();
		if (text == null) {
			var team = info.getTeam();
			String rawName = info.getProfile().name();
			text = team != null ? team.getFormattedName(Component.literal(rawName)) : Component.literal(rawName);
		}
		String clean = FORMATTING_CODE.matcher(text.getString()).replaceAll("");
		clean = PRIVATE_USE_AREA.matcher(clean).replaceAll("");
		return clean.replace(' ', ' ').trim();
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
