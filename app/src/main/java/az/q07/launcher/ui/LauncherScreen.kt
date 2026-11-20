package az.q07.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import az.q07.launcher.ui.theme.CarColors

/** 3 sütunlu üfüqi layout: Dock | Modullar | Tətbiqlər */
@Composable
fun LauncherScreen(appsVersion: Int) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(CarColors.Background)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DockPanel(Modifier.weight(0.22f).fillMaxHeight())
        ModulesPanel(Modifier.weight(0.38f).fillMaxHeight())
        AppsPanel(appsVersion, Modifier.weight(0.40f).fillMaxHeight())
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
