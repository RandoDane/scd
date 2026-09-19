package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the SkyBlock sidebar scoreboard to find the player's active Slayer
 * quest and whether its boss has spawned yet. SkyBlock itself drives this
 * off the scoreboard, so it's available from the moment a quest starts -
 * long before any boss entity exists - unlike scanning for a boss mob, which
 * only ever works after one has spawned.
 */
public final class ScdSlayerScoreboard {
	private static final Pattern TIER_SUFFIX = Pattern.compile("(V|IV|III|II|I)\\s*$");
	private static final String BOSS_SPAWNED_LINE = "Slay the boss!";

	private ScdSlayerScoreboard() {
	}

	public static ScdSlayerQuest read() {
		var mc = Minecraft.getInstance();
		if (mc.level == null) return null;

		Scoreboard scoreboard = mc.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return null;

		ScdSlayerType type = null;
		String tier = null;
		boolean bossSpawned = false;

		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			String line = resolveLine(scoreboard, entry);
			if (line.isEmpty()) continue;

			if (line.contains(BOSS_SPAWNED_LINE)) {
				bossSpawned = true;
				continue;
			}

			ScdSlayerType matched = ScdSlayerType.fromBossName(line);
			if (matched != null) {
				type = matched;
				Matcher m = TIER_SUFFIX.matcher(line);
				tier = m.find() ? m.group(1) : null;
			}
		}

		return type != null ? new ScdSlayerQuest(type, tier, bossSpawned) : null;
	}

	/**
	 * True if the sidebar currently shows an area line matching one of ScdSlayerAreaAllowlist's known
	 * names for this type (or if that type has no location restriction at all). Hypixel's own area
	 * line shows a specific sub-location ("Void Sepulture"), not a broader island name - the same
	 * sidebar the quest/tier detection above already reads, just checked against area names here
	 * instead of boss names.
	 */
	public static boolean isInAllowedArea(ScdSlayerType type) {
		if (!ScdSlayerAreaAllowlist.isRestricted(type)) return true;

		var mc = Minecraft.getInstance();
		if (mc.level == null) return false;

		Scoreboard scoreboard = mc.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return false;

		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			if (ScdSlayerAreaAllowlist.matches(type, resolveLine(scoreboard, entry))) return true;
		}
		return false;
	}

	/**
	 * PlayerScoreEntry.ownerName() is just a literal wrap of the raw score holder name, with no
	 * team-prefix/suffix resolution applied. Hypixel's sidebar lines are the classic vanilla trick:
	 * each line's "owner" is a unique, invisible (pure color-code) fake player name, and the actual
	 * visible text is applied via that player's PlayerTeam prefix + suffix - so ownerName() alone
	 * always comes back empty even when the sidebar is fully populated.
	 */
	// Some heavily-modded clients (not Hypixel itself) inject bogus, non-standard "formatting" codes
	// (section sign + a letter that isn't any real Minecraft color/format code) at random points
	// INSIDE words on the scoreboard - likely an anti-scraping feature of a market/anti-scam mod,
	// since the vanilla renderer just skips anything after a section sign so it still looks fine on
	// screen while getString() returns it uncleaned. Stripping every section-sign-plus-one-character
	// pair before matching neutralizes it regardless of the source.
	private static final Pattern FORMATTING_CODE = Pattern.compile("\u00A7.");
	// The area line is prefixed with U+E067, a Private Use Area codepoint - not whitespace, but a
	// custom icon glyph rendered via Hypixel's own resource-pack font (a "location pin"-style icon
	// before the area name). Stripped as a whole range since a PUA icon-glyph prefix is common across
	// Hypixel's UI generally, not just this one line.
	private static final Pattern PRIVATE_USE_AREA = Pattern.compile("[\uE000-\uF8FF]");

	private static String resolveLine(Scoreboard scoreboard, PlayerScoreEntry entry) {
		String rawOwner = entry.owner();
		var team = scoreboard.getPlayersTeam(rawOwner);
		Component text = team != null ? team.getFormattedName(Component.literal(rawOwner)) : entry.ownerName();
		String clean = FORMATTING_CODE.matcher(text.getString()).replaceAll("");
		clean = PRIVATE_USE_AREA.matcher(clean).replaceAll("");
		// Hypixel also pads some lines with U+00A0 (non-breaking space) rather than a real space,
		// which .trim() deliberately does NOT strip (only codepoints <= U+0020, and NBSP is U+00A0) -
		// converted to a real space first so exact-match checks (area names, etc.) aren't broken by
		// invisible padding.
		return clean.replace('\u00A0', ' ').trim();
	}

	/**
	 * Diagnostic for "quest detection just doesn't fire" reports: dumps the sidebar objective's
	 * presence and every raw line actually read, to tell a genuinely-empty/absent scoreboard
	 * (another mod replacing or hiding it) apart from a parsing bug.
	 */
	public static List<String> describeRaw() {
		List<String> lines = new ArrayList<>();
		var mc = Minecraft.getInstance();
		if (mc.level == null) {
			lines.add("no level loaded");
			return lines;
		}

		Scoreboard scoreboard = mc.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) {
			lines.add("no sidebar objective set (getDisplayObjective(SIDEBAR) is null) - another mod may be clearing/replacing it");
			return lines;
		}

		var entries = scoreboard.listPlayerScores(objective);
		lines.add("sidebar objective present, " + entries.size() + " score entries:");
		if (entries.isEmpty()) {
			lines.add("(no entries - sidebar is showing but empty)");
		}
		for (PlayerScoreEntry entry : entries) {
			String line = resolveLine(scoreboard, entry);
			boolean matchesBoss = ScdSlayerType.fromBossName(line) != null;
			ScdSlayerType matchesArea = null;
			for (ScdSlayerType type : ScdSlayerType.values()) {
				if (ScdSlayerAreaAllowlist.matches(type, line)) {
					matchesArea = type;
					break;
				}
			}
			String suffix = matchesBoss ? "  <- matches a boss name" : matchesArea != null ? "  <- matches a " + matchesArea.displayName() + " area name" : "";
			lines.add("\"" + line + "\"" + suffix);
			// Shown for every line, not just ones that already look boss-related - an area-name
			// mismatch is often a non-breaking space or other invisible character standing in for a
			// normal one, which only shows up once the exact codepoints are printed.
			lines.add("    char codes: " + charCodes(line));
		}
		return lines;
	}

	private static String charCodes(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (i > 0) sb.append(' ');
			if (c >= 0x20 && c < 0x7F) sb.append(c);
			else sb.append(String.format(java.util.Locale.ROOT, "[U+%04X]", (int) c));
		}
		return sb.toString();
	}
}
