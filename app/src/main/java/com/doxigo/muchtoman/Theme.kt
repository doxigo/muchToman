package com.doxigo.muchtoman

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/**
 * The hero is the same object in both themes — deep green field, gold number. Fixing it here
 * rather than deriving it from the scheme also means dark mode cannot quietly invert it into
 * a gold slab.
 */
object Hero {
    /** Top and bottom of the field's gradient. Light hits the top corner, as on a card. */
    val top = Color(0xFF0C544A)
    val bottom = Color(0xFF03201C)

    /** The answer, and the one thing allowed to be gold inside the field. */
    val gold = Color(0xFFF7C948)

    /** Green-tinted secondary text, never flat grey. 8.4:1 on `bottom`. */
    val muted = Color(0xFF93B7B0)
    val strong = Color(0xFFEAF3F0)

    /** Growth. The same convention every Iranian bank app shares. */
    val mint = Color(0xFF3BE0A8)

    /**
     * The one warning colour the field may use. Gold already means "the answer" and "the
     * action" up here; a caution in the same gold reads as a second call to act.
     */
    val warn = Color(0xFFFFB4A6)

    /** Raised surfaces inside the field: action circles, pills, the freshness strip. */
    val well = Color(0x1AFFFFFF)
    val hairline = Color(0x1FFFFFFF)
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
    val xxl = 28.dp
    val huge = 40.dp
}

object Radius {
    /** Fully round. Pills, chips, action circles. */
    val pill = 999.dp
    val field = 18.dp
    val card = 24.dp

    /** The container a band of rows sits in, as one object rather than a stack of cards. */
    val group = 28.dp

    /** Only the two bottom corners; the field runs off the top of the screen. */
    val hero = 36.dp
    val sheet = 32.dp
}

/**
 * Material 3's expressive easing set. Everything that enters decelerates hard from an
 * already-visible state; everything that leaves accelerates away.
 */
object Motion {
    val enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val fast = 180
    const val medium = 340
}

/**
 * Every role is set on purpose. Leaving the container roles unset is what makes an app look
 * unfinished: Material's default baseline is violet, so dialogs, sheets and text fields come
 * out mauve no matter how carefully `primary` was chosen.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0A423B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E8E2),
    onPrimaryContainer = Color(0xFF04231F),

    secondary = Color(0xFF8A5E12),          // gold dark enough to read as text on paper
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE7BE),
    onSecondaryContainer = Color(0xFF33230A),

    // Gain, and only gain. Borrowing primary for "بیشتر" made the same meaning green in
    // light mode and gold in dark — and dark gold is also every button on screen.
    tertiary = Color(0xFF12795A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBDEEDA),
    onTertiaryContainer = Color(0xFF04291D),

    background = Color(0xFFF4F1E9),         // warm paper, not clinical white
    onBackground = Color(0xFF101A18),

    surface = Color.White,
    onSurface = Color(0xFF101A18),
    surfaceVariant = Color(0xFFE4EAE6),
    onSurfaceVariant = Color(0xFF4A5C57),   // green-tinted muted text, never flat grey

    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF9F4),
    surfaceContainer = Color(0xFFF0EDE5),
    surfaceContainerHigh = Color.White,     // dialogs and sheets
    surfaceContainerHighest = Color(0xFFEAE7DF),

    outline = Color(0xFF6D807B),
    outlineVariant = Color(0xFFD5DCD8),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBDAD6),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF06100E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2C64D),
    onPrimary = Color(0xFF2C2004),
    primaryContainer = Color(0xFF14453E),
    onPrimaryContainer = Color(0xFFCFE7E1),

    secondary = Color(0xFFE8C77E),
    onSecondary = Color(0xFF2E1F00),
    secondaryContainer = Color(0xFF4A3A12),
    onSecondaryContainer = Color(0xFFF8E8C4),

    // Gain stays green in the dark too.
    tertiary = Color(0xFF3BE0A8),
    onTertiary = Color(0xFF00311F),
    tertiaryContainer = Color(0xFF064832),
    onTertiaryContainer = Color(0xFFB6F2D8),

    background = Color(0xFF070C0B),
    onBackground = Color(0xFFE8EEEC),

    surface = Color(0xFF121A18),
    onSurface = Color(0xFFE8EEEC),
    surfaceVariant = Color(0xFF26302E),
    onSurfaceVariant = Color(0xFFA9BCB7),

    surfaceContainerLowest = Color(0xFF050908),
    surfaceContainerLow = Color(0xFF0E1615),
    surfaceContainer = Color(0xFF141D1B),
    surfaceContainerHigh = Color(0xFF1A2422),
    surfaceContainerHighest = Color(0xFF212C2A),

    outline = Color(0xFF849691),
    outlineVariant = Color(0xFF33403D),

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
