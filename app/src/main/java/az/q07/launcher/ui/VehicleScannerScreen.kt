package az.q07.launcher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppSettings
import az.q07.launcher.data.EngineFlowBinding
import az.q07.launcher.data.VehicleEngineController
import az.q07.launcher.scanner.FlowImporter
import az.q07.launcher.scanner.FullVehicleScannerManager
import az.q07.launcher.scanner.FullVehicleScannerManager.Mode
import az.q07.launcher.scanner.LearnedFunctionsRepository
import az.q07.launcher.scanner.NavigationEdge
import az.q07.launcher.scanner.NodeInfo
import az.q07.launcher.scanner.RecordedFlow
import az.q07.launcher.scanner.ScreenNodeMap
import az.q07.launcher.scanner.SystemKnowledgeReport
import az.q07.launcher.scanner.VehicleStructureRepository
import az.q07.launcher.ui.theme.CarColors
import az.q07.launcher.ui.theme.CarLauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Tam Skaner ekranı. Görünən olduqca AÇIQ rejim öyrənir və canlı strukturu göstərir. */
class VehicleScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(applicationContext)
        FullVehicleScannerManager.init(applicationContext)
        setContent { CarLauncherTheme { VehicleScannerScreen(onClose = { finish() }) } }
    }

    override fun onStart() { super.onStart(); FullVehicleScannerManager.setScreenVisible(true) }
    override fun onStop() { FullVehicleScannerManager.setScreenVisible(false); super.onStop() }
}

private enum class ScTab(val title: String) {
    LIVE("Canlı ekran"), SCREENS("Ekranlar"), PATHS("Yollar"), FLOWS("Gedişatlar"), IMPORT("Götür və Əlavə Et"), LAUNCHER("Launcher funksiyaları"), KNOWLEDGE("Bilik")
}

@Composable
fun VehicleScannerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val mode by FullVehicleScannerManager.mode.collectAsState()
    var tab by rememberSaveable { mutableStateOf(ScTab.LIVE) }
    var adding by remember { mutableStateOf<FlowImporter.Candidate?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // Açıq rejimdə fon xidməti işləmir — sürəti bu ekran oxusun
    LaunchedEffect(mode) {
        while (mode == Mode.OPEN) {
            val s = withContext(Dispatchers.Default) { FullVehicleScannerManager.readSpeed(context) }
            FullVehicleScannerManager.updateMotion(s)
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize().background(CarColors.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tam Sistem və Gedişat Skaneri", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
            ScIconBtn(Icons.Filled.Close, "Bağla", CarColors.TextPrimary, onClose)
        }
        ScannerModeControl()
        ScChipRow { ScTab.values().forEach { t -> ScChip(t.title, t == tab) { tab = t } } }
        info?.let { Text(it, fontSize = 13.sp, color = CarColors.Green) }
        PlayerStatusLine()
        val pick: (FlowImporter.Candidate) -> Unit = { adding = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ScTab.LIVE -> LiveTab(pick)
                ScTab.SCREENS -> ScreensTab(pick)
                ScTab.PATHS -> PathsTab()
                ScTab.FLOWS -> FlowsTab(pick) { info = it }
                ScTab.IMPORT -> ImportTab(pick) { info = it }
                ScTab.LAUNCHER -> LazyColumn { item { LearnedFunctionsList() } }
                ScTab.KNOWLEDGE -> KnowledgeTab()
            }
        }
    }
    adding?.let { c -> AddCandidateDialog(c, { adding = null }) { info = it; adding = null } }
}

/** Düymədən «Götür»: bazadan yolu hesabla */
private fun buttonCandidate(ctx: android.content.Context, screen: ScreenNodeMap, node: NodeInfo): FlowImporter.Candidate? {
    val path = VehicleStructureRepository.findPath(screen.id, node.key) ?: return null
    return FlowImporter.Candidate.Button(screen, node, path, FullVehicleScannerManager.appLabel(ctx, screen.packageName), 0)
}

// ───────────────────────── Canlı ekran ─────────────────────────

@Composable
private fun LiveTab(pick: (FlowImporter.Candidate) -> Unit) {
    val context = LocalContext.current
    val live by FullVehicleScannerManager.live.collectAsState()
    val mode by FullVehicleScannerManager.mode.collectAsState()
    val screens by VehicleStructureRepository.screens.collectAsState()
    val session by FullVehicleScannerManager.session.collectAsState()
    val l = live
    if (l == null) {
        Text(
            if (mode == Mode.OFF) "Skaner sönülüdür. «Açıq» və ya «Fon İzləməsi» seç, sonra maşının istənilən tətbiqini/menyusunu aç."
            else "Hələ ekran oxunmayıb. Zavod tətbiqini (məs. maşın ayarları) aç — struktur burada görünəcək.",
            fontSize = 15.sp, color = CarColors.TextSecondary
        )
        return
    }
    val screen = screens[l.screenId]
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${FullVehicleScannerManager.appLabel(context, l.packageName)} · ${l.className.substringAfterLast('.')}",
                fontSize = 16.sp, color = CarColors.TextPrimary, fontWeight = FontWeight.Medium)
            Text("${l.nodes.size} element · ${l.nodes.count { it.isClickable }} kliklənən" +
                (screen?.let { " · bazada ${it.nodes.size} element, ${it.seenCount} ziyarət" } ?: ""),
                fontSize = 12.sp, color = CarColors.TextSecondary)
            ScreenMiniMap(l.nodes, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Text("Cari sessiya: ${session.size} addım", fontSize = 13.sp, color = CarColors.TextSecondary)
            session.takeLast(6).forEach {
                Text("• ${it.label()}", fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(l.nodes.sortedByDescending { it.isClickable }, key = { it.key }) { n ->
                NodeRow(n, screen?.nodes?.firstOrNull { it.key == n.key }) {
                    screen?.let { s -> buttonCandidate(context, s, n)?.let(pick) }
                }
            }
        }
    }
}

@Composable
private fun ScreenMiniMap(nodes: List<NodeInfo>, modifier: Modifier) {
    val dm = LocalContext.current.resources.displayMetrics
    val accent = ScannerAccent
    val other = CarColors.TextSecondary
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(CarColors.Panel)) {
        val sx = size.width / dm.widthPixels.coerceAtLeast(1)
        val sy = size.height / dm.heightPixels.coerceAtLeast(1)
        nodes.forEach { n ->
            if (n.bounds.size != 4) return@forEach
            val c = if (n.isClickable) accent else other
            drawRect(c.copy(alpha = if (n.isClickable) 0.8f else 0.35f), Offset(n.bounds[0] * sx, n.bounds[1] * sy),
                Size((n.bounds[2] - n.bounds[0]) * sx, (n.bounds[3] - n.bounds[1]) * sy), style = Stroke(1.5f))
        }
    }
}

@Composable
private fun NodeRow(n: NodeInfo, stored: NodeInfo?, onTake: () -> Unit) {
    ScCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (n.isClickable) "👆 " else "") + n.label(), fontSize = 14.sp, color = CarColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        n.viewIdResName.substringAfter(":id/").takeIf { it.isNotBlank() },
                        n.className.substringAfterLast('.'),
                        n.bounds.joinToString(","),
                        stored?.let { "görülüb ${it.seenCount} · klik ${it.clickCount}" }
                    ).joinToString(" · "),
                    fontSize = 11.sp, color = CarColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (n.isClickable) ScPill("Götür", Icons.Filled.Add, CarColors.Green, onClick = onTake)
        }
    }
}

// ───────────────────────── Ekranlar ─────────────────────────

@Composable
private fun ScreensTab(pick: (FlowImporter.Candidate) -> Unit) {
    val context = LocalContext.current
    val screens by VehicleStructureRepository.screens.collectAsState()
    val edges by VehicleStructureRepository.edges.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    val list = remember(screens) { screens.values.sortedByDescending { it.lastSeen } }
    if (list.isEmpty()) { Text("Ekran xəritəsi boşdur.", color = CarColors.TextSecondary); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { s ->
            ScCard(onClick = { open = if (open == s.id) null else s.id }) {
                Text(FullVehicleScannerManager.screenTitle(context, s), fontSize = 15.sp, color = CarColors.TextPrimary)
                Text("${s.nodes.size} element · ${s.clickableCount} düymə · ${s.seenCount} ziyarət" +
                    (if (s.entry) " · giriş ekranı" else "") +
                    " · çıxan yol: ${edges.count { it.fromScreen == s.id }}", fontSize = 12.sp, color = CarColors.TextSecondary)
                if (open == s.id) {
                    Spacer(Modifier.height(6.dp))
                    s.nodes.filter { it.isClickable }.sortedByDescending { it.clickCount * 1000 + it.seenCount }.take(80).forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("👆 ${n.label()}  ·  ${n.clickCount} klik / ${n.seenCount} görülüb", fontSize = 13.sp,
                                color = CarColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            ScPill("Götür", Icons.Filled.Add, CarColors.Green) { buttonCandidate(context, s, n)?.let(pick) }
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { VehicleStructureRepository.clearStructure() }) { Text("Ekran xəritəsini və yolları təmizlə", color = CarColors.Danger) }
        }
    }
}

// ───────────────────────── Yollar ─────────────────────────

@Composable
private fun PathsTab() {
    val context = LocalContext.current
    val edges by VehicleStructureRepository.edges.collectAsState()
    val screens by VehicleStructureRepository.screens.collectAsState()
    val list = remember(edges) { edges.sortedByDescending { it.lastSeen } }
    if (list.isEmpty()) { Text("Hələ keçid yolu öyrənilməyib — skaner açıqkən maşının menyularında gəz.", color = CarColors.TextSecondary); return }
    fun name(id: String) = screens[id]?.let { FullVehicleScannerManager.screenTitle(context, it) } ?: id.substringAfterLast('.')
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.id }) { e ->
            ScCard {
                Text("${name(e.fromScreen)}  —[ ${e.action.label()} ]→  " +
                    if (e.kind == NavigationEdge.Kind.NAVIGATE) name(e.toScreen) else "açılır: ${e.revealed.take(5).joinToString(", ")}",
                    fontSize = 14.sp, color = CarColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${e.kind.label} · ×${e.count}", fontSize = 12.sp, color = CarColors.TextSecondary)
            }
        }
    }
}

// ───────────────────────── Gedişatlar ─────────────────────────

@Composable
private fun FlowsTab(pick: (FlowImporter.Candidate) -> Unit, onInfo: (String) -> Unit) {
    val flows by VehicleStructureRepository.flows.collectAsState()
    var kind by remember { mutableStateOf<RecordedFlow.Kind?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<RecordedFlow?>(null) }
    var binding by remember { mutableStateOf<RecordedFlow?>(null) }
    val list = remember(flows, kind) { flows.filter { kind == null || it.kind == kind } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScChipRow {
            ScChip("Hamısı", kind == null) { kind = null }
            ScChip("Funksiyalar", kind == RecordedFlow.Kind.FUNCTION) { kind = RecordedFlow.Kind.FUNCTION }
            ScChip("Gedişatlar", kind == RecordedFlow.Kind.FLOW) { kind = RecordedFlow.Kind.FLOW }
        }
        EngineBindingCard { onInfo(it) }
        if (list.isEmpty()) Text("Hələ qeyd yoxdur.", color = CarColors.TextSecondary)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { f ->
                ScCard(onClick = { open = if (open == f.id) null else f.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 15.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${f.kind.label} · ×${f.count} · ${f.steps.size} addım · hərəkətdə ${f.movingCount} / dayanıb ${f.stoppedCount}",
                                fontSize = 12.sp, color = CarColors.TextSecondary)
                        }
                        ScIconBtn(Icons.Filled.DirectionsCar, "Mühərrik düyməsinə qoy", CarColors.Green) { binding = f }
                        ScPill("Götür", Icons.Filled.Add, CarColors.Green) { pick(FlowImporter.Candidate.Recorded(f, 0)) }
                        ScIconBtn(Icons.Filled.Edit, "Ad") { renaming = f }
                        ScIconBtn(Icons.Filled.Delete, "Sil", CarColors.Danger) { VehicleStructureRepository.deleteFlow(f.id) }
                    }
                    if (open == f.id) { Spacer(Modifier.height(4.dp)); StepsList(f.steps) }
                }
            }
            if (flows.isNotEmpty()) item {
                TextButton(onClick = { VehicleStructureRepository.clearFlows() }) { Text("Gedişatları təmizlə", color = CarColors.Danger) }
            }
        }
    }
    renaming?.let { f -> ScRenameDialog(f.name, { renaming = null }) { VehicleStructureRepository.renameFlow(f.id, it); renaming = null } }
    binding?.let { f -> EngineBindDialog(f, { binding = null }) { onInfo(it) } }
}

/** Mühərrik START/STOP düymələrinə bağlanmış yol + skanerdən tapılan uyğun yol. */
@Composable
private fun EngineBindingCard(onInfo: (String) -> Unit) {
    val context = LocalContext.current
    val learned by LearnedFunctionsRepository.items.collectAsState()
    val flows by VehicleStructureRepository.flows.collectAsState()
    var startId by remember { mutableStateOf(VehicleEngineController.getFlowId(context, true)) }
    var stopId by remember { mutableStateOf(VehicleEngineController.getFlowId(context, false)) }
    val startName = learned.firstOrNull { it.id == startId }?.name
    val stopName = learned.firstOrNull { it.id == stopId }?.name
    val suggestion = remember(flows, learned) { EngineFlowBinding.candidates(true, 1).firstOrNull() }
    // Pay Charging / doldurma rejimində qeydə alınmış yollar — ən yenisi birinci
    val chargingFlows = remember(flows) {
        flows.filter { it.steps.isNotEmpty() && EngineFlowBinding.isCharging(it.steps) }.take(3)
    }

    ScCard {
        Text("Mühərrik düyməsi (yalnız P)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
        Text(
            "START: ${startName ?: "seçilməyib"} · STOP: ${stopName ?: "seçilməyib"}",
            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (startName != null) CarColors.Green else CarColors.Warning
        )
        if (suggestion != null) {
            Text(
                "Skanerdən uyğun yol: ${suggestion.name}" + (if (suggestion.charging) " · doldurma / Pay Charging" else ""),
                fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScPill("START-a qoy", Icons.Filled.PlayArrow, CarColors.Green) {
                    val fn = EngineFlowBinding.assign(context, suggestion, true)
                    startId = fn.id
                    onInfo("✓ Mühərrik START: ${fn.name}")
                }
                ScPill("STOP-a qoy", Icons.Filled.Stop, CarColors.Danger) {
                    val fn = EngineFlowBinding.assign(context, suggestion, false)
                    stopId = fn.id
                    onInfo("✓ Mühərrik STOP: ${fn.name}")
                }
            }
        } else {
            Text(
                "Skanerdə «işə sal» sözlü yol hələ yoxdur — aşağıdakı Pay Charging yolunu seç və ya maşının ekranında «işə sal»ı bir dəfə bas.",
                fontSize = 12.sp, color = CarColors.TextSecondary
            )
        }
        if (chargingFlows.isNotEmpty()) {
            Text(
                "Pay Charging / doldurma yolları (skaner qeydləri)",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary
            )
            chargingFlows.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f.name, fontSize = 12.sp, color = CarColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    ScPill("START", Icons.Filled.PlayArrow, CarColors.Green) {
                        val fn = EngineFlowBinding.assign(context, EngineFlowBinding.fromFlow(f, true), true)
                        startId = fn.id
                        onInfo("✓ Mühərrik START: ${fn.name}")
                    }
                    Spacer(Modifier.width(6.dp))
                    ScPill("STOP", Icons.Filled.Stop, CarColors.Danger) {
                        val fn = EngineFlowBinding.assign(context, EngineFlowBinding.fromFlow(f, false), false)
                        stopId = fn.id
                        onInfo("✓ Mühərrik STOP: ${fn.name}")
                    }
                }
            }
        }
    }
}

/** «Gedişatlar» siyahısından birbaşa mühərrik düyməsinə bağlama. */
@Composable
private fun EngineBindDialog(flow: RecordedFlow, onDismiss: () -> Unit, onInfo: (String) -> Unit) {
    val context = LocalContext.current
    val startCandidate = remember(flow) { EngineFlowBinding.fromFlow(flow, true) }
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mühərrik düyməsinə qoy") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(flow.name, fontSize = 15.sp, color = CarColors.TextPrimary)
                Text(
                    "${flow.steps.size} addım · ×${flow.count}" +
                        (if (startCandidate.charging) " · doldurma / Pay Charging" else ""),
                    fontSize = 12.sp, color = CarColors.TextSecondary
                )
                StepsList(flow.steps)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fn = EngineFlowBinding.assign(context, startCandidate, true)
                onInfo("✓ Mühərrik START: ${fn.name}")
                onDismiss()
            }) { Text("START-a qoy") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val fn = EngineFlowBinding.assign(context, EngineFlowBinding.fromFlow(flow, false), false)
                    onInfo("✓ Mühərrik STOP: ${fn.name}")
                    onDismiss()
                }) { Text("STOP-a qoy") }
                TextButton(onClick = onDismiss) { Text("Bağla") }
            }
        }
    )
}

// ───────────────────────── Bilik hesabatı ─────────────────────────

/** Öyrənilən bütün sistem: hansı düymə nə edir, nə çatmır — tək fayl. */
@Composable
private fun KnowledgeTab() {
    val context = LocalContext.current
    val summary by SystemKnowledgeReport.summary.collectAsState()
    val missing by SystemKnowledgeReport.missing.collectAsState()
    var fileText by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScCard {
            Text("Öyrənilmiş sistem: ${summary.knownButtons}/${summary.buttons} düymə (${summary.percent}%)",
                fontSize = 15.sp, color = CarColors.TextPrimary)
            Text("${summary.screens} ekran · ${summary.flows} gedişat/funksiya · çatışmayan məlumat: ${summary.missing}",
                fontSize = 12.sp, color = CarColors.TextSecondary)
            Text("Fayl: ${summary.path.ifBlank { "hazırlanır…" }}", fontSize = 11.sp, color = CarColors.TextSecondary)
        }
        ScChipRow {
            ScChip("Hesabatı yenilə", false) {
                SystemKnowledgeReport.refresh(context, force = true)
                fileText = SystemKnowledgeReport.read(context)
            }
            ScChip("Faylı göstər", false) { fileText = SystemKnowledgeReport.read(context) }
            if (fileText != null) ScChip("Gizlət", false) { fileText = null }
        }
        fileText?.let { t ->
            Text(
                t, fontSize = 11.sp, color = CarColors.TextSecondary,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    .background(CarColors.Panel).padding(10.dp)
            )
        }
        if (missing.isEmpty()) Text("Çatışmayan məlumat yoxdur ✓", fontSize = 14.sp, color = CarColors.Green)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(missing, key = { "${it.kind}|${it.where}|${it.what}" }) { m ->
                ScCard {
                    Text("${m.kind} · ${m.where}", fontSize = 13.sp, color = CarColors.TextPrimary)
                    Text(m.what, fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ───────────────────────── Götür və Əlavə Et ─────────────────────────

@Composable
private fun ImportTab(pick: (FlowImporter.Candidate) -> Unit, onInfo: (String) -> Unit) {
    val context = LocalContext.current
    val flows by VehicleStructureRepository.flows.collectAsState()
    val screens by VehicleStructureRepository.screens.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var source by remember { mutableStateOf(FlowImporter.Source.ALL) }
    var results by remember { mutableStateOf<List<FlowImporter.Candidate>>(emptyList()) }

    LaunchedEffect(query, source, flows, screens) {
        delay(250)
        results = withContext(Dispatchers.Default) { FlowImporter.search(context, query, source) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Nə əlavə edək? Ad / açar söz (məs. «asqı idman», «kondisioner», «sükan»)") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ScChipRow { FlowImporter.Source.values().forEach { s -> ScChip(s.label, s == source) { source = s } } } }
            if (query.isNotBlank()) ScPill("Ən uyğununu əlavə et", Icons.Filled.Add, CarColors.Green) {
                val r = FlowImporter.importBest(context, query, source)
                onInfo(when { r == null -> "«$query» üçün bazada heç nə tapılmadı"; r.second -> "✓ Əlavə edildi: ${r.first.name}"; else -> "Artıq var: ${r.first.name}" })
            }
        }
        if (results.isEmpty()) Text(if (query.isBlank()) "Axtarış yaz və ya ən çox istifadə olunanlardan seç." else "Uyğun nəticə yoxdur.",
            fontSize = 14.sp, color = CarColors.TextSecondary)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.fingerprint }) { c ->
                ScCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 15.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.subtitle, fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (FlowImporter.isAdded(c)) Text("✓ launcher-də", fontSize = 12.sp, color = CarColors.Green, modifier = Modifier.padding(horizontal = 6.dp))
                        else ScPill("Götür", Icons.Filled.Add, CarColors.Green) { pick(c) }
                    }
                }
            }
        }
    }
}
