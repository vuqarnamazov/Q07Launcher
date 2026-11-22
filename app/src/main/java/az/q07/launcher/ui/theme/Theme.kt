package az.q07.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object CarColors {
    /** Seçilmiş mövzunun palitrası (AppSettings.themeIndex — Compose state, dəyişəndə hər şey yenilənir) */
    val theme: ThemeColors get() = getThemeColors(az.q07.launcher.data.AppSettings.launcherTheme)
    val BackgroundBrush get() = theme.background
    val Background get() = theme.backgroundSolid
    val Panel get() = theme.cardBg
    val PanelAlt get() = theme.cardAlt
    val Border get() = theme.border
    /** Mövzunun vurğu rəngi, yoxdursa Ayarlarda seçilən */
    val Accent: Color get() = theme.accent ?: az.q07.launcher.data.AppSettings.accentColor
    val Green = Color(0xFF00D1A0)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5A5F)
    val Purple = Color(0xFF8E7CFF)
    val YouTube = Color(0xFFFF3D3D)
    val TextPrimary get() = theme.textPrimary
    val TextSecondary get() = theme.textSecondary
}

private fun carLightScheme() = lightColorScheme(
    primary = CarColors.Accent,
    secondary = CarColors.Green,
    background = CarColors.Background,
    surface = CarColors.Panel,
    surfaceVariant = CarColors.PanelAlt,
    onPrimary = Color.White,
    onBackground = CarColors.TextPrimary,
    onSurface = CarColors.TextPrimary,
    onSurfaceVariant = CarColors.TextSecondary,
    error = CarColors.Danger,
)

private fun carDarkScheme() = darkColorScheme(
    primary = CarColors.Accent,
    secondary = CarColors.Green,
    background = CarColors.Background,
    surface = CarColors.Panel,
    surfaceVariant = CarColors.PanelAlt,
    onPrimary = Color.Black,
    onBackground = CarColors.TextPrimary,
    onSurface = CarColors.TextPrimary,
    onSurfaceVariant = CarColors.TextSecondary,
    error = CarColors.Danger,
)

private fun serifTypography(): androidx.compose.material3.Typography {
    val t = androidx.compose.material3.Typography()
    val f = androidx.compose.ui.text.font.FontFamily.Serif
    return t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = f), displayMedium = t.displayMedium.copy(fontFamily = f),
        displaySmall = t.displaySmall.copy(fontFamily = f), headlineLarge = t.headlineLarge.copy(fontFamily = f),
        headlineMedium = t.headlineMedium.copy(fontFamily = f), headlineSmall = t.headlineSmall.copy(fontFamily = f),
        titleLarge = t.titleLarge.copy(fontFamily = f), titleMedium = t.titleMedium.copy(fontFamily = f),
        titleSmall = t.titleSmall.copy(fontFamily = f), bodyLarge = t.bodyLarge.copy(fontFamily = f),
        bodyMedium = t.bodyMedium.copy(fontFamily = f), bodySmall = t.bodySmall.copy(fontFamily = f),
        labelLarge = t.labelLarge.copy(fontFamily = f), labelMedium = t.labelMedium.copy(fontFamily = f),
        labelSmall = t.labelSmall.copy(fontFamily = f),
    )
}

@Composable
fun CarLauncherTheme(content: @Composable () -> Unit) {
    val klassik = az.q07.launcher.data.AppSettings.launcherTheme == LauncherTheme.KLASSIK
    MaterialTheme(
        colorScheme = if (CarColors.theme.isLight) carLightScheme() else carDarkScheme(),
        typography = if (klassik) serifTypography() else androidx.compose.material3.Typography(),
        content = content
    )
}
