package az.q07.launcher.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import az.q07.launcher.ui.LearnedFunctionsList
import az.q07.launcher.ui.PlayerStatusLine
import az.q07.launcher.ui.ScPill
import az.q07.launcher.ui.ScSection
import az.q07.launcher.ui.ScannerAccent
import az.q07.launcher.ui.ScannerModeControl
import az.q07.launcher.ui.openScannerScreen
import az.q07.launcher.ui.theme.CarColors

/**
 * Tam Sistem və Gedişat Skaneri — launcher kartı:
 * rejim (Sönülü / Açıq / Fon İzləməsi), skaner ekranına keçid, «Götür və Əlavə Et», launcher funksiyaları.
 */
object VehicleScannerModule : LauncherModule {
    override val id = "scanner"
    override val title = "Tam Skaner"
    override val shortTitle = "Skaner"
    override val icon: ImageVector = Icons.Filled.Radar
    override val accent: Color = ScannerAccent

    @Composable
    override fun Summary() {
        val context = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScannerModeControl()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScPill("Skaner ekranını aç", Icons.Filled.OpenInNew, ScannerAccent) { openScannerScreen(context) }
                ScPill("Funksiya / Gedişat Götür və Əlavə Et", Icons.Filled.Add, CarColors.Green) { openScannerScreen(context) }
            }
            PlayerStatusLine()
        }
    }

    @Composable
    override fun Expanded() {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScSection("Launcher-in funksiya və gedişatları (1 toxunuş)")
            LearnedFunctionsList()
        }
    }
}
