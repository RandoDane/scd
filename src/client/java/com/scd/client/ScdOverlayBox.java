package com.scd.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A small reusable "floating box" HUD element: a title line plus N body lines
 * (each with an optional item icon), with the background sized to whatever
 * the widest line actually is (not a fixed guess) so it never clips or
 * over-pads.
 */
public class ScdOverlayBox {
	// ~15% lower opacity than the original 0x90 (144 -> 122), per direct request 2026-09-22.
	private static final int BG_COLOR = 0x7A000000;
	private static final int PADDING = 4;
	private static final int ICON_SIZE = 16;

	public record Line(String text, int color, ItemStack icon) {
		public Line(String text, int color) {
			this(text, color, ItemStack.EMPTY);
		}
	}

	public record Bounds(int x, int y, int width, int height) {
	}

	public static Bounds render(GuiGraphicsExtractor graphics, Font font, int x, int y, String title, int titleColor, List<Line> lines) {
		int lineHeight = Math.max(font.lineHeight + 2, ICON_SIZE + 2);
		boolean anyIcons = lines.stream().anyMatch(l -> !l.icon().isEmpty());
		int textX = x + (anyIcons ? ICON_SIZE + 4 : 0);

		int maxWidth = font.width(title) - (anyIcons ? ICON_SIZE + 4 : 0);
		for (Line line : lines) {
			maxWidth = Math.max(maxWidth, font.width(line.text()));
		}

		int boxWidth = maxWidth + PADDING * 2 + (anyIcons ? ICON_SIZE + 4 : 0);
		int boxHeight = PADDING * 2 + lineHeight * lines.size() + (font.lineHeight + 2);
		graphics.fill(x - PADDING, y - PADDING, x - PADDING + boxWidth, y - PADDING + boxHeight, BG_COLOR);

		graphics.text(font, Component.literal(title), x, y, titleColor, true);
		int lineY = y + font.lineHeight + 2;
		for (Line line : lines) {
			if (!line.icon().isEmpty()) {
				graphics.item(line.icon(), x, lineY - 1);
			}
			int textY = lineY + (lineHeight - font.lineHeight) / 2 - 1;
			graphics.text(font, Component.literal(line.text()), textX, textY, line.color(), true);
			lineY += lineHeight;
		}

		return new Bounds(x - PADDING, y - PADDING, boxWidth, boxHeight);
	}
}
