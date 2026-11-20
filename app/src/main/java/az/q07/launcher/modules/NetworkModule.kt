package az.q07.launcher.modules

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.NetState
import az.q07.launcher.data.NetworkMonitor
import az.q07.launcher.data.QuickActions
import az.q07.launcher.ui.theme.CarColors

object NetworkModule : LauncherModule {
    override val id = "network"
    override val title = "Şəbəkə / bağlantı"
    override val icon: ImageVector = Icons.Filled.NetworkCheck
    override val accent: Color = CarColors.Green

    @Composable
    private fun rememberNetState(): NetState {
        val context = LocalContext.current
        val flow = remember { NetworkMonitor.stateFlow(context.applicationContext) }
        val state by flow.collectAsState(initial = NetState())
        return state
    }

    @Composable
    override fun Summary() {
        val s = rememberNetState()
        val statusColor = when {
            s.internet -> CarColors.Green
            s.connected -> CarColors.Warning
            else -> CarColors.Danger
        }
        val icon = when {
            !s.connected -> Icons.Filled.WifiOff
            s.type == "Wi-Fi" -> Icons.Filled.Wifi
            s.type == "Mobil şəbəkə" -> Icons.Filled.SignalCellularAlt
            else -> Icons.Filled.SettingsEthernet
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = statusColor, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.type, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary)
                Text(
                    when {
                        s.internet -> "İnternet var"
                        s.connected -> "Qoşulub, internet yoxlanılmayıb"
                        else -> "Oflayn"
                    },
                    fontSize = 15.sp,
                    color = statusColor
                )
            }
            Box(Modifier.size(14.dp).clip(CircleShape).background(statusColor))
        }
    }

    @Composable
    override fun Expanded() {
        val context = LocalContext.current
        val s = rememberNetState()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow("IP ünvanı", s.ip ?: "—")
            InfoRow("Endirmə (təxmini)", s.downKbps?.let { formatKbps(it) } ?: "—")
            InfoRow("Yükləmə (təxmini)", s.upKbps?.let { formatKbps(it) } ?: "—")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    QuickActions.start(context, Intent(Settings.ACTION_WIFI_SETTINGS))
                }) { Text("Wi-Fi ayarları") }
                OutlinedButton(onClick = {
                    QuickActions.start(context, Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }) { Text("Şəbəkə ayarları") }
            }
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 15.sp, color = CarColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(value, fontSize = 15.sp, color = CarColors.TextPrimary)
        }
    }

    private fun formatKbps(kbps: Int): String =
        if (kbps >= 1000) "%.1f Mbit/s".format(kbps / 1000f) else "$kbps kbit/s"
}
