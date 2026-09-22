package com.scd.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Hypixel's real, raw dungeon-run-completion report out of the SYSTEM chat packets
 * ScdChatPacketMixin forwards - built 2026-09-22 once two real captures (see
 * FEATURE_ROADMAP.md §3) confirmed the exact wording and structure. Hypixel actually sends this
 * as TWO separate bordered multi-line blocks in quick succession right when the floor's boss
 * dies - a short one ("EXTRA STATS": floor/score/boss/time/Cata+class EXP) immediately, then a
 * fuller one ("Floor {roman} Stats": the same floor/score/boss/time plus Total Damage/Ally
 * Healing/Enemies Killed/Deaths/Secrets Found) about a second later. Both are bounded by a line of
 * repeated "▬" characters at start and end - this class buffers lines between borders and parses
 * whatever block just closed, firing once per block, so a consumer sees two events per real
 * completion - the second strictly richer than the first. Deliberate, not deduped: the first is
 * the earliest possible signal (matters for carry-tracking, §20), and a consumer that only wants
 * the full picture can just wait for/prefer whichever event has the extra fields populated.
 *
 * This is what other installed mods (Odin/Skyblocker/etc, per the account owner) intercept and
 * reformat into their own single combined chat message - this class reads Hypixel's real,
 * pre-reformatting text instead, via ScdChatPacketMixin injecting before any of that can happen.
 * Confirmed live against two real F6/Sadan completions with matching field values both times -
 * not yet confirmed against every floor/boss or Master Mode, so an unfamiliar boss name or a
 * missing field elsewhere in the run is expected, not a bug, until more of those are captured.
 */
public final class ScdDungeonCompletion {
	private static final Pattern BORDER = Pattern.compile("^▬+$");
	private static final Pattern FLOOR_LINE = Pattern.compile("Catacombs\\s*-\\s*Floor\\s+([IVXLCDM]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SCORE_LINE = Pattern.compile("Team Score:\\s*(\\d+)\\s*\\(([^)]+)\\)");
	private static final Pattern DEFEATED_LINE = Pattern.compile("Defeated\\s+(.+?)\\s+in\\s+(\\d+m\\s*\\d+s)");
	private static final Pattern CATA_EXP_LINE = Pattern.compile("\\+([\\d,.]+)\\s+Catacombs Experience");
	private static final Pattern CLASS_EXP_LINE = Pattern.compile("\\+([\\d,.]+)\\s+(\\w+)\\s+Experience");
	private static final Pattern DAMAGE_LINE = Pattern.compile("Total Damage as (\\w+):\\s*([\\d,]+)");
	private static final Pattern HEALING_LINE = Pattern.compile("Ally Healing:\\s*([\\d,]+)");
	private static final Pattern KILLS_LINE = Pattern.compile("Enemies Killed:\\s*([\\d,]+)");
	private static final Pattern DEATHS_LINE = Pattern.compile("Deaths:\\s*(\\d+)");
	private static final Pattern SECRETS_LINE = Pattern.compile("Secrets Found:\\s*(\\d+)");
	private static final Pattern FORMATTING_CODE = Pattern.compile("§.");
	// SkyHanni's own confirmed pattern comment for Master Mode's floor line ("Master Mode The
	// Catacombs - Floor V") shows the prefix comes before "Catacombs" on the same line - checked
	// separately from FLOOR_LINE above since it needs to search the whole line, not just capture
	// the numeral. Not yet confirmed against a real Master Mode completion by this project itself.
	private static final Pattern MASTER_MODE_MARKER = Pattern.compile("Master Mode", Pattern.CASE_INSENSITIVE);
	private static final Map<String, Integer> ROMAN_TO_ARABIC = Map.of(
			"I", 1, "II", 2, "III", 3, "IV", 4, "V", 5, "VI", 6, "VII", 7);

	private static boolean insideBlock = false;
	private static final List<String> buffer = new ArrayList<>();
	private static volatile Consumer<CompletionReport> listener;

	private ScdDungeonCompletion() {
	}

	public static void setListener(Consumer<CompletionReport> newListener) {
		listener = newListener;
	}

	public record CompletionReport(
			String floor,
			String floorKey,
			boolean masterMode,
			Integer teamScore,
			String scoreRank,
			String boss,
			String clearTime,
			Double cataExp,
			String classExpClass,
			Double classExp,
			String damageClass,
			Long totalDamage,
			Long allyHealing,
			Long enemiesKilled,
			Integer deaths,
			Integer secretsFound) {
	}

	/** Fed every SYSTEM-channel message (raw, pre-any-other-mod) by ScdChatPacketMixin, unconditionally - this is a real feature, not gated behind the debug capture toggle. */
	public static void onSystemMessage(String rawText) {
		if (rawText == null) return;
		String text = FORMATTING_CODE.matcher(rawText).replaceAll("");
		String trimmed = text.trim();

		if (BORDER.matcher(trimmed).matches()) {
			if (insideBlock) {
				CompletionReport report = parseBlock(buffer);
				buffer.clear();
				insideBlock = false;
				if (report != null) {
					Consumer<CompletionReport> l = listener;
					if (l != null) {
						try {
							l.accept(report);
						} catch (Throwable t) {
							ScdLog.error("dungeon completion listener threw", t);
						}
					}
				}
			} else {
				insideBlock = true;
				buffer.clear();
			}
			return;
		}

		if (insideBlock) {
			buffer.add(text);
		}
	}

	private static CompletionReport parseBlock(List<String> lines) {
		String floor = null;
		boolean masterMode = false;
		Integer teamScore = null;
		String scoreRank = null;
		String boss = null;
		String clearTime = null;
		Double cataExp = null;
		String classExpClass = null;
		Double classExp = null;
		String damageClass = null;
		Long totalDamage = null;
		Long allyHealing = null;
		Long enemiesKilled = null;
		Integer deaths = null;
		Integer secretsFound = null;

		for (String line : lines) {
			Matcher m;
			if ((m = FLOOR_LINE.matcher(line)).find()) {
				floor = m.group(1);
				if (MASTER_MODE_MARKER.matcher(line).find()) masterMode = true;
			}
			if ((m = SCORE_LINE.matcher(line)).find()) {
				teamScore = parseInt(m.group(1));
				scoreRank = m.group(2);
			}
			if ((m = DEFEATED_LINE.matcher(line)).find()) {
				boss = m.group(1).trim();
				clearTime = m.group(2);
			}
			// Checked in this order deliberately: CLASS_EXP_LINE's generic \w+ would also match a
			// "Catacombs Experience" line, so CATA_EXP_LINE must be tried first and skip the
			// generic one on a hit, or cataExp would get misfiled as a fake "Catacombs" class gain.
			if ((m = CATA_EXP_LINE.matcher(line)).find()) {
				cataExp = parseDouble(m.group(1));
			} else if ((m = CLASS_EXP_LINE.matcher(line)).find()) {
				classExp = parseDouble(m.group(1));
				classExpClass = m.group(2);
			}
			if ((m = DAMAGE_LINE.matcher(line)).find()) {
				damageClass = m.group(1);
				totalDamage = parseLong(m.group(2));
			}
			if ((m = HEALING_LINE.matcher(line)).find()) allyHealing = parseLong(m.group(1));
			if ((m = KILLS_LINE.matcher(line)).find()) enemiesKilled = parseLong(m.group(1));
			if ((m = DEATHS_LINE.matcher(line)).find()) deaths = parseInt(m.group(1));
			if ((m = SECRETS_LINE.matcher(line)).find()) secretsFound = parseInt(m.group(1));
		}

		// Only a real completion block if it at least named a defeated boss - guards against ever
		// firing a false event off some unrelated pair of border lines, if any other feature (ours
		// or another mod's) ever also happens to use them.
		if (boss == null) return null;

		String floorKey = normalizeFloorKey(floor, masterMode);

		return new CompletionReport(floor, floorKey, masterMode, teamScore, scoreRank, boss, clearTime, cataExp,
				classExpClass, classExp, damageClass, totalDamage, allyHealing, enemiesKilled, deaths, secretsFound);
	}

	/** Roman numeral ("VI") + Master Mode flag -> this project's canonical floor key ("F6"/"M6"), the same format ScdDungeonManager/ScdDungeonScore already use - lets a carry entry match a completion report without every consumer re-deriving this conversion itself. */
	private static String normalizeFloorKey(String romanFloor, boolean masterMode) {
		if (romanFloor == null) return null;
		Integer arabic = ROMAN_TO_ARABIC.get(romanFloor.toUpperCase(Locale.ROOT));
		if (arabic == null) return null;
		return (masterMode ? "M" : "F") + arabic;
	}

	private static Integer parseInt(String s) {
		try {
			return Integer.parseInt(s.replace(",", ""));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Long parseLong(String s) {
		try {
			return Long.parseLong(s.replace(",", ""));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Double parseDouble(String s) {
		try {
			return Double.parseDouble(s.replace(",", ""));
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
