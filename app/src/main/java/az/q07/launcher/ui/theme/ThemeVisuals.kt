package az.q07.launcher.ui.theme

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppSettings
import az.q07.launcher.data.MediaRepo
import az.q07.launcher.data.QuickActions
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/** Mövzuya görə görünüş seçimi üçün qısa yollar */
object Look {
    val theme get() = AppSettings.launcherTheme
    val future get() = theme == LauncherTheme.FUTURE
    val klassik get() = theme == LauncherTheme.KLASSIK
    val modern get() = theme == LauncherTheme.MODERN
    val elegant get() = theme == LauncherTheme.ELEGANT
    /** Solda analoq saat göstərilsin */
    val analogClock get() = klassik || modern || elegant
}

/** Fon: mövzu gradienti + Klassik üçün karbon toxuması */
fun Modifier.themeBackground(): Modifier = this
    .background(CarColors.BackgroundBrush)
    .drawBehind {
        if (AppSettings.launcherTheme == LauncherTheme.KLASSIK) {
            val step = 7.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(Color(0x14FFFFFF), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1f)
                x += step
            }
        }
    }

@Composable
private fun rememberNow(periodMs: Long = 1000L): Long =
    produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(periodMs - value % periodMs) }
    }.value

/** Analoq saat (Klassik / Modern / Elegant) */
@Composable
fun AnalogClock(size: Dp, modifier: Modifier = Modifier) {
    val now = rememberNow(1000L)
    val c = CarColors.theme
    val accent = CarColors.Accent
    Canvas(modifier.size(size)) {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val r = this.size.minDimension / 2
        val ctr = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(if (c.isLight) Color.White else Color(0x22000000), r, ctr)
        drawCircle(if (Look.klassik) accent else c.textPrimary.copy(alpha = 0.6f), r, ctr, style = Stroke(r * 0.05f))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90)
            val o = Offset(ctr.x + cos(a).toFloat() * r * 0.86f, ctr.y + sin(a).toFloat() * r * 0.86f)
            val inner = Offset(ctr.x + cos(a).toFloat() * r * (if (i % 3 == 0) 0.72f else 0.78f), ctr.y + sin(a).toFloat() * r * (if (i % 3 == 0) 0.72f else 0.78f))
            drawLine(c.textPrimary.copy(alpha = 0.7f), inner, o, strokeWidth = r * 0.035f, cap = StrokeCap.Round)
        }
        fun hand(deg: Float, len: Float, w: Float, col: Color) {
            val a = Math.toRadians(deg - 90.0)
            drawLine(col, ctr, Offset(ctr.x + cos(a).toFloat() * r * len, ctr.y + sin(a).toFloat() * r * len), strokeWidth = r * w, cap = StrokeCap.Round)
        }
        val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
        val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        hand(h * 30f, 0.48f, 0.07f, c.textPrimary)
        hand(m * 6f, 0.70f, 0.05f, c.textPrimary)
        hand(cal.get(Calendar.SECOND) * 6f, 0.78f, 0.02f, accent)
        drawCircle(accent, r * 0.06f, ctr)
    }
}

/** Klassik: analoq göstərici (sürət, batareya) */
@Composable
fun AnalogGauge(value: Double?, max: Double, label: String, unit: String, modifier: Modifier = Modifier) {
    val c = CarColors.theme
    val accent = CarColors.Accent
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * 0.94f
            val ctr = Offset(size.width / 2, size.height / 2)
            drawCircle(Color(0xFF0F0B08), r, ctr)
            drawCircle(accent, r, ctr, style = Stroke(r * 0.06f))
            drawCircle(c.textPrimary.copy(alpha = 0.35f), r * 0.9f, ctr, style = Stroke(r * 0.01f))
            // 225°-dən 315°-ə qədər şkala (270°)
            for (i in 0..20) {
                val deg = 135f + i * 13.5f
                val a = Math.toRadians(deg.toDouble())
                val big = i % 5 == 0
                val o = Offset(ctr.x + cos(a).toFloat() * r * 0.84f, ctr.y + sin(a).toFloat() * r * 0.84f)
                val inn = Offset(ctr.x + cos(a).toFloat() * r * (if (big) 0.70f else 0.77f), ctr.y + sin(a).toFloat() * r * (if (big) 0.70f else 0.77f))
                drawLine(c.textPrimary, inn, o, strokeWidth = if (big) r * 0.03f else r * 0.015f)
            }
            val f = ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat()
            rotate(135f + 270f * f, ctr) {
                drawLine(accent, ctr, Offset(ctr.x + r * 0.72f, ctr.y), strokeWidth = r * 0.04f, cap = StrokeCap.Round)
            }
            drawCircle(c.textPrimary, r * 0.07f, ctr)
        }
        Column(Modifier.padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value?.let { "%.0f".format(it) } ?: "—", fontSize = 26.sp, color = CarColors.TextPrimary)
            Text("$label · $unit", fontSize = 12.sp, color = CarColors.TextSecondary)
        }
    }
}

/** Future: telemetriya halqası */
@Composable
fun RingGauge(value: Double?, max: Double, label: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val w = size.minDimension * 0.09f
            drawArc(Color(0x331F2937), 135f, 270f, false, style = Stroke(w, cap = StrokeCap.Round))
            val f = ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat()
            drawArc(color, 135f, 270f * f, false, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value?.let { "%.0f".format(it) } ?: "—", fontSize = 20.sp, color = CarColors.TextPrimary)
            Text(label, fontSize = 11.sp, color = CarColors.TextSecondary)
        }
    }
}

/** Future: son dəyərlərin xətti qrafiki */
@Composable
fun Sparkline(points: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val mn = points.min(); val mx = points.max().coerceAtLeast(mn + 1)
        val dx = size.width / (points.size - 1)
        var prev: Offset? = null
        points.forEachIndexed { i, v ->
            val p = Offset(i * dx, size.height - ((v - mn) / (mx - mn)).toFloat() * size.height)
            prev?.let { drawLine(color, it, p, strokeWidth = 3f, cap = StrokeCap.Round) }
            prev = p
        }
    }
}

/** Elegant: musiqi çalanda tərpənən səs dalğası */
@Composable
fun WaveBars(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { MediaRepo.acquire(context); onDispose { MediaRepo.release() } }
    val np by MediaRepo.state.collectAsState()
    val t by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "w"
    )
    val col = CarColors.Accent
    Canvas(modifier) {
        val n = 15
        val gap = size.width / n
        for (i in 0 until n) {
            val base = 0.25f + 0.2f * sin(i * 0.9f)
            val amp = if (np.playing) (0.5f + 0.45f * sin(t + i * 0.7f)) else 0.08f
            val h = size.height * (base * 0.4f + amp * 0.6f).coerceIn(0.06f, 1f)
            val x = gap * i + gap / 2
            drawLine(col, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), strokeWidth = gap * 0.45f, cap = StrokeCap.Round)
        }
    }
}

/** Elegant: gün batımı səhnəsi (maşının arxasında) */
fun Modifier.sunsetBackdrop(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(
        0f to Color(0xFF2E3446), 0.42f to Color(0xFF6B5262), 0.58f to Color(0xFFB57A55),
        0.60f to Color(0xFF1A1D26), 1f to Color(0xFF0E1118)
    ))
    drawCircle(Color(0x66FDBA74), size.minDimension * 0.10f, Offset(size.width * 0.72f, size.height * 0.56f))
    // yol xətti
    drawLine(Color(0x55FB923C), Offset(0f, size.height * 0.92f), Offset(size.width, size.height * 0.80f), strokeWidth = 2f)
}

/** Media düymələri — aktiv media sessiyasını idarə edir, yoxdursa sistem media düyməsi göndərir */
private fun mediaKey(context: Context, code: Int) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val t = SystemClock.uptimeMillis()
    am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
    am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
}

@Composable
fun MediaStrip(modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(Unit) { MediaRepo.acquire(context); onDispose { MediaRepo.release() } }
    val np by MediaRepo.state.collectAsState()
    val tc = np.controller?.transportControls
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(CarColors.PanelAlt)
            .border(1.dp, CarColors.Border, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showTitle) Column(Modifier.weight(1f)) {
            Text(if (np.active) np.title.ifBlank { np.appLabel } else "Musiqi", fontSize = 14.sp,
                color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (np.active && np.artist.isNotBlank()) Text(np.artist, fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 1)
        }
        @Composable fun btn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, main: Boolean = false, onClick: () -> Unit) {
            Box(
                Modifier.size(if (main) 44.dp else 36.dp).clip(CircleShape)
                    .background(if (main) CarColors.Accent else Color.Transparent).clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) { Icon(icon, desc, tint = if (main) (if (CarColors.theme.isLight) Color.White else Color.Black) else CarColors.TextPrimary) }
        }
        btn(Icons.Filled.SkipPrevious, "Əvvəlki") { tc?.skipToPrevious() ?: mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        btn(if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Oynat/Pauza", main = true) {
            if (tc != null) { if (np.playing) tc.pause() else tc.play() } else mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        btn(Icons.Filled.SkipNext, "Növbəti") { tc?.skipToNext() ?: mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) }
    }
}

/** Modern: kiçik kart (hava, naviqasiya) */
@Composable
fun InfoCard(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(CarColors.Panel)
            .border(1.dp, CarColors.Border, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(title, fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 1)
        Text(value, fontSize = 18.sp, color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NavCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(CarColors.Panel)
            .border(1.dp, CarColors.Border, RoundedCornerShape(16.dp))
            .clickable { QuickActions.openNavigation(context) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Navigation, null, tint = CarColors.Accent, modifier = Modifier.size(26.dp))
        Column {
            Text("Naviqasiya", fontSize = 12.sp, color = CarColors.TextSecondary)
            Text("Waze aç", fontSize = 18.sp, color = CarColors.TextPrimary, maxLines = 1)
        }
    }
}
