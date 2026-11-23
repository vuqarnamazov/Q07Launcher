package az.q07.launcher.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Checkbox
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
import az.q07.launcher.scanner.FlowStep
import az.q07.launcher.scanner.FullVehicleScannerManager
import az.q07.launcher.scanner.FullVehicleScannerManager.Mode
import az.q07.launcher.scanner.LearnedFunction
import az.q07.launcher.scanner.LearnedFunctionsRepository
import az.q07.launcher.scanner.MotionState
import az.q07.launcher.scanner.VehicleStructureRepository
import az.q07.launcher.ui.theme.CarColors

val ScannerAccent = Color(0xFFFF4D8D)

fun openScannerScreen(context: Context) {
    // Ayrıca "Q07 Skaner" tətbiqi qurulubsa — onu aç
    context.packageManager.getLaunchIntentForPackage("az.q07.scanner")?.let { i ->
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    runCatching {
        context.startActivity(Intent(context, VehicleScannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ───────────────────────── rejim idarəsi ─────────────────────────

/** "Skaner: Sönülü / Açıq / Fon İzləməsi" seçimi + qısa vəziyyət */
@Composable
fun ScannerModeControl(modifier: Modifier = Modifier, showStats: Boolean = true) {
    val context = LocalContext.current
    val mode by FullVehicleScannerManager.mode.collectAsState()
    val motion by FullVehicleScannerManager.motion.collectAsState()
    val speed by FullVehicleScannerManager.speedKmh.collectAsState()
    val screens by VehicleStructureRepository.screens.collectAsState()
    val edges by VehicleStructureRepository.edges.collectAsState()
    val flows by VehicleStructureRepository.flows.collectAsState()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.values().forEach { m ->
                val sel = m == mode
                val c = when (m) { Mode.OFF -> CarColors.TextSecondary; Mode.OPEN -> CarColors.Accent; Mode.BACKGROUND -> CarColors.Danger }
                Box(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
                        .background(c.copy(alpha = if (sel) 0.22f else 0.06f))
                        .border(2.dp, c.copy(alpha = if (sel) 0.9f else 0.25f), RoundedCornerShape(14.dp))
                        .clickable { FullVehicleScannerManager.setMode(context, m) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(m.label, fontSize = 16.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) CarColors.TextPrimary else CarColors.TextSecondary)
                }
            }
        }
        if (showStats) {
            val mc = when (motion) { MotionState.MOVING -> CarColors.Green; MotionState.STOPPED -> CarColors.Warning; else -> CarColors.TextSecondary }
            Text(
                "${motion.label}${speed?.let { " · ${it.toInt()} km/s" } ?: ""} · ${screens.size} ekran · " +
                    "${edges.size} yol · ${flows.size} funksiya/gedişat",
                fontSize = 13.sp, color = mc
            )
            if (mode != Mode.OFF && !FullVehicleScannerManager.isAccessibilityReady(context)) {
                Text("⚠ Xüsusi imkan xidməti aktiv deyil — heç nə öyrənilmir", fontSize = 13.sp, color = CarColors.Danger)
            }
            if (mode == Mode.OPEN) Text("Açıq rejim: yalnız skaner ekranı açıq olanda öyrənir", fontSize = 12.sp, color = CarColors.TextSecondary)
        }
    }
}

/** Kokpit üçün kiçik düymə: Fon İzləməsi aç/söndür */
@Composable
fun ScannerBackgroundToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode by FullVehicleScannerManager.mode.collectAsState()
    val on = mode == Mode.BACKGROUND
    val c = if (on) CarColors.Danger else CarColors.TextSecondary
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp))
            .background(c.copy(alpha = if (on) 0.16f else 0.08f))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Skaner: " + if (on) "Fon İzləməsi" else mode.label, fontSize = 15.sp, color = CarColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable { FullVehicleScannerManager.toggleBackground(context) }
        )
        Text("Aç ›", fontSize = 14.sp, color = ScannerAccent, modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .clickable { openScannerScreen(context) }.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

/** Sürətli idarəetməyə bərkidilmiş funksiyalar — 1 toxunuş */
@Composable
fun PinnedQuickActions(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by LearnedFunctionsRepository.items.collectAsState()
    val playing by FlowPlayer.playing.collectAsState()
    val pinned = items.filter { it.pinned }
    if (pinned.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pinned.forEach { f ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                    .background(ScannerAccent.copy(alpha = 0.10f))
                    .border(1.dp, ScannerAccent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .clickable(enabled = !playing) { FlowPlayer.play(context, f) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = ScannerAccent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(f.name, fontSize = 15.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ───────────────────────── launcher funksiyaları ─────────────────────────

@Composable
fun PlayerStatusLine() {
    val status by FlowPlayer.status.collectAsState()
    val playing by FlowPlayer.playing.collectAsState()
    if (status == null && !playing) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(status.orEmpty(), fontSize = 13.sp, color = CarColors.TextSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        if (playing) ScPill("Dayandır", Icons.Filled.Stop, CarColors.Danger) { FlowPlayer.stop() }
    }
}

@Composable
fun LearnedFunctionsList() {
    val context = LocalContext.current
    val items by LearnedFunctionsRepository.items.collectAsState()
    val playing by FlowPlayer.playing.collectAsState()
    var renaming by remember { mutableStateOf<LearnedFunction?>(null) }

    if (items.isEmpty()) {
        Text("Launcher-ə hələ funksiya/gedişat əlavə edilməyib. Skaner ekranında «Götür və Əlavə Et» bölməsindən əlavə et.",
            fontSize = 14.sp, color = CarColors.TextSecondary)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CarColors.PanelAlt)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(ScannerAccent.copy(alpha = 0.2f))
                        .clickable(enabled = !playing) { FlowPlayer.play(context, f) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.PlayArrow, "İcra et", tint = ScannerAccent, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 15.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (f.isFlow) "Gedişat · ${f.steps.size} addım · ${f.stepDelayMs} ms" else "Funksiya · 1 addım") +
                            " · ${f.runs} dəfə",
                        fontSize = 12.sp, color = CarColors.TextSecondary
                    )
                }
                if (f.isFlow) {
                    ScSmallBtn("−") { LearnedFunctionsRepository.setDelay(f.id, f.stepDelayMs - 200) }
                    ScSmallBtn("+") { LearnedFunctionsRepository.setDelay(f.id, f.stepDelayMs + 200) }
                }
                ScIconBtn(if (f.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    if (f.pinned) "Sürətli idarəetmədən çıxar" else "Sürətli idarəetməyə əlavə et",
                    if (f.pinned) ScannerAccent else CarColors.TextSecondary) { LearnedFunctionsRepository.setPinned(f.id, !f.pinned) }
                ScIconBtn(Icons.Filled.Edit, "Ad") { renaming = f }
                ScIconBtn(Icons.Filled.Delete, "Sil", CarColors.Danger) { LearnedFunctionsRepository.delete(f.id) }
            }
        }
    }
    renaming?.let { f -> ScRenameDialog(f.name, { renaming = null }) { LearnedFunctionsRepository.rename(f.id, it); renaming = null } }
}

// ───────────────────────── Götür və Əlavə Et dialoqu ─────────────────────────

@Composable
fun AddCandidateDialog(candidate: FlowImporter.Candidate, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember(candidate) { mutableStateOf(candidate.name) }
    var pin by remember(candidate) { mutableStateOf(false) }
    val added = FlowImporter.isAdded(candidate)
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Götür və launcher-ə əlavə et") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Ad") }, modifier = Modifier.fillMaxWidth())
                Text(candidate.subtitle, fontSize = 13.sp, color = CarColors.TextSecondary)
                StepsList(candidate.steps)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { pin = !pin }) {
                    Checkbox(pin, { pin = it })
                    Text("Kokpitin sürətli idarəetməsinə də əlavə et", fontSize = 14.sp)
                }
                if (added) Text("Bu addımlar artıq launcher-dədir.", fontSize = 13.sp, color = CarColors.Warning)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (fn, isNew) = FlowImporter.import(candidate, name, pin)
                onDone(if (isNew) "✓ Əlavə edildi: ${fn.name}" else "Artıq var: ${fn.name}")
            }) { Text("Əlavə et") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    // yoxlamaq üçün əlavə etmədən icra et
                    FlowPlayer.play(context, LearnedFunction("test", name, candidate.steps))
                    onDismiss()
                }) { Text("Sına") }
                TextButton(onClick = onDismiss) { Text("Ləğv et") }
            }
        }
    )
}

@Composable
fun StepsList(steps: List<FlowStep>) {
    val context = LocalContext.current
    steps.forEachIndexed { i, s ->
        Text(
            "${i + 1}. ${if (s.type == FlowStep.Type.CLICK) "👆" else "🗔"} ${s.label()} · " +
                FullVehicleScannerManager.appLabel(context, s.packageName) +
                (if (s.viewIdResName.isNotBlank()) " · ${s.viewIdResName.substringAfter(":id/")}" else ""),
            fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 2
        )
    }
}

// ───────────────────────── kiçik komponentlər ─────────────────────────

@Composable
fun ScSection(t: String) = Text(t, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)

@Composable
fun ScPill(text: String, icon: ImageVector?, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (enabled) 0.16f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 14.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp))
    }
}

@Composable
fun ScChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) ScannerAccent else CarColors.TextSecondary
    Text(
        text, fontSize = 14.sp, color = if (selected) CarColors.TextPrimary else CarColors.TextSecondary,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = if (selected) 0.22f else 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
fun ScChipRow(content: @Composable () -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) { content() }

@Composable
fun ScSmallBtn(t: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(CarColors.Panel).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, fontSize = 18.sp, color = CarColors.TextPrimary) }
    Spacer(Modifier.width(4.dp))
}

@Composable
fun ScIconBtn(icon: ImageVector, desc: String, tint: Color = CarColors.TextSecondary, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun ScRenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adını dəyiş") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Yeni ad") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Yadda saxla") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Ləğv et") } }
    )
}

@Composable
fun ScCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CarColors.PanelAlt)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) { content() }
}
