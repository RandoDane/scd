package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * First slice of the "Dungeon mapping initiative" (see FEATURE_ROADMAP.md §3, 2026-09-22
 * research pass) - a crowd-sourced room block-fingerprint collector, opt-in via
 * ScdConfig.Dungeon.roomMappingEnabled.
 *
 * Genuinely experimental: this assumes Catacombs rooms sit on a fixed 32-block-wide world grid
 * (confirmed as a real Hypixel dungeon mechanic by every reference mod researched) and tracks
 * which 32x32 cell the player is currently standing in, scanning nearby non-air blocks into a
 * running per-cell fingerprint as the player walks around. When the player crosses into a
 * different cell, the previous cell's fingerprint (if it has enough blocks to be worth keeping)
 * is submitted to the SCD backend and a fresh collection starts for the new cell.
 *
 * This does NOT do room-shape matching/rotation-normalization (see Skyblocker's real approach
 * documented in the roadmap) - it only collects raw data. Matching against the accumulated
 * database is a later phase, once there's real data to match against.
 */
public final class ScdDungeonRoomScanner {
	private static final int GRID_SIZE = 32;
	private static final int SCAN_RADIUS_XZ = 6;
	private static final int SCAN_RADIUS_Y = 10;
	private static final int SCAN_INTERVAL_TICKS = 10;
	private static final int MIN_BLOCKS_TO_SUBMIT = 50;
	// Biases Y (which can be negative) into an always-non-negative range before packing into the
	// dedup key below - covers the full -512..+15871 world-height range with room to spare.
	private static final int Y_BIAS = 512;

	private Long currentCellKey;
	private String currentFloor;
	private final Map<Long, String> fingerprint = new HashMap<>();
	private int tickCounter;

	public record BlockSample(int relX, int y, int relZ, String blockId) {
	}

	public void tick(ScdApiClient api, boolean mappingEnabled) {
		if (!mappingEnabled) {
			reset();
			return;
		}

		var mc = Minecraft.getInstance();
		var player = mc.player;
		if (mc.level == null || player == null) {
			reset();
			return;
		}

		ScdDungeonManager.DungeonState state = ScdDungeonManager.read();
		if (!state.inDungeon()) {
			reset();
			return;
		}

		BlockPos pos = player.blockPosition();
		int cellX = Math.floorDiv(pos.getX(), GRID_SIZE);
		int cellZ = Math.floorDiv(pos.getZ(), GRID_SIZE);
		long cellKey = (((long) cellX) << 32) ^ (cellZ & 0xFFFFFFFFL);

		if (currentCellKey == null) {
			currentCellKey = cellKey;
			currentFloor = state.floor();
		} else if (cellKey != currentCellKey) {
			submitIfWorthwhile(api);
			fingerprint.clear();
			currentCellKey = cellKey;
			currentFloor = state.floor();
		}

		if (++tickCounter < SCAN_INTERVAL_TICKS) return;
		tickCounter = 0;
		scanAround(mc, pos, cellX, cellZ);
	}

	private void scanAround(Minecraft mc, BlockPos pos, int cellX, int cellZ) {
		int cellOriginX = cellX * GRID_SIZE;
		int cellOriginZ = cellZ * GRID_SIZE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -SCAN_RADIUS_XZ; dx <= SCAN_RADIUS_XZ; dx++) {
			for (int dz = -SCAN_RADIUS_XZ; dz <= SCAN_RADIUS_XZ; dz++) {
				for (int dy = -SCAN_RADIUS_Y; dy <= SCAN_RADIUS_Y; dy++) {
					int worldX = pos.getX() + dx;
					int worldY = pos.getY() + dy;
					int worldZ = pos.getZ() + dz;
					int relX = worldX - cellOriginX;
					int relZ = worldZ - cellOriginZ;
					// Outside this cell entirely (corridor or a neighboring room bleeding into the
					// scan radius) - only fingerprint blocks that belong to the cell we're tracking.
					if (relX < 0 || relX >= GRID_SIZE || relZ < 0 || relZ >= GRID_SIZE) continue;

					cursor.set(worldX, worldY, worldZ);
					BlockState bs = mc.level.getBlockState(cursor);
					if (bs.isAir()) continue;

					long key = encode(relX, worldY, relZ);
					fingerprint.putIfAbsent(key, BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString());
				}
			}
		}
	}

	private void submitIfWorthwhile(ScdApiClient api) {
		if (fingerprint.size() < MIN_BLOCKS_TO_SUBMIT || currentFloor == null) return;

		List<BlockSample> samples = new ArrayList<>(fingerprint.size());
		for (var entry : fingerprint.entrySet()) {
			long key = entry.getKey();
			int relX = (int) ((key >> 20) & 0x3F);
			int y = (int) ((key >> 6) & 0x3FFF) - Y_BIAS;
			int relZ = (int) (key & 0x3F);
			samples.add(new BlockSample(relX, y, relZ, entry.getValue()));
		}
		api.reportDungeonRoom(currentFloor, samples);
	}

	private void reset() {
		if (currentCellKey == null) return;
		// Left the dungeon (or mapping got disabled) mid-cell - whatever was collected so far is a
		// partial view of the room, not a finished one, so it's dropped rather than submitted
		// half-scanned.
		currentCellKey = null;
		currentFloor = null;
		fingerprint.clear();
		tickCounter = 0;
	}

	private static long encode(int relX, int y, int relZ) {
		long ux = relX & 0x3F;
		long uy = (y + Y_BIAS) & 0x3FFF;
		long uz = relZ & 0x3F;
		return (ux << 20) | (uy << 6) | uz;
	}

	/** For `/scd dungeon debug room` - a live sanity check of what this tick's state actually is. */
	public String debugState() {
		if (currentCellKey == null) return "not currently tracking a room cell";
		return "cell=" + currentCellKey + " floor=" + currentFloor + " blocks=" + fingerprint.size()
				+ " (submits at " + MIN_BLOCKS_TO_SUBMIT + "+)";
	}
}
