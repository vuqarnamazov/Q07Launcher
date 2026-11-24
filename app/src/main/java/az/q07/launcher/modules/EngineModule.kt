package az.q07.launcher.modules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.ui.EngineControlWidget
import az.q07.launcher.ui.theme.CarColors

/** Mühərrik idarəsi — yalnız P (park) vəziyyətində işləyir */
object EngineModule : LauncherModule {
    override val id = "engine_control"
    override val title = "Mühərrik İdarəsi"
    override val shortTitle = "Mühərrik"
    override val icon: ImageVector = Icons.Filled.DirectionsCar
    override val accent: Color = Color(0xFF10B981)

    @Composable
    override fun Summary() {
        Text("Yalnız P vəziyyətində", fontSize = 13.sp, color = CarColors.TextSecondary)
    }

    @Composable
    override fun Expanded() {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            EngineControlWidget(Modifier.fillMaxWidth())
        }
    }
}
