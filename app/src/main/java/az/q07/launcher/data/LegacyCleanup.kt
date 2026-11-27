package az.q07.launcher.data

import android.content.Context
import java.io.File

/**
 * Köhnə tt / skan məlumatlarının birdəfəlik və səssiz silinməsi.
 * Ekranda heç bir mesaj göstərmir.
 */
object LegacyCleanup {

    private const val FLAG_PREFS = "q07_legacy_cleanup"
    private const val KEY_VERSION = "tt_scan_version"
    private const val VERSION = 2

    /** filesDir-də köhnə skaner/tt faylları (və onların `.tmp` qalıqları) */
    private val FILE_NAMES = listOf(
        "learned_functions.json",
        "vehicle_structure_map.json",
        "recorded_flows.json"
    )

    /** Diagnostika .md faylı — fayl sisteminin iki yerində ola bilər */
    private const val KNOWLEDGE_FILE = "system_knowledge.md"

    /** Köhnə funksiyaların saxladığı SharedPreferences adları */
    private val PREFS_NAMES = listOf(
        "q07_control_bindings",
        "q07_tt_fresh",
        "full_scanner"
    )

    fun runIfNeeded(context: Context) {
        val ctx = context.applicationContext
        val flag = ctx.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        if (flag.getInt(KEY_VERSION, 0) >= VERSION) return

        val dirs = listOfNotNull(
            ctx.filesDir,
            File(ctx.filesDir, "diag"),
            ctx.getExternalFilesDir("diag"),
            ctx.getExternalFilesDir(null)
        )
        runCatching {
            dirs.forEach { dir ->
                FILE_NAMES.forEach { name ->
                    File(dir, name).delete()
                    File(dir, "$name.tmp").delete()
                }
                File(dir, KNOWLEDGE_FILE).delete()
            }
        }
        PREFS_NAMES.forEach { name ->
            runCatching { ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply() }
        }
        // Mühərrikin saxlanmış gedişat id-ləri — gedişatlar artıq yoxdur
        runCatching {
            ctx.getSharedPreferences("q07_engine_control_prefs", Context.MODE_PRIVATE)
                .edit().remove("start_flow_id").remove("stop_flow_id").apply()
        }
        flag.edit().putInt(KEY_VERSION, VERSION).apply()
        android.util.Log.i("Q07", "köhnə tt/skan məlumatları silindi")
    }
}
