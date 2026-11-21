package az.q07.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import az.q07.launcher.media.MediaListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Launcher ekranda görünürmü — görünməyəndə sorğular dayandırılır (enerji və sürət üçün) */
object AppVisibility {
    @Volatile
    var visible: Boolean = true
}

/**
 * Bir neçə yerdə istifadə olunan məlumatlar üçün TƏK mənbə.
 * Hər biri yalnız ekranda istifadəçisi (abunəçisi) olanda işləyir → launcher yüngül qalır.
 */
abstract class SharedPoller<T>(initial: T) {
    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var users = 0
    protected val _state = MutableStateFlow(initial)
    val state: StateFlow<T> = _state

    protected abstract suspend fun run(context: Context)

    @Synchronized
    fun acquire(context: Context) {
        users++
        if (job?.isActive != true) {
            val app = context.applicationContext
            job = scope.launch { run(app) }
        }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { job?.cancel(); job = null }
    }

    @Synchronized
    fun restart(context: Context) {
        if (users > 0) {
            job?.cancel()
            val app = context.applicationContext
            job = scope.launch { run(app) }
        }
    }
}

// ───────────────────────── Media ─────────────────────────

data class NowPlaying(
    val permission: Boolean = true,
    val active: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val appLabel: String = "",
    val packageName: String = "",
    val playing: Boolean = false,
    val art: Bitmap? = null,
    val controller: MediaController? = null
)

object MediaRepo : SharedPoller<NowPlaying>(NowPlaying()) {
    fun hasPermission(c: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    private fun read(c: Context): NowPlaying {
        if (!hasPermission(c)) return NowPlaying(permission = false)
        return try {
            val msm = c.getSystemService(MediaSessionManager::class.java)
            val sessions = msm.getActiveSessions(ComponentName(c, MediaListenerService::class.java))
            val ctl = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
                ?: return NowPlaying()
            val md = ctl.metadata
            val pm = c.packageManager
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(ctl.packageName, 0)).toString()
            }.getOrDefault(ctl.packageName)
            NowPlaying(
                active = true,
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                appLabel = label,
                packageName = ctl.packageName,
                playing = ctl.playbackState?.state == PlaybackState.STATE_PLAYING,
                art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                controller = ctl
            )
        } catch (e: SecurityException) {
            NowPlaying(permission = false)
        }
    }

    override suspend fun run(context: Context) {
        while (true) {
            if (!AppVisibility.visible) { delay(2000); continue }
            val v = read(context)
            val old = _state.value
            // Eyni məlumatda state yeniləmə → lazımsız recomposition olmur
            if (v.title != old.title || v.playing != old.playing || v.packageName != old.packageName ||
                v.permission != old.permission || v.active != old.active || v.art !== old.art
            ) _state.value = v
            else if (v.controller != null && old.controller == null) _state.value = v
            delay(1500)
        }
    }

    fun togglePlay() {
        val s = _state.value
        val tc = s.controller?.transportControls ?: return
        if (s.playing) tc.pause() else tc.play()
    }
}

// ───────────────────────── Hava ─────────────────────────

data class DayForecast(val date: String, val code: Int, val max: Double, val min: Double)
data class WeatherData(
    val ok: Boolean = false,
    val error: String? = null,
    val temp: Double = 0.0,
    val code: Int = 0,
    val wind: Double = 0.0,
    val humidity: Int = 0,
    val days: List<DayForecast> = emptyList(),
    val city: String = ""
)

object WeatherRepo : SharedPoller<WeatherData>(WeatherData()) {
    fun describe(code: Int): Pair<String, String> = when (code) {
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
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val cur = o.getJSONObject("current")
            val d = o.getJSONObject("daily")
            val times = d.getJSONArray("time")
            WeatherData(
                ok = true,
                temp = cur.getDouble("temperature_2m"),
                code = cur.getInt("weather_code"),
                wind = cur.getDouble("wind_speed_10m"),
                humidity = cur.getInt("relative_humidity_2m"),
                days = (0 until times.length()).map { i ->
                    DayForecast(
                        times.getString(i),
                        d.getJSONArray("weather_code").getInt(i),
                        d.getJSONArray("temperature_2m_max").getDouble(i),
                        d.getJSONArray("temperature_2m_min").getDouble(i)
                    )
                },
                city = c.name
            )
        } catch (e: Exception) {
            WeatherData(error = "İnternet yoxdur və ya xidmət cavab vermir", city = c.name)
        }
    }

    override suspend fun run(context: Context) {
        while (true) {
            // Son 15 dəqiqədə alınıbsa və şəhər eynidirsə yenidən yükləmə
            val cur = _state.value
            val fresh = cur.ok && cur.city == AppSettings.city.name &&
                System.currentTimeMillis() - lastFetch < 15 * 60_000L
            if (!fresh) {
                val r = kotlinx.coroutines.withContext(Dispatchers.IO) { fetch() }
                _state.value = r
                if (r.ok) lastFetch = System.currentTimeMillis()
            }
            delay(if (_state.value.ok) 60_000L else 30_000L)
        }
    }

    private var lastFetch = 0L

    fun cityChanged(context: Context) {
        lastFetch = 0
        restart(context)
    }
}

// ───────────────────── Son açılan tətbiq ─────────────────────

object ForegroundApp {
    /** «İstifadə statistikası» icazəsi varmı (ADB: appops set az.q07.launcher GET_USAGE_STATS allow) */
    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Son 10 dəqiqədə ön plana çıxan son tətbiq (launcher-in özü və sistem UI-si çıxılmaqla) */
    fun lastPackage(c: Context): String? {
        if (!hasAccess(c)) return null
        return try {
            val usm = c.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10 * 60_000L, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    e.packageName != c.packageName &&
                    !e.packageName.startsWith("com.android.systemui") &&
                    !e.packageName.contains("launcher", ignoreCase = true)
                ) last = e.packageName
            }
            last
        } catch (t: Throwable) {
            null
        }
    }
}
