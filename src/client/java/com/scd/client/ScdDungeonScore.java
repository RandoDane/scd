package com.scd.client;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live dungeon score estimate - a faithful port of Skyblocker's real `DungeonScore.java`
 * (LGPL-3.0, fetched raw and read directly rather than summarized, see FEATURE_ROADMAP.md §3's
 * 2026-09-23 research pass), not the SkyBlock Wiki's own simplified formula text, which turned
 * out to itself be an approximation of the real, more nuanced logic below.
 *
 * Reads three kinds of live data, none of it live-confirmed yet for the tab-list half:
 * - Sidebar scoreboard (ScdDungeonManager): floor, clear %, time elapsed - the same mechanism
 *   already confirmed live for Slayer/dungeon detection.
 * - Tab list (ScdDungeonManager.readTabList(), new as of this class): Completed Rooms, Secrets
 *   Found %, Crypts, Puzzle count + per-puzzle pass/fail. NOT yet read by this project before -
 *   genuinely unverified, same "ship the best real-mod-derived guess, confirm live, correct if
 *   wrong" posture as every other new pattern in this project.
 * - Chat (fed by ScdChatPacketMixin, same raw-before-any-other-mod pipeline as
 *   ScdDungeonCompletion): deaths, mimic/prince/bat kills. Also unverified - this project has
 *   never actually seen a real player-death message during a dungeon run yet.
 *
 * Deliberately does NOT implement the "first death had a Legendary Spirit Pet, so it only costs
 * 1 point instead of 2" refinement Skyblocker has - that needs an async SkyBlock profile lookup
 * for whoever died, which is real scope on its own. Every death costs the full 2 points here;
 * flagged so the gap is a known simplification, not a silent inaccuracy.
 */
public final class ScdDungeonScore {
	private static final Pattern COMPLETED_ROOMS_LINE = Pattern.compile("Completed Rooms:\\s*(\\d+)");
	private static final Pattern SECRETS_PERCENT_LINE = Pattern.compile("Secrets Found:\\s*(\\d+\\.?\\d*)%");
	private static final Pattern CRYPTS_LINE = Pattern.compile("Crypts:\\s*(\\d+)");
	private static final Pattern PUZZLE_COUNT_LINE = Pattern.compile("Puzzles:\\s*\\((\\d+)\\)");
	// Confirmed live 2026-09-23 (real tab list dump, /scd dungeon debug tablist): an
	// undiscovered/not-yet-reached puzzle reads `"???: [✦]"` - "✦" means PENDING, not failed. The
	// original guess here wrongly included "✦" as a fail glyph (copied from Skyblocker's own
	// pattern, which came through a mangled encoding on fetch and was misread) - that counted
	// every not-yet-reached puzzle as a failure, inflating the Skill penalty by 10 points each
	// (confirmed: a run with 2 undiscovered, 0 actually-failed puzzles was scoring 20 points low
	// versus Odin's own display until this was fixed). "✖" (heavy X) is the remaining guess for
	// an actually-failed puzzle - still NOT confirmed live, this project hasn't seen one yet.
	private static final Pattern PUZZLE_LINE = Pattern.compile(".+?: \\[(.)]");
	private static final String PUZZLE_FAIL_GLYPHS = "✖";

	// A real player-death message during a run - NOT the end-of-run "Defeated {boss}" report
	// (ScdDungeonCompletion handles that separately). Unconfirmed live: this project has never
	// captured an actual mid-run death message, so this pattern is Skyblocker's own shape, not a
	// verified one of ours.
	private static final Pattern DEATH_LINE = Pattern.compile("^☠ \\S+ .*");
	private static final Pattern MIMIC_LINE = Pattern.compile(".*?(?:Mimic dead!?|Mimic Killed!)$");
	private static final Pattern PRINCE_LINE = Pattern.compile(".*?(?:Prince dead!?|Prince Killed!)$|^A Prince falls\\. \\+1 Bonus Score$");
	private static final Pattern BAT_LINE = Pattern.compile(".*?(?:Bat dead!?|Bat Killed!)$|^A Bat has been slain\\. \\+1 Bonus Score$");

	/** Secret-requirement % and time budget (seconds) per floor - literal FloorRequirement table from Skyblocker's real source. */
	private record FloorRequirement(int secretPercent, int timeLimitSeconds) {
	}

	private static final Map<String, FloorRequirement> FLOOR_REQUIREMENTS = Map.ofEntries(
			Map.entry("ENTRANCE", new FloorRequirement(30, 1200)),
			Map.entry("F1", new FloorRequirement(30, 600)),
			Map.entry("F2", new FloorRequirement(40, 600)),
			Map.entry("F3", new FloorRequirement(50, 600)),
			Map.entry("F4", new FloorRequirement(60, 720)),
			Map.entry("F5", new FloorRequirement(70, 600)),
			Map.entry("F6", new FloorRequirement(85, 720)),
			Map.entry("F7", new FloorRequirement(100, 840)),
			Map.entry("M1", new FloorRequirement(100, 480)),
			Map.entry("M2", new FloorRequirement(100, 480)),
			Map.entry("M3", new FloorRequirement(100, 480)),
			Map.entry("M4", new FloorRequirement(100, 480)),
			Map.entry("M5", new FloorRequirement(100, 480)),
			Map.entry("M6", new FloorRequirement(100, 600)),
			Map.entry("M7", new FloorRequirement(100, 840)));

	private static boolean wasInDungeon = false;
	private static int deaths = 0;
	private static boolean mimicKilled = false;
	private static boolean princeKilled = false;
	private static boolean batKilled = false;

	private ScdDungeonScore() {
	}

	public record ScoreBreakdown(
			int total,
			int skill,
			int explore,
			int speed,
			int bonus,
			boolean isEntrance,
			int completedRooms,
			int totalRoomsEstimate,
			double secretsPercent,
			int crypts,
			int deaths,
			int incompletePuzzles) {
	}

	/** Fed every incoming SYSTEM message by ScdChatPacketMixin, unconditionally - tracks the handful of run events the formula needs that aren't visible on the scoreboard/tab list at all (deaths, mimic/prince/bat kills). */
	public static void onSystemMessage(String rawText) {
		if (rawText == null) return;
		String text = rawText.replaceAll("§.", "").trim();
		if (text.isEmpty()) return;

		if (DEATH_LINE.matcher(text).find()) deaths++;
		if (MIMIC_LINE.matcher(text).matches()) mimicKilled = true;
		if (PRINCE_LINE.matcher(text).matches()) princeKilled = true;
		if (BAT_LINE.matcher(text).matches()) batKilled = true;
	}

	/**
	 * Computes the current score breakdown, or null if not currently in a dungeon / the floor
	 * isn't recognized. Resets all chat-tracked run state (deaths, mimic/prince/bat) the moment a
	 * fresh dungeon is entered, detected the same way ScdAccessoryBagWatcher resets on a fresh
	 * menu visit - a false-to-true transition, tracked here rather than requiring a caller to
	 * manage it.
	 */
	public static ScoreBreakdown computeOrNull(ScdMayorPerks mayorPerks) {
		ScdDungeonManager.DungeonState state = ScdDungeonManager.read();
		if (!state.inDungeon()) {
			wasInDungeon = false;
			return null;
		}
		if (!wasInDungeon) {
			deaths = 0;
			mimicKilled = false;
			princeKilled = false;
			batKilled = false;
		}
		wasInDungeon = true;

		String floorKey = normalizeFloorKey(state.floor());
		FloorRequirement requirement = floorKey != null ? FLOOR_REQUIREMENTS.get(floorKey) : null;
		if (requirement == null) return null;
		boolean isEntrance = "ENTRANCE".equals(floorKey);

		List<String> tabList = ScdDungeonManager.readTabList();
		int completedRooms = firstIntMatch(tabList, COMPLETED_ROOMS_LINE, 0);
		double secretsPercent = firstDoubleMatch(tabList, SECRETS_PERCENT_LINE, 0);
		int crypts = firstIntMatch(tabList, CRYPTS_LINE, 0);
		int puzzleCount = firstIntMatch(tabList, PUZZLE_COUNT_LINE, 0);
		int incompletePuzzles = countIncompletePuzzles(tabList, puzzleCount);

		double clearFraction = state.clearPercent() != null ? state.clearPercent() / 100.0 : 0;
		int totalRooms = clearFraction > 0 ? (int) Math.round(completedRooms / clearFraction) : 0;

		int skill = calculateSkill(completedRooms, totalRooms, incompletePuzzles, deaths);
		int explore = calculateExplore(completedRooms, totalRooms, secretsPercent, requirement.secretPercent());
		int speed = calculateSpeed(state.timeElapsedSeconds(), requirement.timeLimitSeconds());
		int bonus = calculateBonus(crypts, secretsPercent, mayorPerks.hasPerkNamed("EZPZ"));

		int total = isEntrance
				? Math.round(speed * 0.7f) + Math.round(explore * 0.7f) + Math.round(skill * 0.7f) + Math.round(bonus * 0.7f)
				: speed + explore + skill + bonus;

		return new ScoreBreakdown(total, skill, explore, speed, bonus, isEntrance,
				completedRooms, totalRooms, secretsPercent, crypts, deaths, incompletePuzzles);
	}

	private static int calculateSkill(int completedRooms, int totalRooms, int incompletePuzzles, int deaths) {
		int roomPortion = totalRooms > 0 ? clamp((int) (80.0 * completedRooms / totalRooms), 0, 80) : 0;
		int puzzlePenalty = incompletePuzzles * 10;
		int deathPenalty = deaths * 2;
		return 20 + clamp(roomPortion - puzzlePenalty - deathPenalty, 0, 80);
	}

	private static int calculateExplore(int completedRooms, int totalRooms, double secretsPercent, int requiredSecretPercent) {
		int roomPortion = totalRooms > 0 ? clamp((int) (60.0 * completedRooms / totalRooms), 0, 60) : 0;
		double effectiveSecrets = Math.min(requiredSecretPercent, secretsPercent);
		int secretsPortion = clamp((int) (40 * effectiveSecrets / requiredSecretPercent), 0, 40);
		return roomPortion + secretsPortion;
	}

	private static int calculateSpeed(Integer timeElapsedSeconds, int timeLimitSeconds) {
		if (timeElapsedSeconds == null || timeElapsedSeconds < timeLimitSeconds) return 100;
		double percentOver = (timeElapsedSeconds - timeLimitSeconds) / (double) timeLimitSeconds * 100;
		if (percentOver < 20) return 100 - (int) (percentOver / 2);
		if (percentOver < 40) return 100 - (int) (10 + (percentOver - 20) / 4);
		if (percentOver < 50) return 100 - (int) (15 + (percentOver - 40) / 5);
		if (percentOver < 60) return 100 - (int) (17 + (percentOver - 50) / 6);
		return clamp(100 - (int) (18 + 2.0 / 3 + (percentOver - 60) / 7), 0, 100);
	}

	private static int calculateBonus(int crypts, double secretsPercent, boolean isMayorPaul) {
		int paulScore = isMayorPaul ? 10 : 0;
		int cryptsScore = clamp(crypts, 0, 5);
		// If secrets are already at 100% but no explicit mimic-kill message ever fired, the mimic
		// must have died anyway (100% isn't reachable otherwise) - same fallback Skyblocker uses.
		int mimicScore = (mimicKilled || secretsPercent >= 100) ? 2 : 0;
		int princeScore = princeKilled ? 1 : 0;
		int batScore = batKilled ? 1 : 0;
		return paulScore + cryptsScore + mimicScore + princeScore + batScore;
	}

	private static int countIncompletePuzzles(List<String> tabList, int puzzleCount) {
		if (puzzleCount <= 0) return 0;
		int count = 0;
		for (String line : tabList) {
			Matcher m = PUZZLE_LINE.matcher(line);
			if (!m.matches()) continue;
			String glyph = m.group(1);
			if (PUZZLE_FAIL_GLYPHS.indexOf(glyph) >= 0) count++;
		}
		return count;
	}

	/** "F6"/"M6" pass through as-is; "Entrance" (or a bare "E", in case that turns out to be the real format) maps to the ENTRANCE bucket. */
	private static String normalizeFloorKey(String floor) {
		if (floor == null) return null;
		String upper = floor.toUpperCase(java.util.Locale.ROOT);
		if (upper.equals("ENTRANCE") || upper.equals("E")) return "ENTRANCE";
		return upper;
	}

	private static int firstIntMatch(List<String> lines, Pattern pattern, int fallback) {
		for (String line : lines) {
			Matcher m = pattern.matcher(line);
			if (m.find()) return Integer.parseInt(m.group(1));
		}
		return fallback;
	}

	private static double firstDoubleMatch(List<String> lines, Pattern pattern, double fallback) {
		for (String line : lines) {
			Matcher m = pattern.matcher(line);
			if (m.find()) return Double.parseDouble(m.group(1));
		}
		return fallback;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
