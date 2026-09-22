package com.scd.client;

/**
 * Static bridge for ScdChatPacketMixin (which, being a Mixin into vanilla's own
 * ClientPacketListener, has no access to the ScdClient instance) to feed captured raw
 * chat/system-message text into the same "/scd dungeon debug capture" log as the ordinary
 * Fabric-event-based hooks in ScdClient. A plain static flag rather than an instance field
 * specifically so the mixin - injected straight into the packet handler, before Fabric's own chat
 * events and before any other mod's event-based interception even runs - can check it without
 * holding a reference to anything else. See ScdChatPacketMixin's own doc comment for why this
 * exists: two real dungeon runs confirmed the end-of-run completion summary never reaches
 * ClientReceiveMessageEvents.GAME/.CHAT/.ALLOW_GAME/.ALLOW_CHAT at all, because (per the account
 * owner) other installed mods rewrite it before any of those fire.
 */
public final class ScdRawChatCapture {
	public static volatile boolean active = false;

	private ScdRawChatCapture() {
	}

	public static void onRawMessage(String channel, String text) {
		if (!active) return;
		if (text == null || text.isBlank()) return;
		ScdLog.info("[DUNGEON-DEBUG] chat(" + channel + "): " + text);
	}
}
