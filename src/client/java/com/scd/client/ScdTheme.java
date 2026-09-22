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
 *
 * Every color below (except SHADOW) is a mutable field, not a constant - see applyTheme(). Every
 * /scd screen already draws exclusively through this class's own helpers (panel/card/label/etc.),
 * so re-skinning here is what makes a picked theme apply everywhere at once, with no per-screen work.
 */
public final class ScdTheme {
	// Classic/default palette - the values every field below started as, before theming existed.
	// applyTheme() tints these rather than replacing the whole palette outright, so Classic (whose
	// "background" tint is the multiply-identity 0xFFFFFFFF) reproduces this exact look, and every
	// other theme's panels/cards stay proportioned the same way, just recolored.
	private static final int BASE_PANEL_TOP = 0xF0181B24;
	private static final int BASE_PANEL_BOTTOM = 0xF00F1117;
	private static final int BASE_PANEL_BORDER = 0x30FFFFFF;
	private static final int BASE_CARD_TOP = 0xFF2A303D;
	private static final int BASE_CARD_BOTTOM = 0xFF1C212B;
	private static final int BASE_CARD_HOVER_TOP = 0xFF343B4C;
	private static final int BASE_CARD_HOVER_BOTTOM = 0xFF242A37;
	private static final int BASE_DISABLED_TOP = 0xFF23262E;
	private static final int BASE_DISABLED_BOTTOM = 0xFF17191F;
	private static final int BASE_TRACK_OFF = 0xFF3A3F4B;

	// Not `final` any more - every field below is live-reassigned by applyTheme() whenever the
	// player picks a theme from ScdConfigScreen's "Themes" box (see ScdConfig.menuTheme), which is
	// what makes a theme apply to every /scd menu for free: they all already read these same shared
	// fields through ScdTheme's own drawing helpers rather than hardcoding a color themselves.
	public static int TEXT_PRIMARY = 0xFFF4F6FA;
	public static int TEXT_SECONDARY = 0xFFA7ADBB;
	public static int TEXT_MUTED = 0xFF6B7280;

	public static int PANEL_TOP = BASE_PANEL_TOP;
	public static int PANEL_BOTTOM = BASE_PANEL_BOTTOM;
	public static int PANEL_BORDER = BASE_PANEL_BORDER;

	public static int CARD_TOP = BASE_CARD_TOP;
	public static int CARD_BOTTOM = BASE_CARD_BOTTOM;
	public static int CARD_HOVER_TOP = BASE_CARD_HOVER_TOP;
	public static int CARD_HOVER_BOTTOM = BASE_CARD_HOVER_BOTTOM;

	public static int DISABLED_TOP = BASE_DISABLED_TOP;
	public static int DISABLED_BOTTOM = BASE_DISABLED_BOTTOM;

	// Pure translucent black - a shadow is a depth cue, not a color choice, so this is the one
	// palette role that intentionally never themes.
	public static final int SHADOW = 0x4D000000;

	public static int TRACK_OFF = BASE_TRACK_OFF;
	public static int KNOB = 0xFFF4F6FA;

	// All three used to be fixed per-category identity colors (blue/red/gold). Since 2026-09-22 a
	// theme overrides all of them to the same shared accent - see ScdHudTheme's doc comment for why
	// (the user's own call: one accent per theme, everywhere, rather than keeping categories themed
	// independently of the chosen theme).
	public static int ACCENT_BAZAAR = 0xFF5B8DEF;
	public static int ACCENT_SLAYER = 0xFFEF5B5B;
	public static int ACCENT_ACCESSORIES = 0xFFE8B84B;

	/** Multiplies each RGB channel of `base` by the matching channel of `tintColor` (0-255 scale) - the same math blitSprite's own argbTint does, reused here so panel()/card()'s literal fillGradient colors theme consistently with panelRounded()'s sprite tinting. Keeps base's own alpha untouched. */
	private static int tint(int base, int tintColor) {
		int a = (base >>> 24) & 0xFF;
		int r = ((base >> 16) & 0xFF) * ((tintColor >> 16) & 0xFF) / 255;
		int g = ((base >> 8) & 0xFF) * ((tintColor >> 8) & 0xFF) / 255;
		int b = (base & 0xFF) * (tintColor & 0xFF) / 255;
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/**
	 * Re-skins every /scd menu at once: called on startup (with whichever theme ScdConfig.menuTheme
	 * names) and again immediately whenever the player picks a different one from ScdConfigScreen's
	 * "Themes" box. Reuses the same 5-field ScdHudTheme model the Slayer HUD customizer already had -
	 * see ScdHudTheme's doc comment for the field-to-role mapping.
	 */
	public static void applyTheme(ScdHudTheme theme) {
		int bgTint = ScdColorSlot.resolve(theme.colors(), ScdSlayerColorSlot.BACKGROUND);
		PANEL_TOP = tint(BASE_PANEL_TOP, bgTint);
		PANEL_BOTTOM = tint(BASE_PANEL_BOTTOM, bgTint);
		PANEL_BORDER = tint(BASE_PANEL_BORDER, bgTint);
		CARD_TOP = tint(BASE_CARD_TOP, bgTint);
		CARD_BOTTOM = tint(BASE_CARD_BOTTOM, bgTint);
		CARD_HOVER_TOP = tint(BASE_CARD_HOVER_TOP, bgTint);
		CARD_HOVER_BOTTOM = tint(BASE_CARD_HOVER_BOTTOM, bgTint);
		DISABLED_TOP = tint(BASE_DISABLED_TOP, bgTint);
		DISABLED_BOTTOM = tint(BASE_DISABLED_BOTTOM, bgTint);
		TRACK_OFF = tint(BASE_TRACK_OFF, bgTint);

		int accent = ScdColorSlot.resolve(theme.colors(), ScdSlayerColorSlot.BOSS_TITLE);
		ACCENT_BAZAAR = accent;
		ACCENT_SLAYER = accent;
		ACCENT_ACCESSORIES = accent;

		TEXT_SECONDARY = ScdColorSlot.resolve(theme.colors(), ScdSlayerColorSlot.BOSS_TEXT);
		TEXT_MUTED = ScdColorSlot.resolve(theme.colors(), ScdSlayerColorSlot.STATS_LABEL);
		TEXT_PRIMARY = ScdColorSlot.resolve(theme.colors(), ScdSlayerColorSlot.STATS_VALUE);
		KNOB = TEXT_PRIMARY;
	}

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
