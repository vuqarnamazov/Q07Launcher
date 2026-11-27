package az.q07.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Palette
import az.q07.launcher.ui.theme.AnalogGauge
import az.q07.launcher.ui.theme.InfoCard
import az.q07.launcher.ui.theme.LauncherTheme
import az.q07.launcher.ui.theme.Look
import az.q07.launcher.ui.theme.MediaStrip
import az.q07.launcher.ui.theme.NavCard
import az.q07.launcher.ui.theme.RingGauge
import az.q07.launcher.ui.theme.sunsetBackdrop
import az.q07.launcher.data.WeatherRepo
import androidx.compose.runtime.DisposableEffect
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
                TopPill("Q07 İdarəetmə və Modullar", Icons.Filled.ChevronRight, Modifier.weight(1.3f), trailing = true, onClick = onOpenModules)
                TopPill("Sürətli Makrolar", Icons.Filled.PlayCircle, Modifier.weight(0.9f)) {
                    context.startActivity(
                        android.content.Intent(context, MacroActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                TopPill("Sürətli Qeyd", Icons.Filled.EditNote, Modifier.weight(0.8f)) { noteOpen = true }
            }
            // qalan yer: avtomobil + chip-lər; ekran kiçiləndə sürüşür, heç nə itmir
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val carH = (maxHeight * 0.56f).coerceIn(170.dp, 440.dp)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ControlCard(Modifier.weight(1f)) { ControlChips(VehicleControl.ENERGY_MODE, compactTitle = "Enerji rejimi") }
                        ControlCard(Modifier.weight(1f)) { ControlChips(VehicleControl.DRIVE_MODE, compactTitle = "Sürüş") }
                    }
                    ThemedCenterpiece(carH, onOpenModules)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ControlCard(Modifier.weight(1f)) { ControlChips(VehicleControl.SUSPENSION, compactTitle = "Asqı") }
                        ControlCard(Modifier.weight(1f)) { ControlChips(VehicleControl.ENERGY_RECOVERY, compactTitle = "Enerji bərpası") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ControlCard(Modifier.weight(1f)) { ControlChips(VehicleControl.POWER_STEERING, compactTitle = "Sükan") }
                        ControlCard(Modifier.weight(1f)) { ControlChips(VehicleControl.BRAKE_MODE, compactTitle = "Əyləc") }
                    }
                    if (Look.klassik || Look.modern || Look.elegant) MediaStrip(Modifier.fillMaxWidth())
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

/** Hər idarəetmə qrupu üçün eyni görünüşlü kart — səliqəli şəbəkə */
@Composable
private fun ControlCard(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(CarColors.PanelAlt)
            .border(1.dp, CarColors.Border, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) { content() }
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
/** Mərkəz hissə mövzuya görə: maşın, analoq göstəricilər, telemetriya, kartlar */
@Composable
private fun ThemedCenterpiece(carH: androidx.compose.ui.unit.Dp, onOpenModules: () -> Unit) {
    val obd by ObdMonitor.state.collectAsState()
    val v = obd.values
    when {
        Look.klassik -> {
            val tyres by FactoryBridge.tyres.collectAsState()
            Row(Modifier.fillMaxWidth().height(carH), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                AnalogGauge(v["speed"], 200.0, "Sürət", "km/s", Modifier.fillMaxHeight())
                AnalogGauge(v["soc"], 100.0, "Batareya", "%", Modifier.fillMaxHeight())
            }
            val t = tyres
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TyreTag("Ön ${t?.fl ?: "—"} / ${t?.fr ?: "—"}", Modifier)
                TyreTag("Arxa ${t?.rl ?: "—"} / ${t?.rr ?: "—"}", Modifier)
            }
        }
        Look.modern -> {
            val context = LocalContext.current
            DisposableEffect(Unit) { WeatherRepo.acquire(context); onDispose { WeatherRepo.release() } }
            val w by WeatherRepo.state.collectAsState()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoCard("Hava · ${AppSettings.city.name}",
                    if (w.ok) "${WeatherRepo.describe(w.code).first} ${Math.round(w.temp)}°C" else "—",
                    Modifier.weight(1f), onClick = onOpenModules)
                NavCard(Modifier.weight(1f))
            }
            RotatingCar(Modifier.fillMaxWidth().height(carH))
        }
        Look.elegant -> RotatingCar(
            Modifier.fillMaxWidth().height(carH).clip(RoundedCornerShape(18.dp)).sunsetBackdrop()
        )
        Look.future -> {
            RotatingCar(Modifier.fillMaxWidth().height(carH))
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                RingGauge(v["speed"], 180.0, "km/s", CarColors.Accent, Modifier.fillMaxHeight())
                RingGauge(v["soc"], 100.0, "SOC %", Color(0xFF9333EA), Modifier.fillMaxHeight())
                RingGauge(v["load"], 100.0, "yük %", Color(0xFF22C55E), Modifier.fillMaxHeight())
            }
        }
        else -> RotatingCar(Modifier.fillMaxWidth().height(carH))
    }
}

@Composable
private fun RotatingCar(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val angle = remember { Animatable(0f) }
    val density = LocalDensity.current
    val tyres by FactoryBridge.tyres.collectAsState()
    val obd by ObdMonitor.state.collectAsState()
    // Maşının zavod 3D modeli varsa — tam 3D (barmaqla hər tərəfə fırlanır), yoxdursa köhnə rəsm
    val ctx3d = LocalContext.current
    val use3d = remember { Car3DModel.available(ctx3d) }
    Box(
        if (use3d) modifier else modifier
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
        val glow = CarColors.Accent
        val future = Look.future
        Canvas(Modifier.fillMaxSize()) {
            if (future) {
                listOf(0.40f to glow, 0.30f to Color(0xFF9333EA)).forEach { (k, col) ->
                    drawOval(col.copy(alpha = 0.8f), topLeft = Offset(size.width * (0.5f - k), size.height * 0.80f),
                        size = Size(size.width * k * 2, size.height * 0.12f), style = Stroke(width = 2f))
                }
            }
            drawOval(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.33f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.82f), radius = size.width * 0.38f),
                topLeft = Offset(size.width * 0.12f, size.height * 0.70f), size = Size(size.width * 0.76f, size.height * 0.24f)
            )
        }
        val a = angle.value
        if (use3d) InteractiveQ07ModelStage(Modifier.fillMaxSize()) { _, _, _ ->
            // Yalnız 3D maşın. Nömrə maşının öz üstündədir; ayrıca overlay nişan yoxdur.
            // Zoom: iki barmaq (pinch). Nömrəni dəyişmək: maşına uzun bas.
            Car3DView(Modifier.fillMaxSize())
        }
        else Box(
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
            if (use3d) CarColorPicker(Modifier.align(Alignment.TopEnd))
        }
    }
}

/** 🎨 — kuzov rəngini seçmək (3D maşın üçün) */
@Composable
private fun CarColorPicker(modifier: Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val sel by az.q07.launcher.data.CarLook.colorIndex.collectAsState()
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(0x99000000))
                .border(1.dp, CarColors.Border, CircleShape).clickable { open = !open },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Palette, "Rəng", tint = CarColors.TextPrimary, modifier = Modifier.size(24.dp)) }
        if (open) Row(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xCC000000)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            az.q07.launcher.data.CarLook.colors.forEachIndexed { i, c ->
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(c.rgb?.let { Color(it) } ?: Color(0xFF8A94A6))
                        .border(if (i == sel) 3.dp else 1.dp, if (i == sel) CarColors.Accent else Color(0x55FFFFFF), CircleShape)
                        .clickable { az.q07.launcher.data.CarLook.setColor(context, i) },
                    contentAlignment = Alignment.Center
                ) { if (c.rgb == null) Text("Z", fontSize = 12.sp, color = Color.White) }
            }
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
    val pal = carPalette()
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
        val wire = pal.wire
        if (!wire) drawPath(body, Brush.verticalGradient(listOf(pal.top, pal.bottom), startY = oy, endY = oy + ch * 0.75f))
        else drawPath(body, pal.edge.copy(alpha = 0.10f))
        drawPath(body, pal.edge.copy(alpha = if (wire) 0.95f else 0.55f), style = Stroke(width = if (wire) 2f else 2.5f))
        if (wire) {
            // tel karkas xətləri
            listOf(0.30f, 0.46f, 0.62f, 0.78f).forEach { x -> drawLine(pal.edge.copy(alpha = 0.35f), p(x, 0.18f), p(x, 0.70f), strokeWidth = 1f) }
            listOf(0.50f, 0.62f).forEach { y -> drawLine(pal.edge.copy(alpha = 0.35f), p(0.06f, y), p(0.97f, y), strokeWidth = 1f) }
        }
        // şüşələr
        val glass = Path().apply {
            moveTo(p(0.34f, 0.40f).x, p(0.34f, 0.40f).y)
            cubicTo(p(0.40f, 0.25f).x, p(0.40f, 0.25f).y, p(0.45f, 0.21f).x, p(0.45f, 0.21f).y, p(0.56f, 0.20f).x, p(0.56f, 0.20f).y)
            lineTo(p(0.82f, 0.21f).x, p(0.82f, 0.21f).y)
            cubicTo(p(0.86f, 0.22f).x, p(0.86f, 0.22f).y, p(0.89f, 0.30f).x, p(0.89f, 0.30f).y, p(0.90f, 0.40f).x, p(0.90f, 0.40f).y)
            close()
        }
        if (wire) drawPath(glass, pal.edge.copy(alpha = 0.6f), style = Stroke(width = 1.2f))
        else drawPath(glass, Brush.linearGradient(listOf(pal.glassA, pal.glassB), p(0.4f, 0.2f), p(0.9f, 0.4f)))
        drawLine(if (wire) pal.edge.copy(alpha = 0.6f) else pal.bottom, p(0.62f, 0.20f), p(0.62f, 0.40f), strokeWidth = if (wire) 1.2f else 5f)
        // işıq zolaqları
        drawRoundRect(Color(0xFFE8F6FF), p(0.04f, 0.47f), Size(cw * 0.07f, ch * 0.035f), CornerRadius(6f))
        drawRoundRect(Color(0xFFFF3B3B), p(0.90f, 0.43f), Size(cw * 0.07f, ch * 0.035f), CornerRadius(6f))
        drawLine(pal.edge.copy(alpha = 0.7f), p(0.16f, 0.56f), p(0.90f, 0.56f), strokeWidth = 2f)
        // təkərlər
        listOf(0.22f, 0.79f).forEach { cx ->
            val c = p(cx, 0.72f); val r = ch * 0.17f
            if (wire) {
                drawCircle(pal.edge, r * 1.05f, c, style = Stroke(width = 2f))
                drawCircle(pal.edge.copy(alpha = 0.6f), r * 0.55f, c, style = Stroke(width = 1.5f))
            } else {
                drawCircle(Color(0xFF0A0D12), r * 1.12f, c)
                drawCircle(pal.tyre, r, c)
                drawCircle(pal.rim, r * 0.55f, c, style = Stroke(width = 4f))
                drawCircle(pal.edge.copy(alpha = 0.5f), r * 1.12f, c, style = Stroke(width = 2f))
            }
        }
    }
}

/** Maşın rəsminin mövzuya görə rəngləri */
private class CarPalette(
    val top: Color, val bottom: Color, val edge: Color, val glassA: Color, val glassB: Color,
    val tyre: Color, val rim: Color, val wire: Boolean = false
)

private fun carPalette(): CarPalette = when (az.q07.launcher.data.AppSettings.launcherTheme) {
    LauncherTheme.FUTURE -> CarPalette(Color.Transparent, Color.Transparent, Color(0xFF67E8F9), Color.Transparent, Color.Transparent, Color.Transparent, Color.Transparent, wire = true)
    LauncherTheme.KLASSIK -> CarPalette(Color(0xFF5A4030), Color(0xFF1E140F), Color(0xFFD4AF37), Color(0xFF1A120C), Color(0xFF3A2A1C), Color(0xFF2A1C14), Color(0xFFCD7F32))
    LauncherTheme.MODERN -> CarPalette(Color(0xFFFFFFFF), Color(0xFFD1D5DB), Color(0xFF9CA3AF), Color(0xFF374151), Color(0xFF6B7280), Color(0xFF374151), Color(0xFFD1D5DB))
    LauncherTheme.ELEGANT -> CarPalette(Color(0xFF4A5468), Color(0xFF1F2533), Color(0xFFFDBA74), Color(0xFF0E1118), Color(0xFF3A3F52), Color(0xFF1F293D), Color(0xFF94A3B8))
    else -> CarPalette(Color(0xFF5A6B80), Color(0xFF1C2430), Color(0xFF8FD3FF), Color(0xFF0E1A26), Color(0xFF2B4C66), Color(0xFF2A313B), Color(0xFF8FA3B8))
}
