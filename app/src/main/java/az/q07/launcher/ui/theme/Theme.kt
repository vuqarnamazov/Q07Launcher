package az.q07.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object CarColors {
    val Background = Color(0xFF0B0E13)
    val Panel = Color(0xFF141922)
    val PanelAlt = Color(0xFF1C2330)
    val Border = Color(0xFF263042)
    val Accent = Color(0xFF3DA9FC)
    val Green = Color(0xFF00D1A0)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5A5F)
    val Purple = Color(0xFF8E7CFF)
    val YouTube = Color(0xFFFF3D3D)
    val TextPrimary = Color(0xFFEDF2F7)
    val TextSecondary = Color(0xFF8A94A6)
}

private val CarDarkScheme = darkColorScheme(
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

@Composable
fun CarLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CarDarkScheme, content = content)
}
