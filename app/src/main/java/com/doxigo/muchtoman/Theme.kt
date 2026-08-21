package com.doxigo.muchtoman

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle

/*
 * DIRECTION CONTRACT — seed wise-fa-2026-08
 *
 * THESIS: One answer, said the way Wise says money — friendly, flat, unmistakably green.
 * Refuses both the dark-fintech dashboard and the retired gold-on-teal heirloom look.
 * OWN-WORLD: forest green #163300 field with the answer in bright green #9FE870; white paper
 * with gray-green cards in light, forest-black in dark; amber caution; Modam set heavy; pills
 * and circles; flat colour, no gloss; critically-damped springs.
 * STORY: she opens, one bright number on deep green says how much; pills say what to do next.
 * FIRST VIEWPORT: forest field to the glass — greeting, bright-green total, the words under
 * it, change pill, month band, freshness strip — then bands of quiet cards.
 * FORM: Wise app design language, brief-pinned (concept roll skipped: user-pinned direction
 * beats the roll).
 * FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review,
 * the verdict, DESIGN.md, and every shipping raster carrying its provenance.
 */

/**
 * Modam, as one variable font file with two axes: wght 200–900 and wdth 70–100.
 *
 * `res/font/modam.ttf` is not the file the foundry ships. That one defaults to ExtraLight
 * Condensed, and `setFontVariationSettings` is API 26+ — so on API 24–25, where Compose
 * silently drops the settings below, every word in the app would come out hairline and
 * squeezed. The copy here has been retargeted so its default instance is Regular at normal
 * width: the axes still work where they can, and where they cannot the fallback is the
 * weight the text wanted anyway.
 */
@OptIn(ExperimentalTextApi::class)
private fun modam(weight: FontWeight, width: Float = 100f) = Font(
    R.font.modam,
    weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.width(width),
    ),
)

val Modam = FontFamily(
    modam(FontWeight.Light),
    modam(FontWeight.Normal),
    modam(FontWeight.Medium),
    modam(FontWeight.SemiBold),
    modam(FontWeight.Bold),
    modam(FontWeight.ExtraBold),
    modam(FontWeight.Black),
)

/**
 * The face every figure is set in: same font, ten percent narrower.
 *
 * A Toman total is the longest string in the app and the one thing that must never wrap, and
 * the width axis is the only way to buy room without giving up size — which is the opposite
 * trade shrink-to-fit makes. Negative tracking would be the usual answer and is the wrong one
 * here: Persian is a connected script, and pulling glyphs together breaks the joins.
 */
val ModamFigures = FontFamily(
    modam(FontWeight.Medium, width = 90f),
    modam(FontWeight.SemiBold, width = 90f),
    modam(FontWeight.Bold, width = 90f),
    modam(FontWeight.ExtraBold, width = 90f),
    modam(FontWeight.Black, width = 90f),
)

/**
 * Tabular figures. Modam's default digits are proportional, so an unchanged ۱ next to a
 * changed ۸ shifts the whole number sideways every time a rate lands — visible as a twitch on
 * the one line she is watching. `tnum` pins every digit to the same advance.
 */
const val TABULAR = "tnum"

/** The hero total and any figure that stands on its own. */
fun figureStyle(color: Color, weight: FontWeight = FontWeight.ExtraBold) = TextStyle(
    fontFamily = ModamFigures,
    fontWeight = weight,
    fontFeatureSettings = TABULAR,
    color = color,
)

internal fun heroFigure(figure: String, tone: Color): AnnotatedString =
    heroFigure(AnnotatedString(figure), tone)

internal fun heroFigure(figure: AnnotatedString, tone: Color): AnnotatedString = buildAnnotatedString {
    append(figure)
    withStyle(SpanStyle(fontSize = 0.9.em)) { append(" ") }
    withStyle(
        SpanStyle(
            fontSize = 0.42.em,
            fontWeight = FontWeight.Bold,
            color = tone.copy(alpha = 0.75f),
        ),
    ) { append("تومان") }
}

/**
 * The hero card's own palette. In the light it is Wise's forest with the answer in bright
 * green; in the dark it is what Wise's dark cards actually are — a neutral elevated room the
 * green visits — because a forest slab on a near-black page reads as olive mud, and the
 * first two cuts (gradient, then flat forest) both proved it. The answer itself stays bright
 * green in both worlds; the lock screen and the widget keep the fixed forest, where it sits
 * on its own with nothing dark behind it.
 */
object Hero {
    /** Wise's forest, flat. The light theme's card, the lock screen, the widget. */
    val forest = Color(0xFF163300)

    private val dark: Boolean
        @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    /** The card's ground. */
    val field: Color
        @Composable get() = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else forest

    /** The answer, and the one thing allowed to be bright green inside the field. */
    val accent = Color(0xFF9FE870)

    /** Secondary text on the field: green-tinted on forest, the scheme's neutral in the dark. */
    val muted: Color
        @Composable get() = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFA9C295)
    val strong: Color
        @Composable get() = if (dark) MaterialTheme.colorScheme.onSurface else Color(0xFFF0F6E9)

    /**
     * Growth. The same convention every Iranian bank app shares — and here it is the brand
     * green itself, which is Wise's own rule: good news and the identity speak in one colour.
     */
    val mint = Color(0xFF9FE870)

    /**
     * The one warning colour the field may use. Bright green already means "the answer" and
     * "the action" up here; a caution in the same green reads as a second call to act.
     */
    val warn = Color(0xFFFFB59F)

    /** Raised surfaces inside the field: pills, wells. Translucent, so they sit on either ground. */
    val well = Color(0x17FFFFFF)
    val hairline = Color(0x24FFFFFF)
}

/**
 * The one loud button, fixed across both themes like the hero: bright green with forest ink —
 * Wise's own call to action. Selection stays on the scheme's `primary` (forest in the light),
 * so *press this* and *this one* are two different statements even when both are green.
 */
object Cta {
    val fill = Color(0xFF9FE870)
    val ink = Color(0xFF163300)
}

/** Three states, not a switch: a two-state toggle cannot express "follow the phone". */
enum class ThemeMode(val fa: String) {
    SYSTEM("خودکار"),
    LIGHT("روشن"),
    DARK("تیره"),
}

/** One spacing scale. Anything not on it is a decision someone has to defend. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 32.dp
    val huge = 48.dp
}

object Radius {
    /** Fully round. Pills, chips, action circles. */
    val pill = 999.dp
    val field = 14.dp
    val card = 18.dp

    /** The container a band of rows sits in, as one object rather than a stack of cards. */
    val group = 22.dp

    /** Only the two bottom corners; the field runs off the top of the screen. */
    val hero = 34.dp
    val sheet = 28.dp
}

/**
 * Motion in two voices. Springs are the default for anything that moves a value — they start
 * from the current state, absorb interruption, and settle without a scripted duration. The
 * easing pair stays for enter/exit choreography where a spring has no distance to own.
 */
object Motion {
    val enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val fast = 180
    const val medium = 340

    /** Critically damped, ~0.35s response — the default voice. Nothing overshoots. */
    fun <T> settle() = spring<T>(dampingRatio = 1f, stiffness = 340f)

    /** A press giving under the thumb: same response, still no bounce, just live. */
    fun <T> press() = spring<T>(dampingRatio = 1f, stiffness = 700f)
}

/**
 * Every role is set on purpose. Leaving the container roles unset is what makes an app look
 * unfinished: Material's default baseline is violet, so dialogs, sheets and text fields come
 * out mauve no matter how carefully `primary` was chosen.
 *
 * Light is Wise's daylight: white paper, gray-green cards, and the identity pair inverted —
 * forest fills with the bright green inside them, so every filled pill is the logo lockup and
 * every primary-coloured word is forest, which reads at 13.9:1 on this paper.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF163300),
    onPrimary = Color(0xFF9FE870),
    primaryContainer = Color(0xFFDCF2C0),
    onPrimaryContainer = Color(0xFF12290A),

    secondary = Color(0xFF7A5900),          // amber dark enough to read as caution on paper
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7ECC3),
    onSecondaryContainer = Color(0xFF3D2E00),

    // Gain, and only gain. Distinct from the forest primary so «بیشتر» never reads as a
    // button, and green in both themes — the one convention this app never trades away.
    tertiary = Color(0xFF1F7A40),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBFEECC),
    onTertiaryContainer = Color(0xFF052912),

    background = Color.White,               // Wise daylight: real white, cards carry the tint
    onBackground = Color(0xFF131711),

    surface = Color(0xFFF3F5EE),            // gray with a green whisper — the card colour
    onSurface = Color(0xFF131711),
    surfaceVariant = Color(0xFFEAEEE0),
    onSurfaceVariant = Color(0xFF59614F),   // green-tinted muted text, never flat grey

    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBF7),
    surfaceContainer = Color(0xFFEAEDE2),
    surfaceContainerHigh = Color(0xFFFCFDFA),   // dialogs, sheets, the bar
    surfaceContainerHighest = Color(0xFFE3E8D7),

    outline = Color(0xFF6F7965),
    outlineVariant = Color(0xFFDDE2D2),

    error = Color(0xFFA8200D),
    onError = Color.White,
    errorContainer = Color(0xFFF9DCD5),
    onErrorContainer = Color(0xFF400A02),
    scrim = Color(0xFF0A0F08),
)

/**
 * Wise's dark: near-neutral darks with a breath of green, white-ish text, neutral grey for
 * the muted voice, and the bright green spent only where it means something. The first cut
 * tinted every surface toward forest and the whole theme read as olive mud — dark mode is
 * not the light theme dimmed, it is its own neutral room the green visits.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FE870),
    onPrimary = Color(0xFF163300),
    // A quiet dark chip with the bright green as its content — Wise's dark action circles —
    // rather than a green slab. Visible on the bar and the page alike.
    primaryContainer = Color(0xFF2A3A1D),
    onPrimaryContainer = Color(0xFF9FE870),

    secondary = Color(0xFFEDCB5A),
    onSecondary = Color(0xFF362A00),
    secondaryContainer = Color(0xFF4C3F0E),
    onSecondaryContainer = Color(0xFFF8ECC1),

    // Gain speaks in the brand green itself in the dark — Wise's own rule. What keeps it
    // distinct from «press this» is shape and context, not a second green.
    tertiary = Color(0xFF9FE870),
    onTertiary = Color(0xFF163300),
    tertiaryContainer = Color(0xFF21361B),
    onTertiaryContainer = Color(0xFFC2EFAE),

    background = Color(0xFF121511),         // Wise's dark screen: near-black, barely green
    onBackground = Color(0xFFF2F3EF),

    surface = Color(0xFF1C1F1A),
    onSurface = Color(0xFFF2F3EF),
    surfaceVariant = Color(0xFF262923),
    onSurfaceVariant = Color(0xFFA8AAA6),   // Wise's neutral grey, not a green tint

    surfaceContainerLowest = Color(0xFF0D0F0C),
    surfaceContainerLow = Color(0xFF181B16),
    surfaceContainer = Color(0xFF22251F),
    surfaceContainerHigh = Color(0xFF272A24),
    surfaceContainerHighest = Color(0xFF30332C),

    outline = Color(0xFF7F827C),
    outlineVariant = Color(0xFF383B35),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5F1410),
    errorContainer = Color(0xFF7A2820),
    onErrorContainer = Color(0xFFFFDAD5),
    scrim = Color(0xFF000000),
)

@Composable
fun MuchTomanTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = Typography()
    // Roboto has no real Persian coverage, so every style gets Modam — and zero tracking.
    // Persian is a connected script: Material's default letterSpacing (0.5sp on body, more on
    // labels) opens gaps between glyphs that are supposed to be joined. Tightness, where the
    // design wants it, comes off the width axis instead.
    fun Typography.persian() = Typography(
        displayLarge = displayLarge.copy(fontFamily = ModamFigures, fontWeight = FontWeight.Black, letterSpacing = 0.sp),
        displayMedium = displayMedium.copy(fontFamily = ModamFigures, fontWeight = FontWeight.Black, letterSpacing = 0.sp),
        displaySmall = displaySmall.copy(fontFamily = ModamFigures, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp),
        headlineLarge = headlineLarge.copy(fontFamily = Modam, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp),
        headlineMedium = headlineMedium.copy(fontFamily = Modam, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp),
        headlineSmall = headlineSmall.copy(fontFamily = Modam, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontFamily = Modam, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontFamily = Modam, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontFamily = Modam, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontFamily = Modam, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Modam, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = Modam, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontFamily = Modam, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = Modam, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontFamily = Modam, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    )

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = base.persian(),
        content = content,
    )
}
