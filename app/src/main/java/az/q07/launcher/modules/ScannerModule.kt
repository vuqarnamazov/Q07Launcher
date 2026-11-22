package az.q07.launcher.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.scanner.FlowImporter
import az.q07.launcher.scanner.FlowPlayer
import az.q07.launcher.scanner.LearnedFunction
import az.q07.launcher.scanner.LearnedFunctionsRepository
import az.q07.launcher.scanner.MotionState
import az.q07.launcher.scanner.RecordedFlow
import az.q07.launcher.scanner.RecordedFlowRepository
import az.q07.launcher.scanner.RecorderScannerManager
import az.q07.launcher.ui.ScaledAlertDialog
import az.q07.launcher.ui.theme.CarColors

/**
 * İzləmə Skaneri modulu:
 *  - böyük "Skaner İzləməsi: Aktiv / Sönülü" düyməsi,
 *  - qeydə alınmış funksiya/gedişatlar (axtarış, ad dəyişmə, silmə, launcher-ə əlavə),
 *  - öyrənilmiş funksiyalar — 1 toxunuşla icra.
 */
object ScannerModule : LauncherModule {
    override val id = "scanner"
    override val title = "İzləmə Skaneri"
    override val shortTitle = "Skaner"
    override val icon: ImageVector = Icons.Filled.Radar
    override val accent: Color = Color(0xFFFF4D8D)

    @Composable
    override fun Summary() {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScannerToggleButton()
            LearnedQuickRow()
        }
    }

    @Composable
    override fun Expanded() {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            LearnedSection()
            RecordedSection()
        }
    }
}

// ───────────────────────── Aktiv / Sönülü düyməsi ─────────────────────────

/** Launcher-in istənilən yerində (modul, sürətli panel) istifadə oluna bilən böyük düymə */
@Composable
fun ScannerToggleButton(modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val active by RecorderScannerManager.active.collectAsState()
    val motion by RecorderScannerManager.motion.collectAsState()
    val speed by RecorderScannerManager.speedKmh.collectAsState()
    val session by RecorderScannerManager.session.collectAsState()
    val written by RecorderScannerManager.recordedThisRun.collectAsState()
    val all by RecordedFlowRepository.flows.collectAsState()
    val color = if (active) CarColors.Danger else CarColors.TextSecondary

    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(color.copy(alpha = if (active) 0.16f else 0.08f))
            .border(2.dp, color.copy(alpha = if (active) 0.8f else 0.35f), RoundedCornerShape(18.dp))
            .clickable { RecorderScannerManager.toggle(context) }
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (active) Icons.Filled.FiberManualRecord else Icons.Filled.Radar, null, tint = color,
                modifier = Modifier.size(if (compact) 24.dp else 32.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Skaner İzləməsi: " + if (active) "Aktiv" else "Sönülü",
                fontSize = if (compact) 16.sp else 21.sp, fontWeight = FontWeight.SemiBold,
                color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            val motionColor = when (motion) { MotionState.MOVING -> CarColors.Green; MotionState.STOPPED -> CarColors.Warning; else -> CarColors.TextSecondary }
            Text(
                if (!active) "Toxun — sürüş və dayanma zamanı bütün kliklər və ekran keçidləri yadda saxlanılsın"
                else "${motion.label}${speed?.let { " · ${it.toInt()} km/s" } ?: ""} · sessiya: ${session.size} addım · bu dəfə yazıldı: $written",
                fontSize = 14.sp, color = if (active) motionColor else CarColors.TextSecondary
            )
            Text("Bazada: ${all.count { it.kind == RecordedFlow.Kind.FUNCTION }} funksiya · ${all.count { it.kind == RecordedFlow.Kind.FLOW }} gedişat",
                fontSize = 13.sp, color = CarColors.TextSecondary)
            if (active && !RecorderScannerManager.isAccessibilityReady(context)) {
                Text("⚠ Xüsusi imkan xidməti aktiv deyil — hərəkətlər yazılmır", fontSize = 13.sp, color = CarColors.Danger)
            }
        }
    }
}

// ───────────────────────── 1 toxunuşla icra ─────────────────────────

@Composable
private fun LearnedQuickRow() {
    val context = LocalContext.current
    val items by LearnedFunctionsRepository.items.collectAsState()
    val status by FlowPlayer.status.collectAsState()
    val playing by FlowPlayer.playing.collectAsState()
    if (items.isEmpty() && status == null) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.sortedByDescending { it.runs }.take(8).forEach { f ->
            Pill(f.name, if (f.isFlow) Icons.Filled.PlayArrow else Icons.Filled.Check, ScannerModule.accent, enabled = !playing) {
                FlowPlayer.play(context, f)
            }
        }
        if (playing) Pill("Dayandır", Icons.Filled.Stop, CarColors.Danger) { FlowPlayer.stop() }
    }
    status?.let { Text(it, fontSize = 13.sp, color = CarColors.TextSecondary, maxLines = 2) }
}

@Composable
private fun LearnedSection() {
    val context = LocalContext.current
    val items by LearnedFunctionsRepository.items.collectAsState()
    val playing by FlowPlayer.playing.collectAsState()
    var renaming by remember { mutableStateOf<LearnedFunction?>(null) }

    SectionTitle("Launcher-in funksiya və gedişatları (1 toxunuş)")
    if (items.isEmpty()) {
        Text("Hələ heç nə əlavə edilməyib. Aşağıdakı skaner bazasından axtarıb «Əlavə et» bas.",
            fontSize = 14.sp, color = CarColors.TextSecondary)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CarColors.PanelAlt)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(ScannerModule.accent.copy(alpha = 0.2f))
                        .clickable(enabled = !playing) { FlowPlayer.play(context, f) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.PlayArrow, "İcra et", tint = ScannerModule.accent, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 16.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (f.isFlow) "Gedişat · ${f.steps.size} addım · ${f.stepDelayMs} ms gecikmə" else "Funksiya · 1 addım") +
                            " · ${f.runs} dəfə işlədilib",
                        fontSize = 12.sp, color = CarColors.TextSecondary
                    )
                }
                if (f.isFlow) {
                    SmallBtn("−") { LearnedFunctionsRepository.setDelay(f.id, f.stepDelayMs - 200) }
                    SmallBtn("+") { LearnedFunctionsRepository.setDelay(f.id, f.stepDelayMs + 200) }
                }
                IconBtn(Icons.Filled.Edit, "Ad") { renaming = f }
                IconBtn(Icons.Filled.Delete, "Sil", CarColors.Danger) { LearnedFunctionsRepository.delete(f.id) }
            }
        }
    }
    renaming?.let { f ->
        RenameDialog(f.name, onDismiss = { renaming = null }) { LearnedFunctionsRepository.rename(f.id, it); renaming = null }
    }
}

// ───────────────────────── Skaner bazası ─────────────────────────

@Composable
private fun RecordedSection() {
    val context = LocalContext.current
    val flows by RecordedFlowRepository.flows.collectAsState()
    val learned by LearnedFunctionsRepository.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<RecordedFlow.Kind?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<RecordedFlow?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // flows/learned dəyişəndə yenidən hesablansın
    val matches = remember(flows, learned, query, kind) { FlowImporter.search(context, query, kind).take(40) }

    SectionTitle("Skaner bazası — funksiya / gedişat çək və əlavə et")
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        label = { Text("Ad və ya açar söz ilə axtar (məs. «kondisioner», «asqı», «Waze»)") },
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip("Hamısı", kind == null) { kind = null }
        Chip("Funksiyalar", kind == RecordedFlow.Kind.FUNCTION) { kind = RecordedFlow.Kind.FUNCTION }
        Chip("Gedişatlar", kind == RecordedFlow.Kind.FLOW) { kind = RecordedFlow.Kind.FLOW }
        Spacer(Modifier.weight(1f))
        if (query.isNotBlank()) Pill("Ən uyğununu əlavə et", Icons.Filled.Add, CarColors.Green) {
            val r = FlowImporter.importBest(context, query, kind)
            info = when {
                r == null -> "«$query» üçün heç nə tapılmadı"
                r.second -> "✓ Əlavə edildi: ${r.first.name}"
                else -> "Artıq əlavə edilib: ${r.first.name}"
            }
        }
    }
    info?.let { Text(it, fontSize = 13.sp, color = CarColors.Green) }
    if (matches.isEmpty()) {
        Text(if (flows.isEmpty()) "Baza boşdur — yuxarıdakı düymə ilə izləməni aktiv et və maşında adi qaydada işlə."
        else "Uyğun nəticə yoxdur.", fontSize = 14.sp, color = CarColors.TextSecondary)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        matches.forEach { m ->
            val f = m.flow
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CarColors.PanelAlt)
                    .clickable { expandedId = if (expandedId == f.id) null else f.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val kc = if (f.kind == RecordedFlow.Kind.FLOW) CarColors.Purple else CarColors.Accent
                    Text(f.kind.label, fontSize = 11.sp, color = kc,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(kc.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 15.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("×${f.count} · ${f.steps.size} addım · hərəkətdə ${f.movingCount} / dayanıb ${f.stoppedCount}",
                            fontSize = 12.sp, color = CarColors.TextSecondary)
                    }
                    if (m.alreadyAdded) Text("✓ əlavədir", fontSize = 12.sp, color = CarColors.Green, modifier = Modifier.padding(horizontal = 6.dp))
                    else Pill("Əlavə et", Icons.Filled.Add, CarColors.Green) {
                        val (fn, isNew) = FlowImporter.import(f)
                        info = if (isNew) "✓ Əlavə edildi: ${fn.name}" else "Artıq əlavə edilib: ${fn.name}"
                    }
                    IconBtn(Icons.Filled.Edit, "Ad") { renaming = f }
                    IconBtn(Icons.Filled.Delete, "Sil", CarColors.Danger) { RecordedFlowRepository.delete(f.id) }
                }
                if (expandedId == f.id) {
                    Spacer(Modifier.height(6.dp))
                    f.steps.forEachIndexed { i, s ->
                        Text(
                            "${i + 1}. ${if (s.type.name == "CLICK") "👆" else "🗔"} ${s.label()}  ·  " +
                                "${RecorderScannerManager.appLabel(context, s.packageName)}" +
                                (if (s.viewIdResName.isNotBlank()) "  ·  ${s.viewIdResName.substringAfter(":id/")}" else "") +
                                "  ·  ${s.motion.label}",
                            fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 2
                        )
                    }
                }
            }
        }
    }
    if (flows.isNotEmpty()) {
        TextButton(onClick = { RecordedFlowRepository.clear() }) { Text("Bazanı təmizlə", color = CarColors.Danger) }
    }
    renaming?.let { f ->
        RenameDialog(f.name, onDismiss = { renaming = null }) { RecordedFlowRepository.rename(f.id, it); renaming = null }
    }
}

// ───────────────────────── köməkçilər ─────────────────────────

@Composable
private fun SectionTitle(t: String) =
    Text(t, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)

@Composable
private fun Pill(text: String, icon: ImageVector, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (enabled) 0.16f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp))
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) ScannerModule.accent else CarColors.TextSecondary
    Text(
        text, fontSize = 14.sp, color = if (selected) CarColors.TextPrimary else CarColors.TextSecondary,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = if (selected) 0.22f else 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun SmallBtn(t: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(CarColors.Panel).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, fontSize = 18.sp, color = CarColors.TextPrimary) }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, tint: Color = CarColors.TextSecondary, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adını dəyiş") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Yeni ad") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Yadda saxla") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Ləğv et") } }
    )
}
