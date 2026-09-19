package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A two-line navigation card (title + description) used by the top-level category picker. */
public class ScdCategoryCard extends AbstractWidget {
	@FunctionalInterface
	public interface OnPress {
		void onPress();
	}

	private final String description;
	private final int accentColor;
	private final OnPress onPress;

	public ScdCategoryCard(int x, int y, int width, int height, Component title, String description, int accentColor, OnPress onPress) {
		super(x, y, width, height, title);
		this.description = description;
		this.accentColor = accentColor;
		this.onPress = onPress;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		playDownSound(Minecraft.getInstance().getSoundManager());
		onPress.onPress();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		ScdTheme.card(g, x, y, w, h, accentColor, isHovered());

		var font = Minecraft.getInstance().font;
		int textX = x + 10;
		int lineHeight = ScdTheme.lineHeight(font);
		ScdTheme.scaledText(g, font, getMessage(), textX, y + 5, ScdTheme.TEXT_PRIMARY);
		ScdTheme.scaledText(g, font, Component.literal(description), textX, y + 5 + lineHeight + 1, ScdTheme.TEXT_SECONDARY);

		String chevron = ">";
		int chevronColor = isHovered() ? accentColor : ScdTheme.TEXT_MUTED;
		int chevronWidth = ScdTheme.textWidth(font, chevron);
		ScdTheme.scaledText(g, font, Component.literal(chevron), x + w - 10 - chevronWidth, y + h / 2 - lineHeight / 2, chevronColor);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
