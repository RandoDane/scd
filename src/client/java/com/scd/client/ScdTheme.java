package com.scd.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * Shared palette and drawing primitives for SCD's own flat-card GUI style,
 * used instead of vanilla's beveled button/checkbox look everywhere we build
 * a screen (panel/card/etc. below - the /scd config screens). "pretty" there
 * means: gradient panels, a soft drop shadow (on top-level surfaces only -
 * nested widgets stay flat so shadows don't stack into a smear),
 * accent-colored left bars, and a hover brighten.
 *
 * panelRounded() below is a SEPARATE, newer style for the always-on HUD
 * overlays specifically (boss tracker, session stats - never the /scd
 * screens) - see ScdSlayerHud/ScdSlayerStatsHud. Confirmed live
 * (2026-09-19) that despite this class's own older assumption otherwise,
 * GuiGraphicsExtractor DOES support real rounded corners via
 * blitSprite()'s automatic nine-slice handling, reusing vanilla's own
 * "popup/background" sprite rather than shipping a custom texture.
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
	public static final int ACCENT_ACCESSORIES = 0xFFE8B84B;

	// Same rarity colors Hypixel itself uses on item tooltips/chat (standard Minecraft formatting
	// colors underneath: white/green/blue/dark_purple/gold/light_purple/aqua) - so a rarity-tagged
	// row here reads exactly like the in-game item name would. Order matches worst-to-best, reused
	// both for color lookup and for ranking missing accessories best-first (see ScdClient's
	// sortedMissingAccessories).
	private static final String[] RARITY_ORDER = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE"};
	private static final int[] RARITY_COLORS = {0xFFFFFFFF, 0xFF55FF55, 0xFF5555FF, 0xFFAA00AA, 0xFFFFAA00, 0xFFFF55FF, 0xFF55FFFF};

	/** Index into RARITY_ORDER (worst=0), or -1 for an unknown/missing tier - used to sort missing accessories best-first. */
	public static int rarityRank(String tier) {
		if (tier == null) return -1;
		for (int i = 0; i < RARITY_ORDER.length; i++) {
			if (RARITY_ORDER[i].equals(tier)) return i;
		}
		return -1;
	}

	/** The color an item of this rarity's name would render in, matching Hypixel's own tooltip colors. Falls back to TEXT_SECONDARY for an unknown/missing tier rather than guessing. */
	public static int rarityColor(String tier) {
		int rank = rarityRank(tier);
		return rank >= 0 ? RARITY_COLORS[rank] : TEXT_SECONDARY;
	}

	/** "LEGENDARY" -> "Legendary" - for display next to the colored name instead of shouty all-caps. */
	public static String prettyTier(String tier) {
		if (tier == null || tier.isEmpty()) return "Unknown";
		return tier.charAt(0) + tier.substring(1).toLowerCase(Locale.ROOT);
	}

	/**
	 * Everything drawn through the scaled* helpers below renders at this fraction of native font size,
	 * for denser screens (10+ categories). MUST stay a whole number reciprocal / exact multiple - vanilla's
	 * font is a small pixel bitmap sampled with nearest-neighbor, so a fractional matrix scale (the
	 * original 0.85f) maps source pixels onto destination pixels unevenly, thickening some strokes and
	 * thinning others - that unevenness is what read as "ugly"/muddy font, not the font itself. 1.0
	 * (native, unscaled) is the only value guaranteed crisp for shrinking; only grow via whole multiples
	 * (see HERO_SCALE).
	 */
	public static final float TEXT_SCALE = 1.0f;
	/**
	 * heroNumber() renders at this multiple of native font size - the single most important stat on a
	 * HUD box, e.g. kills/hour. Kept as a whole integer for the same nearest-neighbor-sampling reason as
	 * TEXT_SCALE above: 2.2x scaled every glyph (and its built-in drop shadow) unevenly, which is what
	 * made the big number look chunky/blurry. 2x samples every source pixel onto a clean 2x2 block.
	 */
	public static final float HERO_SCALE = 2.0f;

	// Our own custom nine-slice sprite (mod/src/client/resources/assets/scd/textures/gui/sprites/hud/panel.png)
	// - vanilla's neutral gray "popup/background" was tried first and looked exactly like generic
	// vanilla system UI, which is precisely the "old Minecraft" look this was meant to move away
	// from. A real asset with actual color/depth is what "modern" here actually requires.
	private static final Identifier HUD_PANEL_SPRITE = Identifier.fromNamespaceAndPath("scd", "hud/panel");

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

	/** How far the shadow halo behind panelRounded() extends past the panel on every side. */
	private static final int PANEL_SHADOW_SPREAD = 2;

	/**
	 * Rounded-corner HUD panel background, for the always-on overlays (never the /scd screens - use
	 * panel() there). blitSprite() automatically nine-slice-scales this to (w, h) using the sprite's
	 * own .mcmeta scaling metadata (a plain dark fill with a subtle lighter border, already exactly
	 * what a HUD box needs) - no custom texture asset required.
	 *
	 * The shadow is the same rounded sprite (tinted black/translucent) drawn CONCENTRICALLY LARGER
	 * (grown by PANEL_SHADOW_SPREAD on every side) rather than offset diagonally like shadow() below -
	 * a diagonal offset shifts the shadow's own rounded corners away from the panel's corners by a
	 * different amount on each axis, so the two curves no longer line up and the mismatch shows as a
	 * stray dark sliver/flat edge exactly at the corners and along whichever side got the least
	 * coverage. Growing both dimensions by the same amount instead keeps every corner concentric with
	 * the panel's own, so the halo is a uniform ring with no seam anywhere on the perimeter.
	 */
	public static void panelRounded(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		panelRounded(g, x, y, w, h, 0xFFFFFFFF);
	}

	/**
	 * Same as above, but with the sprite tinted by {@code argbTint} (multiplied into its own colors -
	 * 0xFFFFFFFF is "no change") - lets a HUD's background color be player-customizable without a
	 * second texture asset. The shadow halo is intentionally NOT tinted (always plain black/translucent
	 * SHADOW), since a shadow that changed color with the background would stop reading as a shadow.
	 */
	public static void panelRounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int argbTint) {
		int s = PANEL_SHADOW_SPREAD;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, HUD_PANEL_SPRITE, x - s, y - s, w + s * 2, h + s * 2, SHADOW);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, HUD_PANEL_SPRITE, x, y, w, h, argbTint);
	}

	/** The single most important number on a HUD box (e.g. kills/hour) - big and bold, left-aligned at (x, y). */
	public static void heroNumber(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		heroNumber(g, font, text, x, y, color, 1.0f);
	}

	/**
	 * Same as above, but with HERO_SCALE further multiplied by {@code textScaleMultiplier} - a per-HUD
	 * user preference (see ScdConfig.Slayer.hudTextScale and friends). Deliberately still just a
	 * multiply on top of the already-integer HERO_SCALE rather than a free-form size in pixels: nudging
	 * it away from 1.0 reintroduces the fractional nearest-neighbor softness HERO_SCALE=2.0 was chosen
	 * to avoid, so callers should keep the allowed range small (see ScdHudAppearanceScreen).
	 */
	public static void heroNumber(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, float textScaleMultiplier) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		float scale = HERO_SCALE * textScaleMultiplier;
		pose.scale(scale, scale);
		g.text(font, Component.literal(text), 0, 0, color, true);
		pose.popMatrix();
	}

	/** Height in native (unscaled) pixels that a heroNumber() line occupies - for laying out whatever comes after it. */
	public static int heroLineHeight(Font font) {
		return heroLineHeight(font, 1.0f);
	}

	public static int heroLineHeight(Font font, float textScaleMultiplier) {
		return Math.round(font.lineHeight * HERO_SCALE * textScaleMultiplier);
	}

	public static void divider(GuiGraphicsExtractor g, int x, int y, int width) {
		g.fill(x, y, x + width, y + 1, PANEL_BORDER);
	}

	public static int lineHeight(Font font) {
		return lineHeight(font, TEXT_SCALE);
	}

	public static int lineHeight(Font font, float scale) {
		return Math.round(font.lineHeight * scale);
	}

	public static int textWidth(Font font, String text) {
		return Math.round(font.width(text) * TEXT_SCALE);
	}

	public static void label(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		scaledText(g, font, Component.literal(text), x, y, color, TEXT_SCALE);
	}

	public static void label(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, float scale) {
		scaledText(g, font, Component.literal(text), x, y, color, scale);
	}

	public static void sectionLabel(GuiGraphicsExtractor g, Font font, String text, int x, int y) {
		sectionLabel(g, font, text, x, y, TEXT_MUTED, TEXT_SCALE);
	}

	public static void sectionLabel(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, float scale) {
		label(g, font, text.toUpperCase(Locale.ROOT), x, y, color, scale);
	}

	public static void scaledText(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
		scaledText(g, font, text, x, y, color, TEXT_SCALE);
	}

	public static void scaledText(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color, float scale) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
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
