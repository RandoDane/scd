package com.scd.client.mixin;

import com.scd.client.ScdGlowRegistry;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sets a custom per-entity outline/glow color, bypassing vanilla's own
 * scoreboard-team-based glow entirely - EntityRenderState.outlineColor is a
 * plain public field on the render-state-extraction object (confirmed via
 * decompiling this project's own client jar), the same field CaribouStonks'
 * real, current source writes to the same way (see FEATURE_ROADMAP.md's
 * "T2/T3 re-scoped" section for the research this is based on). Injecting at
 * TAIL means this always wins over whatever default (usually
 * EntityRenderState.NO_OUTLINE) the base method already set.
 *
 * extractRenderState is declared on the generic EntityRenderer&lt;T extends
 * Entity, S extends EntityRenderState&gt; as extractRenderState(T, S, float);
 * type erasure means the actual bytecode signature Mixin has to match is
 * extractRenderState(Entity, EntityRenderState, float) - targeting the raw
 * EntityRenderer class with those erased parameter types is correct and is
 * exactly what CaribouStonks' own mixin does.
 */
@Mixin(EntityRenderer.class)
public class ScdEntityRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void scd$applyGlow(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		Integer color = ScdGlowRegistry.colorForOrNull(entity);
		if (color != null) {
			state.outlineColor = color;
		}
	}
}
