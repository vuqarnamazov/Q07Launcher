package az.q07.launcher.modules

import android.app.ActivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.ui.theme.CarColors

/** Head unit-in real ekran və sistem parametrləri (dizaynı dəqiqləşdirmək üçün) */
object SystemInfoModule : LauncherModule {
    override val id = "system"
    override val title = "Sistem / ekran"
    override val shortTitle = "Sistem"
    override val icon: ImageVector = Icons.Filled.Memory
    override val accent: Color = CarColors.Warning

    @Composable
    override fun Summary() {
        val dm = LocalContext.current.resources.displayMetrics
        Text(
            "${dm.widthPixels}×${dm.heightPixels} px · ${dm.densityDpi} dpi · Android ${Build.VERSION.RELEASE}",
            fontSize = 16.sp, color = CarColors.TextPrimary
        )
    }

    @Composable
    override fun Expanded() {
        val context = LocalContext.current
        val dm = context.resources.displayMetrics
        val conf = LocalConfiguration.current
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024 * 1024

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Info("Cihaz", "${Build.MANUFACTURER} ${Build.MODEL}")
            Info("Platforma", Build.HARDWARE + " / " + Build.BOARD)
            Info("Android / API", "${Build.VERSION.RELEASE} / ${Build.VERSION.SDK_INT}")
            Info("Ekran (px)", "${dm.widthPixels} × ${dm.heightPixels}")
            Info("Density", "${dm.densityDpi} dpi (×${"%.2f".format(dm.density)})")
            Info("Ekran (dp)", "${conf.screenWidthDp} × ${conf.screenHeightDp}")
            Info("RAM", "%.1f / %.1f GB boş".format(mem.availMem / gb, mem.totalMem / gb))
            Info("Yaddaş", "%.1f / %.1f GB boş".format(stat.availableBytes / gb, stat.totalBytes / gb))
        }
    }

    @Composable
    private fun Info(label: String, value: String) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 15.sp, color = CarColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(value, fontSize = 15.sp, color = CarColors.TextPrimary)
        }
    }
}
