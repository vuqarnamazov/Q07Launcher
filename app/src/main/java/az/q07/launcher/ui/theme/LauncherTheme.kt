package az.q07.launcher.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Launcher mövzuları (Plan 5). ORIGINAL = indiki tünd görünüş. */
enum class LauncherTheme(val title: String) {
    ORIGINAL("Orijinal"), FUTURE("Future"), KLASSIK("Klassik"), MODERN("Modern"), ELEGANT("Elegant")
}

// Mövzuya görə fərqlənən rəng palitraları
data class ThemeColors(
    val background: Brush,
    val backgroundSolid: Color,
    val cardBg: Color,
    val cardAlt: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color?,          // null = Ayarlardakı vurğu rəngi
    val dockBg: Color,
    val isLight: Boolean = false
)

fun getThemeColors(theme: LauncherTheme): ThemeColors = when (theme) {
    LauncherTheme.ORIGINAL -> ThemeColors(
        background = Brush.linearGradient(listOf(Color(0xFF0B0E13), Color(0xFF0B0E13))),
        backgroundSolid = Color(0xFF0B0E13),
        cardBg = Color(0xFF141922), cardAlt = Color(0xFF1C2330), border = Color(0xFF263042),
        textPrimary = Color(0xFFEDF2F7), textSecondary = Color(0xFF8A94A6),
        accent = null, dockBg = Color(0xFF141922)
    )
    LauncherTheme.FUTURE -> ThemeColors(
        background = Brush.horizontalGradient(listOf(Color(0xFF030712), Color(0xFF0B0F19), Color(0xFF030712))),
        backgroundSolid = Color(0xFF060A12),
        cardBg = Color(0x1A1F2937), // Şəffaf glassmorphic
        cardAlt = Color(0x331F2937), border = Color(0x5506B6D4),
        textPrimary = Color(0xFF67E8F9), // Neon Cyan
        textSecondary = Color(0xFF9333EA), // Neon Bənövşəyi
        accent = Color(0xFF06B6D4),
        dockBg = Color(0x261F2937)
    )
    LauncherTheme.KLASSIK -> ThemeColors(
        background = Brush.linearGradient(listOf(Color(0xFF0F0B08), Color(0xFF1F1510))),
        backgroundSolid = Color(0xFF16100C),
        cardBg = Color(0xFF1E140F), // Karbon / Tünd tütün
        cardAlt = Color(0xFF2A1C14), border = Color(0x55D4AF37),
        textPrimary = Color(0xFFD4AF37), // Gold
        textSecondary = Color(0xFF8C6B53), // Bürünc
        accent = Color(0xFFCD7F32), // Rose Gold / Mis
        dockBg = Color(0xFF19100B)
    )
    LauncherTheme.MODERN -> ThemeColors(
        background = Brush.verticalGradient(listOf(Color(0xFFF3F4F6), Color(0xFFE5E7EB))),
        backgroundSolid = Color(0xFFECEEF1),
        cardBg = Color(0xFFFFFFFF), // Parlaq ağ
        cardAlt = Color(0xFFF3F4F6), border = Color(0xFFD1D5DB),
        textPrimary = Color(0xFF111827), // Tünd boz
        textSecondary = Color(0xFF4B5563),
        accent = Color(0xFF3B82F6), // Sürətli Göy
        dockBg = Color(0xFFE5E7EB),
        isLight = true
    )
    LauncherTheme.ELEGANT -> ThemeColors(
        background = Brush.radialGradient(listOf(Color(0xFF1A1F2C), Color(0xFF0E1118))),
        backgroundSolid = Color(0xFF141824),
        cardBg = Color(0x4D1F293D), // Zərif alatoranlıq şəffaflığı
        cardAlt = Color(0x661F293D), border = Color(0x44FB923C),
        textPrimary = Color(0xFFE2E8F0), // İncə gümüşü
        textSecondary = Color(0xFFFDBA74), // Günbatımı amber
        accent = Color(0xFFFB923C),
        dockBg = Color(0x331F293D)
    )
}
