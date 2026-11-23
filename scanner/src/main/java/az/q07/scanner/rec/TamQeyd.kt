package az.q07.scanner.rec

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import az.q07.scanner.data.SelfDiag
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * TAM QEYD — maşında baş verən hər şeyi davamlı yazır (root olmadan mümkün olan hər mənbə):
 *  ui       — bütün ekran hadisələri (hansı tətbiq, pəncərə, düymə id-si, mətn, klik)
 *  car      — bütün oxuna bilən avtomobil xassələri (VHAL kodları, o cümlədən zavod/vendor kodları) və dəyişiklikləri
 *  logcat   — bütün sistem jurnalı (READ_LOGS adb ilə verilir) — proseslər, servislər, CAN/MCU mesajları
 *  settings — System/Secure/Global parametrlərinin dəyişiklikləri (zavod tətbiqləri vəziyyəti burada saxlayır)
 *  props    — sistem xassələrinin (getprop) dəyişiklikləri
 *  usage    — hansı activity açıldı/bağlandı (gizli activity-lər daxil; GET_USAGE_STATS adb ilə)
 *  bcast    — sistem yayımları (ekran, güc, şəbəkə, BT, paketlər...)
 *  inventar — bütün paketlər: activity/servis/receiver/provider/icazələr — gizli funksiyaların siyahısı
 *
 * Yer: Android/data/az.q07.scanner/files/qeyd/<gün>/<saat>_<növ>.jsonl.gz  (PC-yə adb pull ilə çəkilir)
 * Fayllar gzip-lə sıxılır; ümumi həcm MAX_BYTES-ı keçəndə ən köhnə günlər silinir.
 */
object TamQeyd {
    private const val MAX_BYTES = 1_500L * 1024 * 1024      // ~1.5 GB
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val hourFmt = SimpleDateFormat("HH", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile var running = false; private set
    private lateinit var app: Context
    private lateinit var root: File
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var logcatProc: Process? = null
    private val streams = HashMap<String, Pair<String, OutputStream>>()   // növ → (fayl adı, axın)
    @Volatile var eventsWritten = 0L; private set

    /**
     * Qeyd qovluğu: icazə varsa /sdcard/Q07Qeyd (proqram silinib-qurulsa belə qalır),
     * yoxdursa proqramın öz qovluğu (Android/data/... — silinəndə itir).
     */
    fun storageRoot(context: Context): File {
        // Həqiqi yazma sınağı (canWrite bəzi yaddaş rejimlərində yalan deyir)
        val shared = runCatching {
            val d = File(android.os.Environment.getExternalStorageDirectory(), "Q07Qeyd").apply { mkdirs() }
            val probe = File(d, ".yazma_sinagi")
            probe.writeText(System.currentTimeMillis().toString())
            if (probe.exists() && probe.length() > 0) d else null
        }.getOrNull()
        return shared ?: File(context.getExternalFilesDir(null), "qeyd").apply { mkdirs() }
    }

    private val baseFiles = listOf("vehicle_structure_map.json", "recorded_flows.json", "learned_functions.json")

    /**
     * Skanerin öz bazasının bərpası — repozitoriyalar yüklənməzdən ƏVVƏL çağırılır:
     *  1) qeyd/berpa/ qovluğunda fayl varsa → daxili bazanın üzərinə yazılır (əl ilə bərpa), sonra berpa_edildi/-yə köçür
     *  2) daxili fayl yoxdursa (proqram silinib-qurulub) → qeyd/export/-dakı son surətdən bərpa
     */
    fun restoreBase(context: Context) {
        val r = storageRoot(context)
        val inDir = context.applicationContext.filesDir
        val berpa = File(r, "berpa")
        berpa.listFiles { f -> f.isFile && f.name in baseFiles }?.forEach { f ->
            runCatching {
                f.copyTo(File(inDir, f.name), overwrite = true)
                val done = File(r, "berpa_edildi").apply { mkdirs() }
                f.renameTo(File(done, f.name + "." + System.currentTimeMillis()))
                SelfDiag.log("berpa", "əl ilə bərpa edildi: ${f.name}")
            }
        }
        val exp = File(r, "export")
        for (n in baseFiles) {
            val dst = File(inDir, n); val src = File(exp, n)
            if (!dst.exists() && src.exists() && src.length() > 0) runCatching {
                src.copyTo(dst); SelfDiag.log("berpa", "avtomatik bərpa (proqram yenidən qurulub): $n")
            }
        }
    }

    // ---------------- başlat / dayandır ----------------
    @Synchronized
    fun start(context: Context) {
        if (running) return
        app = context.applicationContext
        root = storageRoot(app)
        running = true
        val t = HandlerThread("tam-qeyd").also { it.start() }
        thread = t
        val handler = Handler(t.looper); h = handler
        write("sistem", JSONObject().put("hadise", "başladı").put("model", Build.MODEL).put("build", Build.DISPLAY))
        handler.post { safe("inventar") { inventory() } }
        handler.post { safe("car") { CarRec.start(app) } }
        handler.post { safe("settings") { settingsTick(first = true) } }
        handler.post { safe("props") { propsTick() } }
        handler.post { safe("export") { exportTick() } }
        handler.postDelayed(::tick10s, 10_000)
        handler.postDelayed(::tick60s, 60_000)
        startLogcat()
        registerBroadcasts()
        SelfDiag.log("tamqeyd", "tam qeyd başladı", mapOf("yer" to root.absolutePath))
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { logcatProc?.destroy() }
        runCatching { app.unregisterReceiver(bcast) }
        runCatching { CarRec.stop() }
        h?.post { closeAll() }
        thread?.quitSafely()
    }

    private fun tick10s() {
        if (!running) return
        safe("usage") { usageTick() }
        flushAll()
        h?.postDelayed(::tick10s, 10_000)
    }

    private var minutes = 0
    private fun tick60s() {
        if (!running) return
        minutes++
        safe("settings") { settingsTick(first = false) }
        if (minutes % 5 == 0) { safe("props") { propsTick() }; safe("export") { exportTick() } }
        if (minutes % 30 == 0) safe("yer") { trim() }
        if (minutes % 360 == 0) safe("inventar") { inventory() }
        h?.postDelayed(::tick60s, 60_000)
    }

    private inline fun safe(tag: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            write("xeta", JSONObject().put("menbe", tag).put("xeta", "${t.javaClass.simpleName}: ${t.message}"))
        }
    }

    // ---------------- yazma ----------------
    /** Bir qeyd: növ üzrə saatlıq gzip faylına bir JSON sətri */
    fun write(kind: String, o: JSONObject) {
        if (!running) return
        o.put("t", tsFmt.format(Date()))
        writeLine(kind, o.toString(), "jsonl")
    }

    @Synchronized
    private fun writeLine(kind: String, line: String, ext: String) {
        if (!running && kind != "sistem") return
        try {
            val now = Date()
            val name = "${dayFmt.format(now)}/${hourFmt.format(now)}_$kind.$ext.gz"
            val cur = streams[kind]
            val out = if (cur != null && cur.first == name) cur.second else {
                cur?.second?.let { runCatching { it.close() } }
                val f = File(root, name).apply { parentFile?.mkdirs() }
                // mövcud fayla əlavə: yeni gzip "üzvü" (gunzip ardıcıl üzvləri birləşdirir)
                val s = GZIPOutputStream(java.io.FileOutputStream(f, true).buffered(64 * 1024), 64 * 1024, true)
                streams[kind] = name to s
                s
            }
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            eventsWritten++
        } catch (_: Throwable) {}
    }

    @Synchronized private fun flushAll() { streams.values.forEach { runCatching { it.second.flush() } } }
    @Synchronized private fun closeAll() { streams.values.forEach { runCatching { it.second.close() } }; streams.clear() }

    /** Həcm limiti: ən köhnə gün qovluqlarını sil */
    private fun trim() {
        val days = root.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }?.sortedBy { it.name } ?: return
        var total = days.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (d in days.dropLast(1)) {
            if (total <= MAX_BYTES) break
            val sz = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively(); total -= sz
            write("sistem", JSONObject().put("hadise", "köhnə gün silindi (yer limiti)").put("gun", d.name))
        }
    }

    // ---------------- 1. ekran (accessibility) ----------------
    private var lastSig = ""
    private var lastSigAt = 0L

    fun onAccessibility(e: AccessibilityEvent) {
        if (!running) return
        val pkg = e.packageName?.toString() ?: ""
        val type = AccessibilityEvent.eventTypeToString(e.eventType)
        val cls = e.className?.toString() ?: ""
        val src = runCatching { e.source }.getOrNull()
        val vid = src?.viewIdResourceName ?: ""
        val text = e.text?.joinToString(" | ")?.take(300) ?: ""
        val desc = e.contentDescription?.toString()?.take(200) ?: ""
        // eyni məzmun dəyişikliyi təkrarlarını sıx (ekran animasiyaları sel kimi hadisə yaradır)
        val sig = "$type|$pkg|$cls|$vid|$text"
        val now = System.currentTimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (sig == lastSig && now - lastSigAt < 1500) return
        }
        lastSig = sig; lastSigAt = now
        val o = JSONObject().put("tip", type).put("pkg", pkg).put("sinif", cls)
        if (vid.isNotEmpty()) o.put("id", vid)
        if (text.isNotEmpty()) o.put("metn", text)
        if (desc.isNotEmpty()) o.put("tesvir", desc)
        if (e.windowId != -1) o.put("pencere", e.windowId)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) o.put("deyisiklik", e.contentChangeTypes)
        src?.let { n ->
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            o.put("yer", "${r.left},${r.top},${r.right},${r.bottom}")
            if (n.isChecked) o.put("secili", true)
        }
        write("ui", o)
    }

    // ---------------- 2. logcat ----------------
    private const val PREF = "tamqeyd"
    private fun startLogcat() {
        Thread({
            var backoff = 5_000L
            while (running) {
                try {
                    val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    val since = prefs.getString("logcat_son", null)
                    val cmd = mutableListOf("logcat", "-b", "main,system,events,crash,radio", "-v", "threadtime", "-v", "year")
                    if (since != null) { cmd += "-T"; cmd += since }
                    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    logcatProc = p
                    write("sistem", JSONObject().put("hadise", "logcat qoşuldu").put("since", since ?: "bufer"))
                    val r = BufferedReader(InputStreamReader(p.inputStream), 32 * 1024)
                    var n = 0L
                    while (running) {
                        val line = r.readLine() ?: break
                        writeLine("logcat", line, "txt")
                        if (++n % 500 == 0L && line.length > 23 && line[4] == '-') {
                            // "2026-11-23 16:44:30.546" → "11-23 16:44:30.546" (logcat -T formatı) — növbəti başlanğıc üçün
                            prefs.edit().putString("logcat_son", line.substring(5, 23)).apply()
                        }
                    }
                    p.destroy()
                    if (n < 5) {
                        write("sistem", JSONObject().put("hadise", "logcat oxunmur — READ_LOGS icazəsi yoxdur? (Q07_skaner_qur.bat)"))
                        backoff = (backoff * 2).coerceAtMost(600_000)
                    } else backoff = 5_000
                } catch (t: Throwable) {
                    write("xeta", JSONObject().put("menbe", "logcat").put("xeta", t.toString()))
                }
                Thread.sleep(backoff)
            }
        }, "tam-qeyd-logcat").apply { isDaemon = true; start() }
    }

    // ---------------- 3. Settings (System/Secure/Global) ----------------
    private val lastSettings = HashMap<String, Map<String, String?>>()

    private fun readTable(table: String): Map<String, String?> {
        val m = HashMap<String, String?>()
        app.contentResolver.query(Uri.parse("content://settings/$table"), arrayOf("name", "value"), null, null, null)?.use { c ->
            while (c.moveToNext()) m[c.getString(0) ?: continue] = c.getString(1)
        }
        return m
    }

    private fun settingsTick(first: Boolean) {
        for (table in listOf("system", "secure", "global")) {
            val now = runCatching { readTable(table) }.getOrNull() ?: continue
            val prev = lastSettings[table]
            if (prev == null) {
                if (first) write("settings", JSONObject().put("cedvel", table).put("tam_surət", JSONObject(now as Map<*, *>)))
            } else {
                for ((k, v) in now) if (prev[k] != v) write("settings", JSONObject().put("cedvel", table).put("ad", k).put("kohne", prev[k] ?: JSONObject.NULL).put("yeni", v ?: JSONObject.NULL))
                for (k in prev.keys - now.keys) write("settings", JSONObject().put("cedvel", table).put("ad", k).put("silindi", true))
            }
            lastSettings[table] = now
        }
    }

    // ---------------- 4. Sistem xassələri (getprop) ----------------
    private var lastProps: Map<String, String>? = null

    private fun propsTick() {
        val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val m = HashMap<String, String>()
        p.inputStream.bufferedReader().forEachLine { l ->
            val i = l.indexOf("]: [")
            if (l.startsWith("[") && i > 0 && l.endsWith("]")) m[l.substring(1, i)] = l.substring(i + 4, l.length - 1)
        }
        p.waitFor()
        val prev = lastProps
        if (prev == null) write("props", JSONObject().put("tam_surət", JSONObject(m as Map<*, *>)))
        else for ((k, v) in m) if (prev[k] != v) write("props", JSONObject().put("ad", k).put("kohne", prev[k] ?: JSONObject.NULL).put("yeni", v))
        lastProps = m
    }

    // ---------------- 5. Activity keçidləri (UsageStats) ----------------
    private var usageSince = 0L

    private fun usageTick() {
        val um = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        if (usageSince == 0L) usageSince = now - 60_000
        val ev = um.queryEvents(usageSince, now) ?: return
        val e = UsageEvents.Event()
        var any = false
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e); any = true
            val name = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> "açıldı"
                UsageEvents.Event.ACTIVITY_PAUSED -> "fasilə"
                UsageEvents.Event.ACTIVITY_STOPPED -> "dayandı"
                UsageEvents.Event.FOREGROUND_SERVICE_START -> "servis_başladı"
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "servis_dayandı"
                UsageEvents.Event.SCREEN_INTERACTIVE -> "ekran_açıq"
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "ekran_qaralı"
                UsageEvents.Event.CONFIGURATION_CHANGE -> "konfiqurasiya"
                else -> "tip_${e.eventType}"
            }
            write("usage", JSONObject().put("hadise", name).put("pkg", e.packageName ?: "").put("sinif", e.className ?: JSONObject.NULL)
                .put("vaxt", tsFmt.format(Date(e.timeStamp))))
        }
        usageSince = now
        if (!any && minutes == 1) write("sistem", JSONObject().put("hadise", "usage boşdur — GET_USAGE_STATS icazəsi? (Q07_skaner_qur.bat)"))
    }

    // ---------------- 6. Yayımlar ----------------
    private val bcast = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val o = JSONObject().put("action", i.action ?: "")
            i.dataString?.let { o.put("data", it) }
            i.extras?.let { b -> val x = JSONObject(); b.keySet().forEach { k -> x.put(k, runCatching { b.get(k).toString() }.getOrDefault("?")) }; o.put("extras", x) }
            write("bcast", o)
        }
    }

    private fun registerBroadcasts() {
        val actions = listOf(
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT,
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY,
            Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_HEADSET_PLUG,
            Intent.ACTION_AIRPLANE_MODE_CHANGED, Intent.ACTION_DOCK_EVENT, Intent.ACTION_MEDIA_BUTTON,
            "android.net.conn.CONNECTIVITY_CHANGE", "android.net.wifi.STATE_CHANGE", "android.net.wifi.WIFI_STATE_CHANGED",
            "android.bluetooth.adapter.action.STATE_CHANGED", "android.bluetooth.device.action.ACL_CONNECTED",
            "android.bluetooth.device.action.ACL_DISCONNECTED", "android.media.AUDIO_BECOMING_NOISY",
            "android.media.VOLUME_CHANGED_ACTION", "android.intent.action.ACTION_SHUTDOWN",
            "android.location.PROVIDERS_CHANGED", "android.intent.action.DREAMING_STARTED", "android.intent.action.DREAMING_STOPPED"
        )
        val f = IntentFilter().apply { actions.forEach { addAction(it) } }
        app.registerReceiver(bcast, f, null, h)
        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        app.registerReceiver(bcast, pf, null, h)
    }

    // ---------------- 7. İnventar — bütün paketlər və gizli komponentlər ----------------
    @Suppress("DEPRECATION")
    private fun inventory() {
        val pm = app.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }.toSet()
        val arr = JSONArray()
        for (p in pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            val pi = runCatching { pm.getPackageInfo(p.packageName, flags) }.getOrNull() ?: p
            val o = JSONObject().put("pkg", pi.packageName).put("versiya", pi.versionName ?: "").put("kod", pi.longVersionCode)
                .put("sistem", (pi.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                .put("aktiv", pi.applicationInfo?.enabled ?: true)
                .put("ad", runCatching { pi.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: "")
            pi.activities?.let { a -> o.put("activity", JSONArray(a.map {
                JSONObject().put("ad", it.name).put("export", it.exported).put("aktiv", it.enabled)
                    .put("launcher", "${pi.packageName}/${it.name}" in launchable)
                    .apply { it.permission?.let { pp -> put("icaze", pp) } }
            })) }
            pi.services?.let { a -> o.put("servis", JSONArray(a.map { JSONObject().put("ad", it.name).put("export", it.exported).apply { it.permission?.let { pp -> put("icaze", pp) } } })) }
            pi.receivers?.let { a -> o.put("receiver", JSONArray(a.map { JSONObject().put("ad", it.name).put("export", it.exported) })) }
            pi.providers?.let { a -> o.put("provider", JSONArray(a.map { JSONObject().put("ad", it.name).put("authority", it.authority ?: "").put("export", it.exported) })) }
            pi.permissions?.let { a -> o.put("teyin_etdiyi_icazeler", JSONArray(a.map { it.name })) }
            pi.requestedPermissions?.let { o.put("istediyi_icazeler", JSONArray(it.toList())) }
            arr.put(o)
        }
        // inventar ayrıca tam faylda (saatlıq axında yox) — hər dəfə yenilənir
        val f = File(root, "inventar.json.gz")
        GZIPOutputStream(f.outputStream()).use { it.write(JSONObject().put("t", tsFmt.format(Date())).put("paketler", arr).toString(1).toByteArray()) }
        write("sistem", JSONObject().put("hadise", "inventar yazıldı").put("paket_sayi", arr.length()))
    }

    // ---------------- 8. Skanerin öz bazası (xəritə, axınlar, öyrənilmiş funksiyalar) ----------------
    private fun exportTick() {
        val out = File(root, "export").apply { mkdirs() }
        app.filesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { src ->
            val dst = File(out, src.name)
            if (!dst.exists() || dst.lastModified() < src.lastModified()) {
                // təzə/boş baza köhnə yaxşı surəti əzməsin: əvvəlki surət .onceki kimi saxlanır
                if (dst.exists() && src.length() < dst.length() / 2) dst.copyTo(File(out, src.name + ".onceki"), overwrite = true)
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    /** Ekran üçün qısa vəziyyət */
    fun status(): String {
        if (!running) return "dayanıb"
        val mb = runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024) }.getOrDefault(0)
        return "aktiv · $eventsWritten qeyd · ${mb} MB · avto xassə: ${CarRec.count}"
    }
}
