package com.scd.client;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live dungeon score estimate - a faithful port of Skyblocker's real `DungeonScore.java`
 * (LGPL-3.0, fetched raw and read directly rather than summarized, see FEATURE_ROADMAP.md §3's
 * 2026-09-23 research pass), not the SkyBlock Wiki's own simplified formula text, which turned
 * out to itself be an approximation of the real, more nuanced logic below. Cross-checked
 * 2026-09-23 against Odin's real source (`DungeonUtils.kt`, also fetched raw) after a live
 * side-by-side comparison disagreed - Odin's independently-written formula agrees with
 * Skyblocker's on every point that matters, which is what caught two real mistakes below.
 *
 * Reads three kinds of live data, none of it live-confirmed yet for the tab-list half:
 * - Sidebar scoreboard (ScdDungeonManager): floor, clear %, time elapsed - the same mechanism
 *   already confirmed live for Slayer/dungeon detection.
 * - Tab list (ScdDungeonManager.readTabList(), new as of this class): Completed Rooms, Secrets
 *   Found %, Crypts, Puzzle count + per-puzzle pass/fail. NOT yet read by this project before -
 *   genuinely unverified, same "ship the best real-mod-derived guess, confirm live, correct if
 *   wrong" posture as every other new pattern in this project.
 * - Chat (fed by ScdChatPacketMixin, same raw-before-any-other-mod pipeline as
 *   ScdDungeonCompletion): deaths, mimic/prince/bat kills, and now blood-door-opened. Also
 *   unverified except blood-door - this project has never actually seen a real player-death
 *   message during a dungeon run yet.
 *
 * **Correction 2026-09-23**: the previous version here treated only the "✖" glyph as a puzzle
 * penalty, having removed "✦" (the undiscovered-puzzle glyph) on the theory that "not yet
 * reached" shouldn't count as "failed." That reasoning doesn't hold up: Skyblocker's own,
 * unmodified pattern counts BOTH glyphs (its variable is literally named `incompletePuzzles`,
 * not `failedPuzzles`), and Odin's independent formula agrees exactly -
 * `(puzzleCount - completedCount) * 10`, no distinction between "pending" and "actively failed."
 * A live, in-progress score treating every not-yet-solved puzzle as a temporary penalty (that
 * clears the moment it's actually solved) is the correct behavior, not a bug - reverted.
 *
 * **Also added**: Odin's "virtual completed rooms" - it pads the room-completion count with +1
 * while not yet in the boss room and +1 while the blood room hasn't been WON yet, compensating
 * for Hypixel's own Completed Rooms counter lagging reality by up to 2 rooms right at the end.
 * Confirmed by hand-computing Odin's exact formula against this project's own raw tab-list/
 * scoreboard reads from a live side-by-side run and getting Odin's displayed score exactly
 * (161) - this padding, plus reverting the puzzle-penalty mistake above, accounts for the whole
 * gap that prompted this correction. "Not yet in boss room" is approximated here as always true
 * (no real boss-room detection exists yet - that needs the room-ID system, still not built) -
 * only wrong for the last stretch of a floor, not the run overall.
 *
 * **Correction 2026-09-23, round two**: the blood-room trigger was wired to the wrong message.
 * "The BLOOD DOOR has been opened!" only marks the START of the Watcher's trial, not its
 * completion - a live side-by-side comparison caught this directly: our score DROPPED the
 * instant that message fired (the padding was removed immediately), while Odin's score actually
 * ROSE once the trial was won (its real Completed Rooms count caught up by more than the padding
 * it lost). Fixed to key off the trial's real completion message instead - Skyblocker's own real
 * source uses the identical trigger (`checkMessageForWatcher`): `"[BOSS] The Watcher: You have
 * proven yourself. You may pass."` - a message this project had already captured live once
 * before, just not connected to this.
 *
 * **Deliberately NOT copied from Odin**: it never computes a real time-based Speed score at all -
 * `updateScore()` just adds a flat +100, assuming the team is always within budget. This project's
 * own Speed calculation (Skyblocker's real tiered decay) is kept instead, since matching true
 * Hypixel behavior matters more than matching another mod's simplification - the two will
 * intentionally diverge once a run goes over its time budget, and that's correct, not a bug.
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
	// undiscovered/not-yet-reached puzzle reads `"???: [✦]"`. BOTH glyphs count toward the
	// penalty (see the class doc's 2026-09-23 correction) - "✦" = not yet reached/solved, "✖" =
	// actively failed (still unconfirmed live, this project hasn't seen one yet), neither is
	// "done" so both count against the live estimate until actually completed.
	private static final Pattern PUZZLE_LINE = Pattern.compile(".+?: \\[(.)]");
	private static final String PUZZLE_INCOMPLETE_GLYPHS = "✖✦";

	// A real player-death message during a run - NOT the end-of-run "Defeated {boss}" report
	// (ScdDungeonCompletion handles that separately). Unconfirmed live: this project has never
	// captured an actual mid-run death message, so this pattern is Skyblocker's own shape, not a
	// verified one of ours.
	private static final Pattern DEATH_LINE = Pattern.compile("^☠ \\S+ .*");
	private static final Pattern MIMIC_LINE = Pattern.compile(".*?(?:Mimic dead!?|Mimic Killed!)$");
	private static final Pattern PRINCE_LINE = Pattern.compile(".*?(?:Prince dead!?|Prince Killed!)$|^A Prince falls\\. \\+1 Bonus Score$");
	private static final Pattern BAT_LINE = Pattern.compile(".*?(?:Bat dead!?|Bat Killed!)$|^A Bat has been slain\\. \\+1 Bonus Score$");
	// Corrected 2026-09-23 (see class doc's "blood door" correction): the trigger for Odin's
	// "virtual completed rooms" padding is the WATCHER TRIAL's completion, not the door opening -
	// opening it just starts the trial. Matches Skyblocker's own real source exactly
	// (`checkMessageForWatcher`, `message.equals("[BOSS] The Watcher: You have proven yourself.
	// You may pass.")`) - confirmed live 2026-09-22 as a genuine message this project has actually
	// seen (captured during the F6/Sadan runs), just wired to the wrong trigger until now.
	private static final Pattern BLOOD_ROOM_COMPLETE_LINE = Pattern.compile("^\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.$");

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
	private static boolean bloodRoomCompleted = false;

	private ScdDungeonScore() {
	}

	/**
	 * Resets every per-run counter (deaths, mimic/prince/bat, blood room) immediately on a real
	 * dungeon completion - called from ScdClient.creditDungeonCarries. Added 2026-09-23 as a
	 * second, more reliable reset trigger alongside computeOrNull()'s own wasInDungeon
	 * false-to-true check: confirmed live that quick re-queuing ("Click HERE to re-queue") doesn't
	 * reliably leave the dungeon area long enough for that transition to fire, which let
	 * bloodRoomCompleted (true from a floor that genuinely had one) bleed into the next run and
	 * under-penalize its Skill/Explore score by skipping the "blood room not done yet" padding it
	 * should have gotten. A real run-completion firing is an unambiguous "whatever's next is a new
	 * run" signal regardless of whether the area-transition check also caught it.
	 */
	public static void resetRunState() {
		deaths = 0;
		mimicKilled = false;
		princeKilled = false;
		batKilled = false;
		bloodRoomCompleted = false;
	}

	public record ScoreBreakdown(
			int total,
			int skill,
			int explore,
			int speed,
			int bonus,
			boolean isEntrance,
			int completedRooms,
			int paddedCompletedRooms,
			int totalRoomsEstimate,
			double secretsPercent,
			int crypts,
			int deaths,
			int incompletePuzzles,
			boolean bloodRoomCompleted) {
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
		if (BLOOD_ROOM_COMPLETE_LINE.matcher(text).matches()) bloodRoomCompleted = true;
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
			bloodRoomCompleted = false;
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

		// Odin's "virtual completed rooms" compensation (see class doc) - Hypixel's own Completed
		// Rooms counter lags reality by up to 2 rooms right at the end (blood room + boss room
		// don't increment it immediately). "Not yet in boss" is approximated as always true - no
		// real boss-room detection exists yet, so this stays +1 for the whole run except the very
		// last stretch, which is the same limitation Odin's own unconditional check has. The blood
		// term only drops once the WATCHER TRIAL is actually won (bloodRoomCompleted), not when the
		// door is merely opened - see BLOOD_ROOM_COMPLETE_LINE's own comment for the 2026-09-23 fix.
		int paddedCompletedRooms = completedRooms + 1 + (bloodRoomCompleted ? 0 : 1);

		int skill = calculateSkill(paddedCompletedRooms, totalRooms, incompletePuzzles, deaths);
		int explore = calculateExplore(paddedCompletedRooms, totalRooms, secretsPercent, requirement.secretPercent());
		int speed = calculateSpeed(state.timeElapsedSeconds(), requirement.timeLimitSeconds());
		int bonus = calculateBonus(crypts, secretsPercent, mayorPerks.hasPerkNamed("EZPZ"));

		int total = isEntrance
				? Math.round(speed * 0.7f) + Math.round(explore * 0.7f) + Math.round(skill * 0.7f) + Math.round(bonus * 0.7f)
				: speed + explore + skill + bonus;

		return new ScoreBreakdown(total, skill, explore, speed, bonus, isEntrance,
				completedRooms, paddedCompletedRooms, totalRooms, secretsPercent, crypts, deaths, incompletePuzzles, bloodRoomCompleted);
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
			if (PUZZLE_INCOMPLETE_GLYPHS.indexOf(glyph) >= 0) count++;
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
