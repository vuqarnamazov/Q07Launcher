package az.q07.launcher.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.WeatherData
import az.q07.launcher.data.WeatherRepo
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** Hava — Open-Meteo (açar tələb etmir). Şəhər: Ayarlar → Hava. */
object WeatherModule : LauncherModule {
    override val id = "weather"
    override val title = "Hava"
    override val shortTitle = "Hava"
    override val icon: ImageVector = Icons.Filled.WbSunny
    override val accent: Color = Color(0xFFFFC94D)

    private fun describe(code: Int) = WeatherRepo.describe(code)

    @Composable
    private fun rememberWeather(): WeatherData {
        val context = LocalContext.current
        DisposableEffect(Unit) {
            WeatherRepo.acquire(context)
            onDispose { WeatherRepo.release() }
        }
        val w by WeatherRepo.state.collectAsState()
        return w
    }

    @Composable
    override fun Summary() {
        val w = rememberWeather()
        if (!w.ok) {
            Text(w.error ?: "Yüklənir…", fontSize = 15.sp, color = CarColors.TextSecondary)
            return
        }
        val (emoji, text) = describe(w.code)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 44.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${Math.round(w.temp)}°C  $text",
                    fontSize = 26.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary
                )
                Text(
                    "${w.city} · külək ${Math.round(w.wind)} km/s · rütubət ${w.humidity}%",
                    fontSize = 14.sp, color = CarColors.TextSecondary
                )
            }
        }
    }

    @Composable
    override fun Expanded() {
        val w = rememberWeather()
        if (!w.ok) return
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE", Locale.forLanguageTag("az-AZ"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            w.days.forEach { d ->
                val day = runCatching { outFmt.format(inFmt.parse(d.date)!!) }.getOrDefault(d.date)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CarColors.Panel)
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(day.replaceFirstChar { it.titlecase() }, fontSize = 14.sp, color = CarColors.TextSecondary)
                    Text(describe(d.code).first, fontSize = 28.sp)
                    Text("${Math.round(d.max)}° / ${Math.round(d.min)}°", fontSize = 15.sp, color = CarColors.TextPrimary)
                }
            }
        }
    }
}
