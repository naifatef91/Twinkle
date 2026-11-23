package app.twinkle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.twinkle.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tokens from the design canvas (Tokens artboard). Material roles map onto them:
 *   primary            = accent blue            tertiary  = ok green
 *   primaryContainer   = primary-soft           secondary = warn amber
 *   background / surface / onSurface / onSurfaceVariant / outline = bg / surface / ink / muted / line
 */
object TwinklePalette {
    // Twinkle brand (twinkle-brand/HANDOFF.md): indigo #1E1B4B, yellow #FFD23F, light surface #F4F3FB,
    // secondary text #55527A on light / #C9C7E8 on dark, mono-dark #0B0A1F. Light theme leads with indigo,
    // dark theme with the yellow star colour, which is what reads on an indigo night background.
    val LightPrimary = Color(0xFF1E1B4B)
    val LightPrimarySoft = Color(0xFFE6E4F7)
    val LightBg = Color(0xFFF4F3FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightInk = Color(0xFF1E1B4B)
    val LightMuted = Color(0xFF55527A)
    val LightLine = Color(0xFFDDDBEF)
    val LightOk = Color(0xFF1E7A4C)
    val LightOkSoft = Color(0xFFE1F2E8)
    val LightWarn = Color(0xFFA55A00)
    val LightWarnSoft = Color(0xFFFBEFDD)
    val LightError = Color(0xFFB3261E)

    val DarkPrimary = Color(0xFFFFD23F)
    val DarkPrimarySoft = Color(0xFF2B2766)
    val DarkBg = Color(0xFF0B0A1F)
    val DarkSurface = Color(0xFF16143A)
    val DarkInk = Color(0xFFF4F3FB)
    val DarkMuted = Color(0xFFC9C7E8)
    val DarkLine = Color(0xFF2B2860)
    val DarkOk = Color(0xFF6FCF9B)
    val DarkOkSoft = Color(0xFF173326)
    val DarkWarn = Color(0xFFF0B260)
    val DarkWarnSoft = Color(0xFF3A2B16)
    val DarkError = Color(0xFFF2A9A3)
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = TwinklePalette.LightPrimary,
    onPrimary = Color.White,
    primaryContainer = TwinklePalette.LightPrimarySoft,
    onPrimaryContainer = TwinklePalette.LightPrimary,
    secondary = TwinklePalette.LightWarn,
    onSecondary = Color.White,
    secondaryContainer = TwinklePalette.LightWarnSoft,
    onSecondaryContainer = TwinklePalette.LightWarn,
    tertiary = TwinklePalette.LightOk,
    onTertiary = Color.White,
    tertiaryContainer = TwinklePalette.LightOkSoft,
    onTertiaryContainer = TwinklePalette.LightOk,
    background = TwinklePalette.LightBg,
    onBackground = TwinklePalette.LightInk,
    surface = TwinklePalette.LightSurface,
    onSurface = TwinklePalette.LightInk,
    surfaceVariant = TwinklePalette.LightBg,
    onSurfaceVariant = TwinklePalette.LightMuted,
    outline = TwinklePalette.LightLine,
    outlineVariant = TwinklePalette.LightLine,
    error = TwinklePalette.LightError,
    onError = Color.White,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = TwinklePalette.DarkPrimary,
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = TwinklePalette.DarkPrimarySoft,
    onPrimaryContainer = Color(0xFFFFE38A),
    secondary = TwinklePalette.DarkWarn,
    onSecondary = TwinklePalette.DarkBg,
    secondaryContainer = TwinklePalette.DarkWarnSoft,
    onSecondaryContainer = TwinklePalette.DarkWarn,
    tertiary = TwinklePalette.DarkOk,
    onTertiary = TwinklePalette.DarkBg,
    tertiaryContainer = TwinklePalette.DarkOkSoft,
    onTertiaryContainer = TwinklePalette.DarkOk,
    background = TwinklePalette.DarkBg,
    onBackground = TwinklePalette.DarkInk,
    surface = TwinklePalette.DarkSurface,
    onSurface = TwinklePalette.DarkInk,
    surfaceVariant = TwinklePalette.DarkBg,
    onSurfaceVariant = TwinklePalette.DarkMuted,
    outline = TwinklePalette.DarkLine,
    outlineVariant = TwinklePalette.DarkLine,
    error = TwinklePalette.DarkError,
    onError = TwinklePalette.DarkBg,
)

/**
 * Display face (Readex Pro, variable weight axis) for titles and numbers,
 * body face (IBM Plex Sans Arabic, static weights) for text. Files in res/font.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object TwinkleFonts {
    val Display: FontFamily = FontFamily(
        Font(R.font.readex_pro, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.readex_pro, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
    val Body: FontFamily = FontFamily(
        Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
    )
    val Mono: FontFamily = FontFamily.Monospace
}

val TwinkleTypography = Typography(
    displayLarge = TextStyle(fontFamily = TwinkleFonts.Display, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = TwinkleFonts.Display, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = TwinkleFonts.Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = TwinkleFonts.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = TwinkleFonts.Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = TwinkleFonts.Body, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = TwinkleFonts.Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = TwinkleFonts.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = TwinkleFonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = TwinkleFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = TwinkleFonts.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = TwinkleFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

val TwinkleShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Semantic colors the Material scheme has no slot for. */
data class TwinkleExtras(
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
)

val LocalTwinkleExtras = staticCompositionLocalOf {
    TwinkleExtras(TwinklePalette.LightOk, TwinklePalette.LightOkSoft, TwinklePalette.LightWarn, TwinklePalette.LightWarnSoft)
}

val MaterialTheme.extras: TwinkleExtras
    @Composable get() = LocalTwinkleExtras.current

@Composable
fun TwinkleTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extras = if (darkTheme) {
        TwinkleExtras(TwinklePalette.DarkOk, TwinklePalette.DarkOkSoft, TwinklePalette.DarkWarn, TwinklePalette.DarkWarnSoft)
    } else {
        TwinkleExtras(TwinklePalette.LightOk, TwinklePalette.LightOkSoft, TwinklePalette.LightWarn, TwinklePalette.LightWarnSoft)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTwinkleExtras provides extras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = TwinkleTypography,
            shapes = TwinkleShapes,
            content = content,
        )
    }
}
