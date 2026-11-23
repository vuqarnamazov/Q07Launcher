package az.q07.scanner.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import az.q07.scanner.data.AppSettings
import az.q07.scanner.FlowImporter
import az.q07.scanner.FullVehicleScannerManager
import az.q07.scanner.FullVehicleScannerManager.Mode
import az.q07.scanner.NavigationEdge
import az.q07.scanner.NodeInfo
import az.q07.scanner.RecordedFlow
import az.q07.scanner.ScreenNodeMap
import az.q07.scanner.VehicleStructureRepository
import az.q07.scanner.ui.theme.CarColors
import az.q07.scanner.ui.theme.CarLauncherTheme
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
    LIVE("Canlı ekran"), SYSTEM("Sistem skanı"), SCREENS("Ekranlar"), PATHS("Yollar"), FLOWS("Gedişatlar"), IMPORT("Götür və Əlavə Et"), LAUNCHER("Launcher funksiyaları")
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
        TamQeydLine()
        ScChipRow { ScTab.values().forEach { t -> ScChip(t.title, t == tab) { tab = t } } }
        info?.let { Text(it, fontSize = 13.sp, color = CarColors.Green) }
        PlayerStatusLine()
        val pick: (FlowImporter.Candidate) -> Unit = { adding = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ScTab.LIVE -> LiveTab(pick)
                ScTab.SYSTEM -> SystemScanTab()
                ScTab.SCREENS -> ScreensTab(pick)
                ScTab.PATHS -> PathsTab()
                ScTab.FLOWS -> FlowsTab(pick)
                ScTab.IMPORT -> ImportTab(pick) { info = it }
                ScTab.LAUNCHER -> LazyColumn { item { LearnedFunctionsList() } }
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
private fun FlowsTab(pick: (FlowImporter.Candidate) -> Unit) {
    val flows by VehicleStructureRepository.flows.collectAsState()
    var kind by remember { mutableStateOf<RecordedFlow.Kind?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<RecordedFlow?>(null) }
    val list = remember(flows, kind) { flows.filter { kind == null || it.kind == kind } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScChipRow {
            ScChip("Hamısı", kind == null) { kind = null }
            ScChip("Funksiyalar", kind == RecordedFlow.Kind.FUNCTION) { kind = RecordedFlow.Kind.FUNCTION }
            ScChip("Gedişatlar", kind == RecordedFlow.Kind.FLOW) { kind = RecordedFlow.Kind.FLOW }
        }
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

/** Tam qeyd vəziyyəti (fonda daim yazılır) */
@Composable
private fun TamQeydLine() {
    var st by remember { mutableStateOf(az.q07.scanner.rec.TamQeyd.status()) }
    LaunchedEffect(Unit) { while (true) { delay(3000); st = az.q07.scanner.rec.TamQeyd.status() } }
    Text("● Tam qeyd: $st", fontSize = 13.sp,
        color = if (az.q07.scanner.rec.TamQeyd.running) CarColors.Green else CarColors.TextSecondary)
}
