package az.q07.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.OtaUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Yeni versiya bildirişi: launcher açılanda və hər 6 saatdan bir GitHub version.json yoxlanılır */
@Composable
fun OtaUpdateHost() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        delay(15_000) // açılışı yükləməsin
        while (true) {
            withContext(Dispatchers.IO) { OtaUpdater.check() }
            delay(30 * 60 * 1000L)
        }
    }
    val info by OtaUpdater.update.collectAsState()
    val status by OtaUpdater.status.collectAsState()
    var downloading by remember { mutableStateOf(false) }
    val u = info ?: return
    AlertDialog(
        onDismissRequest = { if (!downloading) OtaUpdater.dismiss() },
        title = { Text("Yeni versiya mövcuddur: v${u.latestVersionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yeniliklər:\n${u.changelog}", fontSize = 15.sp)
                Text("Lisenziya və ayarlar silinmir.", fontSize = 13.sp)
                status?.let { Text(it, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(enabled = !downloading, onClick = {
                downloading = true
                OtaUpdater.downloadAndInstall(context, u)
            }) { Text(if (downloading) "Yüklənir…" else "İndi yenilə") }
        },
        dismissButton = {
            TextButton(onClick = { downloading = false; OtaUpdater.dismiss() }) { Text("Sonra") }
        }
    )
}
