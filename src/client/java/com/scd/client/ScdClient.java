package com.scd.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ScdClient implements ClientModInitializer {
	private ScdConfig config;
	private ScdApiClient api;
	private final ScdPriceStore prices = new ScdPriceStore();
	private final ScdHoverState hoverState = new ScdHoverState();
	private final ScdAttributeShards attributeShards = new ScdAttributeShards();
	private ScdHistoryStore history;
	private ScdGraphHud graphHud;
	private final ScdSlayerBossTracker slayerTracker = new ScdSlayerBossTracker();
	private ScdSlayerHud slayerHud;
	private ScdSlayerRecords slayerRecords;
	private final ScdSlayerSessionStats slayerSessionStats = new ScdSlayerSessionStats();
	private final ScdMayorPerks mayorPerks = new ScdMayorPerks();
	private ScdSlayerStatsHud slayerStatsHud;
	private final ScdQuiverTracker quiverTracker = new ScdQuiverTracker(slayerTracker);
	private ScdQuiverHud quiverHud;
	private ScdSlayerRngMeter slayerRngMeter;
	private ScdSlayerDrops slayerDrops;
	private ScdCarryQueue carryQueue;
	private final ScdCarryBossWatcher carryBossWatcher = new ScdCarryBossWatcher(this::handleCarryBossKilled);
	private final ScdGizmoTest gizmoTest = new ScdGizmoTest();
	private final ScdInventoryWatcher inventoryWatcher = new ScdInventoryWatcher();
	// Set by the "<Type> Slayer LVL N" completion message, consumed by the "RNG Meter - X Stored
	// XP" message that immediately follows it - see checkSlayerCompletionMessages().
	private String lastCompletedSlayerTypeName;
	// Set by "/scd slayer debug menu dump" - the next screen that opens gets its full slot contents printed
	// to chat/log, then this clears itself.
	private volatile boolean armMenuDump = false;
	private ScdSlayerMenuWatcher slayerMenuWatcher;
	private volatile boolean loggedFirstPriceSuccess = false;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "scd-refresh");
		t.setDaemon(true);
		return t;
	});

	@Override
	public void onInitializeClient() {
		logStartupInfo();

		config = ScdConfig.load();
		api = new ScdApiClient(config.bazaar.serverUrl);
		history = new ScdHistoryStore(api);
		graphHud = new ScdGraphHud(config, prices, history, hoverState);
		graphHud.register();
		new ScdTooltip(config, prices, hoverState, attributeShards).register();
		refreshAttributeShardMap();

		slayerRecords = ScdSlayerRecords.load();
		slayerRngMeter = ScdSlayerRngMeter.load();
		slayerMenuWatcher = new ScdSlayerMenuWatcher(slayerRngMeter);
		slayerDrops = ScdSlayerDrops.load();
		carryQueue = ScdCarryQueue.load();
		slayerHud = new ScdSlayerHud(config, slayerTracker, slayerRecords, slayerRngMeter, slayerDrops);
		slayerHud.register();
		slayerStatsHud = new ScdSlayerStatsHud(config, slayerSessionStats, slayerTracker, mayorPerks);
		slayerStatsHud.register();
		slayerTracker.setListener(new ScdSlayerBossTracker.Listener() {
			@Override
			public void onBossSpawned(ScdSlayerQuest quest) {
				announceSlayer("§c§l" + quest.type().displayName() + " Slayer boss has spawned!");
			}

			@Override
			public void onBossFightEnded(ScdSlayerQuest quest, long elapsedMs) {
				boolean newBest = slayerRecords.recordKill(quest.type(), quest.tier(), elapsedMs);
				slayerSessionStats.recordKill(quest.tier(), elapsedMs);
				slayerRngMeter.recordKill(quest.type(), quest.tier());
				Long baseXp = ScdSlayerRngMeter.baseSlayerXpForTier(quest.tier());
				if (baseXp != null) {
					long xpGained = Math.round(baseXp * mayorPerks.xpMultiplier());
					slayerSessionStats.recordXpGained(xpGained);
					slayerRngMeter.recordKillTowardMeterEstimate(quest.type(), quest.tier(), xpGained);
				}
				String time = ScdSlayerHud.formatElapsed(elapsedMs);
				announceSlayer("§a" + quest.type().displayName() + " Slayer boss down in " + time
						+ (newBest ? " §6§lNEW BEST!" : ""));
			}

			@Override
			public void onMinibossSpawned(ScdSlayerMinibosses.Entry miniboss) {
				if (!config.slayer.minibossAlertEnabled) return;
				announceSlayer((miniboss.stronger() ? "§4§l" : "§6") + miniboss.name() + " miniboss has spawned!"
						+ (miniboss.stronger() ? " §4(strong variant)" : ""));
			}

			@Override
			public void onHuntCompleted(ScdSlayerQuest quest, long huntElapsedMs) {
				slayerSessionStats.recordHunt(quest.tier(), huntElapsedMs);
			}
		});
		quiverHud = new ScdQuiverHud(config, quiverTracker);
		quiverHud.register();

		// Drop attribution is inventory-diff based: Hypixel's Telekinesis is mandatory, so almost
		// everything goes straight to inventory with no ground item entity ever spawning, leaving
		// nothing at the entity/packet level to correlate a pickup to a specific mob's death. A quest
		// being active alone isn't precise enough (it would sweep up bazaar buys, unrelated chests,
		// anything), so gains are also checked against ScdSlayerDropAllowlist.
		inventoryWatcher.setListener((itemId, displayName, amount) -> {
			// The kill itself ends the quest instantly, but the player still has to walk over and
			// collect whatever dropped - recentlyEndedTypeForLootOrNull covers that window, longer than
			// the HUD's "Killed" flash.
			ScdSlayerQuest activeQuest = slayerTracker.currentQuestOrNull();
			ScdSlayerType type = activeQuest != null ? activeQuest.type() : slayerTracker.recentlyEndedTypeForLootOrNull();
			if (type == null) {
				ScdLog.info("Inventory gain: " + displayName + " x" + amount + " -> not recorded (no active or recently-ended Slayer quest)");
				return;
			}
			if (!ScdSlayerDropAllowlist.isKnownDrop(type, itemId, displayName)) {
				ScdLog.info("Inventory gain: " + displayName + " x" + amount + " -> not recorded (not on " + type.displayName() + "'s drop allowlist)");
				return;
			}
			ScdLog.info("Inventory gain: " + displayName + " x" + amount + " -> recorded as a " + type.displayName() + " drop");
			slayerDrops.record(type, itemId, displayName, amount);
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> ScdLog.guard("slayer tick", slayerTracker::tick));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> ScdLog.guard("carry boss watch", () -> carryBossWatcher.tick(carryQueue.active())));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> ScdLog.guard("gizmo test", gizmoTest::tick));
		// A HUD element that renders nothing, purely to piggyback ticking the inventory watcher onto
		// HudElementRegistry rather than the shared ClientTickEvents.END_CLIENT_TICK above - see
		// ScdSlayerHud.register() for why that event can go silent in a heavily modded environment.
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath("scd", "inventory_watch_ticker"),
				(graphics, deltaTracker) -> ScdLog.guard("inventory watch", () -> inventoryWatcher.tick(attributeShards)));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> ScdLog.guard("slayer chat watch", () -> {
			logSlayerChatLines(message);
			checkCocoonMessage(message);
			checkSlayerCompletionMessages(message);
			checkSackPickupMessage(message);
		}));
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			// A screen's real content streams in after it opens, not at creation (Hypixel shows a
			// "Waiting for item stacks to load..." placeholder first) - re-scanning every tick the
			// screen stays open, instead of once, is what catches the real data once it lands.
			ScreenEvents.afterTick(screen).register(s -> ScdLog.guard("slayer menu watch", () -> slayerMenuWatcher.onScreenOpened(s)));

			if (armMenuDump) {
				var ticksWaited = new int[1];
				ScreenEvents.afterTick(screen).register(s -> ScdLog.guard("menu dump", () -> {
					if (!armMenuDump) return;
					// ~2s at 20 ticks/s - enough time for Hypixel to finish streaming the real content in.
					if (++ticksWaited[0] >= 40) {
						armMenuDump = false;
						dumpScreenContents(s);
					}
				}));
			}
		});

		scheduler.scheduleAtFixedRate(this::refreshPrices, 0, 60, TimeUnit.SECONDS);
		// The election/mayor only changes a few times a SkyBlock year - matches how rarely the server
		// itself re-polls Hypixel for it (see server/src/server.js).
		scheduler.scheduleAtFixedRate(this::refreshMayorPerks, 0, 10, TimeUnit.MINUTES);
		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
	}

	/**
	 * Logged unconditionally on every launch (unlike the rest of our debug
	 * output, which is opt-in via /scd debug) because when someone reports
	 * "it doesn't work", the very first thing to rule out is whether SCD even
	 * finished loading, and whether some other mod's version of the same
	 * Fabric API module is different from what we're built against.
	 */
	private void logStartupInfo() {
		var loader = FabricLoader.getInstance();
		String mcVersion = loader.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		String loaderVersion = loader.getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		String scdVersion = loader.getModContainer("scd").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		ScdLog.info("Initializing SCD " + scdVersion + " on Minecraft " + mcVersion + ", Fabric Loader " + loaderVersion
				+ ", Java " + System.getProperty("java.version") + " (" + loader.getAllMods().size() + " mods loaded - run /scd debug for the full list)");
	}

	public ScdGraphHud graphHud() {
		return graphHud;
	}

	public ScdSlayerHud slayerHud() {
		return slayerHud;
	}

	public ScdSlayerStatsHud slayerStatsHud() {
		return slayerStatsHud;
	}

	public ScdQuiverHud quiverHud() {
		return quiverHud;
	}

	public ScdSlayerBossTracker slayerTracker() {
		return slayerTracker;
	}

	public ScdSlayerDrops slayerDrops() {
		return slayerDrops;
	}

	public ScdCarryQueue carryQueue() {
		return carryQueue;
	}

	// A real Minecraft/Hypixel account name is always 1-16 chars of [A-Za-z0-9_] - Hypixel's tab list
	// also carries a bunch of fake, non-player entries purely for scoreboard-team sort/spacing tricks
	// (seen live as "!A-a", "!A-b", ... filling several pages before any real name), which all fail
	// this pattern since real usernames can never contain "!" or "-".
	private static final java.util.regex.Pattern VALID_IGN = java.util.regex.Pattern.compile("^\\w{1,16}$");

	/** Every other real player currently on the tab list (i.e. actually on this server right now) - used to pick/validate carry player names instead of trusting freehand typing. */
	public List<String> onlinePlayerNames() {
		var player = Minecraft.getInstance().player;
		if (player == null || player.connection == null) return List.of();
		String ownName = player.getGameProfile().name();
		return player.connection.getListedOnlinePlayers().stream()
				.map(info -> info.getProfile().name())
				.filter(name -> VALID_IGN.matcher(name).matches())
				.filter(name -> !name.equalsIgnoreCase(ownName))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.toList();
	}

	/** Called after the settings screen closes, in case the server URL changed. */
	public void onConfigChanged() {
		api.setBaseUrl(config.bazaar.serverUrl);
		refreshPrices();
		refreshAttributeShardMap();
		refreshMayorPerks();
	}

	private void refreshMayorPerks() {
		api.fetchMayor().thenAccept(mayorPerks::update)
				.exceptionally(err -> {
					ScdLog.warn("Failed to refresh mayor perks from " + config.bazaar.serverUrl, err);
					return null;
				});
	}

	/** Static wiki-sourced reference data (see server/src/attributeShards.js) - one fetch is enough, no need to poll like prices. */
	private void refreshAttributeShardMap() {
		api.fetchAttributeShardMap().thenAccept(attributeShards::replaceAll)
				.exceptionally(err -> {
					ScdLog.warn("Failed to fetch attribute shard map from " + config.bazaar.serverUrl, err);
					return null;
				});
	}

	private void refreshPrices() {
		api.fetchAll().thenAccept(list -> {
			prices.replaceAll(list);
			if (!loggedFirstPriceSuccess) {
				loggedFirstPriceSuccess = true;
				ScdLog.info("First price refresh succeeded: " + list.size() + " products from " + config.bazaar.serverUrl);
			}
		}).exceptionally(err -> {
			ScdLog.warn("Failed to refresh prices from " + config.bazaar.serverUrl, err);
			return null;
		});
	}

	/**
	 * Logs any chat/system line that looks Slayer-related, purely so we have
	 * real ground-truth wording from the live server to work from instead of
	 * guessing exact strings (Hypixel's text changes over time) - not
	 * currently used to drive any state, since the scoreboard is authoritative.
	 */
	private void logSlayerChatLines(Component message) {
		String text = message.getString();
		if (text.isBlank()) return;
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("slayer") || lower.contains("slain") || lower.contains("cocoon")) {
			ScdLog.info("Slayer-related message: " + text);
		}
	}

	/**
	 * Watches for the exact line Hypixel sends when The Primordial cocoons a
	 * killed Slayer boss ("YOU COCOONED YOUR SLAYER BOSS"), which respawns it
	 * at full HP 6 seconds later. Checked case-insensitively since chat
	 * formatting/casing can vary.
	 */
	private void checkCocoonMessage(Component message) {
		String upper = message.getString().toUpperCase(java.util.Locale.ROOT);
		if (upper.contains("COCOONED YOUR SLAYER BOSS")) {
			slayerTracker.notifyCocoonTriggered();
			announceSlayer("§d§lBoss cocooned! Respawning in 6s...");
		}
	}

	// Items that auto-deposit straight into a sack never touch the player's inventory, so
	// ScdInventoryWatcher's diff-based drop tracking can't see them (see SkyHanni's SackApi.kt for the
	// same "+5,000 Item Name (Sack Name)" shape). Not anchored to a whole line since this text comes
	// from a hover tooltip that can list several items - matched with find() in a loop instead, and
	// the item name capture is lazy so it stops at the first "(...)" that follows. Requires the player
	// to have Hypixel's "Personal -> Chat Feedback -> Sacks" setting enabled in /sbmenu.
	private static final java.util.regex.Pattern SACK_PICKUP_MESSAGE = java.util.regex.Pattern.compile("([+-][\\d,]+)\\s+(.+?)\\s+\\(([^()]+)\\)");
	// Anchored to the very start of the (trimmed) message - a genuine Hypixel system notification is
	// the whole message with no chat-channel/player-name prefix, whereas pasting this text into party/
	// guild/public chat always has one ("Guild > Player: ...", "Player: ...").
	private static final java.util.regex.Pattern SLAYER_LVL_MESSAGE = java.util.regex.Pattern.compile("^(\\w+) Slayer LVL \\d+");
	private static final java.util.regex.Pattern RNG_METER_MESSAGE = java.util.regex.Pattern.compile("^RNG Meter\\s*-\\s*([\\d,]+) Stored XP");
	// Defensive strip in case some other mod injects bogus §-prefixed junk into chat text the same way
	// it does to the sidebar scoreboard - see ScdSlayerScoreboard.
	private static final java.util.regex.Pattern FORMATTING_CODE = java.util.regex.Pattern.compile("§.");

	/**
	 * Hypixel sends the RNG meter's exact stored-XP value in chat right after
	 * every completed quest ("RNG Meter - 1,141,512 Stored XP"), immediately
	 * preceded by a "<Type> Slayer LVL N" message - no menu reading needed at
	 * all for this one, unlike the RNG meter *percentage*.
	 */
	private void checkSlayerCompletionMessages(Component message) {
		String text = FORMATTING_CODE.matcher(message.getString()).replaceAll("").trim();

		var lvlMatch = SLAYER_LVL_MESSAGE.matcher(text);
		if (lvlMatch.find()) {
			lastCompletedSlayerTypeName = lvlMatch.group(1);
			return;
		}

		var rngMatch = RNG_METER_MESSAGE.matcher(text);
		if (rngMatch.find() && lastCompletedSlayerTypeName != null) {
			for (ScdSlayerType type : ScdSlayerType.values()) {
				if (type.displayName().equalsIgnoreCase(lastCompletedSlayerTypeName)) {
					long storedXp = Long.parseLong(rngMatch.group(1).replace(",", ""));
					slayerRngMeter.recordStoredXp(type, storedXp);
					break;
				}
			}
			lastCompletedSlayerTypeName = null;
		}
	}

	/**
	 * Catches sack pickups, which never touch the inventory at all (the item goes straight into the
	 * sack). Only counts gains ("+"), gated the same way as the inventory-diff path: quest active +
	 * item is a known drop for that type (ScdSlayerDropAllowlist).
	 *
	 * The visible chat line is just a collapsed summary ("[Sacks] +2 items:") - the actual item name/
	 * amount/sack only exists in a hover tooltip attached to that line ("Added items: +2 Enchanted
	 * Ender Pearl (Enchanted Combat Sack)"), which Component.getString() does not include (hover
	 * events are a Style property, not text content). Extracts that hover text via the message's Style
	 * instead, and searches it for every "+N Item (Sack)" occurrence, since a batched pickup can list
	 * more than one item.
	 */
	private void checkSackPickupMessage(Component message) {
		String hoverText = extractHoverText(message);
		String source = hoverText != null ? hoverText : message.getString();
		String clean = FORMATTING_CODE.matcher(source).replaceAll("");

		var matcher = SACK_PICKUP_MESSAGE.matcher(clean);
		while (matcher.find()) {
			String amountText = matcher.group(1);
			if (amountText.startsWith("-")) continue;

			int amount;
			try {
				amount = Integer.parseInt(amountText.substring(1).replace(",", ""));
			} catch (NumberFormatException e) {
				continue;
			}

			String itemName = matcher.group(2).trim();
			ScdSlayerQuest activeQuest = slayerTracker.currentQuestOrNull();
			ScdSlayerType type = activeQuest != null ? activeQuest.type() : slayerTracker.recentlyEndedTypeForLootOrNull();
			if (type == null) {
				ScdLog.info("Sack pickup: " + itemName + " x" + amount + " -> not recorded (no active or recently-ended Slayer quest)");
			} else if (!ScdSlayerDropAllowlist.isKnownDrop(type, null, itemName)) {
				ScdLog.info("Sack pickup: " + itemName + " x" + amount + " -> not recorded (not on " + type.displayName() + "'s drop allowlist)");
			} else {
				ScdLog.info("Sack pickup: " + itemName + " x" + amount + " -> recorded as a " + type.displayName() + " drop");
				slayerDrops.record(type, "SACK:" + itemName, itemName, amount);
			}
		}
	}

	/** Walks a chat component and its siblings for the first attached "show text" hover tooltip, returning its own text - null if none of them have one. */
	private static String extractHoverText(Component component) {
		var hoverEvent = component.getStyle().getHoverEvent();
		if (hoverEvent instanceof net.minecraft.network.chat.HoverEvent.ShowText showText) {
			return showText.value().getString();
		}
		for (Component sibling : component.getSiblings()) {
			String found = extractHoverText(sibling);
			if (found != null) return found;
		}
		return null;
	}

	/** Client-local chat line (never sent to the server/other players) used for Slayer spawn/kill alerts. */
	private void announceSlayer(String text) {
		var player = Minecraft.getInstance().player;
		if (player != null) player.sendSystemMessage(Component.literal(text));
	}

	/**
	 * Fired by ScdCarryBossWatcher once a specific carry entry's OWN boss (found
	 * via its "Spawned by: &lt;playerName&gt;" ownership tag, independent of
	 * the local player's own Slayer quest - see ScdCarryBossWatcher) is
	 * confirmed dead. Credits the kill, then announces the new progress in
	 * party chat - unlike announceSlayer above, this is a real message sent to
	 * the server (via "/pc", the same as if it had been typed), since the
	 * whole point is for the customer being carried to see it. Reaching the
	 * target count doesn't close the carry by itself (see
	 * ScdCarryQueue.creditKill) - it instead prints a client-local, clickable
	 * follow-up prompt (see promptCarryTargetReached) so closing out and
	 * asking for a review stays a deliberate action.
	 */
	private void handleCarryBossKilled(ScdCarryEntry entry, long elapsedMs) {
		boolean justReachedTarget = carryQueue.creditKill(entry, elapsedMs);
		sendPartyChat(entry.playerName + ": " + entry.killsCompleted + "/" + entry.killsOwed + " kills");
		if (justReachedTarget) {
			promptCarryTargetReached(entry);
		}
	}

	/**
	 * Client-local (never sent to the server) chat prompt with clickable
	 * buttons, shown once a carry first hits its target kill count: "Done"
	 * closes it out and sends the review message the same as the GUI button;
	 * "+5"/"+10" extend it at its own already-agreed price without opening any
	 * screen; "Custom" fills the chat input with the extend command and lets
	 * the amount be typed in, rather than running it immediately. Not yet
	 * confirmed live whether Hypixel or another mod intercepts/strips click
	 * events on received chat lines - if these buttons don't respond, check
	 * that first.
	 */
	private void promptCarryTargetReached(ScdCarryEntry entry) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;

		MutableComponent line = Component.literal("[SCD] " + entry.playerName + "'s carry hit "
				+ entry.killsCompleted + "/" + entry.killsOwed + "! ").withStyle(net.minecraft.ChatFormatting.AQUA);
		line.append(chatButton("Done", "/scd carry completeid " + entry.id, true, "Close this carry and send the review message"));
		line.append(Component.literal("  "));
		line.append(chatButton("+5", "/scd carry extendid " + entry.id + " 5", true, "Add 5 more " + entry.typeEnum().displayName() + " bosses at the same price"));
		line.append(Component.literal("  "));
		line.append(chatButton("+10", "/scd carry extendid " + entry.id + " 10", true, "Add 10 more " + entry.typeEnum().displayName() + " bosses at the same price"));
		line.append(Component.literal("  "));
		line.append(chatButton("Custom", "/scd carry extendid " + entry.id + " ", false, "Fill the chat box to type a custom amount"));
		player.sendSystemMessage(line);
	}

	/** One clickable "[Label]" chat segment - runImmediately true executes the command on click (ClickEvent.RunCommand), false only fills the chat input box for editing first (ClickEvent.SuggestCommand). */
	private static MutableComponent chatButton(String label, String command, boolean runImmediately, String hoverText) {
		var click = runImmediately ? new net.minecraft.network.chat.ClickEvent.RunCommand(command)
				: new net.minecraft.network.chat.ClickEvent.SuggestCommand(command);
		Style style = Style.EMPTY.withColor(net.minecraft.ChatFormatting.YELLOW).withUnderlined(true)
				.withClickEvent(click)
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(hoverText)));
		return Component.literal("[" + label + "]").setStyle(style);
	}

	/**
	 * Called from ScdCarryQueueScreen's "Done" button - manually closing out a
	 * carry (whether or not the kill count actually reached killsOwed) sends a
	 * closing party-chat message asking for a review, separate from the
	 * automatic per-kill progress/completion pings above.
	 */
	public void finishCarryManually(ScdCarryEntry entry) {
		carryQueue.markComplete(entry.id);
		sendPartyChat("gg " + entry.playerName + " Please leave a review in #reviews in the relevant Discord");
	}

	/**
	 * Sends a real message to party chat, exactly as if "/pc <text>" had been
	 * typed and submitted - goes through the same client connection method the
	 * chat screen itself uses for a slash-prefixed command, so signing/
	 * whatever else the server expects is handled the same way it always is.
	 */
	private void sendPartyChat(String text) {
		var player = Minecraft.getInstance().player;
		if (player != null && player.connection != null) player.connection.sendCommand("pc " + text);
	}

	private void openConfigScreen() {
		// Submitting a chat command closes the chat screen right after this callback
		// returns, which would immediately stomp a setScreen() called from inside it -
		// deferring to the next client tick lets our screen "win".
		Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ScdConfigScreen(config, this)));
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext) {
		var root = ClientCommands.literal("scd")
				.executes(ctx -> {
					openConfigScreen();
					return 1;
				})
				.then(ClientCommands.literal("price")
						.then(ClientCommands.argument("item", StringArgumentType.greedyString())
								.executes(ctx -> {
									String query = StringArgumentType.getString(ctx, "item");
									var source = ctx.getSource();
									api.search(query).thenAccept(results -> {
										if (results.isEmpty()) {
											source.sendFeedback(Component.literal("No bazaar item matches \"" + query + "\""));
											return;
										}
										var p = results.get(0);
										source.sendFeedback(Component.literal(
												p.name() + " -> instasell " + ScdFormat.coins(p.sellPrice(), 2) + ", instabuy " + ScdFormat.coins(p.buyPrice(), 2)));
									});
									return 1;
								})))
				.then(ClientCommands.literal("server")
						.then(ClientCommands.argument("url", StringArgumentType.string())
								.executes(ctx -> {
									String url = StringArgumentType.getString(ctx, "url");
									config.bazaar.serverUrl = url;
									config.save();
									api.setBaseUrl(url);
									ctx.getSource().sendFeedback(Component.literal("SCD server set to " + url));
									return 1;
								})))
				.then(ClientCommands.literal("config")
						.executes(ctx -> {
							openConfigScreen();
							return 1;
						}))
				.then(ClientCommands.literal("dev")
						.then(ClientCommands.argument("code", StringArgumentType.word())
								.executes(ctx -> {
									handleDevCommand(ctx.getSource(), StringArgumentType.getString(ctx, "code"));
									return 1;
								})))
				.then(ClientCommands.literal("debug")
						.requires(source -> config.devUnlocked)
						.executes(ctx -> {
							runDebugReport(ctx.getSource());
							return 1;
						})
						// Throwaway proof-of-concept for the vanilla Gizmos rendering API - see
						// ScdGizmoTest and FEATURE_ROADMAP.md's "T2/T3 re-scoped" section. Delete
						// alongside that class once it's served its purpose.
						.then(ClientCommands.literal("gizmotest")
								.executes(ctx -> {
									gizmoTest.toggle();
									ctx.getSource().sendFeedback(Component.literal(gizmoTest.isActive()
											? "Gizmo test ON - a red arrow (normal) and a green arrow (always-on-top), side by side, should point 10 blocks out from where you're looking, with START/TARGET labels. Walk behind a wall and see which arrow disappears."
											: "Gizmo test OFF."));
									return 1;
								}))
						.then(ClientCommands.literal("item")
								.then(ClientCommands.literal("nbt")
										.executes(ctx -> {
											reportLastHoveredNbt(ctx.getSource());
											return 1;
										}))))
				.then(ClientCommands.literal("slayer")
						.then(ClientCommands.literal("debug")
								.requires(source -> config.devUnlocked)
								.executes(ctx -> {
									reportSlayerDebug(ctx.getSource());
									return 1;
								})
								.then(ClientCommands.literal("nearby")
										.executes(ctx -> {
											reportNearbySlayerEntities(ctx.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("scoreboard")
										.executes(ctx -> {
											reportSlayerScoreboard(ctx.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("menu")
										.then(ClientCommands.literal("dump")
												.executes(ctx -> {
													armMenuDump = true;
													ctx.getSource().sendFeedback(Component.literal(
															"Armed - open the Slayer menu now, its contents will be dumped to chat and logs/latest.log."));
													return 1;
												}))))
						.then(ClientCommands.literal("drops")
								.then(ClientCommands.literal("list")
										.then(ClientCommands.argument("type", StringArgumentType.word())
												.executes(ctx -> {
													reportSlayerDrops(ctx.getSource(), StringArgumentType.getString(ctx, "type"));
													return 1;
												})))
								.then(ClientCommands.literal("clear")
										.then(ClientCommands.argument("type", StringArgumentType.word())
												.executes(ctx -> {
													clearSlayerDrops(ctx.getSource(), StringArgumentType.getString(ctx, "type"));
													return 1;
												})))
								.then(ClientCommands.literal("remove")
										.then(ClientCommands.argument("type", StringArgumentType.word())
												.then(ClientCommands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
														.executes(ctx -> {
															removeSlayerDrop(ctx.getSource(), StringArgumentType.getString(ctx, "type"),
																	com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"));
															return 1;
														}))))))
				.then(ClientCommands.literal("carry")
						.then(ClientCommands.literal("list")
								.executes(ctx -> {
									reportCarries(ctx.getSource());
									return 1;
								}))
						.then(ClientCommands.literal("add")
								.then(ClientCommands.argument("player", StringArgumentType.word())
										.then(ClientCommands.argument("type", StringArgumentType.word())
												.then(ClientCommands.argument("tier", StringArgumentType.word())
														.then(ClientCommands.argument("pricePerKill", StringArgumentType.word())
																.then(ClientCommands.argument("bossCount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
																		.executes(ctx -> {
																			addCarryCommand(ctx.getSource(),
																					StringArgumentType.getString(ctx, "player"),
																					StringArgumentType.getString(ctx, "type"),
																					StringArgumentType.getString(ctx, "tier"),
																					StringArgumentType.getString(ctx, "pricePerKill"),
																					com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "bossCount"));
																			return 1;
																		})))))))
						.then(ClientCommands.literal("complete")
								.then(ClientCommands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
										.executes(ctx -> {
											completeCarryCommand(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"));
											return 1;
										})))
						// completeid/extendid address an entry by its stable id rather than its
						// position in the list - these back the clickable chat buttons in
						// promptCarryTargetReached, where an index could shift by the time it's
						// actually clicked.
						.then(ClientCommands.literal("completeid")
								.then(ClientCommands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg())
										.executes(ctx -> {
											completeCarryByIdCommand(ctx.getSource(), com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"));
											return 1;
										})))
						.then(ClientCommands.literal("extendid")
								.then(ClientCommands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg())
										.then(ClientCommands.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
												.executes(ctx -> {
													extendCarryByIdCommand(ctx.getSource(), com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"),
															com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "amount"));
													return 1;
												}))))
						.then(ClientCommands.literal("debug")
								.requires(source -> config.devUnlocked)
								.executes(ctx -> {
									reportCarryDebug(ctx.getSource());
									return 1;
								})));

		dispatcher.register(root);
	}

	private static final String DEV_UNLOCK_CODE = "1234";

	/** Gate for the diagnostic commands above - see the devUnlocked field comment in ScdConfig. */
	private void handleDevCommand(FabricClientCommandSource source, String code) {
		if ("off".equalsIgnoreCase(code)) {
			config.devUnlocked = false;
			config.save();
			source.sendFeedback(Component.literal("Developer commands locked."));
			return;
		}
		if (DEV_UNLOCK_CODE.equals(code)) {
			config.devUnlocked = true;
			config.save();
			source.sendFeedback(Component.literal(
					"Developer commands unlocked: /scd debug (gizmotest, item nbt), /scd slayer debug (nearby, scoreboard, menu dump), /scd carry debug."));
		} else {
			source.sendFeedback(Component.literal("Incorrect code."));
		}
	}

	private ScdSlayerType parseSlayerType(FabricClientCommandSource source, String typeText) {
		for (ScdSlayerType type : ScdSlayerType.values()) {
			if (type.name().equalsIgnoreCase(typeText) || type.displayName().equalsIgnoreCase(typeText)) return type;
		}
		source.sendFeedback(Component.literal("Unknown Slayer type \"" + typeText + "\" - try one of: "
				+ java.util.Arrays.stream(ScdSlayerType.values()).map(ScdSlayerType::displayName).collect(Collectors.joining(", "))));
		return null;
	}

	/** Full drop list for one Slayer type, indexed for use with "/scd slayer drops remove". */
	private void reportSlayerDrops(FabricClientCommandSource source, String typeText) {
		ScdSlayerType type = parseSlayerType(source, typeText);
		if (type == null) return;

		var entries = slayerDrops.entriesForType(type);
		source.sendFeedback(Component.literal("=== " + type.displayName() + " Slayer drops (" + entries.size() + ") ==="));
		if (entries.isEmpty()) {
			source.sendFeedback(Component.literal("Nothing recorded yet."));
			return;
		}
		for (int i = 0; i < entries.size(); i++) {
			var entry = entries.get(i);
			source.sendFeedback(Component.literal((i + 1) + ". " + entry.displayName() + ": " + ScdFormat.compactCount(entry.count())));
		}
		source.sendFeedback(Component.literal("Remove one with /scd slayer drops remove " + type.name().toLowerCase(java.util.Locale.ROOT) + " <number>"));
	}

	private void clearSlayerDrops(FabricClientCommandSource source, String typeText) {
		ScdSlayerType type = parseSlayerType(source, typeText);
		if (type == null) return;
		slayerDrops.clear(type);
		source.sendFeedback(Component.literal("Cleared all recorded " + type.displayName() + " Slayer drops."));
	}

	private void removeSlayerDrop(FabricClientCommandSource source, String typeText, int oneBasedIndex) {
		ScdSlayerType type = parseSlayerType(source, typeText);
		if (type == null) return;

		var entries = slayerDrops.entriesForType(type);
		int index = oneBasedIndex - 1;
		if (index < 0 || index >= entries.size()) {
			source.sendFeedback(Component.literal("No drop #" + oneBasedIndex + " - run /scd slayer drops list " + typeText + " first."));
			return;
		}
		var entry = entries.get(index);
		slayerDrops.remove(type, entry.itemId());
		source.sendFeedback(Component.literal("Removed \"" + entry.displayName() + "\" from " + type.displayName() + " Slayer drops."));
	}

	/** Full carry list for chat-only use, indexed for use with "/scd carry complete". */
	private void reportCarries(FabricClientCommandSource source) {
		var entries = carryQueue.all();
		source.sendFeedback(Component.literal("=== Carries (" + entries.size() + ") ==="));
		if (entries.isEmpty()) {
			source.sendFeedback(Component.literal("No carries yet."));
			return;
		}
		for (int i = 0; i < entries.size(); i++) {
			var entry = entries.get(i);
			String progress = entry.isActive() ? entry.killsCompleted + "/" + entry.killsOwed : "done";
			source.sendFeedback(Component.literal((i + 1) + ". " + entry.playerName + " - "
					+ entry.typeEnum().displayName() + " " + entry.tier + " (" + progress + ")"));
		}
		source.sendFeedback(Component.literal("Mark one finished with /scd carry complete <number>"));
	}

	private static final List<String> CARRY_TIERS = List.of("I", "II", "III", "IV", "V");

	private void addCarryCommand(FabricClientCommandSource source, String player, String typeText, String tier, String pricePerKillText, long bossCount) {
		ScdSlayerType type = parseSlayerType(source, typeText);
		if (type == null) return;
		String tierUpper = tier.toUpperCase(java.util.Locale.ROOT);
		if (!CARRY_TIERS.contains(tierUpper)) {
			source.sendFeedback(Component.literal("Unknown tier \"" + tier + "\" - try one of: " + String.join(", ", CARRY_TIERS)));
			return;
		}
		tier = tierUpper;
		Long pricePerKill = ScdFormat.parseCompactLong(pricePerKillText);
		if (pricePerKill == null || pricePerKill <= 0) {
			source.sendFeedback(Component.literal("Invalid price per kill \"" + pricePerKillText + "\" - try a plain number or e.g. 1.3m, 800k."));
			return;
		}
		ScdCarryEntry entry = carryQueue.add(player, type, tier, pricePerKill, pricePerKill * bossCount);
		source.sendFeedback(Component.literal("Added carry for " + player + ": " + entry.typeEnum().displayName()
				+ " " + tier + ", " + entry.killsOwed + " kills for " + ScdFormat.coins(entry.totalAmount) + " coins."));
	}

	private void completeCarryCommand(FabricClientCommandSource source, int oneBasedIndex) {
		var entries = carryQueue.all();
		int index = oneBasedIndex - 1;
		if (index < 0 || index >= entries.size()) {
			source.sendFeedback(Component.literal("No carry #" + oneBasedIndex + " - run /scd carry list first."));
			return;
		}
		var entry = entries.get(index);
		if (!entry.isActive()) {
			source.sendFeedback(Component.literal("That carry is already finished."));
			return;
		}
		carryQueue.markComplete(entry.id);
		source.sendFeedback(Component.literal("Marked carry for " + entry.playerName + " as finished."));
	}

	/** Backs the "Done" chat button in promptCarryTargetReached - unlike completeCarryCommand above (index-based, chat-only, no review message), this matches the GUI "Done" button exactly: marks finished AND sends the review prompt. */
	private void completeCarryByIdCommand(FabricClientCommandSource source, long id) {
		ScdCarryEntry entry = carryQueue.findByIdOrNull(id);
		if (entry == null || !entry.isActive()) {
			source.sendFeedback(Component.literal("That carry is already finished or no longer exists."));
			return;
		}
		finishCarryManually(entry);
		source.sendFeedback(Component.literal("Marked carry for " + entry.playerName + " as finished."));
	}

	/** Backs the "+5"/"+10"/"Custom" chat buttons in promptCarryTargetReached. */
	private void extendCarryByIdCommand(FabricClientCommandSource source, long id, long additionalBossCount) {
		ScdCarryEntry entry = carryQueue.findByIdOrNull(id);
		if (entry == null) {
			source.sendFeedback(Component.literal("That carry no longer exists."));
			return;
		}
		carryQueue.extend(id, additionalBossCount);
		source.sendFeedback(Component.literal("Added " + additionalBossCount + " more " + entry.typeEnum().displayName()
				+ " bosses for " + entry.playerName + " (now " + entry.killsOwed + " total)."));
	}

	/**
	 * Diagnostic for "the carry isn't tracking that IGN's boss" reports: for
	 * every active carry, shows whether a "Spawned by: &lt;playerName&gt;"
	 * boss for that exact type is findable right now, and what
	 * ScdCarryBossWatcher currently has tracked for it - the same live-vs-
	 * tracker-belief split as /scd slayer debug.
	 */
	private void reportCarryDebug(FabricClientCommandSource source) {
		var entries = carryQueue.active();
		source.sendFeedback(Component.literal("=== Carry Debug (" + entries.size() + " active) ==="));
		if (entries.isEmpty()) {
			source.sendFeedback(Component.literal("No active carries."));
			return;
		}
		for (ScdCarryEntry entry : entries) {
			String line = entry.playerName + " - " + entry.typeEnum().displayName() + " " + entry.tier + ": "
					+ carryBossWatcher.describeLive(entry);
			source.sendFeedback(Component.literal(line));
			ScdLog.info("carry debug: " + line);
		}
	}

	/**
	 * Dumps every slot's item name + lore for whatever screen just opened - armed by
	 * "/scd slayer debug menu dump". AbstractContainerMenu.slots is a plain client-visible field for any
	 * open container screen, so this works for the Slayer NPC menu the same as any chest.
	 */
	private void dumpScreenContents(Screen screen) {
		String title = screen.getTitle().getString();
		ScdLog.info("=== /scd slayer debug menu dump: " + screen.getClass().getSimpleName() + " (title=\"" + title + "\") ===");

		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
			announceSlayer("§e[SCD] Opened screen \"" + title + "\" (" + screen.getClass().getSimpleName() + ") isn't a container screen - nothing to dump.");
			return;
		}

		announceSlayer("§a[SCD] Dumping \"" + title + "\" (" + containerScreen.getMenu().slots.size() + " slots) - full text in logs/latest.log");
		for (var slot : containerScreen.getMenu().slots) {
			if (!slot.hasItem()) continue;
			var stack = slot.getItem();
			ItemLore lore = stack.get(DataComponents.LORE);
			StringBuilder sb = new StringBuilder("[" + slot.index + "] " + stack.getHoverName().getString());
			if (lore != null) {
				for (var line : lore.lines()) sb.append(" | ").append(line.getString());
			}
			ScdLog.info(sb.toString());
		}
	}

	/**
	 * Diagnostic for item categories whose Bazaar identity isn't just the flat
	 * "id" field (enchanted books - which all share id=ENCHANTED_BOOK
	 * regardless of enchantment - being the prompting case). Prints the full
	 * custom_data NBT of whatever item was last hovered, since you can't type
	 * a command while a tooltip/inventory screen is actually open.
	 */
	private void reportLastHoveredNbt(FabricClientCommandSource source) {
		String nbt = hoverState.lastRawNbtOrNull();
		if (nbt == null) {
			source.sendFeedback(Component.literal("No item hovered yet this session - hover one, then run this again."));
			return;
		}
		source.sendFeedback(Component.literal("=== Last hovered item NBT (full text in logs/latest.log) ==="));
		int chunkSize = 200;
		for (int i = 0; i < nbt.length(); i += chunkSize) {
			source.sendFeedback(Component.literal(nbt.substring(i, Math.min(nbt.length(), i + chunkSize))));
		}
		ScdLog.info("=== /scd item nbt ===");
		ScdLog.info(nbt);
	}

	/**
	 * Diagnostic for "the slayer box never shows up" reports: prints every
	 * nearby living entity's raw nameplate and whether our boss matcher
	 * accepts it, so a mismatch (extra text, wrong entity, out of range) is
	 * visible directly instead of guessed at.
	 */
	private void reportNearbySlayerEntities(FabricClientCommandSource source) {
		List<String> lines = slayerTracker.describeNearby(48.0);
		source.sendFeedback(Component.literal("=== Nearby entities (slayer boss match check) ==="));
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		ScdLog.info("=== /scd slayer debug nearby ===");
		for (String line : lines) {
			ScdLog.info(line);
		}
	}

	/**
	 * Diagnostic for "quest detection just doesn't fire, but works fine in a
	 * clean instance" reports - points at another mod interfering with the
	 * scoreboard specifically (a known category of conflict for "all-in-one"
	 * Hypixel QoL mods that reskin/declutter the sidebar) rather than a crash
	 * in our own code, which would already show up via the guarded tick/HUD
	 * logging. Dumps exactly what ScdSlayerScoreboard sees.
	 */
	private void reportSlayerScoreboard(FabricClientCommandSource source) {
		List<String> lines = ScdSlayerScoreboard.describeRaw();
		source.sendFeedback(Component.literal("=== Sidebar scoreboard (slayer quest detection check) ==="));
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		ScdLog.info("=== /scd slayer debug scoreboard ===");
		for (String line : lines) {
			ScdLog.info(line);
		}
	}

	/**
	 * One combined, copy-from-chat snapshot: is the HUD callback even firing,
	 * what does the tracker currently believe, what's actually on the
	 * scoreboard, and what's the config state - everything needed to diagnose
	 * "it doesn't show up" without asking for a log file.
	 */
	private void reportSlayerDebug(FabricClientCommandSource source) {
		List<String> lines = new java.util.ArrayList<>();
		lines.add("=== SCD Slayer Debug ===");
		lines.add("bossTrackerEnabled=" + config.slayer.bossTrackerEnabled);
		lines.add("HUD render callback fired " + slayerHud.renderCallCount() + " times this session");
		for (ScdSlayerType type : ScdSlayerType.values()) {
			if (ScdSlayerAreaAllowlist.isRestricted(type)) {
				lines.add(type.displayName() + " area check: " + (ScdSlayerScoreboard.isInAllowedArea(type) ? "PASS (in an allowed area)" : "FAIL (not in an allowed area)"));
			}
		}
		lines.add("--- tracker state ---");
		lines.addAll(slayerTracker.describeState());
		lines.add("--- scoreboard ---");
		lines.addAll(ScdSlayerScoreboard.describeRaw());

		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		for (String line : lines) {
			ScdLog.info(line);
		}
	}

	/**
	 * Dumps environment, config, and live connectivity info to both chat and
	 * the log, for diagnosing "it doesn't work" reports - especially mod
	 * conflicts, which show up as another loaded mod ID sharing a Fabric API
	 * hook (tooltip/HUD/tick), or a version mismatch between SCD's declared
	 * Fabric API dependency and what's actually installed.
	 */
	private void runDebugReport(FabricClientCommandSource source) {
		var loader = FabricLoader.getInstance();
		String mcVersion = loader.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		String loaderVersion = loader.getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		String scdVersion = loader.getModContainer("scd").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		var allMods = loader.getAllMods();

		source.sendFeedback(Component.literal("=== SCD Debug ==="));
		source.sendFeedback(Component.literal("SCD " + scdVersion + " | MC " + mcVersion + " | Fabric Loader " + loaderVersion + " | Java " + System.getProperty("java.version")));
		source.sendFeedback(Component.literal(allMods.size() + " mods loaded (full list -> logs/latest.log)"));
		source.sendFeedback(Component.literal("Server: " + config.bazaar.serverUrl + " | cached " + prices.size() + " items"));
		source.sendFeedback(Component.literal("Tooltip=" + config.bazaar.tooltipEnabled + " Graph=" + config.bazaar.graphEnabled + " BossTracker=" + config.slayer.bossTrackerEnabled));
		source.sendFeedback(Component.literal("Checking server connectivity..."));

		ScdLog.info("=== /scd debug ===");
		ScdLog.info("SCD " + scdVersion + " on MC " + mcVersion + ", Fabric Loader " + loaderVersion
				+ ", Java " + System.getProperty("java.version") + ", OS " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
		ScdLog.info("Config: bazaar.serverUrl=" + config.bazaar.serverUrl + " bazaar.tooltipEnabled=" + config.bazaar.tooltipEnabled
				+ " bazaar.graphEnabled=" + config.bazaar.graphEnabled + " slayer.bossTrackerEnabled=" + config.slayer.bossTrackerEnabled);
		ScdLog.info("Price cache: " + prices.size() + " items");
		String modList = allMods.stream()
				.map(c -> c.getMetadata().getId() + "@" + c.getMetadata().getVersion().getFriendlyString())
				.sorted()
				.collect(Collectors.joining(", "));
		ScdLog.info("Loaded mods (" + allMods.size() + "): " + modList);

		long start = System.currentTimeMillis();
		api.fetchAll().whenComplete((result, err) -> Minecraft.getInstance().execute(() -> {
			long elapsedMs = System.currentTimeMillis() - start;
			if (err != null) {
				ScdLog.warn("Debug connectivity check to " + config.bazaar.serverUrl + " failed after " + elapsedMs + "ms", err);
				source.sendFeedback(Component.literal("Server check FAILED after " + elapsedMs + "ms: " + err.getMessage()));
			} else {
				ScdLog.info("Debug connectivity check to " + config.bazaar.serverUrl + " succeeded in " + elapsedMs + "ms, " + result.size() + " products");
				source.sendFeedback(Component.literal("Server check OK in " + elapsedMs + "ms, " + result.size() + " products"));
			}
		}));
	}
}
