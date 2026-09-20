package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Slayer settings: the shared boss-tracker/miniboss/stats options up top, then
 * one collapsible ("dropdown") section per Slayer type below - each type has
 * its own set of ability call-outs that can be toggled individually, rather
 * than the box being all-or-nothing. Sections default collapsed to keep the
 * list compact; click a type's header to expand it.
 */
public class ScdSlayerConfigScreen extends Screen {
	private static final int PANEL_WIDTH = 300;
	private static final int PADDING = 16;
	private static final int ROW_HEIGHT = 20;
	private static final int TOGGLE_ROW_HEIGHT = 18;

	private record LabelRow(String text, int x, int y) {
	}

	private final Screen parent;
	private final ScdConfig config;
	private final ScdClient client;
	private final Set<ScdSlayerType> expanded = EnumSet.noneOf(ScdSlayerType.class);

	private int panelX, panelY, panelWidth, panelHeight;
	private int trackerLabelY, minibossLabelY, bossHighlightLabelY, statsLabelY;
	private final List<LabelRow> labelRows = new ArrayList<>();

	public ScdSlayerConfigScreen(Screen parent, ScdConfig config, ScdClient client) {
		super(Component.literal("SCD - Slayer"));
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
		trackerLabelY = y + 3;
		addRenderableWidget(new ScdToggle(contentX + fieldWidth - 26, y, Component.literal("Boss tracker"),
				ScdTheme.ACCENT_SLAYER, config.slayer.bossTrackerEnabled, v -> config.slayer.bossTrackerEnabled = v));
		y += 18;

		minibossLabelY = y + 3;
		addRenderableWidget(new ScdToggle(contentX + fieldWidth - 26, y, Component.literal("Miniboss alerts"),
				ScdTheme.ACCENT_SLAYER, config.slayer.minibossAlertEnabled, v -> config.slayer.minibossAlertEnabled = v));
		y += 18;

		bossHighlightLabelY = y + 3;
		addRenderableWidget(new ScdToggle(contentX + fieldWidth - 26, y, Component.literal("Highlight boss"),
				ScdTheme.ACCENT_SLAYER, config.slayer.bossHighlightEnabled, v -> config.slayer.bossHighlightEnabled = v));
		y += 18;

		statsLabelY = y + 3;
		addRenderableWidget(new ScdToggle(contentX + fieldWidth - 26, y, Component.literal("Session stats"),
				ScdTheme.ACCENT_SLAYER, config.slayer.statsHudEnabled, v -> config.slayer.statsHudEnabled = v));
		y += 24;

		// One combined box now (boss/quest info + session stats stacked together) - see ScdSlayerHud.
		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Move Slayer HUD..."), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(new ScdHudEditScreen(this, config, config.slayer.bossTrackerPosition, ScdTheme.ACCENT_SLAYER,
						(g, f) -> client.slayerHud().renderPreview(g, f)))));
		y += 22;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Customize appearance..."), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(new ScdHudAppearanceScreen(this, config, "Slayer HUD", ScdTheme.ACCENT_SLAYER,
						List.of(ScdSlayerColorSlot.values()), config.slayer.hudColors, config.slayer.bossTrackerPosition,
						() -> config.slayer.hudTextScale, v -> config.slayer.hudTextScale = v,
						(g, f, px, py) -> client.slayerHud().renderPreviewAt(g, f, px, py)))));
		y += 22;

		int activeCarries = client.carryQueue().activeCount();
		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16,
				Component.literal(activeCarries > 0 ? "Carries (" + activeCarries + " active)..." : "Carries..."),
				ScdTheme.ACCENT_SLAYER, () ->
						Minecraft.getInstance().setScreen(new ScdCarryQueueScreen(this, client))));
		y += 26;

		for (ScdSlayerType type : ScdSlayerType.values()) {
			y = addTypeSection(type, contentX, fieldWidth, y);
		}

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_SLAYER, () -> {
			config.save();
			Minecraft.getInstance().setScreen(parent);
		}));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_SLAYER, () -> {
			config.save();
			Minecraft.getInstance().setScreen(null);
		}));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	/** Adds one Slayer type's collapsible header, and (if expanded) its ability toggles, returning the new running y. */
	private int addTypeSection(ScdSlayerType type, int contentX, int fieldWidth, int y) {
		boolean isExpanded = expanded.contains(type);
		String status = client.slayerTracker().phaseLabelOrNull(type);

		addRenderableWidget(new ScdExpandableCard(contentX, y, fieldWidth, ROW_HEIGHT,
				Component.literal(type.displayName()), status, ScdTheme.ACCENT_SLAYER, isExpanded, () -> {
					if (!expanded.add(type)) expanded.remove(type);
					rebuildWidgets();
				}));
		y += ROW_HEIGHT + 4;

		if (!isExpanded) return y;

		int indentX = contentX + 10;
		int toggleWidth = fieldWidth - 10;

		switch (type) {
			case ZOMBIE -> y = addAbilityToggle(indentX, toggleWidth, y, "Enrage timer",
					config.slayer.zombie.enrageEnabled, v -> config.slayer.zombie.enrageEnabled = v);
			case VAMPIRE -> {
				y = addAbilityToggle(indentX, toggleWidth, y, "Twinclaw timer",
						config.slayer.vampire.twinclawEnabled, v -> config.slayer.vampire.twinclawEnabled = v);
				y = addAbilityToggle(indentX, toggleWidth, y, "Mania warning",
						config.slayer.vampire.maniaEnabled, v -> config.slayer.vampire.maniaEnabled = v);
			}
			case SPIDER -> {
				y = addAbilityToggle(indentX, toggleWidth, y, "Egg sac warning",
						config.slayer.spider.eggSacEnabled, v -> config.slayer.spider.eggSacEnabled = v);
				y = addAbilityToggle(indentX, toggleWidth, y, "Conjoined Brood warning",
						config.slayer.spider.conjoinedBroodWarningEnabled, v -> config.slayer.spider.conjoinedBroodWarningEnabled = v);
			}
			case BLAZE -> {
				y = addAbilityToggle(indentX, toggleWidth, y, "Fire Pillar warning",
						config.slayer.blaze.firePillarEnabled, v -> config.slayer.blaze.firePillarEnabled = v);
				y = addAbilityToggle(indentX, toggleWidth, y, "Demonsplit warning",
						config.slayer.blaze.demonsplitEnabled, v -> config.slayer.blaze.demonsplitEnabled = v);
			}
			case ENDERMAN -> {
				y = addAbilityToggle(indentX, toggleWidth, y, "Beam phase warning",
						config.slayer.enderman.beamPhaseEnabled, v -> config.slayer.enderman.beamPhaseEnabled = v);
				y = addAbilityToggle(indentX, toggleWidth, y, "Hitshield indicator",
						config.slayer.enderman.hitshieldEnabled, v -> config.slayer.enderman.hitshieldEnabled = v);
				y = addAbilityToggle(indentX, toggleWidth, y, "Explosive Arrow counter",
						config.slayer.enderman.explosiveArrowCounterEnabled, v -> config.slayer.enderman.explosiveArrowCounterEnabled = v);
				addRenderableWidget(new ScdButton(indentX, y, toggleWidth, 16, Component.literal("Move arrow counter box..."), ScdTheme.ACCENT_SLAYER, () ->
						Minecraft.getInstance().setScreen(new ScdHudEditScreen(this, config, config.slayer.enderman.explosiveArrowCounterPosition, ScdTheme.ACCENT_SLAYER,
								(g, f) -> client.quiverHud().renderPreview(g, f)))));
				y += 22;
			}
			case WOLF -> y = addAbilityToggle(indentX, toggleWidth, y, "Call the Pups warning",
					config.slayer.wolf.callThePupsEnabled, v -> config.slayer.wolf.callThePupsEnabled = v);
		}

		int dropCount = client.slayerDrops().entriesForType(type).size();
		addRenderableWidget(new ScdButton(indentX, y, toggleWidth, 16,
				Component.literal(dropCount > 0 ? "View drops (" + dropCount + ")..." : "View drops..."),
				ScdTheme.ACCENT_SLAYER, () ->
						Minecraft.getInstance().setScreen(new ScdSlayerDropsScreen(this, client, type))));
		y += 22;

		return y + 4;
	}

	private int addAbilityToggle(int x, int width, int y, String label, boolean initial, java.util.function.Consumer<Boolean> onChange) {
		addRenderableWidget(new ScdToggle(x + width - 26, y, Component.literal(label), ScdTheme.ACCENT_SLAYER, initial, onChange::accept));
		labelRows.add(new LabelRow(label, x, y + 3));
		return y + TOGGLE_ROW_HEIGHT;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// Panel must be drawn before the super call - that call renders our widgets (buttons/toggles),
		// and drawing the panel afterward paints it right over them, making everything look dark and
		// "behind" the panel.
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "Slayer", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		ScdTheme.label(g, this.font, "Show tracker while fighting a boss", panelX + PADDING, trackerLabelY, ScdTheme.TEXT_SECONDARY);
		ScdTheme.label(g, this.font, "Chat alert when a miniboss spawns", panelX + PADDING, minibossLabelY, ScdTheme.TEXT_SECONDARY);
		ScdTheme.label(g, this.font, "Glow, line + box on the tracked boss, through walls", panelX + PADDING, bossHighlightLabelY, ScdTheme.TEXT_SECONDARY);
		ScdTheme.label(g, this.font, "Session kill-count / avg time HUD", panelX + PADDING, statsLabelY, ScdTheme.TEXT_SECONDARY);

		for (LabelRow row : labelRows) {
			ScdTheme.label(g, this.font, row.text(), row.x(), row.y(), ScdTheme.TEXT_SECONDARY);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
