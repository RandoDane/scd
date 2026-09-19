package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A clickable header row for a collapsible section of a settings list (the
 * "dropdown" for a feature with multiple sub-options) - same flat-card look
 * as ScdCategoryCard, but toggles open/closed in place instead of navigating
 * to another screen, and shows a live one-word status badge (e.g. "Spawned")
 * on the right instead of a fixed description.
 */
public class ScdExpandableCard extends AbstractWidget {
	@FunctionalInterface
	public interface OnToggle {
		void onToggle();
	}

	private final String statusOrNull;
	private final int accentColor;
	private final boolean expanded;
	private final OnToggle onToggle;

	public ScdExpandableCard(int x, int y, int width, int height, Component title, String statusOrNull,
			int accentColor, boolean expanded, OnToggle onToggle) {
		super(x, y, width, height, title);
		this.statusOrNull = statusOrNull;
		this.accentColor = accentColor;
		this.expanded = expanded;
		this.onToggle = onToggle;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		playDownSound(Minecraft.getInstance().getSoundManager());
		onToggle.onToggle();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		ScdTheme.card(g, x, y, w, h, accentColor, isHovered());

		var font = Minecraft.getInstance().font;
		int lineHeight = ScdTheme.lineHeight(font);
		int textX = x + 10;
		ScdTheme.scaledText(g, font, getMessage(), textX, y + (h - lineHeight) / 2, ScdTheme.TEXT_PRIMARY);

		String chevron = expanded ? "▾" : "▸";
		int chevronColor = isHovered() ? accentColor : ScdTheme.TEXT_MUTED;

		int rightEdge = x + w - 10;
		if (statusOrNull != null) {
			int statusWidth = ScdTheme.textWidth(font, statusOrNull);
			ScdTheme.scaledText(g, font, Component.literal(statusOrNull), rightEdge - statusWidth, y + (h - lineHeight) / 2, accentColor);
			rightEdge -= statusWidth + 8;
		}
		int chevronWidth = ScdTheme.textWidth(font, chevron);
		ScdTheme.scaledText(g, font, Component.literal(chevron), rightEdge - chevronWidth, y + (h - lineHeight) / 2, chevronColor);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
