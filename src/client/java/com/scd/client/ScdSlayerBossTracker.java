package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the player's active Slayer quest and, once its boss has spawned,
 * the actual boss entity and fight duration.
 *
 * Quest/spawn state comes from the sidebar scoreboard (ScdSlayerScoreboard),
 * which is authoritative and available before any boss entity exists. Once
 * the scoreboard says the boss has spawned, the entity itself is found one
 * of two ways: a few bosses carry their own name directly on the mob, but
 * Hypixel puts most bosses' nameplate on a SEPARATE armor stand next to the
 * (unnamed) mob entity - so if the direct match fails, we look for a nearby
 * armor stand matching the boss name and correlate it to the closest living
 * mob near it.
 */
public class ScdSlayerBossTracker {
	private static final double ENTITY_SCAN_RADIUS = 30.0;
	// Hypixel nameplates commonly show "<name> 0<heart>" once a mob is dead - filter those out so a
	// lingering corpse armor stand doesn't get re-matched as the boss for a tick before it despawns.
	private static final String DEAD_SUFFIX = "0❤";

	// Once cocooned, the boss respawns at full HP after this long (The Primordial accessory's effect).
	private static final long COCOON_RESPAWN_MS = 6_000;

	// How long a one-shot "HP just crossed a mechanic's trigger point" alert stays flagged as active.
	private static final long HP_CROSSING_ALERT_WINDOW_MS = 6_000;

	// How long without hitting anything before the hunt-phase timer pauses - keeps AFK/away time
	// before the boss spawns from inflating the recorded "time to spawn boss" stat. Only applies to
	// the hunting phase, not the boss fight itself.
	private static final long HUNT_PAUSE_THRESHOLD_MS = 5_000;

	// How long the "Killed" status badge stays showing next to a type in the config screen's
	// per-Slayer list after its fight ends, before that type just goes back to showing nothing.
	private static final long KILLED_DISPLAY_WINDOW_MS = 5_000;

	// Separate, much longer window used only for drop attribution (not the UI flash above) - the kill
	// itself ends the quest instantly, but a boss's own drop isn't always delivered instantly the way
	// Telekinesis delivers common loot, so this needs to comfortably cover walking back for a drop
	// that landed a short distance away.
	private static final long LOOT_ATTRIBUTION_WINDOW_MS = 20_000;

	// Every boss nameplate stack has its own "Spawned by: <IGN>" line sitting just above the name/HP
	// line - the authoritative way to tell your own boss apart from another nearby player's, since
	// on a shared Slayer island several players can have an identically-named boss up at once. Reuses
	// the same "strip any section-sign formatting code" trick as the scoreboard fix, since a garbled
	// or colored line would otherwise fail a plain string match.
	private static final Pattern FORMATTING_CODE = Pattern.compile("§.");
	private static final Pattern SPAWNED_BY = Pattern.compile("Spawned by:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	// How close a candidate boss entity must be to its own "Spawned by" stand to count as the same
	// nameplate stack - generous enough for any stacking offset Hypixel uses, tight enough to never
	// reach across to a different player's boss standing a few blocks away.
	private static final double OWNERSHIP_STACK_RADIUS = 5.0;

	/** Fired off scoreboard/state transitions (not raw entity sightings), so a momentarily-lost entity never causes a duplicate or missed event. */
	public interface Listener {
		void onBossSpawned(ScdSlayerQuest quest);

		void onBossFightEnded(ScdSlayerQuest quest, long elapsedMs);

		void onMinibossSpawned(ScdSlayerMinibosses.Entry miniboss);

		/** Fired the instant the boss spawns, with how long the hunting phase (quest accepted to boss up) took, idle time excluded. */
		void onHuntCompleted(ScdSlayerQuest quest, long huntElapsedMs);
	}

	private ScdSlayerQuest quest;
	private LivingEntity boss;
	private long fightStartMs;
	private long cocoonRespawnAtMs;
	// Highest HP reading seen since the current boss was acquired - stands in for "max" when the
	// nameplate only ever shows a single current-HP number rather than a current/max pair, since a
	// boss's HP only counts down over a fight (short of the rare Wolf regen/Call-the-Pups window).
	private double maxHpSeen;
	private Float lastHpFrac;
	private boolean lastNameWasConjoinedBrood;
	private ScdSlayerType lastEndedType;
	// Unlike `quest`, never cleared just because the current type's area check fails - lets the
	// session stats HUD tell "left the designated area for a restricted type" apart from "never
	// tracked anything this session" without needing its own type-keyed bookkeeping.
	private ScdSlayerType lastActiveType;
	private long killedDisplayUntilMs;
	private long lootAttributionUntilMs;
	// Hunting-phase timing: quest accepted to boss spawned, paused whenever nothing nearby has taken
	// damage for HUNT_PAUSE_THRESHOLD_MS. Tracked separately from the boss fight itself.
	private long huntStartMs;
	private long huntLastHitAtMs;
	private long huntPauseStartedAtMs;
	private long huntTotalPausedMs;
	private final Map<Integer, Float> huntTrackedHealth = new HashMap<>();
	private final Map<String, Long> alertUntilMs = new HashMap<>();
	private final Set<UUID> knownMinibossIds = new HashSet<>();
	private Listener listener;

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	/** Called by ScdClient when it sees the "YOU COCOONED YOUR SLAYER BOSS" chat line. */
	public void notifyCocoonTriggered() {
		cocoonRespawnAtMs = System.currentTimeMillis() + COCOON_RESPAWN_MS;
	}

	public long cocoonRemainingMs() {
		return Math.max(0, cocoonRespawnAtMs - System.currentTimeMillis());
	}

	public boolean isCocoonActive() {
		return cocoonRemainingMs() > 0;
	}

	public void tick() {
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			quest = null;
			boss = null;
			knownMinibossIds.clear();
			return;
		}

		ScdSlayerQuest previousQuest = quest;
		quest = ScdSlayerScoreboard.read();
		// A quest can be sitting accepted-but-untouched on the scoreboard from anywhere (SkyBlock
		// quests aren't tied to location) - Enderman/Blaze/Spider are the three whose actual host mob
		// only exists in specific areas, so their entire HUD/ability/drop pipeline should stay dormant
		// everywhere else, exactly as if no quest were active at all. Suppressing it once here, at the
		// source, cascades correctly to every downstream consumer (boss tracker HUD, ability warnings,
		// the config screen's status badges, drop attribution, the Explosive Arrow counter) without
		// needing to repeat this check in each of them individually.
		if (quest != null && !ScdSlayerScoreboard.isInAllowedArea(quest.type())) {
			quest = null;
		}
		if (quest != null) lastActiveType = quest.type();

		if (previousQuest == null && quest != null) {
			// Hunt-phase clock starts the moment the quest itself becomes active on the scoreboard,
			// well before any boss entity exists to fight.
			huntStartMs = System.currentTimeMillis();
			huntLastHitAtMs = huntStartMs;
			huntPauseStartedAtMs = 0;
			huntTotalPausedMs = 0;
			huntTrackedHealth.clear();
		}

		boolean spawnedBefore = previousQuest != null && previousQuest.bossSpawned();
		boolean spawnedNow = quest != null && quest.bossSpawned();

		if (spawnedNow && !spawnedBefore) {
			// The fight-timer/max-HP reset lives here, tied to the scoreboard's own "boss spawned"
			// transition - not to whenever our entity reference happens to become non-null again
			// (see below), since a single missed entity scan could otherwise spuriously restart the
			// timer mid-fight even though the same boss is still up.
			fightStartMs = System.currentTimeMillis();
			maxHpSeen = 0;
			lastHpFrac = null;
			lastNameWasConjoinedBrood = false;
			alertUntilMs.clear();
			if (listener != null) {
				listener.onHuntCompleted(quest, huntElapsedMs());
				listener.onBossSpawned(quest);
			}
			huntTrackedHealth.clear();
		}
		if (spawnedBefore && !spawnedNow) {
			lastEndedType = previousQuest.type();
			killedDisplayUntilMs = System.currentTimeMillis() + KILLED_DISPLAY_WINDOW_MS;
			lootAttributionUntilMs = System.currentTimeMillis() + LOOT_ATTRIBUTION_WINDOW_MS;
			if (listener != null) listener.onBossFightEnded(previousQuest, fightElapsedMs());
		}

		if (quest != null) {
			scanForMinibosses(mc, quest.type());
		} else {
			knownMinibossIds.clear();
		}

		if (quest != null && !spawnedNow) {
			updateHuntPauseTracking(mc);
		}

		if (!spawnedNow) {
			boss = null;
			return;
		}

		// Re-find the boss entity fresh every tick instead of just holding onto whatever we found once:
		// some Hypixel HP-display armor stands get recreated (a new entity, not the same one mutated in
		// place) each time their HP text updates, which would otherwise leave us reading a frozen, stale
		// copy of the nameplate. A single missed scan (one frame of render-list flicker) doesn't drop
		// the last-known reference, only a confirmed-dead/far one does.
		LivingEntity found = findBoss(mc, quest.type());
		if (found != null) {
			boss = found;
		} else if (boss != null && (!boss.isAlive() || boss.distanceTo(mc.player) > ENTITY_SCAN_RADIUS * 2)) {
			boss = null;
		}

		if (boss != null) {
			var reading = currentHpReading();
			if (reading != null) {
				maxHpSeen = Math.max(maxHpSeen, reading.max() != null ? reading.max() : reading.current());
			}

			String name = boss.getCustomName() != null ? boss.getCustomName().getString() : "";
			boolean isConjoinedBrood = name.contains("Conjoined Brood");
			if (isConjoinedBrood && !lastNameWasConjoinedBrood) {
				markAlert("spider_conjoined_transition");
			}
			lastNameWasConjoinedBrood = isConjoinedBrood;

			Float hpFrac = currentHpFracOrNull();
			if (hpFrac != null) {
				if (lastHpFrac != null) {
					checkCrossing("wolf_pups", 0.5f, hpFrac);
					checkCrossing("spider_egg_66", 0.66f, hpFrac);
					checkCrossing("spider_egg_33", 0.33f, hpFrac);
					checkCrossing("vamp_mania_50", 0.50f, hpFrac);
					checkCrossing("vamp_mania_75", 0.75f, hpFrac);
					checkCrossing("vamp_mania_40", 0.40f, hpFrac);
					checkCrossing("ender_beam_5_6", 5f / 6f, hpFrac);
					checkCrossing("ender_beam_1_2", 0.5f, hpFrac);
					checkCrossing("ender_beam_1_6", 1f / 6f, hpFrac);

				}
				lastHpFrac = hpFrac;
			}
		}
	}

	/** True if hpFrac just dropped below `threshold` compared to the previous tick's reading. */
	private void checkCrossing(String alertId, float threshold, float hpFrac) {
		if (lastHpFrac > threshold && hpFrac <= threshold) {
			markAlert(alertId);
		}
	}

	private void markAlert(String alertId) {
		alertUntilMs.put(alertId, System.currentTimeMillis() + HP_CROSSING_ALERT_WINDOW_MS);
	}

	/** True for a few seconds right after the named one-shot event (an HP-threshold crossing, or the Spider T5 Conjoined Brood transition) happened. */
	public boolean isAlertActive(String alertId) {
		Long until = alertUntilMs.get(alertId);
		return until != null && System.currentTimeMillis() < until;
	}

	/** Current HP as a 0-1 fraction of the best max estimate we have, or null if either is unavailable. */
	public Float currentHpFracOrNull() {
		Double current = currentHpOrNull();
		Double max = maxHpOrNull();
		if (current == null || max == null || max <= 0) return null;
		return (float) Math.max(0, Math.min(1, current / max));
	}

	/** See ScdSlayerShieldReading - parses the Voidgloom Seraph's Malevolent Hitshield state off its own nameplate, same as HP. */
	public ScdSlayerShieldReading.Reading shieldReadingOrNull() {
		if (boss == null || !boss.hasCustomName() || boss.getCustomName() == null) return null;
		return ScdSlayerShieldReading.parse(boss.getCustomName().getString());
	}

	/** Scans for any known miniboss nameplate matching the active quest's type, firing the listener once per newly-seen entity. */
	private void scanForMinibosses(Minecraft mc, ScdSlayerType type) {
		Set<UUID> stillAlive = new HashSet<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand || !living.isAlive()) continue;
			if (living.distanceTo(mc.player) > ENTITY_SCAN_RADIUS) continue;
			if (!living.hasCustomName() || living.getCustomName() == null) continue;

			String name = living.getCustomName().getString();
			if (name.contains(DEAD_SUFFIX)) continue;
			ScdSlayerMinibosses.Entry match = ScdSlayerMinibosses.match(name);
			if (match == null || match.type() != type) continue;

			stillAlive.add(living.getUUID());
			if (!knownMinibossIds.contains(living.getUUID()) && listener != null) {
				listener.onMinibossSpawned(match);
			}
		}
		knownMinibossIds.retainAll(stillAlive);
		knownMinibossIds.addAll(stillAlive);
	}

	/**
	 * Some bosses carry their own name directly on the mob entity; most have their nameplate on a
	 * separate armor stand next to the (unnamed) mob instead. Either way, we only need whichever
	 * entity actually carries the matching nameplate text - that's our sole source for both the name
	 * and (via ScdSlayerHealthReading) the live HP.
	 *
	 * On a shared Slayer island, other players fighting the same type spawn their own,
	 * identically-named boss entities, so this checks three signals in order: (1) a nearby
	 * "Spawned by: <our IGN>" nameplate, which positively identifies our own boss regardless of
	 * distance; (2) the entity we're already tracking, as long as it's still a live match, so a
	 * stranger's boss can't take over just because ours briefly looked "gone" (e.g. mid-teleport); and
	 * (3) if we need to pick a fresh one, the closest match, since the boss you're fighting is almost
	 * always the nearest one to you.
	 */
	private LivingEntity findBoss(Minecraft mc, ScdSlayerType type) {
		LivingEntity owned = findBossViaOwnershipTag(mc, type);
		if (owned != null) return owned;

		if (boss != null && boss.isAlive() && matchesAnyName(boss, type.bossNames())
				&& boss.distanceTo(mc.player) <= ENTITY_SCAN_RADIUS * 2) {
			return boss;
		}

		LivingEntity closest = null;
		double closestDist = Double.MAX_VALUE;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
			double dist = living.distanceTo(mc.player);
			if (dist > ENTITY_SCAN_RADIUS || dist >= closestDist) continue;
			if (!matchesAnyName(living, type.bossNames())) continue;
			closest = living;
			closestDist = dist;
		}
		return closest;
	}

	/**
	 * Looks for a nearby "Spawned by: <our IGN>" nameplate (its own line, sitting just above the
	 * name/HP line in the boss's stacked nameplate), then picks whichever boss-name-matching entity
	 * sits closest to that line's position - i.e. the same stack, not just the same general area.
	 * Returns null if no such line is visible right now (out of the scoreboard's name column, momentary
	 * render-list gap, etc.) so the caller can fall back to the distance/stickiness heuristic.
	 */
	private LivingEntity findBossViaOwnershipTag(Minecraft mc, ScdSlayerType type) {
		return findBossViaOwnershipTag(mc, mc.player.getGameProfile().name(), type);
	}

	/**
	 * Same ownership-tag lookup as above, but for an arbitrary IGN instead of
	 * always the local player's own name - lets ScdCarryBossWatcher track a
	 * carry customer's own boss the same reliable way, since that boss is
	 * never tied to the local player's own scoreboard quest at all (a carry is
	 * fundamentally about someone else's Slayer quest). Static/package-visible
	 * since it needs no per-fight state, just the world snapshot.
	 */
	static LivingEntity findBossViaOwnershipTag(Minecraft mc, String ownerIgn, ScdSlayerType type) {
		Entity ownershipTag = null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!entity.hasCustomName() || entity.getCustomName() == null) continue;
			if (entity.distanceTo(mc.player) > ENTITY_SCAN_RADIUS) continue;

			String clean = FORMATTING_CODE.matcher(entity.getCustomName().getString()).replaceAll("").trim();
			Matcher matcher = SPAWNED_BY.matcher(clean);
			if (matcher.matches() && matcher.group(1).trim().equalsIgnoreCase(ownerIgn)) {
				ownershipTag = entity;
				break;
			}
		}
		if (ownershipTag == null) return null;

		LivingEntity closest = null;
		double closestDist = OWNERSHIP_STACK_RADIUS;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
			if (!matchesAnyName(living, type.bossNames())) continue;
			double dist = living.distanceTo(ownershipTag);
			if (dist < closestDist) {
				closestDist = dist;
				closest = living;
			}
		}
		return closest;
	}

	private static boolean matchesAnyName(LivingEntity entity, List<String> names) {
		if (!entity.hasCustomName()) return false;
		Component custom = entity.getCustomName();
		if (custom == null) return false;
		String name = custom.getString();
		if (name.contains(DEAD_SUFFIX)) return false;
		for (String bossName : names) {
			if (name.contains(bossName)) return true;
		}
		return false;
	}

	public ScdSlayerQuest currentQuestOrNull() {
		return quest;
	}

	/** The last type with a genuinely active (in-area) quest this session, or null before the first one. */
	public ScdSlayerType lastActiveTypeOrNull() {
		return lastActiveType;
	}

	/** The type whose fight just ended, if still within the "Killed" display window - for the HUD box's own brief post-kill flash (see ScdSlayerHud), same window as the config screen's badge. */
	public ScdSlayerType justEndedTypeOrNull() {
		return System.currentTimeMillis() < killedDisplayUntilMs ? lastEndedType : null;
	}

	/** Same idea as justEndedTypeOrNull but with a much longer window meant for drop attribution, not a UI flash - see LOOT_ATTRIBUTION_WINDOW_MS. */
	public ScdSlayerType recentlyEndedTypeForLootOrNull() {
		return System.currentTimeMillis() < lootAttributionUntilMs ? lastEndedType : null;
	}

	/**
	 * Compact one-word status for a given Slayer type, used as a short badge next to that type's
	 * entry in the config screen's per-Slayer list instead of a full sentence: "Spawning" (quest
	 * active on the scoreboard, boss not up yet), "Spawned" (boss currently up), "Killed" (briefly
	 * after a kill), or null if this type has no active or recently-ended quest at all.
	 */
	public String phaseLabelOrNull(ScdSlayerType type) {
		if (quest != null && quest.type() == type) {
			return quest.bossSpawned() ? "Spawned" : "Spawning";
		}
		if (type == lastEndedType && System.currentTimeMillis() < killedDisplayUntilMs) {
			return "Killed";
		}
		return null;
	}

	public LivingEntity currentBossOrNull() {
		return boss;
	}

	public long fightElapsedMs() {
		return boss != null ? System.currentTimeMillis() - fightStartMs : 0;
	}

	/** Time from quest-accept to boss-spawn so far, idle stretches (no hit for HUNT_PAUSE_THRESHOLD_MS) excluded. */
	public long huntElapsedMs() {
		if (quest == null) return 0;
		long now = System.currentTimeMillis();
		long paused = huntTotalPausedMs + (huntPauseStartedAtMs != 0 ? now - huntPauseStartedAtMs : 0);
		return Math.max(0, (now - huntStartMs) - paused);
	}

	/** True once nothing nearby has taken damage for HUNT_PAUSE_THRESHOLD_MS during the hunting phase. */
	public boolean isHuntPaused() {
		return huntPauseStartedAtMs != 0;
	}

	/**
	 * Polls nearby living entities' HP for decreases to detect "a hit happened" during the hunting
	 * phase, since there's no boss entity yet to read a fight-timer signal off of. Any tracked
	 * entity's health dropping (or a new one appearing at less than full, e.g. already-damaged)
	 * counts as activity and resets the idle clock.
	 */
	private void updateHuntPauseTracking(Minecraft mc) {
		boolean hitDetected = false;
		Set<Integer> stillPresent = new HashSet<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand || !living.isAlive()) continue;
			if (living.distanceTo(mc.player) > ENTITY_SCAN_RADIUS) continue;

			stillPresent.add(living.getId());
			Float previous = huntTrackedHealth.get(living.getId());
			float health = living.getHealth();
			if (previous != null && health < previous) hitDetected = true;
			huntTrackedHealth.put(living.getId(), health);
		}
		huntTrackedHealth.keySet().retainAll(stillPresent);

		long now = System.currentTimeMillis();
		if (hitDetected) {
			if (huntPauseStartedAtMs != 0) {
				huntTotalPausedMs += now - huntPauseStartedAtMs;
				huntPauseStartedAtMs = 0;
			}
			huntLastHitAtMs = now;
		} else if (huntPauseStartedAtMs == 0 && now - huntLastHitAtMs > HUNT_PAUSE_THRESHOLD_MS) {
			huntPauseStartedAtMs = now;
		}
	}

	/** Live HP parsed from the boss's own nameplate text, or null if it's absent/unparseable this tick. */
	private ScdSlayerHealthReading.Reading currentHpReading() {
		if (boss == null || !boss.hasCustomName() || boss.getCustomName() == null) return null;
		return ScdSlayerHealthReading.parse(boss.getCustomName().getString());
	}

	public Double currentHpOrNull() {
		var reading = currentHpReading();
		return reading != null ? reading.current() : null;
	}

	/** The nameplate's own "max" half of a current/max pair if it has one, else the highest HP seen so far this fight. */
	public Double maxHpOrNull() {
		var reading = currentHpReading();
		if (reading != null && reading.max() != null) return reading.max();
		return maxHpSeen > 0 ? maxHpSeen : null;
	}

	/** One-shot, copy-from-chat snapshot of everything the tracker currently believes, for /scd slayer debug. */
	public List<String> describeState() {
		List<String> lines = new ArrayList<>();
		lines.add("quest: " + (quest != null
				? quest.type().displayName() + " tier=" + quest.tier() + " bossSpawned=" + quest.bossSpawned()
				: "null (no active quest detected)"));
		if (boss != null) {
			var custom = boss.getCustomName();
			lines.add("boss: \"" + (custom != null ? custom.getString() : "(no custom name)") + "\" type=" + boss.getType()
					+ " alive=" + boss.isAlive() + " parsedHp=" + currentHpOrNull() + "/" + maxHpOrNull()
					+ " fightElapsedMs=" + fightElapsedMs());
		} else {
			lines.add("boss: null (no boss entity currently tracked)");
		}
		lines.add("cocoonActive=" + isCocoonActive() + " knownMinibosses=" + knownMinibossIds.size());
		return lines;
	}

	/**
	 * Diagnostic dump of every nearby living entity's raw nameplate, distance,
	 * and whether it would match any known boss name - used by /scd slayer
	 * nearby to see why detection isn't firing without guessing blindly.
	 */
	public List<String> describeNearby(double radius) {
		List<String> lines = new ArrayList<>();
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			lines.add("no level/player loaded");
			return lines;
		}

		record Row(double distance, String text) {
		}
		List<Row> rows = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living)) continue;
			double dist = living.distanceTo(mc.player);
			if (dist > radius) continue;

			String raw = living.hasCustomName() && living.getCustomName() != null ? living.getCustomName().getString() : null;
			boolean isArmorStand = living instanceof ArmorStand;
			ScdSlayerType matched = raw != null ? ScdSlayerType.fromBossName(raw) : null;
			String text = String.format(java.util.Locale.ROOT, "%.1fm %s %s customName=%s matches=%s",
					dist, net.minecraft.world.entity.EntityType.getKey(living.getType()), isArmorStand ? "(armor stand)" : "",
					raw != null ? "\"" + raw + "\"" : "(none)", matched != null ? matched.displayName() : "false");
			rows.add(new Row(dist, text));
		}
		rows.sort(Comparator.comparingDouble(Row::distance));
		for (Row row : rows) lines.add(row.text());
		if (lines.isEmpty()) lines.add("no living entities within " + radius + " blocks");
		return lines;
	}
}
