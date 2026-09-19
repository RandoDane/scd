package com.scd.client;

import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Thin wrappers over the vanilla Gizmos API (see FEATURE_ROADMAP.md's "T2/T3
 * re-scoped" section) for the handful of shapes SCD's own features need -
 * this project's equivalent of SkyHanni's WorldRenderUtils.kt, just far
 * thinner since Gizmos already does the hard part (camera-relative math,
 * depth-test bypass, batching). Confirmed live via ScdGizmoTest
 * (2026-09-19): color is a plain ARGB int like everywhere else in this
 * codebase, and setAlwaysOnTop() really does draw through walls/terrain.
 *
 * Every call here needs to be re-issued every client tick to keep showing -
 * a Gizmo without persistForMillis() only lasts the tick/frame it was added
 * on, which is exactly what a "while tracking this target" feature wants
 * (stop calling it, it stops rendering, no separate cleanup needed).
 *
 * Note on thickness: EntityRenderState.outlineColor (the entity-glow mixin)
 * has no matching width/thickness field - the glow itself is stuck at
 * whatever fixed width vanilla's own outline pass uses, confirmed by reading
 * every field on that class. Lines and boxes, unlike glow, DO take an
 * explicit width, so LINE_WIDTH/BOX_STROKE_WIDTH below are the practical way
 * to get a chunkier-looking highlight than the glow alone provides.
 */
public final class ScdGizmoUtil {
	private static final float LINE_WIDTH = 3f;
	private static final float BOX_STROKE_WIDTH = 3f;

	private ScdGizmoUtil() {
	}

	/** A line between two world positions. */
	public static void line(Vec3 from, Vec3 to, int argbColor, boolean throughWalls) {
		var properties = Gizmos.line(from, to, argbColor, LINE_WIDTH);
		if (throughWalls) properties.setAlwaysOnTop();
	}

	// Pushed the line's start point forward by this much, toward the target, instead of starting
	// exactly at the given point - confirmed via Skyblocker/SkyHanni/CaribouStonks' real source that
	// none of them start a tracer at the raw eye/camera position (SkyHanni's own raw-eye-position line
	// helper is @Deprecated in favor of this), since a vertex sitting essentially at the camera causes
	// near-plane clipping/distortion. Matches their ~1-2 block offset.
	private static final double LINE_START_PUSH_DISTANCE = 1.5;

	/** A line from a fixed point to wherever an entity currently is - re-call every tick to track it as it moves. */
	public static void lineToEntity(Vec3 from, Entity target, int argbColor, boolean throughWalls) {
		Vec3 to = target.position();
		Vec3 delta = to.subtract(from);
		if (delta.lengthSqr() > LINE_START_PUSH_DISTANCE * LINE_START_PUSH_DISTANCE) {
			from = from.add(delta.normalize().scale(LINE_START_PUSH_DISTANCE));
		}
		line(from, to, argbColor, throughWalls);
	}

	/** Floating billboard text at a fixed world position. */
	public static void label(Vec3 pos, String text, int argbColor, boolean throughWalls) {
		var properties = Gizmos.billboardText(text, pos, TextGizmo.Style.forColorAndCentered(argbColor));
		if (throughWalls) properties.setAlwaysOnTop();
	}

	/** Floating billboard text hovering just above an entity - re-call every tick to keep it following. */
	public static void labelOverEntity(Entity target, String text, int argbColor, boolean throughWalls) {
		label(target.getEyePosition().add(0, 0.6, 0), text, argbColor, throughWalls);
	}

	/** A small point marker at a fixed world position - for a waypoint/beacon-style callout. */
	public static void marker(Vec3 pos, int argbColor, boolean throughWalls) {
		var properties = Gizmos.point(pos, argbColor, 6f);
		if (throughWalls) properties.setAlwaysOnTop();
	}

	/** A stroked (unfilled) box around an entity's current hitbox - re-call every tick to track it as it moves/grows. */
	public static void boxAroundEntity(Entity target, int argbColor, boolean throughWalls) {
		var properties = Gizmos.cuboid(target.getBoundingBox(), GizmoStyle.stroke(argbColor, BOX_STROKE_WIDTH));
		if (throughWalls) properties.setAlwaysOnTop();
	}
}
