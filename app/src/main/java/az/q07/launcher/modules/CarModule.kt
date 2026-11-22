package az.q07.launcher.modules

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import az.q07.launcher.data.WindowLauncher
import az.q07.launcher.data.DriveModeController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import az.q07.launcher.data.WidgetStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.CarApi
import az.q07.launcher.data.ObdMonitor
import az.q07.launcher.data.ObdPid
import az.q07.launcher.data.ObdPids
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class CarApiSnapshot(
    val available: Boolean = false,
    val gear: String? = null,
    val parking: Boolean? = null,
    val speedKmh: Double? = null,
    val night: Boolean? = null,
    val outsideC: Double? = null,
    val hvacSetC: Double? = null
)

/** Maşın göstəriciləri: temperaturlar, batareya, sürət, ötürmə — OBD (ELM327) + Car API */
object CarModule : LauncherModule {
    override val id = "car"
    override val title = "Maşın göstəriciləri"
    override val shortTitle = "Maşın"
    override val icon: ImageVector = Icons.Filled.Thermostat
    override val accent: Color = CarColors.Warning

    @Composable
    private fun rememberCarApi(): CarApiSnapshot {
        val context = LocalContext.current
        val snap by produceState(CarApiSnapshot()) {
            val ok = withContext(Dispatchers.Default) { CarApi.init(context) }
            while (ok) {
                value = withContext(Dispatchers.Default) {
                    CarApiSnapshot(
                        available = true,
                        gear = CarApi.gearName(CarApi.read(CarApi.GEAR_SELECTION) ?: CarApi.read(CarApi.CURRENT_GEAR)),
                        parking = CarApi.read(CarApi.PARKING_BRAKE_ON) as? Boolean,
                        speedKmh = (CarApi.read(CarApi.PERF_VEHICLE_SPEED) as? Number)?.toDouble()?.times(3.6),
                        night = CarApi.read(CarApi.NIGHT_MODE) as? Boolean,
                        outsideC = (CarApi.read(CarApi.ENV_OUTSIDE_TEMPERATURE) as? Number)?.toDouble(),
                        hvacSetC = (CarApi.read(CarApi.HVAC_TEMPERATURE_SET, 0x31) as? Number
                            ?: CarApi.read(CarApi.HVAC_TEMPERATURE_SET, 0x1) as? Number)?.toDouble()
                    )
                }
                delay(1000)
            }
        }
        return snap
    }

    @Composable
    private fun ObdUsage() {
        DisposableEffect(Unit) {
            ObdMonitor.acquire()
            onDispose { ObdMonitor.release() }
        }
    }

    // Qeyd: Q07-də standart Android VHAL emulyator HAL-ıdır (diaqnostika 21.11.2026) —
    // ötürmə/sürət/kondisioner dəyərləri sintetikdir. Ona görə yalnız OBD (real) göstərilir.
    private val mainKeys = listOf("ambient", "coolant", "soc", "voltage")
    private val secondKeys = listOf("speed", "rpm", "fuel", "intake")

    @Composable
    override fun Summary() {
        ObdUsage()
        val obd by ObdMonitor.state.collectAsState()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DriveModeBar()
            CarFactoryWidgets()
            TileGrid(ObdPids.all.filter { it.key in mainKeys + secondKeys }
                .sortedBy { (mainKeys + secondKeys).indexOf(it.key) }, obd.values)
            Text(
                "OBD: ${obd.status}",
                fontSize = 13.sp,
                color = if (obd.connected) CarColors.Green else CarColors.Warning
            )
            if (obd.detail.isNotBlank() && (obd.values.isEmpty() || !obd.connected)) {
                Text(obd.detail, fontSize = 11.sp, color = CarColors.TextSecondary, maxLines = 3)
            }
        }
    }

    /** Eni nə olursa olsun kəsilməyən kafel şəbəkəsi: sütun sayı enə görə 2–4 */
    @Composable
    private fun TileGrid(pids: List<ObdPid>, values: Map<String, Double>) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = (maxWidth / 112.dp).toInt().coerceIn(2, 4)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pids.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { pid -> Tile(pid, values[pid.key], Modifier.weight(1f)) }
                        repeat(cols - row.size) { Column(Modifier.weight(1f)) {} }
                    }
                }
            }
        }
    }

    /**
     * Sürüş rejimləri: Sport / Standart / Komfort.
     * Changan SDK rejimi dəyişməyi yalnız icazəli (zavod) tətbiqlərə verir (-1001), ona görə düymə
     * zavodun «Avtomobil parametrləri» ekranını launcher-in yanında pəncərədə açır — orada bir toxunuşla seçilir.
     */
    @Composable
    private fun DriveModeBar() {
        val context = LocalContext.current
        val current by DriveModeController.current.collectAsState()
        val busy by DriveModeController.busy.collectAsState()
        val msg by DriveModeController.message.collectAsState()
        val enabled = remember(msg) { DriveModeController.isEnabled(context) }
        // Kart ilk dəfə görünəndə hazırkı rejimi səssizcə oxu
        LaunchedEffect(enabled) { if (enabled && DriveModeController.current.value == null) DriveModeController.request(context, null) }
        // launcher düyməsi → zavod sekməsinin indeksi (Komfort=0, Standart=1, İdman=2)
        val modes = listOf(
            Triple(2, "Sport", Color(0xFFFF4D4D)),
            Triple(1, "Standart", Color(0xFF4DA3FF)),
            Triple(0, "Komfort", Color(0xFF3DDC97))
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sürüş rejimi (qaz pedalı)", fontSize = 13.sp, color = CarColors.TextSecondary, modifier = Modifier.weight(1f))
                if (busy) Text("dəyişdirilir…", fontSize = 12.sp, color = CarColors.Warning)
                else msg?.let { Text(it, fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 1) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (idx, title, color) ->
                    val active = current == idx
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (active) color.copy(alpha = 0.28f) else CarColors.PanelAlt)
                            .border(1.5.dp, if (active) color else color.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                            .clickable(enabled = !busy) { DriveModeController.request(context, idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            title, fontSize = 15.sp, maxLines = 1,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) color else CarColors.TextPrimary
                        )
                    }
                }
            }
            if (!enabled) {
                Text(
                    "Düymələrin menyuya girmədən işləməsi üçün «Q07 Launcher — sürüş rejimi» xüsusi imkanını aç (bir dəfə).",
                    fontSize = 12.sp, color = CarColors.Warning,
                    modifier = Modifier.clickable { DriveModeController.openAccessibilitySettings(context) }
                )
            }
        }
    }

    /** Zavodun Enerji və Avtomobil məlumatı vidcetləri — avtomatik qoyulur */
    @Composable
    private fun CarFactoryWidgets() {
        if (!WidgetStore.carWidgetsEnabled) return
        var pending by remember { mutableStateOf<Pair<ComponentName, Int>?>(null) }
        val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            pending?.let { (cmp, id) -> WidgetStore.onCarBound(cmp, id, res.resultCode == Activity.RESULT_OK) }
            pending = null
        }
        WidgetStore.carWidgets.forEach { id -> FittedWidget(id) }
        WidgetStore.carBindNeeded.forEach { cmp ->
            val label = if ("energy" in cmp.packageName) "Enerji vidceti" else "Avtomobil məlumatı vidceti"
            OutlinedButton(onClick = {
                val id = WidgetStore.host.allocateAppWidgetId()
                pending = cmp to id
                runCatching {
                    bind.launch(
                        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, cmp)
                    )
                }.onFailure { WidgetStore.onCarBound(cmp, id, false); pending = null }
            }) { Text("+ $label") }
        }
    }

    @Composable
    override fun Expanded() {
        ObdUsage()
        val obd by ObdMonitor.state.collectAsState()
        val shown = (mainKeys + secondKeys).toSet()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Maşının bütün dəstəklənən göstəriciləri — qruplar üzrə
            ObdPids.groups.forEach { g ->
                val pids = ObdPids.all.filter { it.group == g && it.key !in shown && it.key !in obd.unsupported }
                if (pids.isEmpty()) return@forEach
                Text(g, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
                TileGrid(pids, obd.values)
            }
            val info = listOfNotNull(
                obd.vin?.let { "VIN: $it" },
                obd.adapterVolt?.let { "Adapter: $it" },
                "Bloklar: ECM 7E8 · BECM 7EC · HPCM 7EA"
            ).joinToString("  ·  ")
            Text(info, fontSize = 13.sp, color = CarColors.TextSecondary)
            Text(
                "Mühərrik işləmirsə (elektrik rejimi), motor parametrləri «—» və ya 0 göstərə bilər. " +
                    "Göstəricilər birbaşa OBD-dən, hər biri öz blokundan oxunur.",
                fontSize = 13.sp, color = CarColors.TextSecondary
            )
        }
    }

    private fun valueColor(pid: ObdPid, v: Double?): Color = when {
        v == null -> CarColors.TextSecondary
        pid.warn != null && v >= pid.warn -> CarColors.Danger
        pid.isTemp && v <= 0 -> CarColors.Accent
        else -> CarColors.TextPrimary
    }

    @Composable
    private fun Tile(pid: ObdPid, v: Double?, modifier: Modifier) {
        Column(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(CarColors.Panel)
                .padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(pid.name, fontSize = 13.sp, color = CarColors.TextSecondary, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                val txt = v?.let { ObdMonitor.format(pid, it) } ?: "—"
                // uzun rəqəmlər (14.41) dar kafeldə sətir aşağı düşməsin
                Text(
                    txt, maxLines = 1, softWrap = false,
                    fontSize = if (txt.length >= 5) 22.sp else 28.sp,
                    fontWeight = FontWeight.Medium, color = valueColor(pid, v)
                )
                Text(" " + pid.unit, fontSize = 14.sp, color = CarColors.TextSecondary, maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }

    @Composable
    private fun SimpleTile(label: String, value: String, unit: String, modifier: Modifier) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CarColors.Panel)
                .padding(12.dp)
        ) {
            Text(label, fontSize = 13.sp, color = CarColors.TextSecondary, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary)
                if (unit.isNotEmpty()) Text(" $unit", fontSize = 13.sp, color = CarColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/**
 * Kompakt rejim üçün kiçik göstərici bloku (solda, digər tətbiqlər açıq olanda da görünür):
 * batareya SOC, soyuducu maye, sürət, 12V.
 */
@Composable
fun CarMiniGauges() {
    DisposableEffect(Unit) {
        ObdMonitor.acquire()
        onDispose { ObdMonitor.release() }
    }
    val obd by ObdMonitor.state.collectAsState()
    val keys = listOf("soc", "coolant", "speed", "voltage")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CarColors.Panel)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        keys.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val pid = ObdPids.byKey.getValue(key)
                    val v = obd.values[key]
                    Column(Modifier.weight(1f)) {
                        Text(pid.name.substringBefore(" ("), fontSize = 11.sp, color = CarColors.TextSecondary, maxLines = 1)
                        Text(
                            (v?.let { ObdMonitor.format(pid, it) } ?: "—") + " " + pid.unit,
                            fontSize = 18.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary, maxLines = 1
                        )
                    }
                }
            }
        }
        Text(
            if (obd.connected) "OBD ✓" else "OBD: ${obd.status}",
            fontSize = 11.sp, maxLines = 1,
            color = if (obd.connected) CarColors.Green else CarColors.TextSecondary
        )
    }
}
