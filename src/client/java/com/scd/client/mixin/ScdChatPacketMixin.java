package com.scd.client.mixin;

import com.scd.client.ScdDungeonCompletion;
import com.scd.client.ScdRawChatCapture;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures every incoming chat/system-message packet at the true source - HEAD of
 * ClientPacketListener's own packet handlers - before Fabric's ClientReceiveMessageEvents fire
 * and before any other installed mod's own event-based interception can run. Built 2026-09-22
 * after confirming, via two real dungeon runs with /scd dungeon debug capture armed, that the
 * end-of-run completion summary never reaches ClientReceiveMessageEvents.GAME, .CHAT,
 * .ALLOW_GAME, or .ALLOW_CHAT at all. The account owner confirmed Odin, Skyblocker, and a third
 * mod all rewrite that specific message, and asked to capture it "the same way Odin does, or
 * before Odin does" rather than depend on boss-entity-death tracking instead - a HEAD injection
 * here runs before the vanilla method body, and therefore before any Fabric event fired from
 * within it, so it sees Hypixel's genuinely raw packet content regardless of what any other mod
 * does downstream (as long as that mod isn't using an even-higher-priority mixin at the same
 * HEAD, which isn't something to design around speculatively).
 *
 * Confirmed live via `javap` against this project's own client/common jars (not guessed) that
 * these are the three packet types chat can arrive as: ClientboundSystemChatPacket (the one that
 * feeds GAME), ClientboundPlayerChatPacket (feeds CHAT - real signed player messages), and
 * ClientboundDisguisedChatPacket - a third type with no obvious dedicated Fabric event, and the
 * prime suspect for this specific message, since Hypixel commonly uses it for server-generated
 * "looks like chat" text that isn't a real signed player message.
 *
 * Purely observational: every injected method is a plain (non-cancellable) @Inject, and none of
 * them change or block anything - they only forward the packet's own text to ScdRawChatCapture,
 * which does nothing at all unless /scd dungeon debug capture is currently armed.
 */
@Mixin(ClientPacketListener.class)
public class ScdChatPacketMixin {
	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void scd$captureSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		String text = packet.content().getString();
		ScdRawChatCapture.onRawMessage("MIXIN_SYSTEM", text);
		// The dungeon completion report (see ScdDungeonCompletion) has only ever been observed
		// arriving as SYSTEM messages - fed unconditionally, not gated behind the debug capture
		// toggle, since this is the real feature the mixin exists for.
		ScdDungeonCompletion.onSystemMessage(text);
	}

	@Inject(method = "handleDisguisedChat", at = @At("HEAD"))
	private void scd$captureDisguisedChat(ClientboundDisguisedChatPacket packet, CallbackInfo ci) {
		ScdRawChatCapture.onRawMessage("MIXIN_DISGUISED", packet.message().getString());
	}

	@Inject(method = "handlePlayerChat", at = @At("HEAD"))
	private void scd$capturePlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
		ScdRawChatCapture.onRawMessage("MIXIN_PLAYER", packet.body().content());
	}
}
