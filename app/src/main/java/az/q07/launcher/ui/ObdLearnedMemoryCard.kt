package az.q07.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.DiscoveredIndicator
import az.q07.launcher.data.LearnedReading
import az.q07.launcher.data.LearnedSource
import az.q07.launcher.data.LinkKind
import az.q07.launcher.data.ObdDiscoverySession
import az.q07.launcher.data.ObdFreeFallbackResolver
import az.q07.launcher.data.ObdMonitor
import az.q07.launcher.data.VehicleTelemetryReader
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "OBD Skan Yaddaşı (OBD-siz Rejim)" — öyrənilmiş yollar, test və yeniləmə */
@Composable
fun ObdLearnedMemoryCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VehicleTelemetryReader.start(context) }
    val indicators by ObdDiscoverySession.indicators.collectAsState()
    val active by ObdDiscoverySession.active.collectAsState()
    val status by ObdDiscoverySession.status.collectAsState()
    val readings by ObdFreeFallbackResolver.readings.collectAsState()
    val obd by ObdMonitor.state.collectAsState()
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<List<Pair<DiscoveredIndicator, LearnedReading?>>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val learned = indicators.filter { it.learnedLinks.isNotEmpty() }
    val pathCount = learned.sumOf { it.learnedLinks.size }
    val strongCount = learned.sumOf { i -> i.learnedLinks.count { it.strong } }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarColors.Panel)
            .border(1.dp, CarColors.Border, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OBD Skan Yaddaşı (OBD-siz Rejim)", fontSize = 18.sp, color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (obd.connected) CarColors.Green else CarColors.Warning))
            Spacer(Modifier.size(6.dp))
            Text(if (obd.connected) "OBD qoşulu — öyrənir" else "OBD yoxdur — öyrənilmiş yollar", fontSize = 12.sp,
                color = CarColors.TextSecondary, maxLines = 1)
        }
        Text(
            "${indicators.size} göstərici qeydə alınıb · $pathCount öyrənilmiş yol ($strongCount etibarlı) · " +
                "${learned.size} göstərici OBD-siz oxuna bilər",
            fontSize = 14.sp, color = CarColors.TextPrimary
        )
        Text(status, fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 2)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(if (active) "Öyrənmə: Aktiv" else "Öyrənmə: Dayanıb", if (active) CarColors.Green else CarColors.TextSecondary) {
                ObdDiscoverySession.setActive(!active)
            }
            ActionChip("Test et", CarColors.Accent) {
                scope.launch {
                    testResult = withContext(Dispatchers.Default) { ObdFreeFallbackResolver.testAll() }
                    info = if (testResult.isNullOrEmpty()) "Hələ öyrənilmiş yol yoxdur — OBD qoşulu ikən zavod ekranlarını aç" else null
                }
            }
            ActionChip("Yenilə", CarColors.Accent) {
                ObdDiscoverySession.refreshNow()
                info = "Xassə siyahısı yenidən oxunacaq, yaddaş yazıldı"
            }
            ActionChip(if (confirmClear) "Əminsən? Sil" else "Sıfırla", CarColors.Danger) {
                if (confirmClear) { ObdDiscoverySession.clear(); testResult = null; info = "Yaddaş təmizləndi"; confirmClear = false }
                else confirmClear = true
            }
        }
        info?.let { Text(it, fontSize = 13.sp, color = CarColors.Warning) }

        if (indicators.isEmpty()) {
            Text(
                "Necə öyrədilir: OBD adapteri qoşulu ikən sür və ya dayan, eyni vaxtda zavod ekranlarını (enerji, batareya, " +
                    "kondisioner, səyahət kompüteri) aç. Ekranda və ya sistem xassələrində OBD ilə eyni dəyər dəfələrlə " +
                    "görünəndə yol yadda qalır və OBD olmadan da oxunur.",
                fontSize = 13.sp, color = CarColors.TextSecondary
            )
        }

        val tested = testResult?.associate { it.first.key to it.second }
        val list = (if (showAll) indicators else learned).sortedByDescending { it.learnedLinks.size }
        list.take(if (showAll) 60 else 12).forEach { ind ->
            val r = tested?.get(ind.key) ?: readings[ind.key]
            IndicatorRow(ind, if (obd.connected) obd.values[ind.key] else null, r, tested != null && ind.key in tested)
        }
        if (indicators.size > learned.size || list.size > 12) {
            Text(if (showAll) "Yalnız öyrənilmişləri göstər" else "Hamısını göstər (${indicators.size})", fontSize = 13.sp,
                color = CarColors.Accent, modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun IndicatorRow(ind: DiscoveredIndicator, obdValue: Double?, r: LearnedReading?, tested: Boolean) {
    val best = ind.bestLink
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CarColors.PanelAlt)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(ind.label, fontSize = 15.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    best == null -> "Yol hələ öyrənilməyib · ${ind.samples} nümunə · ${ind.links.size} namizəd"
                    else -> (if (best.kind == LinkKind.UI) "Ekran: " else "Xassə: ") + best.path.substringAfter(':') +
                        (if (best.scale != 1.0) " ×${best.scale}" else "") +
                        " · ${best.matches}/${best.matches + best.misses}" + (if (best.strong) "" else " (zəif)")
                },
                fontSize = 11.sp, color = CarColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val shown = obdValue ?: r?.value
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ObdFreeFallbackResolver.format(ind.key, shown), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    color = if (shown == null) CarColors.TextSecondary else CarColors.TextPrimary)
                if (shown != null && ind.unit.isNotBlank()) Text(" ${ind.unit}", fontSize = 12.sp, color = CarColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(
                when {
                    obdValue != null -> "OBD (canlı)"
                    r != null -> r.source.label
                    tested -> "tapılmadı"
                    else -> "--"
                },
                fontSize = 11.sp, maxLines = 1,
                color = when {
                    obdValue != null -> CarColors.Green
                    r?.source == LearnedSource.CACHE -> CarColors.Warning
                    r != null -> CarColors.Accent
                    else -> CarColors.TextSecondary
                }
            )
        }
    }
}

@Composable
private fun ActionChip(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 14.sp, color = CarColors.TextPrimary, maxLines = 1) }
}
