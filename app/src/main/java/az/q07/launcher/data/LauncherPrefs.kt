package az.q07.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/**
 * Launcher ayarları: dock slotları, gizlədilmiş tətbiqlər, dəyişdirilmiş adlar.
 * Hamısı Compose state-dir — dəyişəndə ekran avtomatik yenilənir.
 */
object LauncherPrefs {
    /** Maksimal slot sayı; ekranda göstərilən say Ayarlardadır (3–6) */
    const val DOCK_SLOTS = 6

    val slotNames = listOf("Naviqasiya", "Musiqi", "Video", "Brauzer / AI", "Radio", "Telefon / AA")

    private lateinit var prefs: SharedPreferences

    /** Hər slotda "paket/klass" (ComponentName.flattenToString) və ya "" (boş) */
    val dockSlots = mutableStateListOf<String>()
    val hidden = mutableStateListOf<String>()
    val renames = mutableStateMapOf<String, String>()

    /** Slot standartları — Google-suz head unit-də ən çox rast gəlinən tətbiqlər */
    private val defaultCandidates: List<List<String>> = listOf(
        // 1) Naviqasiya
        listOf(
            "com.waze", "com.incall.apps.navi", "ru.yandex.yandexnavi", "ru.yandex.yandexmaps",
            "com.autonavi.amapauto", "com.autonavi.minimap", "com.baidu.BaiduMap",
            "com.google.android.apps.maps", "net.osmand", "net.osmand.plus"
        ),
        // 2) Musiqi
        listOf(
            "ru.yandex.music", "com.incall.apps.sdamedia.onlinemusic", "com.netease.cloudmusic.iot",
            "com.spotify.music", "com.google.android.apps.youtube.music",
            "com.tencent.qqmusic", "com.netease.cloudmusic", "com.kugou.android"
        ),
        // 3) Video (Google-suz işləyənlər öndə)
        listOf(
            "app.morphe.android.youtube", "ru.kinopoisk", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv",
            "org.schabi.newpipe", "app.revanced.android.youtube",
            "com.google.android.youtube", "com.qiyi.video", "tv.danmaku.bili"
        ),
        // 4) Brauzer / AI
        listOf(
            "com.brave.browser", "com.google.android.apps.bard", "com.deepseek.chat", "org.mozilla.firefox",
            "com.android.chrome", "com.yandex.browser", "com.android.browser", "mark.via.gp"
        ),
        // 5) Radio
        listOf("com.safakge.radyokulesi", "com.incall.apps.radio", "com.incall.apps.sdamedia.radio"),
        // 6) Telefon / Android Auto / CarPlay
        listOf("com.andrerinas.headunitrevived", "com.incall.apps.bluetooth", "com.incall.apps.btphone")
    )

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("q07_launcher", Context.MODE_PRIVATE)

        val saved = prefs.getString("dock", null)
        dockSlots.clear()
        if (saved == null) {
            dockSlots.addAll(defaultCandidates.map { resolveFirstInstalled(context, it) })
            saveDock()
        } else {
            val parts = saved.split("|")
            repeat(DOCK_SLOTS) { i -> dockSlots.add(parts.getOrElse(i) { "" }) }
        }
        // Yeni standartlar (v2: Q07-də quraşdırılmış tətbiqlər) — yalnız boş slotlara, bir dəfə
        if (prefs.getInt("defaults_v", 1) < 3) {
            defaultCandidates.forEachIndexed { i, list ->
                if (dockSlots[i].isEmpty()) dockSlots[i] = resolveFirstInstalled(context, list)
            }
            saveDock()
            prefs.edit().putInt("defaults_v", 3).apply()
        }

        hidden.clear()
        hidden.addAll(prefs.getStringSet("hidden", emptySet()) ?: emptySet())

        renames.clear()
        runCatching {
            val o = JSONObject(prefs.getString("renames", "{}") ?: "{}")
            o.keys().forEach { k -> renames[k] = o.getString(k) }
        }
    }

    private fun resolveFirstInstalled(context: Context, pkgs: List<String>): String {
        val pm = context.packageManager
        for (p in pkgs) {
            val c = pm.getLaunchIntentForPackage(p)?.component
            if (c != null) return c.flattenToString()
        }
        return ""
    }

    fun setDockSlot(index: Int, component: ComponentName?) {
        if (index !in 0 until DOCK_SLOTS) return
        dockSlots[index] = component?.flattenToString() ?: ""
        saveDock()
    }

    /** Slotun yerini dəyiş (Ayarlar → Dock) */
    fun moveSlot(index: Int, delta: Int) {
        val j = index + delta
        if (index !in 0 until DOCK_SLOTS || j !in 0 until DOCK_SLOTS) return
        val a = dockSlots[index]
        dockSlots[index] = dockSlots[j]
        dockSlots[j] = a
        saveDock()
    }

    private fun saveDock() {
        prefs.edit().putString("dock", dockSlots.joinToString("|")).apply()
    }

    fun isHidden(key: String) = key in hidden

    fun setHidden(key: String, value: Boolean) {
        if (value) { if (key !in hidden) hidden.add(key) } else hidden.remove(key)
        prefs.edit().putStringSet("hidden", hidden.toSet()).apply()
    }

    fun displayLabel(key: String, original: String): String = renames[key] ?: original

    fun rename(key: String, newLabel: String?) {
        if (newLabel.isNullOrBlank()) renames.remove(key) else renames[key] = newLabel.trim()
        val o = JSONObject()
        renames.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("renames", o.toString()).apply()
    }
}
