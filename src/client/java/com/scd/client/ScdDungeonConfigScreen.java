package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeons settings: the live score HUD (with each of its detail lines independently
 * toggleable), room mapping opt-in, and the entry point to Dungeon Carries - consolidated here
 * per the account owner's own request, mirroring ScdSlayerConfigScreen's shape (toggles up top,
 * a "Move ... HUD..." button, a "Carries..." button).
 */
public class ScdDungeonConfigScreen extends Screen {
	private static final int PANEL_WIDTH = 300;
	private static final int PADDING = 16;
	private static final int TOGGLE_ROW_HEIGHT = 18;

	private record LabelRow(String text, int x, int y) {
	}

	private final Screen parent;
	private final ScdConfig config;
	private final ScdClient client;

	private int panelX, panelY, panelWidth, panelHeight;
	private final List<LabelRow> labelRows = new ArrayList<>();

	public ScdDungeonConfigScreen(Screen parent, ScdConfig config, ScdClient client) {
		super(Component.literal("SCD - Dungeons"));
		this.parent = parent;
		this.config = config;
		this.client = client;
	}

	@Override
	protected void init() {
		labelRows.clear();
		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = 20;
		int contentX = panelX + PADDING;
		int fieldWidth = panelWidth - PADDING * 2;

		int y = panelY + 32;

		y = addToggle(contentX, fieldWidth, y, "Score HUD",
				config.dungeon.scoreHudEnabled, v -> config.dungeon.scoreHudEnabled = v);
		y = addToggle(contentX, fieldWidth, y, "Show Skill/Explore/Speed/Bonus",
				config.dungeon.scoreHudShowBreakdown, v -> config.dungeon.scoreHudShowBreakdown = v);
		y = addToggle(contentX, fieldWidth, y, "Show Rooms/Secrets",
				config.dungeon.scoreHudShowRoomsSecrets, v -> config.dungeon.scoreHudShowRoomsSecrets = v);
		y = addToggle(contentX, fieldWidth, y, "Show Crypts/Deaths/Puzzles",
				config.dungeon.scoreHudShowCryptsDeathsPuzzles, v -> config.dungeon.scoreHudShowCryptsDeathsPuzzles = v);
		y += 6;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Move Score HUD..."), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(new ScdHudEditScreen(this, config, config.dungeon.scoreHudPosition, ScdTheme.ACCENT_DUNGEON,
						(g, f) -> client.dungeonScoreHud().renderPreview(g, f)))));
		y += 22 + 4;

		y = addToggle(contentX, fieldWidth, y, "Dungeon room mapping (opt-in, experimental)",
				config.dungeon.roomMappingEnabled, v -> config.dungeon.roomMappingEnabled = v);
		y += 6;

		int activeCarries = client.dungeonCarryQueue().activeCount();
		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16,
				Component.literal(activeCarries > 0 ? "Dungeon Carries (" + activeCarries + " active)..." : "Dungeon Carries..."),
				ScdTheme.ACCENT_DUNGEON, () ->
						Minecraft.getInstance().setScreen(new ScdDungeonCarryQueueScreen(this, client))));
		y += 26;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_DUNGEON, () -> {
			config.save();
			Minecraft.getInstance().setScreen(parent);
		}));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_DUNGEON, () -> {
			config.save();
			Minecraft.getInstance().setScreen(null);
		}));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	private int addToggle(int x, int width, int y, String label, boolean initial, java.util.function.Consumer<Boolean> onChange) {
		addRenderableWidget(new ScdToggle(x + width - 26, y, Component.literal(label), ScdTheme.ACCENT_DUNGEON, initial, onChange::accept));
		labelRows.add(new LabelRow(label, x, y + 3));
		return y + TOGGLE_ROW_HEIGHT;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "Dungeons", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		for (LabelRow row : labelRows) {
			ScdTheme.label(g, this.font, row.text(), row.x(), row.y(), ScdTheme.TEXT_SECONDARY);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
