package az.q07.launcher.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Öz-özünə diaqnostika və öyrənmə jurnalı.
 *
 * Maşın işləyəndə launcher-ə aid hadisələri yazır:
 *  - çökmələr (stack trace), OBD vəziyyət dəyişmələri, zavod idarəetmə əmrlərinin nəticəsi,
 *  - ön plandakı tətbiqin PAKET ADI (mətn/şəxsi məlumat yox),
 *  - zavod «Ayarlar» və zavod ana ekranının element ağacı (id, mətn, ölçü) — yalnız dəyişəndə.
 * Fayllar: /sdcard/Android/data/az.q07.launcher/files/diag/  (root-suz `adb pull` ilə götürülür)
 * Kompüterdəki Q07_avto.bat maşın qoşulanda bunları LAUNCHER\diag\ qovluğuna çəkir.
 *
 * Öz-özünə düzəliş: ard-arda çökmə olarsa «təhlükəsiz rejim» (kokpit səhifəsi söndürülür),
 * zavod elementlərinin başlıqları öyrənilir — id dəyişsə də mətnlə tapılır.
 */
object SelfDiag {
    private const val MAX_BYTES = 2_000_000L
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private val io by lazy { Handler(HandlerThread("q07diag").apply { start() }.looper) }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lastUiHash = HashMap<String, Int>()
    private val lastUiAt = HashMap<String, Long>()

    /** Ard-arda çökmələrdən sonra ağır hissələr söndürülür */
    @Volatile var safeMode = false
        private set

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = (context.getExternalFilesDir("diag") ?: File(context.filesDir, "diag")).apply { mkdirs() }
        prefs = context.getSharedPreferences("q07_diag", Context.MODE_PRIVATE)

        // çökmə dövrü yoxlaması: son 3 dəqiqədə 2+ çökmə → təhlükəsiz rejim
        val now = System.currentTimeMillis()
        val crashes = prefs.getString("crashes", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < 180_000 }
        safeMode = crashes.size >= 2
        if (!safeMode && prefs.getBoolean("forceSafe", false)) safeMode = true

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val list = (crashes + System.currentTimeMillis()).takeLast(5)
                prefs.edit().putString("crashes", list.joinToString(",")).commit()
                writeNow("crash", e.toString(), mapOf("thread" to t.name, "trace" to e.stackTraceToString().take(6000)))
            }
            prev?.uncaughtException(t, e)
        }

        val dm = context.resources.displayMetrics
        log("start", "launcher açıldı", mapOf(
            "version" to runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull(),
            "sdk" to Build.VERSION.SDK_INT, "display" to "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}",
            "uiScale" to AppSettings.uiScalePercent, "safeMode" to safeMode, "recentCrashes" to crashes.size
        ))
        if (safeMode) log("selfheal", "ard-arda çökmə — təhlükəsiz rejim (kokpit söndürüldü)")
    }

    /** İstifadəçi «normal rejimə qayıt» deyəndə */
    fun clearSafeMode() {
        safeMode = false
        prefs.edit().remove("crashes").putBoolean("forceSafe", false).apply()
        log("selfheal", "təhlükəsiz rejim söndürüldü")
    }

    fun log(type: String, msg: String, extra: Map<String, Any?> = emptyMap()) {
        if (!::dir.isInitialized) return
        io.post { writeNow(type, msg, extra) }
    }

    private fun writeNow(type: String, msg: String, extra: Map<String, Any?>) {
        val f = File(dir, "events.jsonl")
        if (f.length() > MAX_BYTES) { File(dir, "events.old.jsonl").delete(); f.renameTo(File(dir, "events.old.jsonl")) }
        val o = JSONObject().put("t", fmt.format(Date())).put("type", type).put("msg", msg)
        extra.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        runCatching { f.appendText(o.toString() + "\n") }
    }

    /** Zavod ekranının element ağacı — yalnız dəyişəndə və ən tez 20 san-dən bir */
    fun snapshotUi(tag: String, root: AccessibilityNodeInfo) {
        if (!::dir.isInitialized) return
        val now = System.currentTimeMillis()
        if (now - (lastUiAt[tag] ?: 0L) < 20_000) return
        val arr = JSONArray()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40 || arr.length() > 1500) return
            val id = n.viewIdResourceName
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            if (id != null || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
                n.getBoundsInScreen(r)
                arr.put(JSONObject().put("id", id?.substringAfter(":id/")).put("text", text).put("desc", desc)
                    .put("b", "${r.left},${r.top},${r.right},${r.bottom}").put("click", n.isClickable)
                    .put("sel", n.isSelected).put("scroll", n.isScrollable).put("vis", n.isVisibleToUser))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        val hash = arr.toString().hashCode()
        lastUiAt[tag] = now
        if (lastUiHash[tag] == hash) return
        lastUiHash[tag] = hash
        io.post {
            runCatching { File(dir, "ui_$tag.json").writeText(arr.toString(1)) }
            writeNow("ui", "ekran ağacı yeniləndi: $tag", mapOf("nodes" to arr.length()))
        }
    }

    // ---------- öyrənilmiş başlıqlar (öz-özünə düzəliş) ----------
    fun learnTitle(control: VehicleControl, text: String) {
        if (!::prefs.isInitialized || text.isBlank()) return
        if (prefs.getString("title_${control.name}", null) != text) prefs.edit().putString("title_${control.name}", text).apply()
    }

    fun learnedTitle(control: VehicleControl): String? =
        if (::prefs.isInitialized) prefs.getString("title_${control.name}", null) else null
}
