package az.q07.launcher.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.VehicleControlBindings
import az.q07.launcher.ui.LearnedPathsScreen
import az.q07.launcher.ui.ScPill
import az.q07.launcher.ui.theme.CarColors

/**
 * «Öyrənilmiş Yollar» kartı — mühərrik, işıqlar, qapılar, kondisioner və pəncərələr üçün
 * öyrənilmiş gedişatların idarə paneli.
 */
object PathsModule : LauncherModule {
    override val id = "paths"
    override val title = "Öyrənilmiş Yollar"
    override val shortTitle = "Yollar"
    override val icon: ImageVector = Icons.Filled.Tune
    override val accent: Color = CarColors.Accent

    @Composable
    override fun Summary() {
        val context = LocalContext.current
        var open by remember { mutableStateOf(false) }
        var tick by remember { mutableIntStateOf(0) }
        val bound = remember(tick) { VehicleControlBindings.boundCount(context) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "$bound / ${VehicleControlBindings.controls.size} idarə bağlanıb",
                fontSize = 15.sp, color = CarColors.TextPrimary
            )
            ScPill("Paneli aç", Icons.Filled.OpenInNew, accent) { open = true }
            Text(
                "Mühərrik, işıqlar, qapılar, kondisioner, pəncərələr — Öyrən / Test / Sil / Əvəz et",
                fontSize = 12.sp, color = CarColors.TextSecondary
            )
        }

        if (open) {
            LearnedPathsScreen(onClose = { open = false; tick++ })
        }
    }

    @Composable
    override fun Expanded() {
        Summary()
    }
}
