package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks each active carry's OWN boss, independently of the local player's
 * own Slayer quest. ScdSlayerBossTracker's onBossFightEnded only ever fires
 * for a boss tied to the local player's own scoreboard quest - a carry is
 * fundamentally about someone ELSE's quest, so if the carrier isn't also
 * running that exact quest themselves, the main tracker never sees the
 * customer's boss at all. This reuses the same "Spawned by: &lt;IGN&gt;"
 * ownership-tag matching the main tracker uses for its own boss
 * (ScdSlayerBossTracker.findBossViaOwnershipTag), just parameterized by each
 * carry entry's own playerName.
 *
 * Kill detection here is necessarily entity-lifecycle based rather than
 * scoreboard-based (there's no scoreboard access into another player's quest
 * state): once a matching boss is found for an entry, losing it (dead or
 * unfindable) for longer than LOST_GRACE_MS counts as a kill. A different
 * entity matching the same ownership tag before that grace period elapses is
 * treated as a fast respawn - the old one is credited as a kill immediately,
 * and tracking restarts on the new one.
 */
public class ScdCarryBossWatcher {
	private static final long LOST_GRACE_MS = 3_000;

	private record State(int entityId, long fightStartMs, long lastSeenMs) {
	}

	public interface Listener {
		/** Fired once a previously-tracked customer boss can no longer be found/alive, with how long it was up. */
		void onCarryBossKilled(ScdCarryEntry entry, long elapsedMs);
	}

	private final Listener listener;
	private final Map<Long, State> tracked = new HashMap<>();

	public ScdCarryBossWatcher(Listener listener) {
		this.listener = listener;
	}

	public void tick(List<ScdCarryEntry> activeEntries) {
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;

		long now = System.currentTimeMillis();
		Set<Long> stillActive = new HashSet<>();
		for (ScdCarryEntry entry : activeEntries) {
			stillActive.add(entry.id);
			LivingEntity found = ScdSlayerBossTracker.findBossViaOwnershipTag(mc, entry.playerName, entry.typeEnum());
			State state = tracked.get(entry.id);

			if (found != null && found.isAlive()) {
				if (state == null) {
					tracked.put(entry.id, new State(found.getId(), now, now));
				} else if (state.entityId() == found.getId()) {
					tracked.put(entry.id, new State(state.entityId(), state.fightStartMs(), now));
				} else {
					// A different entity now matches the same ownership tag+type - the previous one's
					// fight already ended (a respawn faster than LOST_GRACE_MS would otherwise miss).
					if (listener != null) listener.onCarryBossKilled(entry, now - state.fightStartMs());
					tracked.put(entry.id, new State(found.getId(), now, now));
				}
				continue;
			}

			if (state != null && now - state.lastSeenMs() > LOST_GRACE_MS) {
				tracked.remove(entry.id);
				if (listener != null) listener.onCarryBossKilled(entry, now - state.fightStartMs());
			}
		}
		tracked.keySet().retainAll(stillActive);
	}

	/** Live, on-demand diagnostic for /scd carry debug - re-checks right now rather than relying on the last tick's result. */
	public String describeLive(ScdCarryEntry entry) {
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return "no level/player loaded";

		LivingEntity found = ScdSlayerBossTracker.findBossViaOwnershipTag(mc, entry.playerName, entry.typeEnum());
		String liveText = found != null
				? "found now: entity #" + found.getId() + " alive=" + found.isAlive()
						+ " name=\"" + (found.getCustomName() != null ? found.getCustomName().getString() : "(no name)") + "\""
				: "no \"Spawned by: " + entry.playerName + "\" " + entry.typeEnum().displayName() + " boss found nearby right now";

		State state = tracked.get(entry.id);
		String trackedText = state != null
				? "tracking entity #" + state.entityId() + ", fight running " + (System.currentTimeMillis() - state.fightStartMs())
						+ "ms, last seen " + (System.currentTimeMillis() - state.lastSeenMs()) + "ms ago"
				: "nothing currently tracked for this entry";

		return liveText + " | " + trackedText;
	}
}
