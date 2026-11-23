package az.q07.scanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Q07 Launcher-in tünd rəngləri (skaner ayrıca tətbiqdir) */
object CarColors {
    val Background = Color(0xFF0B0E13)
    val Panel = Color(0xFF141922)
    val PanelAlt = Color(0xFF1C2330)
    val Border = Color(0xFF263042)
    val Accent = Color(0xFF33CCFF)
    val Green = Color(0xFF34D399)
    val Warning = Color(0xFFF5A524)
    val Danger = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFEDF2F7)
    val TextSecondary = Color(0xFF8A94A6)
}

@Composable
fun CarLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CarColors.Accent, background = CarColors.Background, surface = CarColors.Panel,
            surfaceVariant = CarColors.PanelAlt, onBackground = CarColors.TextPrimary, onSurface = CarColors.TextPrimary,
            onSurfaceVariant = CarColors.TextSecondary, error = CarColors.Danger
        ),
        content = content
    )
}
