package app.hermes.mobile.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object HermesColors {
    val LightBackground = Color(0xFFF5F5F5)
    val LightSurface = Color(0xFFFFFFFF)
    val LightUserBubble = Color(0xFFE1E1E1)
    val DarkBackground = Color(0xFF303030)
    val DarkSurface = Color(0xFF393939)
    val DarkHermesBubble = Color(0xFF454545)
    val DarkUserBubble = Color(0xFF5A5A5A)
    val Running = Color(0xFF2E9C5C)
    val Waiting = Color(0xFFD88920)
    val Failed = Color(0xFFC85050)
}

val LocalHermesDark = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B5B5B), onPrimary = Color.White,
    background = HermesColors.LightBackground, onBackground = Color(0xFF4A4A4A),
    surface = HermesColors.LightSurface, onSurface = Color(0xFF4A4A4A),
    surfaceVariant = Color(0xFFEFEFEF), onSurfaceVariant = Color(0xFF727272),
    outline = Color(0xFFD7D7D7), error = HermesColors.Failed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCBCBCB), onPrimary = Color(0xFF454545),
    background = HermesColors.DarkBackground, onBackground = Color(0xFFE6E6E6),
    surface = HermesColors.DarkSurface, onSurface = Color(0xFFE6E6E6),
    surfaceVariant = HermesColors.DarkHermesBubble, onSurfaceVariant = Color(0xFFC0C0C0),
    outline = Color(0xFF5A5A5A), error = Color(0xFFE17A7A),
)

private val HermesTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.7.sp),
)

private val HermesShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun HermesTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = resolveDarkTheme(mode, isSystemInDarkTheme())
    val background by animateColorAsState(if (dark) HermesColors.DarkBackground else HermesColors.LightBackground, label = "theme-background")
    val base = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalHermesDark provides dark) {
        MaterialTheme(
            colorScheme = base.copy(background = background),
            typography = HermesTypography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
