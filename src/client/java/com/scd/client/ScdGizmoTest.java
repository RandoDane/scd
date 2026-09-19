package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;

/**
 * Throwaway proof-of-concept for the vanilla Gizmos API (see FEATURE_ROADMAP.md's
 * "T2/T3 re-scoped" section) - NOT a real feature. Exists purely to verify live,
 * with nothing else in the way: does a Gizmos call from ordinary tick code
 * actually render, what color format it expects, and whether setAlwaysOnTop()
 * really draws through walls/terrain the way the API shape suggests. Toggled with
 * /scd debug gizmotest. Delete this class once confirmed and replaced by the real
 * ScdGizmoUtil helper the roadmap calls for.
 *
 * First cut drew both test lines with identical start/end coordinates, so they
 * perfectly overlapped and whichever gets composited second just painted over
 * the other - looked like "only one line" for a reason that had nothing to do
 * with the API itself. This version offsets the two arrows side by side and
 * labels both ends so overlap and direction are no longer ambiguous.
 */
public class ScdGizmoTest {
	private Vec3 origin;
	private Vec3 target;

	public boolean isActive() {
		return target != null;
	}

	/** Fixes both points 10 blocks out from wherever you're looking right now, so they stay put while you walk around to test occlusion instead of swinging around with your view every tick. */
	public void toggle() {
		if (target != null) {
			origin = null;
			target = null;
			return;
		}
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		origin = player.getEyePosition();
		target = origin.add(player.getLookAngle().scale(10));
	}

	public void tick() {
		if (target == null) return;

		// A horizontal vector perpendicular to the line's direction, so the two arrows can sit side
		// by side (~0.5 blocks apart) instead of drawn on top of each other. Falls back to a
		// different reference axis if looking nearly straight up/down, where direction x (0,1,0)
		// would otherwise be a near-zero vector.
		Vec3 direction = target.subtract(origin).normalize();
		Vec3 upReference = Math.abs(direction.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 right = direction.cross(upReference).normalize().scale(0.5);

		Vec3 redFrom = origin.add(right);
		Vec3 redTo = target.add(right);
		Vec3 greenFrom = origin.subtract(right);
		Vec3 greenTo = target.subtract(right);

		// Depth-tested like normal world geometry - should disappear behind a wall/block.
		Gizmos.arrow(redFrom, redTo, 0xFFFF0000);
		// Same shape, always-on-top: should stay visible through walls/terrain if setAlwaysOnTop() is
		// really the depth-test bypass it looks like from the API shape - this is the key unknown.
		Gizmos.arrow(greenFrom, greenTo, 0xFF00FF00).setAlwaysOnTop();

		Gizmos.billboardText("START", origin, TextGizmo.Style.forColorAndCentered(0xFFFFFFFF));
		Gizmos.billboardText("TARGET (arrowheads point here)", target, TextGizmo.Style.forColorAndCentered(0xFFFFFFFF));
	}
}
