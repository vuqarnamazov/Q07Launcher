package az.q07.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import az.q07.launcher.data.AppEntry
import az.q07.launcher.data.AppRepository
import az.q07.launcher.data.AppSettings
import az.q07.launcher.ui.theme.CarColors

/**
 * Q07 ekranı: 15.4", 2560×1600 (2.5K), fırlana bilən.
 * Dizayn 1280×800 dp üçün çəkilib və ekrana görə avtomatik böyüdülür/kiçildilir,
 * ona görə head unit-in density dəyərindən asılı olmayaraq eyni görünür.
 */
private const val DESIGN_LONG_SIDE_DP = 1280f

@Composable
fun LauncherScreen(appsVersion: Int, resumeTick: Int = 0) {
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null, appsVersion) {
        value = AppRepository.loadApps(context)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(CarColors.Background)
    ) {
        val landscape = maxWidth >= maxHeight
        val longSide = if (landscape) maxWidth.value else maxHeight.value
        val scale = (longSide / DESIGN_LONG_SIDE_DP).coerceIn(0.75f, 2.0f) * AppSettings.uiScalePercent / 100f
        val base = LocalDensity.current

        CompositionLocalProvider(
            LocalDensity provides Density(base.density * scale, base.fontScale)
        ) {
            if (landscape && AppSettings.compactMode) {
                // Kompakt rejim: launcher solda dar panel, tətbiqlər sağda pəncərədə açılır
                Row(Modifier.fillMaxSize()) {
                    DockPanel(
                        apps.orEmpty(), vertical = true, resumeTick = resumeTick,
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(az.q07.launcher.data.WindowLauncher.DOCK_FRACTION)
                            .padding(12.dp)
                    )
                    Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            "Kompakt rejim — tətbiqlər burada pəncərədə açılır.\nDock-dan tətbiq seç. Tam görünüş üçün «Tam» düyməsi.",
                            color = CarColors.TextSecondary,
                            fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DockPanel(apps.orEmpty(), vertical = true, resumeTick = resumeTick, modifier = Modifier.weight(0.22f).fillMaxHeight())
                    ModulesPanel(Modifier.weight(0.36f).fillMaxHeight())
                    AppsPanel(apps, columns = AppSettings.gridColumns, Modifier.weight(0.42f).fillMaxHeight())
                }
            } else {
                // Ekran şaquli fırladılanda
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DockPanel(apps.orEmpty(), vertical = false, resumeTick = resumeTick, modifier = Modifier.fillMaxWidth().weight(0.18f))
                    ModulesPanel(Modifier.fillMaxWidth().weight(0.37f))
                    AppsPanel(apps, columns = AppSettings.gridColumns + 1, Modifier.fillMaxWidth().weight(0.45f))
                }
            }
        }
    }
}

/** Bütün panellər üçün ümumi tünd fon */
@Composable
fun PanelSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(CarColors.Panel)
            .border(1.dp, CarColors.Border, shape),
        content = content
    )
}
