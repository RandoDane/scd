package com.scd.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Shared palette and drawing primitives for SCD's own flat-card GUI style,
 * used instead of vanilla's beveled button/checkbox look everywhere we build
 * a screen. GuiGraphicsExtractor has no rounded-rect or blur primitive, so
 * "pretty" here means: gradient panels, a soft drop shadow (on top-level
 * surfaces only - nested widgets stay flat so shadows don't stack into a
 * smear), accent-colored left bars, and a hover brighten.
 *
 * Widget colors are kept near-opaque rather than translucent: a translucent
 * widget stacked on top of an already-translucent panel compounds into a
 * washed-out, low-contrast look instead of reading as a crisp surface.
 */
public final class ScdTheme {
	public static final int TEXT_PRIMARY = 0xFFF4F6FA;
	public static final int TEXT_SECONDARY = 0xFFA7ADBB;
	public static final int TEXT_MUTED = 0xFF6B7280;

	public static final int PANEL_TOP = 0xF0181B24;
	public static final int PANEL_BOTTOM = 0xF00F1117;
	public static final int PANEL_BORDER = 0x30FFFFFF;

	public static final int CARD_TOP = 0xFF2A303D;
	public static final int CARD_BOTTOM = 0xFF1C212B;
	public static final int CARD_HOVER_TOP = 0xFF343B4C;
	public static final int CARD_HOVER_BOTTOM = 0xFF242A37;

	public static final int DISABLED_TOP = 0xFF23262E;
	public static final int DISABLED_BOTTOM = 0xFF17191F;

	public static final int SHADOW = 0x4D000000;

	public static final int TRACK_OFF = 0xFF3A3F4B;
	public static final int KNOB = 0xFFF4F6FA;

	public static final int ACCENT_BAZAAR = 0xFF5B8DEF;
	public static final int ACCENT_SLAYER = 0xFFEF5B5B;

	/** Everything drawn through the scaled* helpers below renders at this fraction of native font size, for denser screens (10+ categories). */
	public static final float TEXT_SCALE = 0.85f;

	private ScdTheme() {
	}

	public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x + 2, y + 3, x + w + 2, y + h + 3, SHADOW);
	}

	/** Top-level container: casts its own shadow. Widgets placed inside one should NOT draw their own shadow too. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		shadow(g, x, y, w, h);
		g.fillGradient(x, y, x + w, y + h, PANEL_TOP, PANEL_BOTTOM);
		g.outline(x, y, w, h, PANEL_BORDER);
	}

	/** For elements sitting directly over the game view with no enclosing panel - e.g. the category cards. */
	public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int accentColor, boolean hovered) {
		shadow(g, x, y, w, h);
		g.fillGradient(x, y, x + w, y + h, hovered ? CARD_HOVER_TOP : CARD_TOP, hovered ? CARD_HOVER_BOTTOM : CARD_BOTTOM);
		g.outline(x, y, w, h, hovered ? accentColor : PANEL_BORDER);
		g.fill(x, y, x + 3, y + h, accentColor);
	}

	public static void divider(GuiGraphicsExtractor g, int x, int y, int width) {
		g.fill(x, y, x + width, y + 1, PANEL_BORDER);
	}

	public static int lineHeight(Font font) {
		return Math.round(font.lineHeight * TEXT_SCALE);
	}

	public static int textWidth(Font font, String text) {
		return Math.round(font.width(text) * TEXT_SCALE);
	}

	public static void label(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		scaledText(g, font, Component.literal(text), x, y, color);
	}

	public static void sectionLabel(GuiGraphicsExtractor g, Font font, String text, int x, int y) {
		label(g, font, text.toUpperCase(Locale.ROOT), x, y, TEXT_MUTED);
	}

	public static void scaledText(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(TEXT_SCALE, TEXT_SCALE);
		g.text(font, text, 0, 0, color, true);
		pose.popMatrix();
	}

	public static void scaledCenteredText(GuiGraphicsExtractor g, Font font, Component text, int centerX, int y, int color) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(centerX, y);
		pose.scale(TEXT_SCALE, TEXT_SCALE);
		g.centeredText(font, text, 0, 0, color);
		pose.popMatrix();
	}
}
