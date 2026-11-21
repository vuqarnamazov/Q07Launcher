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
import az.q07.launcher.data.AppSettings
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

private data class DayForecast(val date: String, val code: Int, val max: Double, val min: Double)
private data class WeatherData(
    val ok: Boolean = false,
    val error: String? = null,
    val temp: Double = 0.0,
    val code: Int = 0,
    val wind: Double = 0.0,
    val humidity: Int = 0,
    val days: List<DayForecast> = emptyList(),
    val city: String = ""
)

/** Hava — Open-Meteo (açar tələb etmir). Şəhər: Ayarlar → Hava. */
object WeatherModule : LauncherModule {
    override val id = "weather"
    override val title = "Hava"
    override val icon: ImageVector = Icons.Filled.WbSunny
    override val accent: Color = Color(0xFFFFC94D)

    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Açıq"
        1, 2 -> "🌤️" to "Az buludlu"
        3 -> "☁️" to "Buludlu"
        45, 48 -> "🌫️" to "Duman"
        in 51..57 -> "🌦️" to "Çiskin"
        in 61..67 -> "🌧️" to "Yağış"
        in 71..77 -> "❄️" to "Qar"
        in 80..82 -> "🌧️" to "Leysan"
        85, 86 -> "🌨️" to "Qar yağışı"
        in 95..99 -> "⛈️" to "İldırım"
        else -> "🌡️" to "—"
    }

    private fun fetch(): WeatherData {
        val c = AppSettings.city
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${c.lat}&longitude=${c.lon}" +
                    "&current=temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val cur = o.getJSONObject("current")
            val d = o.getJSONObject("daily")
            val times = d.getJSONArray("time")
            val days = (0 until times.length()).map { i ->
                DayForecast(
                    times.getString(i),
                    d.getJSONArray("weather_code").getInt(i),
                    d.getJSONArray("temperature_2m_max").getDouble(i),
                    d.getJSONArray("temperature_2m_min").getDouble(i)
                )
            }
            WeatherData(
                ok = true,
                temp = cur.getDouble("temperature_2m"),
                code = cur.getInt("weather_code"),
                wind = cur.getDouble("wind_speed_10m"),
                humidity = cur.getInt("relative_humidity_2m"),
                days = days,
                city = c.name
            )
        } catch (e: Exception) {
            WeatherData(error = "İnternet yoxdur və ya xidmət cavab vermir", city = c.name)
        }
    }

    @Composable
    private fun rememberWeather(): WeatherData {
        val cityIndex = AppSettings.cityIndex
        val w by produceState(WeatherData(city = AppSettings.city.name), cityIndex) {
            while (true) {
                val r = withContext(Dispatchers.IO) { fetch() }
                value = r
                delay(if (r.ok) 15 * 60_000L else 60_000L)
            }
        }
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
