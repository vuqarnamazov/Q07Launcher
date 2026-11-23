package az.q07.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.TelemetryState
import az.q07.launcher.data.VehicleTelemetryReader
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.delay

private const val DASH = "--"

/** "Daxili Göstəricilər" — OBD adapteri olmadan baş qurğunun öz mənbələrindən */
@Composable
fun InternalIndicatorsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VehicleTelemetryReader.start(context) }
    val t by VehicleTelemetryReader.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarColors.Panel)
            .border(1.dp, CarColors.Border, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daxili Göstəricilər", fontSize = 18.sp, color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
            val live = t.sourceLabel != "Cache"
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (live) CarColors.Green else CarColors.TextSecondary))
            Spacer(Modifier.size(6.dp))
            Text(sourceText(t, now), fontSize = 12.sp, color = CarColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val items = listOf(
            Triple("Sürət", t.speedKmh?.let { "%.0f".format(it) }, "km/s"),
            Triple("Batareya", t.batteryPercent?.toString(), "%"),
            Triple("Yürüş", t.rangeKm?.toString(), "km"),
            Triple("Salon", t.innerTempC?.let { "%.1f".format(it) }, "°C"),
            Triple("Xarici", t.outerTempC?.let { "%.1f".format(it) }, "°C"),
            Triple("Ötürmə", t.gear, ""),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Eni nə olursa olsun kəsilməsin: 2, 3 və ya 6 sütun
            val cols = when { maxWidth > 900.dp -> 6; maxWidth > 420.dp -> 3; else -> 2 }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, value, unit) -> Cell(label, value, unit, Modifier.weight(1f)) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(label: String, value: String?, unit: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(CarColors.PanelAlt).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 13.sp, color = CarColors.TextSecondary, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value ?: DASH, fontSize = 24.sp, maxLines = 1,
                color = if (value == null) CarColors.TextSecondary else CarColors.TextPrimary)
            if (value != null && unit.isNotEmpty()) Text(" $unit", fontSize = 13.sp, color = CarColors.TextSecondary,
                modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
        }
    }
}

private fun sourceText(t: TelemetryState, now: Long): String {
    if (t.isEmpty) return "məlumat yoxdur"
    val ago = if (t.lastUpdatedMillis > 0) ((now - t.lastUpdatedMillis) / 1000).coerceAtLeast(0) else null
    val age = when {
        ago == null -> ""
        ago < 5 -> " · indi"
        ago < 120 -> " · ${ago} san əvvəl"
        ago < 7200 -> " · ${ago / 60} dəq əvvəl"
        else -> " · ${ago / 3600} saat əvvəl"
    }
    return t.sourceLabel + age
}
