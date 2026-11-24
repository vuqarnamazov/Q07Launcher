package az.q07.launcher.data

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Baş qurğunun daxili mənbələrindən oxunan göstəricilər (OBD adapteri olmadan) */
data class TelemetryState(
    val speedKmh: Float? = null,
    val batteryPercent: Int? = null,
    val rangeKm: Int? = null,
    val innerTempC: Float? = null,
    val outerTempC: Float? = null,
    val gear: String? = null,
    /** Əlavə: ani doldurma/sərf gücü, kW (EV_BATTERY_INSTANTANEOUS_CHARGE_RATE) */
    val chargeRateKw: Float? = null,
    val lastUpdatedMillis: Long = 0L,
    /** CarProperty / Accessibility / Cache / OBD-Learned (bir neçəsi ola bilər: "CarProperty + Accessibility") */
    val sourceLabel: String = "Cache",
    /** OBD qoşulu deyilkən OBD skanından öyrənilmiş yollarla oxunan göstəricilər (açar → dəyər + mənbə) */
    val obdLearned: Map<String, LearnedReading> = emptyMap()
) {
    val isEmpty get() = speedKmh == null && batteryPercent == null && rangeKm == null &&
        innerTempC == null && outerTempC == null && gear == null && chargeRateKw == null
}

/**
 * Təhlükəsiz çoxmənbəli oxuyucu:
 *  1) CarPropertyManager (refleksiya ilə; icazə/dəstək yoxdursa null — heç vaxt çökmür)
 *  2) Xüsusi imkanlar xidməti (DriveModeService) — ekrandakı "km/h", "%", "km", "°C" mətnləri
 *  3) Son etibarlı dəyərlər şifrələnmiş yaddaşda (EncryptedSharedPreferences)
 * Prioritet: CarProperty > Accessibility > Cache. Hər sahə ayrıca saxlanır.
 */
object VehicleTelemetryReader {

    // Android Automotive VehiclePropertyIds
    private const val PERF_VEHICLE_SPEED = 0x11600207            // float, m/s
    private const val EV_BATTERY_LEVEL = 0x11600309              // float, Wh
    private const val INFO_EV_BATTERY_CAPACITY = 0x11600106      // float, Wh
    private const val EV_BATTERY_INSTANTANEOUS_CHARGE_RATE = 0x1160030C // float, mW
    private const val RANGE_REMAINING = 0x11600308               // float, m
    private const val ENV_OUTSIDE_TEMPERATURE = 0x11600703       // float, °C
    private const val HVAC_TEMPERATURE_CURRENT = 0x15600502      // float, °C (zona üzrə)
    private const val GEAR_SELECTION = 0x11400400
    private const val CURRENT_GEAR = 0x11400401
    private val HVAC_AREAS = intArrayOf(0, 0x1, 0x4, 0x31, 0x75, 0x49)

    private enum class Src(val label: String) { CAR("CarProperty"), A11Y("Accessibility"), CACHE("Cache") }
    private data class Field(val value: Any, val src: Src, val at: Long)

    private const val FRESH_MS = 15_000L
    private val fields = HashMap<String, Field>()
    private val _state = MutableStateFlow(TelemetryState())
    val state: StateFlow<TelemetryState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false
    private var prefs: SharedPreferences? = null
    @Volatile private var dirty = false
    @Volatile private var lastA11yScan = 0L

    /** İdempotent: MainActivity və xidmət çağırır */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            prefs = openPrefs(app)
            loadCache()
            CarApi.init(app)
            // OBD Skan Yaddaşı: OBD qoşulu ikən yolları öyrən, OBD yoxdursa öyrənilmiş yollarla oxu
            runCatching { ObdDiscoverySession.init(app) }
            runCatching { ObdFreeFallbackResolver.init(app) }
            while (isActive) {
                runCatching { pollCarProperty() }
                runCatching { ObdDiscoverySession.tick() }
                runCatching { ObdFreeFallbackResolver.poll() }
                publish()
                if (dirty) { dirty = false; runCatching { saveCache() } }
                delay(if (AppVisibility.visible) 1000L else 5000L)
            }
        }
    }

    // ---------------- 1) CarPropertyManager ----------------
    private fun num(id: Int, area: Int = 0): Float? = (CarApi.read(id, area) as? Number)?.toFloat()
        ?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun pollCarProperty() {
        num(PERF_VEHICLE_SPEED)?.let { put("speed", kotlin.math.abs(it) * 3.6f, Src.CAR) }
        val level = num(EV_BATTERY_LEVEL)
        val cap = num(INFO_EV_BATTERY_CAPACITY)
        if (level != null && cap != null && cap > 0f) put("battery", (level / cap * 100f).toInt().coerceIn(0, 100), Src.CAR)
        num(RANGE_REMAINING)?.let { if (it >= 0f) put("range", (it / 1000f).toInt(), Src.CAR) }
        num(ENV_OUTSIDE_TEMPERATURE)?.let { if (it in -50f..70f) put("outer", it, Src.CAR) }
        HVAC_AREAS.asSequence().mapNotNull { a -> num(HVAC_TEMPERATURE_CURRENT, a)?.takeIf { it in -40f..80f } }.firstOrNull()
            ?.let { put("inner", it, Src.CAR) }
        CarApi.gearName(CarApi.read(GEAR_SELECTION) ?: CarApi.read(CURRENT_GEAR))?.let { put("gear", it, Src.CAR) }
        num(EV_BATTERY_INSTANTANEOUS_CHARGE_RATE)?.let { put("rate", it / 1_000_000f, Src.CAR) }
    }

    // ---------------- 2) Xüsusi imkanlar xidməti ----------------
    private val NUM = Regex("^[-−]?\\d{1,4}([.,]\\d)?$")
    private val INLINE = Regex("([-−]?\\d{1,4}(?:[.,]\\d)?)\\s*(km/h|km/s|km|%|°C|℃)", RegexOption.IGNORE_CASE)
    private val GEAR_RE = Regex("^[PRND]$")
    private val INNER_WORDS = listOf("salon", "daxil", "inside", "inner", "interior", "车内", "室内")
    private val OUTER_WORDS = listOf("xarici", "çöl", "outside", "outer", "exterior", "车外", "室外")

    /**
     * DriveModeService hadisələrindən çağırılır (özü 1.5 san-də bir dəfədən çox işləmir).
     * Yalnız sistem pəncərələri (status zolağı) və zavod ekranları oxunur — başqa tətbiqlərin məzmunu yox.
     */
    /** Xidmət pəncərə siyahısını yalnız lazım olanda istəsin (hər hadisədə yox) */
    fun due(): Boolean = started && System.currentTimeMillis() - lastA11yScan >= 1500

    fun onAccessibility(windows: List<AccessibilityWindowInfo>?) {
        val now = System.currentTimeMillis()
        if (!started || now - lastA11yScan < 1500) return
        lastA11yScan = now
        runCatching {
            windows?.forEach { w ->
                val root = w.root ?: return@forEach
                val pkg = root.packageName?.toString() ?: ""
                val trusted = w.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    pkg == "com.android.systemui" || pkg.startsWith("com.incall.apps")
                if (trusted) parseTexts(collectTexts(root))
            }
        }
    }

    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited < 500) {
            val n = stack.removeLast(); visited++
            (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 40 }?.let { out.add(it) }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    private fun toF(s: String) = s.replace('−', '-').replace(',', '.').toFloatOrNull()

    private fun parseTexts(texts: List<String>) {
        texts.forEachIndexed { i, t ->
            // "42%", "239 km", "0 km/h", "22°C" bir mətndə
            INLINE.findAll(t).forEach { m -> handle(toF(m.groupValues[1]), m.groupValues[2].lowercase(), texts, i) }
            // "0" + ayrıca "km/h" qonşu mətnlərdə
            if (NUM.matches(t) && i + 1 < texts.size) {
                val unit = texts[i + 1].lowercase()
                if (unit in setOf("km/h", "km/s", "km", "%", "°c", "℃")) handle(toF(t), unit, texts, i)
            }
            if (GEAR_RE.matches(t)) put("gear", t, Src.A11Y)
        }
    }

    private fun handle(v: Float?, unit: String, texts: List<String>, i: Int) {
        v ?: return
        when (unit) {
            "km/h", "km/s" -> if (v in 0f..260f) put("speed", v, Src.A11Y)
            "%" -> if (v in 0f..100f) put("battery", v.toInt(), Src.A11Y)
            "km" -> if (v in 0f..1500f) put("range", v.toInt(), Src.A11Y)
            "°c", "℃" -> {
                if (v !in -40f..80f) return
                // Yalnız yanında aydın etiket olanda — salon/xarici qarışmasın
                val ctx = (maxOf(0, i - 2)..minOf(texts.lastIndex, i + 2)).joinToString(" ") { texts[it] }.lowercase()
                when {
                    INNER_WORDS.any { it in ctx } -> put("inner", v, Src.A11Y)
                    OUTER_WORDS.any { it in ctx } -> put("outer", v, Src.A11Y)
                }
            }
        }
    }

    // ---------------- birləşdirmə ----------------
    @Synchronized
    private fun put(key: String, value: Any, src: Src) {
        val now = System.currentTimeMillis()
        val old = fields[key]
        // Təzə CarProperty dəyərini ekran mətni üstələməsin
        if (src == Src.A11Y && old?.src == Src.CAR && now - old.at < FRESH_MS) return
        fields[key] = Field(value, src, now)
        dirty = true
    }

    @Synchronized
    private fun publish() {
        val now = System.currentTimeMillis()
        fun <T> get(k: String): T? = @Suppress("UNCHECKED_CAST") (fields[k]?.value as? T)
        val live = fields.values.filter { it.src != Src.CACHE && now - it.at < FRESH_MS }.map { it.src }.toSet()
        val label = when {
            live.isEmpty() -> Src.CACHE.label
            else -> listOf(Src.CAR, Src.A11Y).filter { it in live }.joinToString(" + ") { it.label }
        }
        // Sürət və ötürmə tez dəyişir — köhnə (keş) dəyəri göstərmək yanlış olar, yalnız təzəsi
        fun fresh(k: String) = fields[k]?.let { it.src != Src.CACHE && now - it.at < FRESH_MS } == true
        // OBD-siz rejim: öz mənbələrdə olmayan sahələri OBD skanından öyrənilmiş yollarla tamamla
        val learned = ObdFreeFallbackResolver.readings.value
        fun liveLearned(k: String) = learned[k]?.takeIf { it.source != LearnedSource.CACHE }
        val lSpeed = if (fresh("speed")) null else liveLearned("speed")
        val lBattery = if (fields["battery"] != null) null else (learned["soc"] ?: learned["soc_hpcm"])
        val lOuter = if (fields["outer"] != null) null else learned["ambient"]
        val usedLearned = listOfNotNull(lSpeed, lBattery, lOuter)
        val s = TelemetryState(
            speedKmh = if (fresh("speed")) get("speed") else lSpeed?.value?.toFloat(),
            batteryPercent = get<Int>("battery") ?: lBattery?.value?.toInt()?.coerceIn(0, 100),
            rangeKm = get("range"),
            innerTempC = get("inner"),
            outerTempC = get<Float>("outer") ?: lOuter?.value?.toFloat(),
            gear = if (fresh("gear")) get("gear") else null,
            chargeRateKw = get("rate"),
            lastUpdatedMillis = maxOf(fields.values.maxOfOrNull { it.at } ?: 0L, usedLearned.maxOfOrNull { it.at } ?: 0L),
            sourceLabel = if (usedLearned.isEmpty()) label
                else (if (live.isEmpty()) "" else "$label + ") + usedLearned.map { it.source.label }.distinct().joinToString(" + "),
            obdLearned = learned
        )
        if (s != _state.value) _state.value = s
    }

    // ---------------- 3) şifrələnmiş keş ----------------
    private fun openPrefs(c: Context): SharedPreferences = try {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "q07_telemetry_secure", alias, c,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // Keystore problemi olsa belə tətbiq işləsin
        SelfDiag.log("telemetry", "şifrələnmiş yaddaş açılmadı: ${t.javaClass.simpleName}")
        c.getSharedPreferences("q07_telemetry_fallback", Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun saveCache() {
        val e = prefs?.edit() ?: return
        fields.forEach { (k, f) ->
            when (val v = f.value) {
                is Float -> e.putFloat("f_$k", v)
                is Int -> e.putInt("i_$k", v)
                is String -> e.putString("s_$k", v)
            }
            e.putLong("t_$k", f.at)
        }
        e.apply()
    }

    @Synchronized
    private fun loadCache() {
        val p = prefs ?: return
        runCatching {
            p.all.keys.filter { it.length > 2 && it[1] == '_' && it[0] in "fis" }.forEach { k ->
                val name = k.substring(2)
                val v: Any? = when (k[0]) { 'f' -> p.getFloat(k, 0f); 'i' -> p.getInt(k, 0); else -> p.getString(k, null) }
                if (v != null) fields[name] = Field(v, Src.CACHE, p.getLong("t_$name", 0L))
            }
        }
        publish()
    }
}
