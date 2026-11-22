package az.q07.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppEntry
import az.q07.launcher.data.AppRepository
import az.q07.launcher.data.RecentApps
import az.q07.launcher.data.WindowLauncher
import az.q07.launcher.ui.theme.CarColors

/**
 * 2-ci səhifə: launcher-dən açdığın proqramlar.
 * Toxunanda proqram dock-un SAĞINDA pəncərədə açılır — soldakı menyu görünən qalır.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentAppsPanel(apps: List<AppEntry>?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { RecentApps.load(context) }
    val byKey = apps.orEmpty().associateBy { it.key }
    val list = RecentApps.keys.mapNotNull { byKey[it] }
    PanelSurface(modifier) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Açdığım proqramlar", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
            LiveCarStrip()
            Text(
                "Toxun — proqram açılır, soldakı menyu (saat, dock, maşın göstəriciləri) görünür qalır. Uzun bas — siyahıdan sil.",
                fontSize = 13.sp, color = CarColors.TextSecondary
            )
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Hələ proqram açmamısan.\nƏsas səhifədən proqram açdıqca burada görünəcək.",
                        fontSize = 16.sp, color = CarColors.TextSecondary, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(130.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(list, key = { it.key }) { app ->
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(CarColors.PanelAlt)
                                .combinedClickable(
                                    onClick = {
                                        az.q07.launcher.data.SidePanel.panelPackages.add(app.packageName)
                                        AppRepository.launch(context, app, WindowLauncher.Area.BESIDE_DOCK)
                                    },
                                    onLongClick = { RecentApps.remove(context, app.key) }
                                )
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Image(app.icon, app.label, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                            Text(
                                app.label, fontSize = 14.sp, color = CarColors.TextPrimary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
