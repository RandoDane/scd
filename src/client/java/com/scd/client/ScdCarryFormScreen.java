package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Pop-up form for starting a new Slayer carry: player name, type, tier, price
 * per kill, and how many bosses were paid for. Total amount is *derived*
 * (bossCount * pricePerKill) and shown live rather than entered - agreeing a
 * carry is naturally "N kills at X coins each", not a lump sum you'd have to
 * do that division yourself to check. Type/tier default to whatever the most
 * recently added carry used (set once in the constructor, not re-applied on
 * a later init() from a resize, so cycling mid-edit is never clobbered), and
 * are cycled with arrow buttons rather than a real dropdown widget (none
 * exists in this project yet) - same interaction as the drops screen's page
 * nav.
 */
public class ScdCarryFormScreen extends Screen {
	private static final String[] TIERS = {"I", "II", "III", "IV", "V"};
	private static final int PANEL_WIDTH = 260;
	private static final int PADDING = 16;
	private static final int ARROW_WIDTH = 16;
	private static final int ROW_HEIGHT = 16;

	private final Screen parent;
	private final ScdClient client;

	private int typeIndex;
	private int tierIndex;
	// setScreen() re-runs init() every time a screen becomes active again (even the same instance,
	// e.g. returning from the player picker), which rebuilds a fresh EditBox from scratch - these
	// survive that rebuild so a chosen name (or anything already typed) isn't silently wiped.
	private String playerNameDraft = "";
	private String pricePerKillDraft = "";
	private String bossCountDraft = "";
	private EditBox playerNameBox;
	private EditBox pricePerKillBox;
	private EditBox bossCountBox;

	private int panelX, panelY, panelWidth, panelHeight;
	private int playerLabelY, typeRowY, tierRowY, priceLabelY, bossCountLabelY, totalLabelY, errorLabelY;
	private int rowContentX, rowFieldWidth;
	private String errorText;

	public ScdCarryFormScreen(Screen parent, ScdClient client) {
		super(Component.literal("SCD - New Carry"));
		this.parent = parent;
		this.client = client;

		ScdCarryEntry last = client.carryQueue().mostRecentOrNull();
		if (last != null) {
			typeIndex = last.typeEnum().ordinal();
			for (int i = 0; i < TIERS.length; i++) {
				if (TIERS[i].equals(last.tier)) {
					tierIndex = i;
					break;
				}
			}
		} else {
			tierIndex = 2; // III - the most commonly carried tier, when there's no prior carry to copy
		}
	}

	@Override
	protected void init() {
		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = this.height / 2 - 115;
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

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Choose online player..."), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(new ScdCarryPlayerPickerScreen(this, client))));
		y += 22;

		typeRowY = y;
		addRenderableWidget(new ScdButton(contentX, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal("<"), ScdTheme.ACCENT_SLAYER,
				() -> typeIndex = Math.floorMod(typeIndex - 1, ScdSlayerType.values().length)));
		addRenderableWidget(new ScdButton(contentX + fieldWidth - ARROW_WIDTH, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal(">"), ScdTheme.ACCENT_SLAYER,
				() -> typeIndex = Math.floorMod(typeIndex + 1, ScdSlayerType.values().length)));
		y += ROW_HEIGHT + 6;

		tierRowY = y;
		addRenderableWidget(new ScdButton(contentX, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal("<"), ScdTheme.ACCENT_SLAYER,
				() -> tierIndex = Math.floorMod(tierIndex - 1, TIERS.length)));
		addRenderableWidget(new ScdButton(contentX + fieldWidth - ARROW_WIDTH, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal(">"), ScdTheme.ACCENT_SLAYER,
				() -> tierIndex = Math.floorMod(tierIndex + 1, TIERS.length)));
		y += ROW_HEIGHT + 10;

		priceLabelY = y;
		y += 10;
		pricePerKillBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Price per kill"));
		pricePerKillBox.setBordered(false);
		pricePerKillBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		pricePerKillBox.setHint(Component.literal("e.g. 1.3m, 800k, 50000"));
		pricePerKillBox.setValue(pricePerKillDraft);
		pricePerKillBox.setResponder(s -> {
			restrictToCompactNumber(pricePerKillBox, s);
			pricePerKillDraft = pricePerKillBox.getValue();
		});
		addRenderableWidget(pricePerKillBox);
		y += 20;

		bossCountLabelY = y;
		y += 10;
		bossCountBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Amount of bosses"));
		bossCountBox.setBordered(false);
		bossCountBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		bossCountBox.setHint(Component.literal("Amount of bosses"));
		bossCountBox.setValue(bossCountDraft);
		bossCountBox.setResponder(s -> {
			restrictToDigits(bossCountBox, s);
			bossCountDraft = bossCountBox.getValue();
		});
		addRenderableWidget(bossCountBox);
		y += 20;

		totalLabelY = y;
		y += 14;

		errorLabelY = y;
		y += 14;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Add"), ScdTheme.ACCENT_SLAYER, this::onAddPressed));
		y += 22;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(parent)));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(null)));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	/** Called by ScdCarryPlayerPickerScreen when a row is clicked - stores into the draft (survives the init() that setScreen() re-runs) as well as the live box. */
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
		Long pricePerKill = ScdFormat.parseCompactLong(pricePerKillBox.getValue());
		if (pricePerKill == null || pricePerKill <= 0) {
			errorText = "Enter a valid price per kill (e.g. 1.3m, 800k, 50000).";
			return;
		}
		long bossCount = parseLongOrZero(bossCountBox.getValue());
		if (bossCount <= 0) {
			errorText = "Enter how many bosses this covers.";
			return;
		}

		ScdSlayerType type = ScdSlayerType.values()[typeIndex];
		String tier = TIERS[tierIndex];
		client.carryQueue().add(playerName, type, tier, pricePerKill, pricePerKill * bossCount);
		Minecraft.getInstance().setScreen(new ScdCarryQueueScreen(parent, client));
	}

	/** EditBox has no built-in input filter in this API - strips non-digits on every keystroke instead. */
	private static void restrictToDigits(EditBox box, String text) {
		String cleaned = text.replaceAll("[^0-9]", "");
		if (!cleaned.equals(text)) box.setValue(cleaned);
	}

	/** Same idea, but also allows a decimal point and a trailing k/m/b suffix - full format validity is checked at parse time (ScdFormat.parseCompactLong), not enforced live. */
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

		ScdTheme.label(g, this.font, "New Carry", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		ScdTheme.sectionLabel(g, this.font, "Player Name", rowContentX, playerLabelY);

		int rowCenterX = rowContentX + rowFieldWidth / 2;
		ScdTheme.scaledCenteredText(g, this.font, Component.literal(ScdSlayerType.values()[typeIndex].displayName()),
				rowCenterX, typeRowY + 4, ScdTheme.TEXT_PRIMARY);
		ScdTheme.scaledCenteredText(g, this.font, Component.literal("Tier " + TIERS[tierIndex]),
				rowCenterX, tierRowY + 4, ScdTheme.TEXT_PRIMARY);

		ScdTheme.sectionLabel(g, this.font, "Price Per Kill", rowContentX, priceLabelY);
		ScdTheme.sectionLabel(g, this.font, "Amount Of Bosses", rowContentX, bossCountLabelY);

		Long pricePerKill = ScdFormat.parseCompactLong(pricePerKillBox.getValue());
		long bossCount = parseLongOrZero(bossCountBox.getValue());
		String totalText = pricePerKill != null && pricePerKill > 0 && bossCount > 0
				? "Total: " + ScdFormat.coins(pricePerKill * bossCount) + " coins"
				: "Total: -";
		ScdTheme.label(g, this.font, totalText, rowContentX, totalLabelY, ScdTheme.TEXT_SECONDARY);

		if (errorText != null) {
			ScdTheme.label(g, this.font, errorText, rowContentX, errorLabelY, ScdTheme.ACCENT_SLAYER);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
