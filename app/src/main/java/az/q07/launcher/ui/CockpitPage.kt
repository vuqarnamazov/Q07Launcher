package az.q07.launcher.ui

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppEntry
import az.q07.launcher.data.AppSettings
import az.q07.launcher.data.FactoryBridge
import az.q07.launcher.data.FactoryJob
import az.q07.launcher.data.NotesStore
import az.q07.launcher.data.ObdMonitor
import az.q07.launcher.data.QuickActions
import az.q07.launcher.data.VehicleControl
import az.q07.launcher.data.VehicleQuickControlHelper
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.abs

/**
 * Səhifə 0 — tünd kokpit:
 *  sol zolaq (saat + qısayollar) | mərkəz (fırlanan avtomobil + idarəetmə chip-ləri) | sağ (tətbiqlər)
 */
@Composable
fun CockpitPage(apps: List<AppEntry>?, onOpenModules: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CockpitSideStrip(Modifier.weight(0.19f).fillMaxHeight())
        CockpitCenter(onOpenModules, Modifier.weight(0.47f).fillMaxHeight())
        AppsPanel(apps, columns = 3, modifier = Modifier.weight(0.34f).fillMaxHeight())
    }
}

// ---------------------------------------------------------------- sol zolaq
@Composable
private fun CockpitSideStrip(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); kotlinx.coroutines.delay(1000) } }
    PanelSurface(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(SimpleDateFormat(if (AppSettings.showSeconds) "HH:mm:ss" else "HH:mm", Locale.getDefault()).format(now),
                fontSize = 46.sp, fontWeight = FontWeight.Light, color = CarColors.TextPrimary, maxLines = 1)
            Text(SimpleDateFormat("EEEE, d MMMM", Locale("az")).format(now), fontSize = 14.sp, color = CarColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            Shortcut("Naviqasiya (Waze)", Icons.Filled.Navigation, Color(0xFF33CCFF)) { QuickActions.openNavigation(context) }
            Shortcut("YouTube", Icons.Filled.PlayCircle, Color(0xFFFF4D4D)) { QuickActions.openYouTube(context) }
            Shortcut("Gemini Səsli", Icons.Filled.Mic, Color(0xFFB36BFF)) { QuickActions.openGemini(context) }
            Shortcut("Zavod Menyusu", Icons.Filled.Home, CarColors.Warning) {
                val homes = QuickActions.findOtherHomes(context)
                if (homes.isNotEmpty()) QuickActions.launchHome(context, homes.first()) else QuickActions.openHomeSettings(context)
            }
            // İzləmə Skaneri — sürətli paneldə Aktiv / Sönülü düyməsi
            az.q07.launcher.modules.ScannerToggleButton(compact = true)
            Spacer(Modifier.height(6.dp))
            LiveMini()
        }
    }
}

@Composable
private fun Shortcut(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Text(title, fontSize = 16.sp, color = CarColors.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LiveMini() {
    val obd by ObdMonitor.state.collectAsState()
    val v = obd.values
    fun f(k: String, d: Int = 0) = v[k]?.let { if (d == 0) it.toInt().toString() else String.format(Locale.US, "%.${d}f", it) } ?: "—"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CarColors.PanelAlt).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("${f("speed")} km/s", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
        Text("SOC ${f("soc", 1)}%  ·  12V ${f("voltage", 1)}", fontSize = 13.sp, color = CarColors.TextSecondary)
        Text("Soyuducu ${f("coolant")}°C  ·  Çöl ${f("ambient")}°C", fontSize = 13.sp, color = CarColors.TextSecondary)
    }
}

// ---------------------------------------------------------------- mərkəz
@Composable
internal fun CockpitCenter(onOpenModules: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    var noteOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (activity != null && VehicleQuickControlHelper.isServiceEnabled(context)) {
            if (FactoryBridge.current.value.isEmpty()) VehicleQuickControlHelper.refresh(activity)
            if (FactoryBridge.tyres.value == null) FactoryBridge.enqueue(activity, FactoryJob.ReadHome)
        }
    }
    PanelSurface(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TopPill("Q07 İdarəetmə və Modullar", Icons.Filled.ChevronRight, Modifier.weight(1.4f), trailing = true, onClick = onOpenModules)
                TopPill("Sürətli Qeyd", Icons.Filled.EditNote, Modifier.weight(1f)) { noteOpen = true }
            }
            // qalan yer: avtomobil + chip-lər; ekran kiçiləndə sürüşür, heç nə itmir
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val carH = (maxHeight * 0.46f).coerceIn(150.dp, 340.dp)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ControlChips(VehicleControl.DRIVE_MODE, compactTitle = "Sürüş")
                    RotatingCar(Modifier.fillMaxWidth().height(carH))
                    ControlChips(VehicleControl.SUSPENSION, compactTitle = "Asqı")
                    ControlChips(VehicleControl.ENERGY_RECOVERY, compactTitle = "Enerji bərpası")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { ControlChips(VehicleControl.POWER_STEERING, compactTitle = "Sükan") }
                        Box(Modifier.weight(1f)) { ControlChips(VehicleControl.BRAKE_MODE, compactTitle = "Əyləc") }
                    }
                    val msg by FactoryBridge.message.collectAsState()
                    msg?.let { Text(it, fontSize = 11.sp, color = CarColors.TextSecondary) }
                    if (!VehicleQuickControlHelper.isServiceEnabled(context)) {
                        Text("Düymələr üçün «Q07 Launcher — sürüş rejimi» xüsusi imkan xidməti aktiv olmalıdır.",
                            fontSize = 11.sp, color = CarColors.Warning)
                    }
                }
            }
        }
    }
    if (noteOpen) QuickNoteDialog { noteOpen = false }
}

@Composable
private fun TopPill(title: String, icon: ImageVector, modifier: Modifier, trailing: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(CarColors.PanelAlt).border(1.dp, CarColors.Border, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!trailing) Icon(icon, null, tint = CarColors.Accent, modifier = Modifier.size(22.dp))
        Text(title, fontSize = 15.sp, color = CarColors.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing) Icon(icon, null, tint = CarColors.TextSecondary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun QuickNoteDialog(onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    ScaledAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sürətli qeyd") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("Qeydini yaz…") }) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) NotesStore.add(text.trim()); onClose() }) { Text("Yadda saxla") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Bağla") } }
    )
}

/** Kompakt idarəetmə chip-ləri — VehicleQuickControlHelper ilə */
@Composable
private fun ControlChips(control: VehicleControl, compactTitle: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val current by FactoryBridge.current.collectAsState()
    val labels by FactoryBridge.labels.collectAsState()
    val busy by FactoryBridge.busyControl.collectAsState()
    val working by FactoryBridge.working.collectAsState()
    val names = labels[control]?.takeIf { it.isNotEmpty() } ?: control.options
    val sel = current[control]
    val palette = listOf(Color(0xFF3DDC97), Color(0xFF4DA3FF), Color(0xFFFF8A3D), Color(0xFFFF4D4D), Color(0xFFB36BFF))
    val order = if (control == VehicleControl.DRIVE_MODE) listOf(2, 1, 0) else names.indices.toList()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(compactTitle, fontSize = 11.sp, color = CarColors.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1)
            if (busy == control) Text("…", fontSize = 11.sp, color = CarColors.Warning)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = if (maxWidth / order.size < 64.dp) 3 else order.size
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                order.chunked(perRow).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { idx ->
                            val c = if (control == VehicleControl.DRIVE_MODE) listOf(palette[0], palette[1], palette[3])[idx] else palette[idx % palette.size]
                            val active = sel == idx
                            Box(
                                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp))
                                    .background(if (active) c.copy(alpha = 0.30f) else Color(0x22FFFFFF))
                                    .border(1.dp, if (active) c else c.copy(alpha = 0.30f), RoundedCornerShape(17.dp))
                                    .clickable(enabled = !working && activity != null && !active) {
                                        if (!VehicleQuickControlHelper.execute(activity!!, control, idx))
                                            VehicleQuickControlHelper.openAccessibilitySettings(context)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(names.getOrNull(idx) ?: "#$idx", fontSize = 12.sp, maxLines = 1,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) c else CarColors.TextPrimary)
                            }
                        }
                        repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- fırlanan avtomobil
/**
 * Sağa/sola sürüşdürəndə avtomobil Y oxu ətrafında fırlanır (perspektivlə),
 * buraxanda yay effekti ilə düz vəziyyətə qayıdır. İki dəfə toxunuş → tam 360° fırlanma.
 * Jest bu sahədə tam istehlak olunur — səhifə (pager) sürüşməsi ilə qarışmır.
 */
@Composable
private fun RotatingCar(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val angle = remember { Animatable(0f) }
    val density = LocalDensity.current
    val tyres by FactoryBridge.tyres.collectAsState()
    val obd by ObdMonitor.state.collectAsState()
    Box(
        modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { scope.launch { angle.stop() } },
                    onDragEnd = { scope.launch { angle.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) } },
                    onDragCancel = { scope.launch { angle.animateTo(0f, spring()) } },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        scope.launch { angle.snapTo((angle.value + dx * 0.35f).coerceIn(-75f, 75f)) }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scope.launch {
                        angle.snapTo(0f)
                        angle.animateTo(360f, tween(1400, easing = LinearEasing))
                        angle.snapTo(0f)
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        // yerdəki işıq ləkəsi (fırlanmır)
        Canvas(Modifier.fillMaxSize()) {
            drawOval(
                Brush.radialGradient(listOf(Color(0x5533CCFF), Color.Transparent), center = Offset(size.width / 2, size.height * 0.82f), radius = size.width * 0.38f),
                topLeft = Offset(size.width * 0.12f, size.height * 0.70f), size = Size(size.width * 0.76f, size.height * 0.24f)
            )
        }
        val a = angle.value
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                rotationY = a
                cameraDistance = 14f * density.density
                // yan tərəfə dönəndə bir az kiçilsin — dərinlik hissi
                val k = 0.9f + 0.1f * abs(cos(Math.toRadians(a.toDouble()))).toFloat()
                scaleX = k; scaleY = k
            }
        ) { CarDrawing(Modifier.fillMaxSize()) }

        // təkər təzyiqi və canlı göstəricilər (fırlanmır, oxunaqlı qalır)
        val t = tyres
        Box(Modifier.fillMaxSize().padding(6.dp)) {
            TyreTag("Ön ${t?.fl ?: "—"} / ${t?.fr ?: "—"}", Modifier.align(Alignment.BottomStart))
            TyreTag("Arxa ${t?.rl ?: "—"} / ${t?.rr ?: "—"}", Modifier.align(Alignment.BottomEnd))
            val v = obd.values
            TyreTag(
                "${v["speed"]?.toInt() ?: "—"} km/s · SOC ${v["soc"]?.let { String.format(Locale.US, "%.0f", it) } ?: "—"}%",
                Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun TyreTag(text: String, modifier: Modifier) {
    Text(
        text, fontSize = 12.sp, color = CarColors.TextPrimary, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Stilizə edilmiş krossover silueti (yan görünüş) — öz rəsmimiz */
@Composable
private fun CarDrawing(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cw = minOf(w * 0.86f, h * 2.6f); val ch = cw / 2.6f
        val ox = (w - cw) / 2; val oy = (h - ch) / 2 + ch * 0.05f
        fun p(x: Float, y: Float) = Offset(ox + x * cw, oy + y * ch)
        val body = Path().apply {
            moveTo(p(0.03f, 0.70f).x, p(0.03f, 0.70f).y)
            cubicTo(p(0.02f, 0.55f).x, p(0.02f, 0.55f).y, p(0.06f, 0.47f).x, p(0.06f, 0.47f).y, p(0.16f, 0.44f).x, p(0.16f, 0.44f).y)
            lineTo(p(0.30f, 0.40f).x, p(0.30f, 0.40f).y)
            cubicTo(p(0.38f, 0.22f).x, p(0.38f, 0.22f).y, p(0.44f, 0.16f).x, p(0.44f, 0.16f).y, p(0.56f, 0.15f).x, p(0.56f, 0.15f).y)
            lineTo(p(0.80f, 0.16f).x, p(0.80f, 0.16f).y)
            cubicTo(p(0.88f, 0.17f).x, p(0.88f, 0.17f).y, p(0.93f, 0.30f).x, p(0.93f, 0.30f).y, p(0.96f, 0.42f).x, p(0.96f, 0.42f).y)
            cubicTo(p(0.99f, 0.50f).x, p(0.99f, 0.50f).y, p(0.99f, 0.62f).x, p(0.99f, 0.62f).y, p(0.97f, 0.72f).x, p(0.97f, 0.72f).y)
            lineTo(p(0.03f, 0.72f).x, p(0.03f, 0.72f).y)
            close()
        }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFF5A6B80), Color(0xFF1C2430)), startY = oy, endY = oy + ch * 0.75f))
        drawPath(body, Color(0xFF8FD3FF).copy(alpha = 0.55f), style = Stroke(width = 2.5f))
        // şüşələr
        val glass = Path().apply {
            moveTo(p(0.34f, 0.40f).x, p(0.34f, 0.40f).y)
            cubicTo(p(0.40f, 0.25f).x, p(0.40f, 0.25f).y, p(0.45f, 0.21f).x, p(0.45f, 0.21f).y, p(0.56f, 0.20f).x, p(0.56f, 0.20f).y)
            lineTo(p(0.82f, 0.21f).x, p(0.82f, 0.21f).y)
            cubicTo(p(0.86f, 0.22f).x, p(0.86f, 0.22f).y, p(0.89f, 0.30f).x, p(0.89f, 0.30f).y, p(0.90f, 0.40f).x, p(0.90f, 0.40f).y)
            close()
        }
        drawPath(glass, Brush.linearGradient(listOf(Color(0xFF0E1A26), Color(0xFF2B4C66)), p(0.4f, 0.2f), p(0.9f, 0.4f)))
        drawLine(Color(0xFF1C2430), p(0.62f, 0.20f), p(0.62f, 0.40f), strokeWidth = 5f)
        // işıq zolaqları
        drawRoundRect(Color(0xFFE8F6FF), p(0.04f, 0.47f), Size(cw * 0.07f, ch * 0.035f), CornerRadius(6f))
        drawRoundRect(Color(0xFFFF3B3B), p(0.90f, 0.43f), Size(cw * 0.07f, ch * 0.035f), CornerRadius(6f))
        drawLine(Color(0xFF33CCFF).copy(alpha = 0.7f), p(0.16f, 0.56f), p(0.90f, 0.56f), strokeWidth = 2f)
        // təkərlər
        listOf(0.22f, 0.79f).forEach { cx ->
            val c = p(cx, 0.72f); val r = ch * 0.17f
            drawCircle(Color(0xFF0A0D12), r * 1.12f, c)
            drawCircle(Color(0xFF2A313B), r, c)
            drawCircle(Color(0xFF8FA3B8), r * 0.55f, c, style = Stroke(width = 4f))
            drawCircle(Color(0xFF33CCFF).copy(alpha = 0.5f), r * 1.12f, c, style = Stroke(width = 2f))
        }
    }
}
