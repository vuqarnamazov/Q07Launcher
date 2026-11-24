package az.q07.launcher.data

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.round

/*
 * OBD Skan Yaddaşı (OBD-siz rejim)
 *
 *  ObdDiscoverySession     — OBD2 qoşulu ikən hər göstəricini (key, label, sampleValue, unit, sourcePackage,
 *                            nodeOrPropertyPath) yazır və eyni anda ekranda / CarPropertyManager-də eyni dəyəri
 *                            göstərən daxili yolları tapıb onunla əlaqələndirir → obd_discovered_paths.json
 *  ObdFreeFallbackResolver — OBD yoxdursa öyrənilmiş yollardan oxuyur:
 *                            ekran (OBD-Learned / UI) → xassə (OBD-Learned / Prop) → son dəyər (OBD-Learned / Cache) → "--"
 *  ObdDiscoveryBridge      — xüsusi imkan xidmətindən (DriveModeService) gələn ekran rəqəmlərinin son görüntüsü
 *
 * Əlaqə yalnız təsadüf olmadıqda "öyrənilmiş" sayılır: ən azı 3 uyğunluq, ≥70% dəqiqlik və ən az 2 FƏRQLİ dəyərdə
 * uyğunluq (sabit "0" hər yerdə üst-üstə düşür). Q07-nin VHAL-ı əsasən sintetik dəyər verir — belə xassələr
 * OBD ilə uyğun gəlmədiyi üçün özləri kənarda qalır.
 */

enum class LinkKind { UI, PROP }

/** Göstərici ilə əlaqələndirilmiş daxili yol */
data class LinkedPath(
    val kind: LinkKind,
    /** UI: "ui:<paket>#<viewId>" / "ui:<paket>~<yanındakı yazı>" / "ui:<paket>@<sinif>:<yol>"; PROP: "prop:0x11600207@0" */
    val path: String,
    val sourcePackage: String,
    /** OBD dəyəri ≈ daxili dəyər × scale */
    val scale: Double = 1.0,
    val matches: Int = 0,
    val misses: Int = 0,
    val distinct: List<Double> = emptyList(),
    val lastValue: Double? = null,
    val lastAt: Long = 0L
) {
    val ratio: Double get() = if (matches + misses == 0) 0.0 else matches.toDouble() / (matches + misses)
    val strong: Boolean get() = matches >= 3 && ratio >= 0.7 && distinct.size >= 2
    /** sabit, amma çox dəfə dəqiq uyğun gələn (məs. çöl temperaturu dayanıqlı ikən) */
    val weak: Boolean get() = !strong && matches >= 6 && ratio >= 0.9 && distinct.any { it != 0.0 }
    val learned: Boolean get() = strong || weak
    val score: Double get() = (if (strong) 1000.0 else if (weak) 500.0 else 0.0) + ratio * 100 + minOf(matches, 50) + distinct.size * 5

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("path", path).put("pkg", sourcePackage)
        .put("scale", scale).put("m", matches).put("x", misses).put("d", JSONArray(distinct))
        .put("lv", lastValue ?: JSONObject.NULL).put("la", lastAt)

    companion object {
        fun fromJson(o: JSONObject): LinkedPath {
            val d = o.optJSONArray("d") ?: JSONArray()
            return LinkedPath(
                runCatching { LinkKind.valueOf(o.optString("kind")) }.getOrDefault(LinkKind.UI),
                o.getString("path"), o.optString("pkg"), o.optDouble("scale", 1.0),
                o.optInt("m"), o.optInt("x"), (0 until d.length()).map { d.optDouble(it) },
                if (o.isNull("lv")) null else o.optDouble("lv"), o.optLong("la")
            )
        }
    }
}

/** OBD skanında tapılan göstərici */
data class DiscoveredIndicator(
    val key: String,
    val label: String,
    val unit: String,
    val sampleValue: Double?,
    /** "az.q07.launcher" (öz OBD axını) və ya xarici OBD tətbiqinin paketi */
    val sourcePackage: String,
    /** OBD-də yolu: "OBD 010D @7E8" və ya xarici tətbiqin ekran yolu */
    val nodeOrPropertyPath: String,
    val samples: Int = 0,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val links: List<LinkedPath> = emptyList()
) {
    val learnedLinks: List<LinkedPath> get() = links.filter { it.learned }.sortedByDescending { it.score }
    val bestLink: LinkedPath? get() = learnedLinks.firstOrNull()

    fun toJson(): JSONObject = JSONObject().put("key", key).put("label", label).put("unit", unit)
        .put("sampleValue", sampleValue ?: JSONObject.NULL).put("sourcePackage", sourcePackage)
        .put("nodeOrPropertyPath", nodeOrPropertyPath).put("samples", samples)
        .put("first", firstSeen).put("last", lastSeen)
        .put("links", JSONArray().apply { links.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): DiscoveredIndicator {
            val l = o.optJSONArray("links") ?: JSONArray()
            return DiscoveredIndicator(
                o.getString("key"), o.optString("label"), o.optString("unit"),
                if (o.isNull("sampleValue")) null else o.optDouble("sampleValue"),
                o.optString("sourcePackage"), o.optString("nodeOrPropertyPath"), o.optInt("samples"),
                o.optLong("first"), o.optLong("last"),
                (0 until l.length()).mapNotNull { runCatching { LinkedPath.fromJson(l.getJSONObject(it)) }.getOrNull() }
            )
        }
    }
}

enum class LearnedSource(val label: String) {
    UI("OBD-Learned / UI"), PROP("OBD-Learned / Prop"), CACHE("OBD-Learned / Cache")
}

/** OBD-siz rejimdə oxunan dəyər */
data class LearnedReading(
    val key: String,
    val label: String,
    val unit: String,
    val value: Double,
    val source: LearnedSource,
    val at: Long,
    val path: String
)

// ═════════════════════════ ekran görüntüsü (DriveModeService) ═════════════════════════

object ObdDiscoveryBridge {
    /** ekrandakı bir rəqəm */
    data class UiNum(val value: Double, val pkg: String, val label: String, val raw: String)

    private const val SCAN_GAP_MS = 1500L
    const val UI_FRESH_MS = 8_000L
    private val NUMBER = Regex("[-−]?\\d{1,5}(?:[.,]\\d{1,3})?")
    private val OBD_APP_HINTS = listOf("obd", "carscanner", "car_scanner", "torque", "elm327", "elm")

    @Volatile var snapshot: Map<String, UiNum> = emptyMap()
        private set
    @Volatile var snapshotAt: Long = 0L
        private set
    @Volatile private var lastScan = 0L
    private var ownPackage = "az.q07.launcher"

    fun due(): Boolean = System.currentTimeMillis() - lastScan >= SCAN_GAP_MS

    fun isObdApp(pkg: String) = pkg != ownPackage && OBD_APP_HINTS.any { pkg.lowercase().contains(it) }

    /** DriveModeService hadisələrindən: bütün pəncərələrdəki rəqəmli mətnləri yolları ilə topla */
    fun onAccessibility(windows: List<AccessibilityWindowInfo>?, ownPkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastScan < SCAN_GAP_MS) return
        lastScan = now
        ownPackage = ownPkg
        // Lazım deyilsə ekranı oxuma: nə öyrənmə gedir, nə də OBD-siz yol var
        if (!ObdDiscoverySession.active.value && !ObdFreeFallbackResolver.hasUiLinks()) return
        val out = HashMap<String, UiNum>()
        runCatching {
            windows?.forEach { w ->
                val root = w.root ?: return@forEach
                val pkg = root.packageName?.toString() ?: return@forEach
                if (pkg == ownPkg) return@forEach
                collect(root, pkg, out)
            }
        }
        snapshot = out
        snapshotAt = now
    }

    fun fresh(): Map<String, UiNum> =
        if (System.currentTimeMillis() - snapshotAt <= UI_FRESH_MS) snapshot else emptyMap()

    fun parseNumber(text: String): Double? =
        NUMBER.find(text.replace(" ", ""))?.value?.replace('−', '-')?.replace(',', '.')?.toDoubleOrNull()

    private fun collect(root: AccessibilityNodeInfo, pkg: String, out: HashMap<String, UiNum>) {
        data class Item(val n: AccessibilityNodeInfo, val path: String, val depth: Int)
        val stack = ArrayDeque<Item>().apply { add(Item(root, "0", 0)) }
        val occurrences = HashMap<String, Int>()
        var visited = 0
        while (stack.isNotEmpty() && visited < 800) {
            val (n, idxPath, depth) = stack.removeLast(); visited++
            val text = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text.length <= 24 && n.isVisibleToUser) {
                val v = parseNumber(text)
                if (v != null) {
                    val label = neighbourLabel(n)
                    val vid = n.viewIdResourceName.orEmpty()
                    val base = when {
                        vid.isNotBlank() -> "ui:$pkg#$vid"
                        label.isNotBlank() -> "ui:$pkg~$label"
                        else -> "ui:$pkg@${n.className?.toString()?.substringAfterLast('.').orEmpty()}:$idxPath"
                    }
                    val k = (occurrences[base] ?: 0) + 1
                    occurrences[base] = k
                    out[if (k == 1) base else "$base[$k]"] = UiNum(v, pkg, label, text)
                }
            }
            if (depth < 40) for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(Item(it, "$idxPath.$i", depth + 1)) }
        }
    }

    /** rəqəmin yanındakı izahedici yazı (eyni valideyndə rəqəmsiz qısa mətn) */
    private fun neighbourLabel(n: AccessibilityNodeInfo): String {
        val p = runCatching { n.parent }.getOrNull() ?: return ""
        for (i in 0 until p.childCount) {
            val c = p.getChild(i) ?: continue
            if (c == n) continue
            val t = (c.text ?: c.contentDescription)?.toString()?.trim().orEmpty()
            if (t.length in 2..28 && t.none { it.isDigit() }) return t
        }
        return ""
    }
}

// ═════════════════════════ skan zamanı qeydiyyat ═════════════════════════

object ObdDiscoverySession {
    private const val FILE = "obd_discovered_paths.json"
    private const val TICK_MS = 2000L
    private const val MAX_LINKS = 12
    private const val PROP_LIST_REFRESH_MS = 60_000L
    private const val MAX_PROPS = 400
    /** xassə dəyərini OBD vahidinə çevirmək üçün sınanan əmsallar (m/s→km/s, Wh→kWh, ...) */
    private val PROP_SCALES = doubleArrayOf(1.0, 3.6, 1 / 3.6, 0.001, 1000.0, 0.01, 100.0, 0.1, 10.0)

    private val _active = MutableStateFlow(true)
    /** Öyrənmə açıqdır (OBD qoşulan kimi avtomatik qeyd edir) */
    val active: StateFlow<Boolean> = _active

    private val _indicators = MutableStateFlow<List<DiscoveredIndicator>>(emptyList())
    val indicators: StateFlow<List<DiscoveredIndicator>> = _indicators

    private val _status = MutableStateFlow("Gözləyir")
    val status: StateFlow<String> = _status

    private var file: File? = null
    private var prefsCtx: Context? = null
    private var lastTick = 0L
    private var lastSave = 0L
    private var dirty = false
    private var propIds: List<Pair<Int, IntArray>> = emptyList()
    private var propListAt = 0L
    private val deniedProps = HashSet<Long>()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val app = context.applicationContext
        prefsCtx = app
        file = File(app.filesDir, FILE)
        _active.value = app.getSharedPreferences("obd_discovery", Context.MODE_PRIVATE).getBoolean("active", true)
        _indicators.value = runCatching {
            val f = file!!
            if (!f.exists()) emptyList() else {
                val root = JSONObject(f.readText())
                val a = root.optJSONArray("indicators") ?: JSONArray()
                (0 until a.length()).mapNotNull { runCatching { DiscoveredIndicator.fromJson(a.getJSONObject(it)) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    fun setActive(on: Boolean) {
        _active.value = on
        prefsCtx?.getSharedPreferences("obd_discovery", Context.MODE_PRIVATE)?.edit()?.putBoolean("active", on)?.apply()
        if (!on) save(force = true)
        _status.value = if (on) "Öyrənmə aktivdir" else "Öyrənmə dayandırılıb"
    }

    @Synchronized
    fun clear() {
        _indicators.value = emptyList()
        dirty = true
        save(force = true)
        ObdFreeFallbackResolver.clearCache()
    }

    /** xassə siyahısını yenidən oxu və dərhal yaz (UI: «Yenilə») */
    fun refreshNow() {
        propListAt = 0L
        deniedProps.clear()
        lastTick = 0L
        save(force = true)
    }

    val learnedPathCount: Int get() = _indicators.value.sumOf { it.learnedLinks.size }

    /** VehicleTelemetryReader dövründən çağırılır (fon axını) */
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick < TICK_MS) return
        lastTick = now
        if (!_active.value) return
        val sources = obdSources(now)
        if (sources.isEmpty()) {
            _status.value = "OBD qoşulu deyil — öyrənmə gözləyir"
            save(force = false)
            return
        }
        val ui = ObdDiscoveryBridge.fresh()
        val props = readProps(now)
        record(sources, ui, props, now)
        _status.value = "Öyrənir: ${sources.size} OBD göstəricisi · ekranda ${ui.size} rəqəm · ${props.size} xassə"
        save(force = false)
    }

    private data class Source(val key: String, val label: String, val unit: String, val value: Double, val pkg: String, val path: String)

    /** 1) launcher-in öz OBD axını (ObdMonitor); 2) ekranda açıq olan xarici OBD tətbiqi */
    private fun obdSources(now: Long): List<Source> {
        val out = ArrayList<Source>()
        val s = ObdMonitor.state.value
        if (s.connected && now - s.updatedAt < 6000) {
            s.values.forEach { (k, v) ->
                val pid = ObdPids.byKey[k] ?: return@forEach
                out += Source(k, pid.name, pid.unit, v, "az.q07.launcher", "OBD ${pid.cmd} @${pid.ecu}")
            }
        }
        ObdDiscoveryBridge.fresh().forEach { (path, n) ->
            if (ObdDiscoveryBridge.isObdApp(n.pkg) && n.label.isNotBlank()) {
                out += Source("ext:${n.pkg}:${n.label}", n.label, n.raw.filter { !it.isDigit() && it !in ".,-− " }.take(8),
                    n.value, n.pkg, path)
            }
        }
        return out
    }

    private fun readProps(now: Long): Map<String, Double> {
        if (!CarApi.init(prefsCtx ?: return emptyMap())) return emptyMap()
        if (now - propListAt > PROP_LIST_REFRESH_MS) {
            propIds = CarApi.propertyList().take(MAX_PROPS)
            propListAt = now
        }
        val out = HashMap<String, Double>()
        for ((id, areas) in propIds) for (area in areas) {
            val k = (id.toLong() shl 32) or (area.toLong() and 0xffffffffL)
            if (k in deniedProps) continue
            val v = try {
                CarApi.readOrThrow(id, area)
            } catch (e: SecurityException) {
                deniedProps += k; null
            } catch (t: Throwable) {
                null
            }
            val d = (v as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: continue
            out[propPath(id, area)] = d
        }
        return out
    }

    fun propPath(id: Int, area: Int) = "prop:0x${Integer.toHexString(id)}@$area"

    fun near(obd: Double, other: Double): Boolean {
        val tol = maxOf(0.6, abs(obd) * 0.03)
        return abs(obd - other) <= tol
    }

    private fun r1(v: Double) = round(v * 10) / 10

    @Synchronized
    private fun record(sources: List<Source>, ui: Map<String, ObdDiscoveryBridge.UiNum>, props: Map<String, Double>, now: Long) {
        val byKey = _indicators.value.associateBy { it.key }.toMutableMap()
        for (src in sources) {
            val old = byKey[src.key] ?: DiscoveredIndicator(src.key, src.label, src.unit, null, src.pkg, src.path, firstSeen = now)
            val nonTrivial = abs(src.value) >= 0.5
            val links = old.links.associateBy { it.path + "|" + it.scale }.toMutableMap()

            // mövcud əlaqələri yoxla: uyğun gəldimi?
            for ((k, l) in links.toList()) {
                val cur = when (l.kind) {
                    LinkKind.UI -> ui[l.path]?.value
                    LinkKind.PROP -> props[l.path]?.times(l.scale)
                } ?: continue // bu an görünmür — nə xal, nə cərimə
                links[k] = if (near(src.value, cur)) l.copy(
                    matches = l.matches + 1, lastValue = cur, lastAt = now,
                    distinct = if (nonTrivial) (l.distinct + r1(src.value)).distinct().takeLast(6) else l.distinct
                ) else l.copy(misses = l.misses + 1, lastValue = cur, lastAt = now)
            }

            // yeni namizədlər — yalnız əhəmiyyətli dəyərdə (0 hər yerdə uyğun gəlir)
            if (nonTrivial) {
                ui.forEach { (path, n) ->
                    if (n.pkg == src.pkg) return@forEach // xarici OBD tətbiqinin öz ekranı daxili yol deyil
                    val k = "$path|1.0"
                    if (k !in links && near(src.value, n.value))
                        links[k] = LinkedPath(LinkKind.UI, path, n.pkg, 1.0, 1, 0, listOf(r1(src.value)), n.value, now)
                }
                props.forEach { (path, raw) ->
                    for (sc in PROP_SCALES) {
                        val k = "$path|$sc"
                        if (k !in links && near(src.value, raw * sc)) {
                            links[k] = LinkedPath(LinkKind.PROP, path, "android.car", sc, 1, 0, listOf(r1(src.value)), raw * sc, now)
                            break
                        }
                    }
                }
            }

            val kept = links.values
                .filterNot { it.misses >= 5 && it.ratio < 0.5 }
                .sortedByDescending { it.score }
                .take(MAX_LINKS)
            byKey[src.key] = old.copy(
                label = src.label, unit = src.unit, sampleValue = src.value, sourcePackage = src.pkg,
                nodeOrPropertyPath = src.path, samples = old.samples + 1, lastSeen = now, links = kept
            )
        }
        _indicators.value = byKey.values.sortedBy { it.label }
        dirty = true
    }

    private fun save(force: Boolean) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!dirty || (!force && now - lastSave < 20_000)) return
        dirty = false
        lastSave = now
        val snapshot = _indicators.value
        runCatching {
            val root = JSONObject().put("version", 1).put("savedAt", now)
                .put("indicators", JSONArray().apply { snapshot.forEach { put(it.toJson()) } })
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
        }.onFailure { SelfDiag.log("obdlearn", "yazılmadı: ${it.javaClass.simpleName}") }
    }
}

// ═════════════════════════ OBD-siz rejim ═════════════════════════

object ObdFreeFallbackResolver {
    private const val PREFS = "q07_obd_learned_secure"
    private var prefs: SharedPreferences? = null

    private val _readings = MutableStateFlow<Map<String, LearnedReading>>(emptyMap())
    /** göstərici açarı → OBD-siz oxunan dəyər (UI / Prop / Cache) */
    val readings: StateFlow<Map<String, LearnedReading>> = _readings

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = try {
            EncryptedSharedPreferences.create(
                PREFS, MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            SelfDiag.log("obdlearn", "şifrələnmiş yaddaş açılmadı: ${t.javaClass.simpleName}")
            context.applicationContext.getSharedPreferences("q07_obd_learned_fallback", Context.MODE_PRIVATE)
        }
    }

    fun hasUiLinks(): Boolean = ObdDiscoverySession.indicators.value.any { i -> i.learnedLinks.any { it.kind == LinkKind.UI } }

    /** Bir göstəricini OBD-siz oxu: əvvəl canlı daxili yollar, sonra son etibarlı dəyər; yoxdursa null ("--") */
    fun resolve(ind: DiscoveredIndicator): LearnedReading? {
        val now = System.currentTimeMillis()
        val ui = ObdDiscoveryBridge.fresh()
        for (l in ind.learnedLinks) {
            val v: Double? = try {
                when (l.kind) {
                    LinkKind.UI -> ui[l.path]?.value?.times(l.scale)
                    LinkKind.PROP -> readProp(l.path)?.times(l.scale)
                }
            } catch (e: SecurityException) {
                null
            } catch (t: Throwable) {
                null
            }
            if (v != null && !v.isNaN() && !v.isInfinite()) {
                val src = if (l.kind == LinkKind.UI) LearnedSource.UI else LearnedSource.PROP
                saveCache(ind.key, v, now, l.path)
                return LearnedReading(ind.key, ind.label, ind.unit, v, src, now, l.path)
            }
        }
        return cached(ind)
    }

    private fun readProp(path: String): Double? {
        // "prop:0x11600207@0"
        val body = path.removePrefix("prop:0x")
        val id = body.substringBefore('@').toLongOrNull(16)?.toInt() ?: return null
        val area = body.substringAfter('@').toIntOrNull() ?: 0
        return (CarApi.readOrThrow(id, area) as? Number)?.toDouble()
    }

    private fun cached(ind: DiscoveredIndicator): LearnedReading? {
        val p = prefs ?: return null
        return runCatching {
            if (!p.contains("v_${ind.key}")) return null
            val v = p.getString("v_${ind.key}", null)?.toDoubleOrNull() ?: return null
            LearnedReading(ind.key, ind.label, ind.unit, v, LearnedSource.CACHE,
                p.getLong("t_${ind.key}", 0L), p.getString("p_${ind.key}", "").orEmpty())
        }.getOrNull()
    }

    private fun saveCache(key: String, v: Double, at: Long, path: String) {
        runCatching { prefs?.edit()?.putString("v_$key", v.toString())?.putLong("t_$key", at)?.putString("p_$key", path)?.apply() }
    }

    /** OBD işləyərkən: öyrənilmiş göstəricinin real dəyərini də son etibarlı dəyər kimi saxla */
    private var lastObdCache = 0L
    private fun cacheFromObd() {
        val now = System.currentTimeMillis()
        if (now - lastObdCache < 30_000) return
        lastObdCache = now
        val s = ObdMonitor.state.value
        ObdDiscoverySession.indicators.value.filter { it.bestLink != null }.forEach { i ->
            s.values[i.key]?.let { saveCache(i.key, it, now, i.nodeOrPropertyPath) }
        }
    }

    fun clearCache() {
        runCatching { prefs?.edit()?.clear()?.apply() }
        _readings.value = emptyMap()
    }

    /** VehicleTelemetryReader dövründən: OBD yoxdursa bütün öyrənilmiş göstəriciləri yenilə */
    fun poll() {
        if (ObdMonitor.state.value.connected) {
            cacheFromObd()
            if (_readings.value.isNotEmpty()) _readings.value = emptyMap()
            return
        }
        val out = HashMap<String, LearnedReading>()
        ObdDiscoverySession.indicators.value.forEach { i ->
            if (i.learnedLinks.isEmpty() && cached(i) == null) return@forEach
            resolve(i)?.let { out[i.key] = it }
        }
        if (out != _readings.value) _readings.value = out
    }

    /** UI «Test et»: OBD-dən asılı olmayaraq hər öyrənilmiş göstəricini sına */
    fun testAll(): List<Pair<DiscoveredIndicator, LearnedReading?>> =
        ObdDiscoverySession.indicators.value.filter { it.learnedLinks.isNotEmpty() }.map { it to resolve(it) }

    fun format(key: String, v: Double?): String {
        v ?: return "--"
        val dec = ObdPids.byKey[key]?.decimals ?: if (abs(v) < 100 && v % 1.0 != 0.0) 1 else 0
        return "%.${dec}f".format(v)
    }
}
