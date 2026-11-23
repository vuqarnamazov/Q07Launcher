package az.q07.scanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.scanner.sys.DiscoveredVehicleNode
import az.q07.scanner.sys.VehicleSystemScanner
import az.q07.scanner.ui.theme.CarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val categories = listOf("HAMISI", "SYSTEM_SERVICE", "ACTIVITY", "SERVICE", "RECEIVER", "SETTINGS_KEY", "PROPERTY")

/** "Sistem skanı" — binder xidmətləri, gizli komponentlər, Settings açarları, getprop; axtarış, test, ixrac */
@Composable
fun SystemScanTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<DiscoveredVehicleNode>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("HAMISI") }
    var busy by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf("«Tam sistem skanı»na bas. Ekran düymələrini tutmaq üçün «Canlı ekran» bölməsindən istifadə et.") }
    var editing by remember { mutableStateOf<DiscoveredVehicleNode?>(null) }

    val filtered = remember(results, query, cat) {
        results.filter { n ->
            (cat == "HAMISI" || n.category == cat) &&
                (query.isBlank() || n.title.contains(query, true) || n.pathOrKey.contains(query, true) || n.currentValue.contains(query, true))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ScPill(if (busy) "Skan edilir…" else "Tam sistem skanı", Icons.Filled.Search, ScannerAccent, enabled = !busy) {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { VehicleSystemScanner.runFullScan(context) }
                    val f = withContext(Dispatchers.IO) { VehicleSystemScanner.saveExport(context, r) }
                    results = r; busy = false
                    banner = "Tapıldı: ${r.size} yol · " + r.groupingBy { it.category }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" } +
                        (if (f != null) " · export/sistem_skani.json-a yazıldı" else "")
                }
            }
            ScPill("Nəticələri kopyala", Icons.Filled.ContentCopy, CarColors.TextSecondary, enabled = results.isNotEmpty()) {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Q07_Scan", VehicleSystemScanner.exportAsJson(filtered)))
                banner = "${filtered.size} nəticə kopyalandı"
            }
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("Axtar: drive, mode, hvac, steer, factory, diag…") })
        }
        ScChipRow {
            categories.forEach { c ->
                val n = if (c == "HAMISI") results.size else results.count { it.category == c }
                ScChip("$c ($n)", c == cat) { cat = c }
            }
        }
        Text(banner, fontSize = 13.sp, color = CarColors.Green, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.category + it.pathOrKey }) { n ->
                ScCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("[${n.category}] ${n.title}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(n.pathOrKey, fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (n.currentValue.isNotBlank()) Text("Dəyər: ${n.currentValue}", fontSize = 12.sp, color = ScannerAccent,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ScIconBtn(Icons.Filled.ContentCopy, "Kopyala") {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("NodePath", n.pathOrKey))
                            banner = "Kopyalandı: ${n.pathOrKey}"
                        }
                        when (n.executableType) {
                            "ACTIVITY" -> ScIconBtn(Icons.Filled.PlayArrow, "Aç", CarColors.Green) { banner = VehicleSystemScanner.openActivity(context, n) }
                            "SETTINGS" -> ScIconBtn(Icons.Filled.PlayArrow, "Dəyiş", CarColors.Green) { editing = n }
                        }
                    }
                }
            }
        }
    }

    // Parametr dəyişikliyi: kor-koranə 0/1 yox — dəyəri göstərib təsdiq istəyir (maşın funksiyasına təsir edə bilər)
    editing?.let { n ->
        var v by remember(n) { mutableStateOf(n.currentValue) }
        ScaledAlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Parametri dəyiş") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(n.pathOrKey, fontSize = 14.sp)
                    Text("İndiki dəyər: ${n.currentValue.ifBlank { "(boş)" }}", fontSize = 13.sp, color = CarColors.TextSecondary)
                    Text("Diqqət: zavod parametrini dəyişmək maşının davranışına təsir edə bilər. Yalnız dayanmış halda sına; köhnə dəyər jurnala yazılır.",
                        fontSize = 12.sp, color = CarColors.TextSecondary)
                    OutlinedTextField(v, { v = it }, singleLine = true, label = { Text("Yeni dəyər") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    banner = VehicleSystemScanner.writeSetting(context, n, v)
                    results = results.map { if (it === n) it.copy(currentValue = v) else it }
                    editing = null
                }) { Text("Yaz") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Ləğv et") } }
        )
    }
}
