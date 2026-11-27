package az.q07.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AdasContact
import az.q07.launcher.data.SmartAdasRadar
import az.q07.launcher.ui.theme.CarColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kiçik "Smart ADAS Radar" kartı — baş ekranın sağında, tətbiqlərin üstündə.
 * Toxunuş: tam radar pəncərəsi (siyahı + görünməz rejim).
 */
@Composable
fun SmartAdasRadarCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val contacts by SmartAdasRadar.contacts.collectAsState()
    val active by SmartAdasRadar.active.collectAsState()
    val message by SmartAdasRadar.message.collectAsState()
    var open by remember { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SmartAdasRadar.ensureStarted(context)
    }
    DisposableEffect(Unit) {
        SmartAdasRadar.acquire(context)
        onDispose { SmartAdasRadar.release() }
    }
    val accent = CarColors.Accent
    val sweep = rememberSweep()

    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CarColors.PanelAlt)
            .border(1.dp, CarColors.Border, RoundedCornerShape(18.dp))
            .clickable {
                if (!SmartAdasRadar.hasPermissions(context)) perms.launch(SmartAdasRadar.requiredPermissions())
                else open = true
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Smart ADAS Radar", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = CarColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1
            )
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (active) CarColors.Green else CarColors.TextSecondary)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${contacts.size}", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = if (contacts.isEmpty()) CarColors.TextSecondary else accent
            )
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 18.sp, color = CarColors.TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadarView(contactList = contacts, sweepDeg = sweep, accent = accent, size = 74.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                Text(
                    if (active) "Yaxınlıqda ${contacts.size} Q07"
                    else (message ?: "Radar hazırlanır…"),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary, maxLines = 2
                )
                Text(
                    if (SmartAdasRadar.invisible) "Görünməz rejim — siz radara düşmürsünüz"
                    else "BLE · ≈200 m · görünən",
                    fontSize = 11.sp, color = CarColors.TextSecondary, maxLines = 2
                )
                if (contacts.isNotEmpty()) {
                    Text(
                        contacts.take(2).joinToString(" · ") { "${it.plate}  ${it.distanceM} m" },
                        fontSize = 11.sp, color = accent, maxLines = 2
                    )
                } else {
                    Text("10 km klub əhatəsi əvəzinə real BLE məsafəsi", fontSize = 10.sp,
                        color = CarColors.TextSecondary, maxLines = 2)
                }
            }
        }
    }

    if (open) SmartAdasRadarDialog(onClose = { open = false })
}

@Composable
private fun rememberSweep(): Float {
    val transition = rememberInfiniteTransition(label = "adas")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )
    return sweep
}

@Composable
private fun RadarView(
    contactList: List<AdasContact>,
    sweepDeg: Float,
    accent: Color,
    size: Dp
) {
    Canvas(Modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        // fon işığı
        drawCircle(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = center, radius = radius
            ),
            radius = radius, center = center
        )
        // halqalar + xaç
        listOf(0.35f, 0.65f, 1f).forEach { k ->
            drawCircle(
                color = accent.copy(alpha = 0.28f), radius = radius * k, center = center,
                style = Stroke(width = 1.2f)
            )
        }
        drawLine(accent.copy(alpha = 0.14f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y))
        drawLine(accent.copy(alpha = 0.14f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius))

        // fırlanan sektor
        drawArc(
            color = accent.copy(alpha = 0.20f),
            startAngle = sweepDeg - 60f, sweepAngle = 60f, useCenter = true
        )
        drawLine(
            color = accent.copy(alpha = 0.85f),
            start = center,
            end = Offset(
                center.x + cos(Math.toRadians(sweepDeg.toDouble())).toFloat() * radius,
                center.y + sin(Math.toRadians(sweepDeg.toDouble())).toFloat() * radius
            ),
            strokeWidth = 1.6f
        )

        // bliplər
        contactList.forEach { c ->
            val a = Math.toRadians(c.bearingDeg.toDouble())
            val r = (c.distanceM.toFloat() / 200f).coerceIn(0.05f, 1f) * radius
            val p = Offset(center.x + cos(a).toFloat() * r, center.y + sin(a).toFloat() * r)
            val near = c.distanceM <= 30
            drawCircle(accent.copy(alpha = 0.16f), radius = if (near) 9f else 7f, center = p)
            drawCircle(accent.copy(alpha = if (near) 0.95f else 0.6f), radius = if (near) 3.4f else 2.6f, center = p)
        }

        // öz maşın
        val car = Path().apply {
            moveTo(center.x, center.y - 6f)
            lineTo(center.x + 4.5f, center.y + 5f)
            lineTo(center.x - 4.5f, center.y + 5f)
            close()
        }
        drawPath(car, Color.White)
    }
}

@Composable
private fun SmartAdasRadarDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val contacts by SmartAdasRadar.contacts.collectAsState()
    val active by SmartAdasRadar.active.collectAsState()
    val message by SmartAdasRadar.message.collectAsState()
    var invisible by remember { mutableStateOf(SmartAdasRadar.invisible) }
    val sweep = rememberSweep()
    val accent = CarColors.Accent

    ScaledDialog(onDismissRequest = onClose) {
      BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxW = minOf(maxWidth * 0.86f, 760.dp)
        Column(
            Modifier
                .width(boxW)
                .clip(RoundedCornerShape(24.dp))
                .background(CarColors.Panel)
                .border(1.dp, CarColors.Border, RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smart ADAS Radar", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CarColors.TextPrimary)
                    Text("BLE klub radarı · ≈200 m · L2 protokolu (F007)",
                        fontSize = 13.sp, color = CarColors.TextSecondary)
                }
                TextButton(onClick = onClose) { Text("Bağla", fontSize = 18.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                RadarView(contactList = contacts, sweepDeg = sweep, accent = accent, size = 260.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Görünməz rejim", fontSize = 15.sp, color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = invisible, onCheckedChange = {
                            invisible = it
                            SmartAdasRadar.setInvisible(context, it)
                        })
                    }
                    Text(
                        when {
                            !active -> message ?: "Radar işə düşmür"
                            contacts.isEmpty() -> "Yaxınlıqda Q07 görünmür — digər maşında da Q07 Launcher açıq olmalıdır."
                            else -> "Yaxınlıqda ${contacts.size} Q07 (siyahı aşağıda)"
                        },
                        fontSize = 13.sp, color = CarColors.TextSecondary
                    )
                    Box(
                        Modifier.fillMaxWidth().height(1.dp).background(CarColors.Border)
                    )
                    if (contacts.isEmpty()) {
                        Text("Radar real BLE siqnalları ilə işləyir; məsafə RSSI-yə görə təxmini hesablanır.",
                            fontSize = 12.sp, color = CarColors.TextSecondary)
                    } else {
                        Column(
                            Modifier.fillMaxWidth().height(180.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            contacts.forEach { c ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .background(CarColors.PanelAlt).padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(c.plate, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
                                    Text("${c.distanceM} m", fontSize = 14.sp, color = accent)
                                    Spacer(Modifier.width(10.dp))
                                    Text("${c.rssi} dBm", fontSize = 12.sp, color = CarColors.TextSecondary)
                                }
                            }
                        }
                    }
                    Text(
                        "Qeyd: BLE istiqamət vermir — bliplərin bucağı təxminidir, məsafə isə real siqnal gücündən hesablanır.",
                        fontSize = 11.sp, color = CarColors.TextSecondary
                    )
                }
            }
        }
      }
    }
}
