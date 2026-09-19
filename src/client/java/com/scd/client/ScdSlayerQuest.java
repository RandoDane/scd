package com.scd.client;

/** Snapshot of the player's currently active Slayer quest, read fresh from the scoreboard every tick. */
public record ScdSlayerQuest(ScdSlayerType type, String tier, boolean bossSpawned) {
}
