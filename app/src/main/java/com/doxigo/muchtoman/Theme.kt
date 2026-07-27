package com.doxigo.muchtoman

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val Vazir = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

/**
 * The hero card is the one thing she opens the app to read, so it is the same object in both
 * themes — deep green, gold number. Fixing it here rather than deriving it from the scheme
 * also means dark mode cannot quietly invert it into a gold slab.
 */
val HeroBg = Color(0xFF0A3733)
val HeroAccent = Color(0xFFF3C75A)
val HeroMuted = Color(0xFF9FBDB7)

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
}

object Radius {
    val field = 16.dp
    val card = 22.dp
    val hero = 28.dp
    val sheet = 28.dp
}

/**
 * Every role is set on purpose. Leaving the container roles unset is what makes an app look
 * unfinished: Material's default baseline is violet, so dialogs, sheets and text fields come
 * out mauve no matter how carefully `primary` was chosen.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0A3733),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE3DE),
    onPrimaryContainer = Color(0xFF04211D),

    secondary = Color(0xFF8A5E12),          // gold dark enough to read as text on cream
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E4BC),
    onSecondaryContainer = Color(0xFF2E1F00),

    background = Color(0xFFF6F3EC),         // warm paper, not clinical white
    onBackground = Color(0xFF12211F),

    surface = Color.White,
    onSurface = Color(0xFF12211F),
    surfaceVariant = Color(0xFFE4EAE7),
    onSurfaceVariant = Color(0xFF44554F),   // green-tinted muted text, never flat grey

    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF9F4),
    surfaceContainer = Color(0xFFF2EFE8),
    surfaceContainerHigh = Color.White,     // dialogs and sheets
    surfaceContainerHighest = Color(0xFFEDEAE3),

    outline = Color(0xFF6F817D),
    outlineVariant = Color(0xFFD3DBD8),

    error = Color(0xFFA4231C),
    onError = Color.White,
    scrim = Color(0xFF07100E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0C24B),
    onPrimary = Color(0xFF2A1F05),
    primaryContainer = Color(0xFF16443E),
    onPrimaryContainer = Color(0xFFCFE7E1),

    secondary = Color(0xFFE8C77E),
    onSecondary = Color(0xFF2E1F00),
    secondaryContainer = Color(0xFF4A3A12),
    onSecondaryContainer = Color(0xFFF6E4BC),

    background = Color(0xFF0C1412),
    onBackground = Color(0xFFE6ECEA),

    surface = Color(0xFF16211F),
    onSurface = Color(0xFFE6ECEA),
    surfaceVariant = Color(0xFF2A3634),
    onSurfaceVariant = Color(0xFFAFBFBB),

    surfaceContainerLowest = Color(0xFF0A110F),
    surfaceContainerLow = Color(0xFF121C1A),
    surfaceContainer = Color(0xFF182422),
    surfaceContainerHigh = Color(0xFF1E2B29),
    surfaceContainerHighest = Color(0xFF253331),

    outline = Color(0xFF87968D),
    outlineVariant = Color(0xFF3A4644),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5F1410),
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
    // Roboto has no real Persian coverage, so every style gets Vazirmatn.
    fun Typography.persian() = Typography(
        displayLarge = displayLarge.copy(fontFamily = Vazir),
        displayMedium = displayMedium.copy(fontFamily = Vazir),
        displaySmall = displaySmall.copy(fontFamily = Vazir),
        headlineLarge = headlineLarge.copy(fontFamily = Vazir),
        headlineMedium = headlineMedium.copy(fontFamily = Vazir),
        headlineSmall = headlineSmall.copy(fontFamily = Vazir),
        titleLarge = titleLarge.copy(fontFamily = Vazir),
        titleMedium = titleMedium.copy(fontFamily = Vazir),
        titleSmall = titleSmall.copy(fontFamily = Vazir),
        bodyLarge = bodyLarge.copy(fontFamily = Vazir),
        bodyMedium = bodyMedium.copy(fontFamily = Vazir),
        bodySmall = bodySmall.copy(fontFamily = Vazir),
        labelLarge = labelLarge.copy(fontFamily = Vazir),
        labelMedium = labelMedium.copy(fontFamily = Vazir),
        labelSmall = labelSmall.copy(fontFamily = Vazir),
    )

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = base.persian(),
        content = content,
    )
}
