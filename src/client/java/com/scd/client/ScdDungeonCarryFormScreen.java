package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Pop-up form for starting a new dungeon carry: player name, floor, price per run, and how many
 * runs were paid for. Mirrors ScdCarryFormScreen closely (same total-is-derived, same draft-
 * survives-reinit pattern) but with a single combined Floor cycle (F1-F7/M1-M7) instead of
 * separate type+tier rows - a dungeon carry is sold per specific floor, not an orthogonal
 * type/tier combo.
 */
public class ScdDungeonCarryFormScreen extends Screen {
	private static final String[] FLOORS = {"F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"};
	private static final int PANEL_WIDTH = 260;
	private static final int PADDING = 16;
	private static final int ARROW_WIDTH = 16;
	private static final int ROW_HEIGHT = 16;

	private final Screen parent;
	private final ScdClient client;

	private int floorIndex;
	private String playerNameDraft = "";
	private String pricePerRunDraft = "";
	private String runCountDraft = "";
	private EditBox playerNameBox;
	private EditBox pricePerRunBox;
	private EditBox runCountBox;

	private int panelX, panelY, panelWidth, panelHeight;
	private int playerLabelY, floorRowY, priceLabelY, runCountLabelY, totalLabelY, errorLabelY;
	private int rowContentX, rowFieldWidth;
	private String errorText;

	public ScdDungeonCarryFormScreen(Screen parent, ScdClient client) {
		super(Component.literal("SCD - New Dungeon Carry"));
		this.parent = parent;
		this.client = client;

		ScdDungeonCarryEntry last = client.dungeonCarryQueue().mostRecentOrNull();
		floorIndex = 6; // F7 - the most commonly carried floor, when there's no prior carry to copy
		if (last != null) {
			for (int i = 0; i < FLOORS.length; i++) {
				if (FLOORS[i].equals(last.floor)) {
					floorIndex = i;
					break;
				}
			}
		}
	}

	@Override
	protected void init() {
		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = this.height / 2 - 105;
		int contentX = panelX + PADDING;
		int fieldWidth = panelWidth - PADDING * 2;
		rowContentX = contentX;
		rowFieldWidth = fieldWidth;

		int y = panelY + 32;

		playerLabelY = y;
		y += 10;
		playerNameBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Player name"));
		playerNameBox.setBordered(false);
		playerNameBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		playerNameBox.setHint(Component.literal("Player name"));
		playerNameBox.setValue(playerNameDraft);
		playerNameBox.setResponder(s -> playerNameDraft = s);
		addRenderableWidget(playerNameBox);
		y += 20;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Choose online player..."), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(new ScdCarryPlayerPickerScreen(this, client, this::setPlayerName))));
		y += 22;

		floorRowY = y;
		addRenderableWidget(new ScdButton(contentX, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal("<"), ScdTheme.ACCENT_DUNGEON,
				() -> floorIndex = Math.floorMod(floorIndex - 1, FLOORS.length)));
		addRenderableWidget(new ScdButton(contentX + fieldWidth - ARROW_WIDTH, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal(">"), ScdTheme.ACCENT_DUNGEON,
				() -> floorIndex = Math.floorMod(floorIndex + 1, FLOORS.length)));
		y += ROW_HEIGHT + 10;

		priceLabelY = y;
		y += 10;
		pricePerRunBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Price per run"));
		pricePerRunBox.setBordered(false);
		pricePerRunBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		pricePerRunBox.setHint(Component.literal("e.g. 1.3m, 800k, 50000"));
		pricePerRunBox.setValue(pricePerRunDraft);
		pricePerRunBox.setResponder(s -> {
			restrictToCompactNumber(pricePerRunBox, s);
			pricePerRunDraft = pricePerRunBox.getValue();
		});
		addRenderableWidget(pricePerRunBox);
		y += 20;

		runCountLabelY = y;
		y += 10;
		runCountBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Amount of runs"));
		runCountBox.setBordered(false);
		runCountBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		runCountBox.setHint(Component.literal("Amount of runs"));
		runCountBox.setValue(runCountDraft);
		runCountBox.setResponder(s -> {
			restrictToDigits(runCountBox, s);
			runCountDraft = runCountBox.getValue();
		});
		addRenderableWidget(runCountBox);
		y += 20;

		totalLabelY = y;
		y += 14;

		errorLabelY = y;
		y += 14;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Add"), ScdTheme.ACCENT_DUNGEON, this::onAddPressed));
		y += 22;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(parent)));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(null)));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	public void setPlayerName(String name) {
		playerNameDraft = name;
		if (playerNameBox != null) playerNameBox.setValue(name);
	}

	private void onAddPressed() {
		String typedName = playerNameBox.getValue().trim();
		if (typedName.isEmpty()) {
			errorText = "Enter a player name.";
			return;
		}
		String playerName = null;
		for (String online : client.onlinePlayerNames()) {
			if (online.equalsIgnoreCase(typedName)) {
				playerName = online;
				break;
			}
		}
		if (playerName == null) {
			errorText = "\"" + typedName + "\" isn't on the server right now - use \"Choose online player...\".";
			return;
		}
		Long pricePerRun = ScdFormat.parseCompactLong(pricePerRunBox.getValue());
		if (pricePerRun == null || pricePerRun <= 0) {
			errorText = "Enter a valid price per run (e.g. 1.3m, 800k, 50000).";
			return;
		}
		long runCount = parseLongOrZero(runCountBox.getValue());
		if (runCount <= 0) {
			errorText = "Enter how many runs this covers.";
			return;
		}

		String floor = FLOORS[floorIndex];
		client.dungeonCarryQueue().add(playerName, floor, pricePerRun, pricePerRun * runCount);
		Minecraft.getInstance().setScreen(new ScdDungeonCarryQueueScreen(parent, client));
	}

	private static void restrictToDigits(EditBox box, String text) {
		String cleaned = text.replaceAll("[^0-9]", "");
		if (!cleaned.equals(text)) box.setValue(cleaned);
	}

	private static void restrictToCompactNumber(EditBox box, String text) {
		String cleaned = text.replaceAll("[^0-9.kKmMbB]", "");
		if (!cleaned.equals(text)) box.setValue(cleaned);
	}

	private static long parseLongOrZero(String text) {
		try {
			return text.isBlank() ? 0 : Long.parseLong(text.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "New Dungeon Carry", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		ScdTheme.sectionLabel(g, this.font, "Player Name", rowContentX, playerLabelY);

		int rowCenterX = rowContentX + rowFieldWidth / 2;
		ScdTheme.scaledCenteredText(g, this.font, Component.literal("Floor " + FLOORS[floorIndex]),
				rowCenterX, floorRowY + 4, ScdTheme.TEXT_PRIMARY);

		ScdTheme.sectionLabel(g, this.font, "Price Per Run", rowContentX, priceLabelY);
		ScdTheme.sectionLabel(g, this.font, "Amount Of Runs", rowContentX, runCountLabelY);

		Long pricePerRun = ScdFormat.parseCompactLong(pricePerRunBox.getValue());
		long runCount = parseLongOrZero(runCountBox.getValue());
		String totalText = pricePerRun != null && pricePerRun > 0 && runCount > 0
				? "Total: " + ScdFormat.coins(pricePerRun * runCount) + " coins"
				: "Total: -";
		ScdTheme.label(g, this.font, totalText, rowContentX, totalLabelY, ScdTheme.TEXT_SECONDARY);

		if (errorText != null) {
			ScdTheme.label(g, this.font, errorText, rowContentX, errorLabelY, ScdTheme.ACCENT_DUNGEON);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
