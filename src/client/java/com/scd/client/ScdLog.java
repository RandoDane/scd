package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central logger so every SCD log line is tagged "SCD" and lands in the
 * normal logs/latest.log, instead of vanishing into an unprefixed
 * System.err line that's easy to miss when someone pastes their log.
 */
public final class ScdLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("SCD");
	private static final long CHAT_ALERT_COOLDOWN_MS = 10_000;
	private static final Map<String, Long> lastChatAlertMs = new ConcurrentHashMap<>();

	private ScdLog() {
	}

	public static void info(String message) {
		LOGGER.info(message);
	}

	public static void warn(String message) {
		LOGGER.warn(message);
	}

	public static void warn(String message, Throwable t) {
		LOGGER.warn(message, t);
	}

	public static void error(String message, Throwable t) {
		LOGGER.error(message, t);
	}

	/**
	 * Runs `action`, logging (not rethrowing) any exception. Every per-frame or
	 * per-tick hook we register (tooltip callback, HUD renderer, tick event)
	 * goes through this, so a bug on our end - or a bad interaction with
	 * another mod sharing the same hook - shows up clearly in the log instead
	 * of silently breaking rendering, or worse, other mods' listeners on the
	 * same event.
	 *
	 * Also posts a short chat line (rate-limited to once per 10s per context)
	 * on the first exception in that window, since asking someone to go find
	 * and paste logs/latest.log is real friction - a heavily modded game log
	 * can be thousands of lines - and a copyable chat message isn't.
	 */
	public static void guard(String context, Runnable action) {
		try {
			action.run();
		} catch (Throwable t) {
			LOGGER.error("[{}] threw - see stack trace below", context, t);
			maybeAlertChat(context, t);
		}
	}

	/** Same as guard(), but for a hook that must return a value (e.g. ScreenMouseEvents.AllowMouseClick) - falls back to `fallback` on error rather than swallowing it silently, since returning the wrong boolean there could block the player's own click instead of just skipping a render. */
	public static boolean guardBoolean(String context, java.util.function.BooleanSupplier action, boolean fallback) {
		try {
			return action.getAsBoolean();
		} catch (Throwable t) {
			LOGGER.error("[{}] threw - see stack trace below", context, t);
			maybeAlertChat(context, t);
			return fallback;
		}
	}

	private static void maybeAlertChat(String context, Throwable t) {
		long now = System.currentTimeMillis();
		Long last = lastChatAlertMs.get(context);
		if (last != null && now - last < CHAT_ALERT_COOLDOWN_MS) return;
		lastChatAlertMs.put(context, now);

		var mc = Minecraft.getInstance();
		var player = mc.player;
		if (player == null) return;
		String exceptionSummary = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
		player.sendSystemMessage(Component.literal("§c[SCD] [" + context + "] error: " + exceptionSummary + " (full trace in logs/latest.log)"));
	}
}
