package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;

/**
 * Throwaway proof-of-concept for the vanilla Gizmos API (see FEATURE_ROADMAP.md's
 * "T2/T3 re-scoped" section) - NOT a real feature. Exists purely to verify live,
 * with nothing else in the way: does a Gizmos.line() call from ordinary tick code
 * actually render, what color format it expects, and whether setAlwaysOnTop()
 * really draws through walls/terrain the way the API shape suggests. Toggled with
 * /scd dev gizmotest. Delete this class once confirmed and replaced by the real
 * ScdGizmoUtil helper the roadmap calls for.
 */
public class ScdGizmoTest {
	private Vec3 target;

	public boolean isActive() {
		return target != null;
	}

	/** Fixes the target 10 blocks out from wherever you're looking right now, so the line stays put while you walk around to test occlusion instead of swinging around with your view every tick. */
	public void toggle() {
		if (target != null) {
			target = null;
			return;
		}
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		target = player.getEyePosition().add(player.getLookAngle().scale(10));
	}

	public void tick() {
		if (target == null) return;
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		Vec3 from = player.getEyePosition();

		// Plain line: depth-tested like normal world geometry - should disappear behind a wall/block.
		Gizmos.line(from, target, 0xFFFF0000);
		// Same line, always-on-top: should stay visible through walls/terrain if setAlwaysOnTop() is
		// really the depth-test bypass it looks like from the API shape - this is the key unknown.
		Gizmos.line(from, target, 0xFF00FF00).setAlwaysOnTop();

		Gizmos.billboardText("SCD gizmo test", target, TextGizmo.Style.forColorAndCentered(0xFFFFFFFF));
	}
}
