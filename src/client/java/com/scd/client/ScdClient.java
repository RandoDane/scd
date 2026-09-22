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
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Comparator;
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
	private ScdDungeonScoreHud dungeonScoreHud;
	private ScdSlayerRngMeter slayerRngMeter;
	private ScdSlayerDrops slayerDrops;
	private ScdCarryQueue carryQueue;
	private ScdDungeonCarryQueue dungeonCarryQueue;
	private final ScdCarryBossWatcher carryBossWatcher = new ScdCarryBossWatcher(this::handleCarryBossKilled);
	private final ScdInventoryWatcher inventoryWatcher = new ScdInventoryWatcher();
	private final ScdAccessoryData accessoryData = new ScdAccessoryData();
	private final ScdAccessoryBagWatcher accessoryBagWatcher = new ScdAccessoryBagWatcher();
	private final ScdDungeonRoomScanner dungeonRoomScanner = new ScdDungeonRoomScanner();
	// Armed via /scd dungeon debug capture - logs every chat/system message plus a periodic
	// scoreboard snapshot to logs/latest.log while a real dungeon run happens, so the actual
	// end-of-run summary text (and its trigger) can be captured and read back rather than guessed.
	// See FEATURE_ROADMAP.md §3's "Dungeon run-completion signal - still open" note.
	private boolean dungeonDebugCaptureActive = false;
	private int dungeonDebugScoreboardTickCounter = 0;
	// True only while the vanilla Accessory Bag screen has been open continuously since the last
	// non-bag screen - tracked here rather than inferred from the watcher's own state, since Hypixel
	// sends a genuinely new Screen instance per page (confirmed live: this AFTER_INIT block fires on
	// every page turn, not just the first open), so "is this screen page 1" alone can't tell a fresh
	// visit apart from paging back to 1 mid-session. Gates accessoryBagWatcher.reset() - see its own
	// updated doc comment.
	private boolean accessoryBagSessionOpen = false;
	private static final int MISSING_GRID_COLUMNS = 8;
	private static final int MISSING_GRID_ROWS = 5;
	private static final int MISSING_ICON_SIZE = 16;
	private static final int MISSING_ICON_BOX_PADDING = 2;
	private static final int MISSING_ICON_BOX_SIZE = MISSING_ICON_SIZE + MISSING_ICON_BOX_PADDING * 2;
	private static final int MISSING_ICON_GAP = 2;
	private static final int MISSING_ACCESSORIES_PAGE_SIZE = MISSING_GRID_COLUMNS * MISSING_GRID_ROWS;
	private int missingAccessoriesPage = 0;
	/**
	 * MAX (default): rarity-best-first, i.e. whichever missing tier is closest to "maxed" for that
	 * family, since getMissingAccessories already collapses each family to its single highest-rarity
	 * missing (or next-upgrade) representative. PRICE: lowest coin cost first, the cheapest talismans
	 * to actually go buy right now. BEST: lowest coins-per-Magical-Power first (price /
	 * magicalPowerGain - the real marginal cost of the power this purchase adds, see
	 * MissingAccessory's own doc comment), the best value regardless of raw price - entries with no
	 * price data sort last (there's nothing to rank them by) rather than being hidden outright, so
	 * "how many things are missing" still reads correctly even in this mode.
	 */
	private enum MissingSortMode { MAX, PRICE, BEST }
	private MissingSortMode missingSortMode = MissingSortMode.MAX;
	// Instantiated once and repositioned/toggled each frame rather than per-screen - the vanilla
	// Accessory Bag screen isn't ours to addRenderableWidget() into, so these are driven manually:
	// extractRenderState() called from renderAccessoryBagOverlay, mouseClicked() called from the
	// ScreenMouseEvents.allowMouseClick hook registered alongside it (see handleAccessoryOverlayClick).
	// Being long-lived fields (constructed once at mod startup, not rebuilt per Screen-open like
	// every other button in the mod) means setAccentColor() must also be called every frame these
	// render, or they keep whatever accent was live at startup forever - confirmed live 2026-09-22,
	// see ScdButton.setAccentColor's own doc comment. Any new button added here needs the same care.
	private final ScdButton missingAccessoriesPrevButton = new ScdButton(0, 0, 16, 16, Component.literal("< Prev"), ScdTheme.ACCENT_ACCESSORIES, () -> missingAccessoriesPage--);
	private final ScdButton missingAccessoriesNextButton = new ScdButton(0, 0, 16, 16, Component.literal("Next >"), ScdTheme.ACCENT_ACCESSORIES, () -> missingAccessoriesPage++);
	private final ScdButton missingAccessoriesSortButton = new ScdButton(0, 0, 80, 16, Component.literal("Max"), ScdTheme.ACCENT_ACCESSORIES, () -> {
		missingSortMode = MissingSortMode.values()[(missingSortMode.ordinal() + 1) % MissingSortMode.values().length];
		missingAccessoriesPage = 0;
	});
	// Throwaway proof-of-concept for the entity-glow mixin (see FEATURE_ROADMAP.md's "T2/T3
	// re-scoped" section) - /scd debug glowtest toggles this, the registered adder below does the
	// rest. Remove alongside its adder/command once confirmed live.
	private volatile boolean glowTestActive = false;
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
		ScdTheme.applyTheme(ScdHudTheme.byName(config.menuTheme));
		api = new ScdApiClient(config.bazaar.serverUrl);
		ScdDungeonCompletion.setListener(this::handleDungeonCompletion);
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
		dungeonCarryQueue = ScdDungeonCarryQueue.load();
		slayerStatsHud = new ScdSlayerStatsHud(config, slayerSessionStats, slayerTracker, mayorPerks);
		slayerHud = new ScdSlayerHud(config, slayerTracker, slayerRecords, slayerRngMeter, slayerDrops, slayerStatsHud);
		slayerHud.register();
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
					Long meterBefore = slayerRngMeter.storedXp(quest.type());
					slayerRngMeter.recordKillTowardMeterEstimate(quest.type(), quest.tier(), xpGained);
					Long meterAfter = slayerRngMeter.storedXp(quest.type());
					// Verification logging for the "RNG meter isn't accounting for tier/mayor boost"
					// report - the formula/keying already looked correct from reading the code, so this
					// gives hard proof of exactly what happened on the next real kill instead of guessing.
					ScdLog.info("RNG meter estimate: " + quest.type().displayName() + " " + quest.tier()
							+ " kill -> baseXp=" + baseXp + " x mayorMultiplier=" + mayorPerks.xpMultiplier()
							+ " (source=" + mayorPerks.sourceMayorNameOrNull() + ") = xpGained=" + xpGained
							+ " | meter " + meterBefore + " -> " + meterAfter);
				} else {
					ScdLog.info("RNG meter estimate: no baseXp for tier \"" + quest.tier() + "\" - nothing added.");
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
		dungeonScoreHud = new ScdDungeonScoreHud(config, mayorPerks);
		dungeonScoreHud.register();

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
		ClientTickEvents.END_CLIENT_TICK.register(mc -> ScdLog.guard("dungeon room scan", () -> dungeonRoomScanner.tick(api, config.dungeon.roomMappingEnabled)));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> ScdLog.guard("dungeon debug capture", this::tickDungeonDebugCapture));
		// A HUD element that renders nothing, purely to piggyback ticking the inventory watcher onto
		// HudElementRegistry rather than the shared ClientTickEvents.END_CLIENT_TICK above - see
		// ScdSlayerHud.register() for why that event can go silent in a heavily modded environment.
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath("scd", "inventory_watch_ticker"),
				(graphics, deltaTracker) -> ScdLog.guard("inventory watch", () -> inventoryWatcher.tick(attributeShards)));
		// Same reliable-ticking trick, for the Slayer boss world-space highlight (ScdGizmoUtil calls
		// need to happen every frame to keep showing - see ScdGizmoUtil's own class doc).
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath("scd", "slayer_boss_highlight_ticker"),
				(graphics, deltaTracker) -> ScdLog.guard("slayer boss highlight", this::renderBossHighlight));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> ScdLog.guard("slayer chat watch", () -> {
			logSlayerChatLines(message);
			checkCocoonMessage(message);
			checkSlayerCompletionMessages(message);
			checkSackPickupMessage(message);
			checkDungeonDebugCapture(message);
		}));
		// Dungeon end-of-run summary confirmed live 2026-09-22 to NOT come through GAME above at
		// all - it only ever showed up under vanilla's own raw chat log, never under our
		// "[DUNGEON-DEBUG] chat:" tag from the GAME hook. CHAT (the signed/player-attributed
		// message event, which Fabric keeps separate from GAME's unsigned system messages) is the
		// most likely real channel for it - registered here so the next capture run confirms or
		// corrects that.
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, senderEntry, indicator) ->
				ScdLog.guard("dungeon chat watch", () -> checkDungeonDebugCapture(message, "CHAT")));
		// A second real capture (also 2026-09-22) showed CHAT missed it too - the summary box isn't
		// arriving through either normal "received" event. ALLOW_GAME/ALLOW_CHAT fire earlier, before
		// another mod's own ALLOW handler gets a chance to cancel/suppress a message, so registering
		// here (always returning true - purely observing, never blocking) rules in or out "Hypixel
		// sent it and Skyblocker/SkyHanni is suppressing+replacing it" vs. "it's never a real network
		// message at all, just rendered straight into chat by another mod's own code" - the latter
		// would mean no Fabric chat event was ever going to see it, no matter which one we pick.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			ScdLog.guard("dungeon chat watch", () -> checkDungeonDebugCapture(message, "ALLOW_GAME"));
			return true;
		});
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, senderEntry, indicator) -> {
			ScdLog.guard("dungeon chat watch", () -> checkDungeonDebugCapture(message, "ALLOW_CHAT"));
			return true;
		});
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

			if (ScdAccessoryBagWatcher.isAccessoryBagScreen(screen) && config.accessories.missingAccessoriesOverlayEnabled) {
				// Reset only on the transition INTO the bag from something else, not on every page-1
				// screen (Hypixel sends a fresh Screen per page turn, so this AAFTER_INIT block fires on
				// every page, not just the first) - otherwise paging 1->2->3->1 wiped the scan right back
				// to empty. See accessoryBagSessionOpen's own doc comment.
				if (!accessoryBagSessionOpen) {
					accessoryBagWatcher.reset();
					missingAccessoriesPage = 0;
					// Re-fetch on every fresh visit, not just the first ever this session. This used to
					// be gated on Status.IDLE, which only holds before the very first fetch - once that
					// lands (LOADED or ERROR), nothing ever puts it back to IDLE, so every later bag-open
					// just kept showing that same first result forever. Confirmed live 2026-09-22: the
					// missing list got stuck and stopped updating at all after a bag-open that happened
					// while in a dungeon (where several accessories show an inflated Magical Power in
					// their lore from Hypixel's own dungeon-only stat boost) - the boost itself isn't a
					// bug and doesn't affect the missing-list logic (that's name-matching only, see
					// excludeLiveScanned), but this staleness bug meant whatever loaded first just never
					// refreshed again regardless. refreshAccessories() already no-ops if a fetch is
					// already in flight, so this is safe to call on every fresh visit.
					refreshAccessories();
				}
				accessoryBagSessionOpen = true;
				ScreenEvents.afterTick(screen).register(s -> ScdLog.guard("accessory bag watch", () -> accessoryBagWatcher.onScreenOpened(s)));
				ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) ->
						ScdLog.guard("accessory bag overlay", () -> renderAccessoryBagOverlay(graphics, mouseX, mouseY, partialTick)));
				// The vanilla bag screen isn't ours, so its own click handling knows nothing about our
				// overlay's Prev/Next arrows - this hook lets us intercept a click before Hypixel's own
				// screen (and by extension the server, for a slot click) ever sees it.
				ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
						ScdLog.guardBoolean("accessory bag overlay click", () -> handleAccessoryOverlayClick(event), true));
			} else {
				accessoryBagSessionOpen = false;
			}
		});

		scheduler.scheduleAtFixedRate(this::refreshPrices, 0, 60, TimeUnit.SECONDS);
		// The election/mayor only changes a few times a SkyBlock year - matches how rarely the server
		// itself re-polls Hypixel for it (see server/src/server.js).
		scheduler.scheduleAtFixedRate(this::refreshMayorPerks, 0, 10, TimeUnit.MINUTES);
		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);

		// Throwaway proof-of-concept adder for the entity-glow mixin - glows whatever's under the
		// crosshair bright magenta while /scd debug glowtest is toggled on. Confirmed live (color,
		// through-walls) - see FEATURE_ROADMAP.md's "T2/T3 re-scoped" section. A follow-up attempt to
		// instead glow "the NPC's body, not its name tag" via ArmorStand-only + isInvisible()/
		// isMarker() filtering turned out to be the wrong approach entirely: /scd debug armorstands
		// showed EVERY nearby armor stand is invisible=true, including the name/interact-prompt
		// labels, with no distinct "body" stand at all - the NPC's actual visible model isn't an
		// armor stand. Every real T2 feature targets one specific, already-identified entity (a
		// found boss, a matched Livid, etc.), never a generic "is this a real NPC" guess, so this
		// simpler crosshair version is both sufficient and correct - no classifier needed.
		ScdGlowRegistry.register(entity -> glowTestActive && entity == Minecraft.getInstance().crosshairPickEntity
				? 0xFFFF00FF : null);

		// First real T2/T3 feature: glow the currently-tracked Slayer boss and draw a line to it -
		// entirely reuses the already-proven boss detection (ScdSlayerBossTracker.currentBossOrNull),
		// no new detection needed. The line half is rendered every frame in renderBossHighlight()
		// (see the HudElementRegistry registration above); the glow half only needs registering once
		// since the adder itself re-checks the live tracked boss on every call.
		ScdGlowRegistry.register(entity -> config.slayer.bossHighlightEnabled && entity == slayerTracker.currentBossOrNull()
				? ScdTheme.ACCENT_SLAYER : null);
	}

	/** Draws a line + box around the currently-tracked Slayer boss, through walls - see the registered ScdGlowRegistry adder above for the matching glow half. */
	private void renderBossHighlight() {
		if (!config.slayer.bossHighlightEnabled) return;
		var boss = slayerTracker.currentBossOrNull();
		var player = Minecraft.getInstance().player;
		if (boss == null || player == null) return;
		ScdGizmoUtil.lineToEntity(player.getEyePosition(), boss, ScdTheme.ACCENT_SLAYER, true);
		ScdGizmoUtil.boxAroundEntity(boss, ScdTheme.ACCENT_SLAYER, true);
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

	public ScdQuiverHud quiverHud() {
		return quiverHud;
	}

	public ScdSlayerBossTracker slayerTracker() {
		return slayerTracker;
	}

	public ScdAccessoryData accessoryData() {
		return accessoryData;
	}

	/**
	 * Fetches the logged-in account's own accessory bag from the server (a live, per-player Hypixel
	 * API lookup - see ScdApiClient.fetchAccessories/server/src/hypixelProfile.js), updating
	 * accessoryData() when it lands. Safe to call repeatedly - a fetch already in flight is not
	 * duplicated. Not queued/retried automatically on failure; ScdAccessoryScreen's "Retry" button
	 * calls this again directly.
	 */
	public void refreshAccessories() {
		if (accessoryData.status() == ScdAccessoryData.Status.LOADING) return;
		String username = Minecraft.getInstance().getUser().getName();
		accessoryData.markLoading();
		api.fetchAccessories(username).whenComplete((result, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				ScdLog.warn("Accessory fetch failed for " + username, err);
				accessoryData.markError(err.getMessage());
			} else {
				accessoryData.markLoaded(result);
			}
		}));
	}

	public ScdSlayerDrops slayerDrops() {
		return slayerDrops;
	}

	public ScdCarryQueue carryQueue() {
		return carryQueue;
	}

	public ScdDungeonCarryQueue dungeonCarryQueue() {
		return dungeonCarryQueue;
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
						// Throwaway proof-of-concept for the entity-glow mixin - see
						// ScdEntityRendererMixin/ScdGlowRegistry and FEATURE_ROADMAP.md's "T2/T3
						// re-scoped" section. Delete alongside those once confirmed live.
						.then(ClientCommands.literal("glowtest")
								.executes(ctx -> {
									glowTestActive = !glowTestActive;
									ctx.getSource().sendFeedback(Component.literal(glowTestActive
											? "Glow test ON - whatever's under your crosshair should glow bright magenta, even through walls."
											: "Glow test OFF."));
									return 1;
								}))
						// General-purpose dump of every nearby armor stand's real flags - see the
						// method doc below for what this ruled out.
						.then(ClientCommands.literal("armorstands")
								.executes(ctx -> {
									reportNearbyArmorStands(ctx.getSource());
									return 1;
								}))
						.then(ClientCommands.literal("item")
								.then(ClientCommands.literal("nbt")
										.executes(ctx -> {
											reportLastHoveredNbt(ctx.getSource());
											return 1;
										})))
						// Dumps ScdAccessoryBagWatcher's own accumulated state - every item it's
						// currently counting plus each one's own Accessory Power, so a total that
						// doesn't match the in-game number (like the account with 826 scanned vs a
						// real 824) can be tracked to a specific item instead of guessed at.
						.then(ClientCommands.literal("accessories")
								.then(ClientCommands.literal("scan")
										.executes(ctx -> {
											reportAccessoryBagScan(ctx.getSource());
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
								})))
				.then(ClientCommands.literal("dungeon")
						// Opt-in toggle for the room-mapping data collector (ScdDungeonRoomScanner) -
						// not dev-gated, since this is meant for any willing player to turn on, not
						// just internal troubleshooting.
						.then(ClientCommands.literal("mapping")
								.then(ClientCommands.literal("on")
										.executes(ctx -> {
											toggleDungeonMapping(ctx.getSource(), true);
											return 1;
										}))
								.then(ClientCommands.literal("off")
										.executes(ctx -> {
											toggleDungeonMapping(ctx.getSource(), false);
											return 1;
										})))
						.then(ClientCommands.literal("debug")
								.requires(source -> config.devUnlocked)
								// Continuous capture: logs every chat/system message and a periodic
								// scoreboard snapshot to logs/latest.log until toggled off - meant to be
								// armed right before a real dungeon run so the actual end-of-run summary
								// text (and whatever precedes it) gets captured verbatim.
								.then(ClientCommands.literal("capture")
										.executes(ctx -> {
											toggleDungeonDebugCapture(ctx.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("scoreboard")
										.executes(ctx -> {
											reportDungeonScoreboardDebug(ctx.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("room")
										.executes(ctx -> {
											reportDungeonRoomDebug(ctx.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("score")
										.executes(ctx -> {
											reportDungeonScoreDebug(ctx.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("tablist")
										.executes(ctx -> {
											reportDungeonTabListDebug(ctx.getSource());
											return 1;
										})))
						.then(ClientCommands.literal("carry")
								.then(ClientCommands.literal("list")
										.executes(ctx -> {
											reportDungeonCarries(ctx.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("add")
										.then(ClientCommands.argument("player", StringArgumentType.word())
												.then(ClientCommands.argument("floor", StringArgumentType.word())
														.then(ClientCommands.argument("pricePerRun", StringArgumentType.word())
																.then(ClientCommands.argument("runCount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
																		.executes(ctx -> {
																			addDungeonCarryCommand(ctx.getSource(),
																					StringArgumentType.getString(ctx, "player"),
																					StringArgumentType.getString(ctx, "floor"),
																					StringArgumentType.getString(ctx, "pricePerRun"),
																					com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "runCount"));
																			return 1;
																		})))))
								.then(ClientCommands.literal("completeid")
										.then(ClientCommands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg())
												.executes(ctx -> {
													completeDungeonCarryByIdCommand(ctx.getSource(), com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"));
													return 1;
												})))
								.then(ClientCommands.literal("extendid")
										.then(ClientCommands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg())
												.then(ClientCommands.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
														.executes(ctx -> {
															extendDungeonCarryByIdCommand(ctx.getSource(), com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"),
																	com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "amount"));
															return 1;
														})))))));

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
					"Developer commands unlocked: /scd debug (glowtest, item nbt), /scd slayer debug (nearby, scoreboard, menu dump), /scd carry debug."));
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

	/** Full dungeon carry list for chat-only use - mirrors reportCarries. */
	private void reportDungeonCarries(FabricClientCommandSource source) {
		var entries = dungeonCarryQueue.all();
		source.sendFeedback(Component.literal("=== Dungeon Carries (" + entries.size() + ") ==="));
		if (entries.isEmpty()) {
			source.sendFeedback(Component.literal("No dungeon carries yet."));
			return;
		}
		for (var entry : entries) {
			String progress = entry.isActive() ? entry.runsCompleted + "/" + entry.runsOwed : "done";
			source.sendFeedback(Component.literal(entry.playerName + " - " + entry.floor + " (" + progress + ")"));
		}
	}

	private static final java.util.List<String> DUNGEON_CARRY_FLOORS = java.util.List.of(
			"F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7");

	private void addDungeonCarryCommand(FabricClientCommandSource source, String player, String floorText, String pricePerRunText, long runCount) {
		String floor = floorText.toUpperCase(java.util.Locale.ROOT);
		if (!DUNGEON_CARRY_FLOORS.contains(floor)) {
			source.sendFeedback(Component.literal("Unknown floor \"" + floorText + "\" - try one of: " + String.join(", ", DUNGEON_CARRY_FLOORS)));
			return;
		}
		Long pricePerRun = ScdFormat.parseCompactLong(pricePerRunText);
		if (pricePerRun == null || pricePerRun <= 0) {
			source.sendFeedback(Component.literal("Invalid price per run \"" + pricePerRunText + "\" - try a plain number or e.g. 1.3m, 800k."));
			return;
		}
		ScdDungeonCarryEntry entry = dungeonCarryQueue.add(player, floor, pricePerRun, pricePerRun * runCount);
		source.sendFeedback(Component.literal("Added dungeon carry for " + player + ": " + floor
				+ ", " + entry.runsOwed + " runs for " + ScdFormat.coins(entry.totalAmount) + " coins."));
	}

	/** Backs the "Done" chat button in promptDungeonCarryTargetReached. */
	private void completeDungeonCarryByIdCommand(FabricClientCommandSource source, long id) {
		ScdDungeonCarryEntry entry = dungeonCarryQueue.findByIdOrNull(id);
		if (entry == null || !entry.isActive()) {
			source.sendFeedback(Component.literal("That dungeon carry is already finished or no longer exists."));
			return;
		}
		finishDungeonCarryManually(entry);
		source.sendFeedback(Component.literal("Marked dungeon carry for " + entry.playerName + " as finished."));
	}

	/** Backs the "+5"/"+10"/"Custom" chat buttons in promptDungeonCarryTargetReached. */
	private void extendDungeonCarryByIdCommand(FabricClientCommandSource source, long id, long additionalRunCount) {
		ScdDungeonCarryEntry entry = dungeonCarryQueue.findByIdOrNull(id);
		if (entry == null) {
			source.sendFeedback(Component.literal("That dungeon carry no longer exists."));
			return;
		}
		dungeonCarryQueue.extend(id, additionalRunCount);
		source.sendFeedback(Component.literal("Added " + additionalRunCount + " more " + entry.floor
				+ " runs for " + entry.playerName + " (now " + entry.runsOwed + " total)."));
	}

	/**
	 * Drops anything accessoryBagWatcher has already scanned live this session from the missing list.
	 * The missing list itself comes from a Hypixel profile snapshot fetched once per bag-open (see
	 * refreshAccessories()/accessoryData), which goes stale the moment the player picks up something
	 * new mid-session - confirmed live 2026-09-22, an added Beastmaster Crest kept showing as missing
	 * until the whole screen was reopened. The live scan is always current, so it wins on conflict.
	 * Matched by display name rather than SkyBlock id, same as accessoryBagWatcher's own scanned map -
	 * lore has no id to read, only the name (see its class doc for why that's an accepted tradeoff
	 * there already).
	 */
	private List<ScdApiClient.MissingAccessory> excludeLiveScanned(List<ScdApiClient.MissingAccessory> missing) {
		if (missing.isEmpty()) return missing;
		var scannedNames = accessoryBagWatcher.accessories().stream()
				.map(ScdAccessoryBagWatcher.ScannedAccessory::name)
				.collect(java.util.stream.Collectors.toSet());
		if (scannedNames.isEmpty()) return missing;
		return missing.stream().filter(m -> !scannedNames.contains(m.name())).toList();
	}

	/**
	 * Missing accessories sorted per MissingSortMode (see its own doc comment for what each mode
	 * means) - name as the tiebreak in every mode. An unknown/missing rarity/price sorts last rather
	 * than crashing a null comparison or floating to the top.
	 */
	private static List<ScdApiClient.MissingAccessory> sortedMissingAccessories(List<ScdApiClient.MissingAccessory> missing, MissingSortMode mode) {
		Comparator<ScdApiClient.MissingAccessory> primary = switch (mode) {
			case MAX -> Comparator.comparingInt((ScdApiClient.MissingAccessory m) -> ScdTheme.rarityRank(m.tier())).reversed();
			case PRICE -> Comparator.comparingDouble((ScdApiClient.MissingAccessory m) -> m.price() != null ? m.price() : Double.POSITIVE_INFINITY);
			case BEST -> Comparator.comparingDouble((ScdApiClient.MissingAccessory m) ->
					m.price() != null && m.magicalPowerGain() > 0 ? m.price() / m.magicalPowerGain() : Double.POSITIVE_INFINITY);
		};
		return missing.stream()
				.sorted(primary.thenComparing(ScdApiClient.MissingAccessory::name))
				.toList();
	}

	/**
	 * Drawn to the left of the vanilla Accessory Bag screen (see ScdAccessoryBagWatcher) while
	 * "Missing accessories overlay" is enabled: scan progress + the live Accessory Power total once
	 * complete, then a paged, rarity-colored list of what's still missing (see
	 * sortedMissingAccessories/ScdTheme.rarityColor). Fixed screen-relative position rather than
	 * docked against the vanilla GUI's own computed bounds - simple and always correct regardless of
	 * that GUI's actual size, at the cost of not being pixel-snug against it.
	 */
	private void renderAccessoryBagOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = 10;
		int y = 10;
		// Sized to exactly fit the grid (10px padding each side) instead of a fixed guess - confirmed
		// live 2026-09-22 that a hardcoded 220 left a visible dead-space gap on the right once the
		// grid's real width (8 columns of boxes + gaps) came in narrower than that guess.
		int width = 20 + MISSING_GRID_COLUMNS * MISSING_ICON_BOX_SIZE + (MISSING_GRID_COLUMNS - 1) * MISSING_ICON_GAP;
		var font = Minecraft.getInstance().font;
		int lineH = ScdTheme.lineHeight(font);

		boolean missingLoaded = accessoryData.status() == ScdAccessoryData.Status.LOADED;
		List<ScdApiClient.MissingAccessory> missing = missingLoaded
				? sortedMissingAccessories(excludeLiveScanned(accessoryData.summary().missingAccessories()), missingSortMode)
				: List.of();
		int pageCount = Math.max(1, (missing.size() + MISSING_ACCESSORIES_PAGE_SIZE - 1) / MISSING_ACCESSORIES_PAGE_SIZE);
		missingAccessoriesPage = Math.max(0, Math.min(missingAccessoriesPage, pageCount - 1));
		int from = missingAccessoriesPage * MISSING_ACCESSORIES_PAGE_SIZE;
		int to = Math.min(from + MISSING_ACCESSORIES_PAGE_SIZE, missing.size());
		int missingRowCount = missingLoaded ? Math.max(1, to - from) : 1;
		boolean showNav = missingLoaded && missing.size() > MISSING_ACCESSORIES_PAGE_SIZE;
		// Icons instead of text rows, MISSING_GRID_COLUMNS per row - a missing "row" in the layout
		// math below now means one grid row, not one accessory.
		int gridRows = missingLoaded ? Math.max(1, (missingRowCount + MISSING_GRID_COLUMNS - 1) / MISSING_GRID_COLUMNS) : 1;

		// Scanned + Pages + (Accessory Power or "keep browsing") + the "Missing (N)" header, each its
		// own lineH+4 row, plus the icon grid's own rows - kept as an exact line-for-line mirror of the
		// draw sequence below (including the +12 section-divider gap and the +26 page-label-plus-
		// button-row when nav is shown) rather than an approximated constant, after an earlier version
		// of this formula quietly undercounted the header line and the divider gap, leaving the panel
		// too short for its own content.
		int fixedLines = 4;
		int height = 52 + fixedLines * (lineH + 4) + gridRows * (MISSING_ICON_BOX_SIZE + MISSING_ICON_GAP) + (showNav ? 26 : 0);

		ScdTheme.panel(g, x, y, width, height);
		ScdTheme.label(g, font, "Accessory Helper", x + 10, y + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, x + 10, y + 22, width - 20);

		int ty = y + 30;
		ScdTheme.label(g, font, "Scanned: " + accessoryBagWatcher.accessoryCount() + " items", x + 10, ty, ScdTheme.TEXT_SECONDARY);
		ty += lineH + 4;
		ScdTheme.label(g, font, "Pages: " + accessoryBagWatcher.pagesScanned() + "/" + Math.max(1, accessoryBagWatcher.totalPages()),
				x + 10, ty, ScdTheme.TEXT_SECONDARY);
		ty += lineH + 4;
		if (accessoryBagWatcher.isComplete()) {
			ScdTheme.label(g, font, "Accessory Power: " + accessoryBagWatcher.totalAccessoryPower(), x + 10, ty, ScdTheme.TEXT_PRIMARY);
			ty += lineH + 4;
		} else {
			ScdTheme.label(g, font, "Keep browsing to finish the scan", x + 10, ty, ScdTheme.TEXT_MUTED);
			ty += lineH + 4;
		}
		ty += 4;
		ScdTheme.divider(g, x + 10, ty, width - 20);
		ty += 8;

		String headerText = switch (accessoryData.status()) {
			case IDLE, LOADING -> "Missing: loading...";
			case ERROR -> "Missing: unavailable";
			case LOADED -> "Missing (" + missing.size() + ")";
		};
		ScdTheme.label(g, font, headerText, x + 10, ty, ScdTheme.TEXT_PRIMARY);
		if (missingLoaded && !missing.isEmpty()) {
			String sortLabel = switch (missingSortMode) {
				case MAX -> "Max";
				case PRICE -> "Price ↑";
				case BEST -> "Best value";
			};
			missingAccessoriesSortButton.setMessage(Component.literal(sortLabel));
			missingAccessoriesSortButton.setX(x + width - 10 - 80);
			missingAccessoriesSortButton.setY(ty - 6);
			missingAccessoriesSortButton.setAccentColor(ScdTheme.ACCENT_ACCESSORIES);
			missingAccessoriesSortButton.active = true;
			missingAccessoriesSortButton.extractRenderState(g, mouseX, mouseY, partialTick);
		} else {
			missingAccessoriesSortButton.active = false;
		}
		// The sort button is 16px tall - positioned 6px above this row's own baseline (rather than
		// growing the row itself) so its bottom edge clears the next row without adding visible dead
		// space. An earlier attempt grew the row instead (a few px above the button, plus an extra
		// gap constant below it) - confirmed live 2026-09-22 that double-counted the clearance and
		// left an oversized gap before the icon grid.
		ty += lineH + 4;

		// Hovered while walking the grid below, tooltip drawn last (after the nav row) so it always
		// paints on top rather than being drawn-over by whatever comes after it.
		ScdApiClient.MissingAccessory hovered = null;
		if (missingLoaded) {
			int col = 0;
			for (int i = from; i < to; i++) {
				var item = missing.get(i);
				int boxX = x + 10 + col * (MISSING_ICON_BOX_SIZE + MISSING_ICON_GAP);
				int boxY = ty;
				boolean isHovered = mouseX >= boxX && mouseX < boxX + MISSING_ICON_BOX_SIZE
						&& mouseY >= boxY && mouseY < boxY + MISSING_ICON_BOX_SIZE;
				// A slot-style box per accessory, not just a bare floating icon - easier to read as a
				// grid, and gives hover a real visible target the same way every other button in this
				// panel already does.
				g.fillGradient(boxX, boxY, boxX + MISSING_ICON_BOX_SIZE, boxY + MISSING_ICON_BOX_SIZE,
						isHovered ? ScdTheme.CARD_HOVER_TOP : ScdTheme.CARD_TOP,
						isHovered ? ScdTheme.CARD_HOVER_BOTTOM : ScdTheme.CARD_BOTTOM);
				g.outline(boxX, boxY, MISSING_ICON_BOX_SIZE, MISSING_ICON_BOX_SIZE,
						isHovered ? ScdTheme.ACCENT_ACCESSORIES : ScdTheme.PANEL_BORDER);
				ItemStack stack = ScdIcons.resolve(item.icon());
				g.item(stack, boxX + MISSING_ICON_BOX_PADDING, boxY + MISSING_ICON_BOX_PADDING);
				if (isHovered) hovered = item;
				col++;
				if (col >= MISSING_GRID_COLUMNS) {
					col = 0;
					ty += MISSING_ICON_BOX_SIZE + MISSING_ICON_GAP;
				}
			}
			if (col != 0) ty += MISSING_ICON_BOX_SIZE + MISSING_ICON_GAP; // a partial last row still needs its own height counted
		}

		if (showNav) {
			// Page label on its own row above a matched pair of full-width Prev/Next buttons - same
			// layout ScdCarryPlayerPickerScreen uses for its own page nav, not the compact flanking-arrow
			// style (that one's for cycling a single value in place, e.g. ScdCarryFormScreen's type/tier
			// pickers - a different interaction, wrong fit here).
			ScdTheme.scaledCenteredText(g, font, Component.literal("Page " + (missingAccessoriesPage + 1) + "/" + pageCount),
					x + width / 2, ty, ScdTheme.TEXT_MUTED);
			ty += 10;

			int navWidth = (width - 20 - 8) / 2;
			missingAccessoriesPrevButton.active = missingAccessoriesPage > 0;
			missingAccessoriesNextButton.active = missingAccessoriesPage < pageCount - 1;
			missingAccessoriesPrevButton.setX(x + 10);
			missingAccessoriesPrevButton.setY(ty);
			missingAccessoriesPrevButton.setWidth(navWidth);
			missingAccessoriesPrevButton.setAccentColor(ScdTheme.ACCENT_ACCESSORIES);
			missingAccessoriesNextButton.setX(x + 10 + navWidth + 8);
			missingAccessoriesNextButton.setY(ty);
			missingAccessoriesNextButton.setWidth(navWidth);
			missingAccessoriesNextButton.setAccentColor(ScdTheme.ACCENT_ACCESSORIES);
			missingAccessoriesPrevButton.extractRenderState(g, mouseX, mouseY, partialTick);
			missingAccessoriesNextButton.extractRenderState(g, mouseX, mouseY, partialTick);
		} else {
			// Not rendered this frame - also deactivate so a stale click at their last on-screen
			// position (e.g. right as the list shrinks to fit one page) can't still fire.
			missingAccessoriesPrevButton.active = false;
			missingAccessoriesNextButton.active = false;
		}

		if (hovered != null) renderMissingAccessoryTooltip(g, font, hovered, mouseX, mouseY);
	}

	/**
	 * Name / price / price-per-Magical-Power / how-to-obtain for whichever grid icon the cursor is
	 * over. obtainMethod is manually curated server-side (accessoryObtainMethods.js) and empty for
	 * everything right now, so it always shows until that gets filled in by hand. Price/MP uses
	 * magicalPowerGain, not the item's total power - for an upgrade entry (owning a lower tier
	 * already) that's the power this purchase actually *adds*, not the target tier's power in
	 * isolation, so the ratio means the same real thing whether this is a fresh accessory or an
	 * upgrade.
	 */
	private void renderMissingAccessoryTooltip(GuiGraphicsExtractor g, Font font, ScdApiClient.MissingAccessory item, int mouseX, int mouseY) {
		String priceText = item.price() != null ? "Price: " + ScdFormat.compactCount(Math.round(item.price())) : "Price: unknown";
		String ratioText = item.price() != null && item.magicalPowerGain() > 0
				? "Price/MP: " + ScdFormat.compactCount(Math.round(item.price() / item.magicalPowerGain()))
				: "Price/MP: unknown";
		String obtainText = "Obtain: " + (item.obtainMethod() != null ? item.obtainMethod() : "unknown");
		List<ScdOverlayBox.Line> lines = List.of(
				new ScdOverlayBox.Line(priceText, ScdTheme.TEXT_SECONDARY),
				new ScdOverlayBox.Line(ratioText, ScdTheme.TEXT_SECONDARY),
				new ScdOverlayBox.Line(obtainText, ScdTheme.TEXT_MUTED));
		String title = item.upgrade() ? item.name() + " (Upgrade)" : item.name();
		ScdOverlayBox.render(g, font, mouseX + 14, mouseY + 4, title, ScdTheme.rarityColor(item.tier()), lines);
	}

	/**
	 * The vanilla Accessory Bag screen isn't ours to addRenderableWidget() into, so these two arrow
	 * buttons are driven manually - registered against ScreenMouseEvents.allowMouseClick (see the
	 * AFTER_INIT block above) instead of going through the normal Screen widget list.
	 * AbstractWidget.mouseClicked() already checks isMouseOver()/isActive() and fires onClick itself,
	 * so returning false here (block the click from reaching Hypixel's own screen/the server) exactly
	 * when one of the two consumed it is enough - no separate hit-testing needed.
	 */
	private boolean handleAccessoryOverlayClick(MouseButtonEvent event) {
		if (event.button() != 0) return true;
		boolean handled = missingAccessoriesPrevButton.mouseClicked(event, false)
				|| missingAccessoriesNextButton.mouseClicked(event, false)
				|| missingAccessoriesSortButton.mouseClicked(event, false);
		return !handled;
	}

	/**
	 * "/scd debug accessories scan" - dumps ScdAccessoryBagWatcher's own accumulated state: every
	 * item it currently has counted, that item's own Accessory Power, and the running total, sorted
	 * so the highest-power items are easiest to eyeball. Built specifically to chase a small (826
	 * scanned vs 824 real) discrepancy reported live - narrows it to a specific item/rarity instead
	 * of guessing at a cause blind.
	 */
	private void reportAccessoryBagScan(FabricClientCommandSource source) {
		var items = accessoryBagWatcher.accessories().stream()
				.sorted((a, b) -> Integer.compare(b.accessoryPower(), a.accessoryPower()))
				.toList();
		source.sendFeedback(Component.literal("=== Accessory scan: " + items.size() + " items, "
				+ accessoryBagWatcher.pagesScanned() + "/" + Math.max(1, accessoryBagWatcher.totalPages()) + " pages, total "
				+ accessoryBagWatcher.totalAccessoryPower() + " Accessory Power (full list in logs/latest.log) ==="));
		ScdLog.info("=== /scd debug accessories scan: " + items.size() + " items, total " + accessoryBagWatcher.totalAccessoryPower() + " Accessory Power ===");
		for (var item : items) {
			ScdLog.info("  " + item.accessoryPower() + " - " + item.name() + " [" + item.rarity() + "]");
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
	 * General-purpose diagnostic: dumps every nearby armor stand's actual
	 * client-visible flags (name, invisible, marker, small, showArms,
	 * showBasePlate, position). Originated from trying to tell an NPC's
	 * "body" armor stand apart from its name-tag one - turned out every
	 * armor stand near a Hypixel NPC is invisible=true (including name/
	 * interact-prompt labels), with no distinct body stand at all, so that
	 * specific question doesn't need re-asking. Kept as a permanent tool
	 * (same category as /scd slayer debug nearby/scoreboard) for whatever
	 * future feature needs to read real armor-stand data off a live server
	 * instead of guessing it.
	 */
	private void reportNearbyArmorStands(FabricClientCommandSource source) {
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			source.sendFeedback(Component.literal("No level/player loaded"));
			return;
		}

		record Row(double distance, String text) {
		}
		List<Row> rows = new java.util.ArrayList<>();
		for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand)) continue;
			double dist = stand.distanceTo(mc.player);
			if (dist > 16.0) continue;

			String name = stand.hasCustomName() && stand.getCustomName() != null ? stand.getCustomName().getString() : "(none)";
			String text = String.format(java.util.Locale.ROOT,
					"%.1fm name=\"%s\" invisible=%s marker=%s small=%s showArms=%s showBasePlate=%s pos=%s",
					dist, name, stand.isInvisible(), stand.isMarker(), stand.isSmall(), stand.showArms(), stand.showBasePlate(),
					stand.position());
			rows.add(new Row(dist, text));
		}
		rows.sort(java.util.Comparator.comparingDouble(Row::distance));

		source.sendFeedback(Component.literal("=== Nearby armor stands (" + rows.size() + ") ==="));
		if (rows.isEmpty()) {
			source.sendFeedback(Component.literal("None within 16 blocks."));
		}
		ScdLog.info("=== /scd debug armorstands ===");
		for (Row row : rows) {
			source.sendFeedback(Component.literal(row.text()));
			ScdLog.info(row.text());
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
	 * Real consumer of ScdDungeonCompletion's parsed reports, registered in onInitializeClient -
	 * fires twice per actual dungeon completion (the early "EXTRA STATS" block, then the fuller
	 * "Floor N Stats" block a second later - see ScdDungeonCompletion's own doc comment). Just
	 * logs + announces both for now, clearly labeled, so this is verifiable end-to-end without
	 * reading raw logs - nothing downstream (Dungeon carries, §20; a real score/completion HUD)
	 * consumes this yet, that's the next layer once this is confirmed solid across more than one
	 * boss/floor.
	 */
	private void handleDungeonCompletion(ScdDungeonCompletion.CompletionReport report) {
		ScdLog.info("[DUNGEON-COMPLETION] " + report);
		StringBuilder sb = new StringBuilder("§b[SCD] §fDungeon complete: F")
				.append(report.floor() != null ? report.floor() : "?")
				.append(" - ").append(report.boss() != null ? report.boss() : "?");
		if (report.clearTime() != null) sb.append(" in ").append(report.clearTime());
		if (report.teamScore() != null) sb.append(" - Score ").append(report.teamScore());
		if (report.scoreRank() != null) sb.append(" (").append(report.scoreRank()).append(")");
		if (report.totalDamage() != null) sb.append(" - ").append(ScdFormat.compactCount(report.totalDamage())).append(" dmg");
		if (report.secretsFound() != null) sb.append(", ").append(report.secretsFound()).append(" secrets");
		var player = Minecraft.getInstance().player;
		if (player != null) player.sendSystemMessage(Component.literal(sb.toString()));

		creditDungeonCarries(report);
	}

	// ScdDungeonCompletion fires twice per real completion (the early "EXTRA STATS" block, then
	// the fuller "Floor Stats" block a second later - see its own doc comment) - both carry the
	// same floor+boss+time, so this skips crediting a second time for what's really one run.
	private String lastCreditedDungeonSignature;

	/**
	 * Credits every active dungeon carry entry for the just-completed floor - unlike Slayer
	 * carries (one specific customer's own boss, watched independently of what the carrier is
	 * doing), a dungeon carry is credited by the carrier's OWN run completing, since carrying a
	 * dungeon means being in the same party/instance as the customer (see
	 * ScdDungeonCarryEntry's doc comment). One completed run credits every matching active entry
	 * at once - multiple customers carried together in the same party all get +1 run each.
	 */
	private void creditDungeonCarries(ScdDungeonCompletion.CompletionReport report) {
		if (report.floorKey() == null) return;
		String signature = report.floorKey() + "|" + report.boss() + "|" + report.clearTime();
		if (signature.equals(lastCreditedDungeonSignature)) return;
		lastCreditedDungeonSignature = signature;

		// Second, more reliable reset trigger for ScdDungeonScore's per-run counters - see its own
		// resetRunState() doc comment for why computeOrNull()'s wasInDungeon transition alone isn't
		// enough (quick re-queuing doesn't reliably leave the dungeon area).
		ScdDungeonScore.resetRunState();

		List<ScdDungeonCarryEntry> matching = dungeonCarryQueue.activeMatching(report.floorKey());
		if (matching.isEmpty()) return;

		long runTimeMs = parseClearTimeMs(report.clearTime());
		for (ScdDungeonCarryEntry entry : matching) {
			boolean justReachedTarget = dungeonCarryQueue.creditRun(entry, runTimeMs);
			// Crediting itself happens immediately (the queue screen reflects progress right away)
			// but the party-chat message is delayed 1s - the moment a run completes, Hypixel floods
			// chat with boss dialogue/blessing pickups/stash summaries/the completion report itself,
			// and a progress ping sent in the middle of that burst is easy to miss.
			scheduler.schedule(() -> Minecraft.getInstance().execute(() -> {
				sendPartyChat(entry.playerName + ": " + entry.runsCompleted + "/" + entry.runsOwed + " runs");
				if (justReachedTarget) promptDungeonCarryTargetReached(entry);
			}), 1, TimeUnit.SECONDS);
		}
	}

	/** Parses ScdDungeonCompletion's clear-time text ("04m 46s", or possibly just "42s") into milliseconds, for ScdDungeonCarryEntry's average-run-time tracking. */
	private static long parseClearTimeMs(String clearTime) {
		if (clearTime == null) return 0;
		var m = java.util.regex.Pattern.compile("(?:(\\d+)m)?\\s*(\\d+)s").matcher(clearTime);
		if (!m.find()) return 0;
		int minutes = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
		int seconds = Integer.parseInt(m.group(2));
		return (minutes * 60L + seconds) * 1000L;
	}

	/** Same clickable-chat-buttons pattern as promptCarryTargetReached, pointed at the dungeon carry command tree instead. */
	private void promptDungeonCarryTargetReached(ScdDungeonCarryEntry entry) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;

		MutableComponent line = Component.literal("[SCD] " + entry.playerName + "'s " + entry.floor + " carry hit "
				+ entry.runsCompleted + "/" + entry.runsOwed + "! ").withStyle(net.minecraft.ChatFormatting.AQUA);
		line.append(chatButton("Done", "/scd dungeon carry completeid " + entry.id, true, "Close this carry and send the review message"));
		line.append(Component.literal("  "));
		line.append(chatButton("+5", "/scd dungeon carry extendid " + entry.id + " 5", true, "Add 5 more " + entry.floor + " runs at the same price"));
		line.append(Component.literal("  "));
		line.append(chatButton("+10", "/scd dungeon carry extendid " + entry.id + " 10", true, "Add 10 more " + entry.floor + " runs at the same price"));
		line.append(Component.literal("  "));
		line.append(chatButton("Custom", "/scd dungeon carry extendid " + entry.id + " ", false, "Fill the chat box to type a custom amount"));
		player.sendSystemMessage(line);
	}

	/** Called from ScdDungeonCarryQueueScreen's "Done" button - mirrors finishCarryManually. */
	public void finishDungeonCarryManually(ScdDungeonCarryEntry entry) {
		dungeonCarryQueue.markComplete(entry.id);
		sendPartyChat("gg " + entry.playerName + " Please leave a review in #reviews in the relevant Discord");
	}

	private void toggleDungeonMapping(FabricClientCommandSource source, boolean enabled) {
		config.dungeon.roomMappingEnabled = enabled;
		config.save();
		source.sendFeedback(Component.literal(enabled
				? "Dungeon room mapping ON - opted in. Room block-fingerprints will be sent to the SCD backend while you're in a dungeon (see FEATURE_ROADMAP.md §3's mapping initiative - this is still experimental)."
				: "Dungeon room mapping OFF."));
	}

	/**
	 * Toggled by /scd dungeon debug capture - see the dungeonDebugCaptureActive field doc for what
	 * this is for. Logs to logs/latest.log via ScdLog rather than chat, since a full run's worth of
	 * messages would be far too much chat spam to read live.
	 */
	private void toggleDungeonDebugCapture(FabricClientCommandSource source) {
		dungeonDebugCaptureActive = !dungeonDebugCaptureActive;
		ScdRawChatCapture.active = dungeonDebugCaptureActive;
		dungeonDebugScoreboardTickCounter = 0;
		source.sendFeedback(Component.literal(dungeonDebugCaptureActive
				? "Dungeon debug capture ON - go do a real dungeon run now. Every chat/system message plus a scoreboard snapshot every ~5s is being logged to logs/latest.log, tagged [DUNGEON-DEBUG]. Run this command again to stop, then send the log."
				: "Dungeon debug capture OFF."));
		ScdLog.info("[DUNGEON-DEBUG] capture " + (dungeonDebugCaptureActive ? "armed" : "disarmed"));
	}

	private void checkDungeonDebugCapture(Component message) {
		checkDungeonDebugCapture(message, "GAME");
	}

	/**
	 * channel is tagged in the log line so a future capture can tell which Fabric event actually
	 * delivered a given message - needed after finding 2026-09-22 that the dungeon end-of-run
	 * summary never came through GAME at all, only through raw vanilla chat, which is why CHAT got
	 * registered as a second source (see the ClientReceiveMessageEvents.CHAT registration above).
	 */
	private void checkDungeonDebugCapture(Component message, String channel) {
		if (!dungeonDebugCaptureActive) return;
		String text = message.getString();
		if (text.isBlank()) return;
		ScdLog.info("[DUNGEON-DEBUG] chat(" + channel + "): " + text);
	}

	/** Runs every client tick - only does anything while capture is armed, throttled to roughly once per 5s so it doesn't flood the log every tick. */
	private void tickDungeonDebugCapture() {
		if (!dungeonDebugCaptureActive) return;
		if (++dungeonDebugScoreboardTickCounter < 100) return;
		dungeonDebugScoreboardTickCounter = 0;

		ScdDungeonManager.DungeonState state = ScdDungeonManager.read();
		ScdLog.info("[DUNGEON-DEBUG] --- scoreboard snapshot (inDungeon=" + state.inDungeon() + " floor=" + state.floor() + " liveScore=" + state.liveScore() + ") ---");
		for (String line : ScdSlayerScoreboard.describeRaw()) {
			ScdLog.info("[DUNGEON-DEBUG] " + line);
		}
	}

	private void reportDungeonScoreboardDebug(FabricClientCommandSource source) {
		List<String> lines = ScdSlayerScoreboard.describeRaw();
		source.sendFeedback(Component.literal("=== Sidebar scoreboard (dungeon detection check) ==="));
		for (String line : lines) {
			source.sendFeedback(Component.literal(line));
		}
		ScdLog.info("=== /scd dungeon debug scoreboard ===");
		for (String line : lines) {
			ScdLog.info(line);
		}
	}

	private void reportDungeonRoomDebug(FabricClientCommandSource source) {
		ScdDungeonManager.DungeonState state = ScdDungeonManager.read();
		String msg = "In dungeon: " + state.inDungeon() + ", floor: " + state.floor() + ", liveScore: " + state.liveScore()
				+ " (only trustworthy once in the boss room, see ScdDungeonManager's doc comment)"
				+ " | scanner: " + dungeonRoomScanner.debugState()
				+ " | mapping enabled: " + config.dungeon.roomMappingEnabled;
		source.sendFeedback(Component.literal(msg));
		ScdLog.info("=== /scd dungeon debug room === " + msg);
	}

	/** Live sanity check for ScdDungeonScore - dumps the full breakdown so a real run can confirm the tab-list-derived numbers (completed rooms, secrets%, crypts, puzzles) actually look right before trusting the total. */
	private void reportDungeonScoreDebug(FabricClientCommandSource source) {
		ScdDungeonScore.ScoreBreakdown breakdown = ScdDungeonScore.computeOrNull(mayorPerks);
		if (breakdown == null) {
			source.sendFeedback(Component.literal("Not in a dungeon, or floor not recognized yet."));
			return;
		}
		String msg = "Score: " + breakdown.total() + " (skill=" + breakdown.skill() + " explore=" + breakdown.explore()
				+ " speed=" + breakdown.speed() + " bonus=" + breakdown.bonus() + (breakdown.isEntrance() ? ", entrance x0.7" : "") + ")"
				+ " | rooms=" + breakdown.completedRooms() + "(padded " + breakdown.paddedCompletedRooms() + ")/" + breakdown.totalRoomsEstimate()
				+ " secrets=" + breakdown.secretsPercent() + "% crypts=" + breakdown.crypts()
				+ " deaths=" + breakdown.deaths() + " incompletePuzzles=" + breakdown.incompletePuzzles()
				+ " bloodDoorOpened=" + breakdown.bloodDoorOpened();
		source.sendFeedback(Component.literal(msg));
		ScdLog.info("=== /scd dungeon debug score === " + msg);
	}

	/** One-shot raw dump of the tab list (hold Tab), same style as reportSlayerScoreboard/reportDungeonScoreboardDebug - lets a real run confirm the exact text ScdDungeonManager.readTabList()/ScdDungeonScore are trying to pattern-match against, since this project has never read the tab list before now. */
	private void reportDungeonTabListDebug(FabricClientCommandSource source) {
		var lines = ScdDungeonManager.readTabList();
		source.sendFeedback(Component.literal("=== Tab list (" + lines.size() + " entries) ==="));
		ScdLog.info("=== /scd dungeon debug tablist ===");
		for (String line : lines) {
			source.sendFeedback(Component.literal("\"" + line + "\""));
			ScdLog.info("\"" + line + "\"");
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
		lines.add("RNG meter Daemon Shard estimate: multiplier=" + slayerRngMeter.daemonMultiplier()
				+ " (~level " + slayerRngMeter.daemonLevelEstimate() + "/10)");
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
