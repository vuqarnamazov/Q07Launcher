package az.q07.launcher.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.ui.ServiceCenterScreen
import az.q07.launcher.ui.theme.CarColors

/** Servis Xidmətləri: xidmət seç, müraciət göndər (SMS təsdiqi ilə / WhatsApp) */
object ServiceModule : LauncherModule {
    override val id = "services"
    override val title = "Servis Xidmətləri"
    override val shortTitle = "Servis"
    override val icon: ImageVector = Icons.Filled.DirectionsCar
    override val accent: Color = Color(0xFF0284C7)

    @Composable
    override fun Summary() {
        Text("Xidmət seçin və müraciət göndərin", fontSize = 13.sp, color = CarColors.TextSecondary)
    }

    @Composable
    override fun Expanded() {
        var open by remember { mutableStateOf(false) }
        if (!open) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent)
                    .clickable { open = true }
                    .padding(14.dp)
            ) {
                Text("Servis Xidmətlərini Aç", fontSize = 16.sp, color = Color.White)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(640.dp)) {
                ServiceCenterScreen(onClose = { open = false })
            }
        }
    }
}
