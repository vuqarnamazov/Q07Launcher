package az.q07.launcher.data

import android.content.Context
import android.util.Base64
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * Launcher ayarlarının (SharedPreferences) köçürülməsi.
 *
 * - [export] — bu maşındakı bütün launcher prefs fayllarını JSON-a yazır (adb ilə çıxarmaq üçün).
 * - [seedIfNeeded] — APK-nın içindəki `assets/q07_default_settings.json` faylını yalnız
 *   mövcud olmayan prefs fayllarına yazır. Yəni başqa maşında quraşdırılanda sənin
 *   ayarların default kimi gəlir; artıq qurulmuş maşında heç nə dəyişmir.
 *
 * Lisenziya/aktivasiya, cihaz kodu, telefon–maşın PIN-i kimi cihaza bağlı fayllar köçürülmür.
 */
object SettingsSnapshot {
    private const val ASSET = "q07_default_settings.json"
    private const val MARKER_PREFS = "q07_seed_prefs"
    private const val MARKER_KEY = "seeded"

    /**
     * Yalnız launcher ayarları köçürülür.
     * Lisenziya (`q07_github_lic_prefs`), şifrəli anbarlar (`*_secure` — Android Keystore-a bağlıdır),
     * cihaz kodu, telefon–maşın PIN-i, son tətbiqlər və s. kimi cihaza bağlı fayllar daxil deyil.
     */
    private val ALLOW_FILES = setOf(
        "q07_settings", "q07_launcher", "q07_widgets", "q07_carlook", "q07_home",
        "q07_drivemode", "q07_dualnet", "q07_multi_app_frame_prefs",
        "q07_engine_control_prefs", "q07_adas_radar", "q07_adb"
    )

    fun prefsDir(context: Context): File =
        File(context.applicationContext.dataDir, "shared_prefs")

    /** Köçürülə bilən launcher prefs faylları */
    fun eligibleFiles(context: Context): List<File> =
        prefsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.filter { it.nameWithoutExtension in ALLOW_FILES }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Bütün uyğun prefs fayllarını JSON kimi xarici qovluğa yazır və yolunu qaytarır */
    fun export(context: Context): String? = runCatching {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(null), "diag").apply { mkdirs() }
        val out = File(dir, "q07_settings_snapshot.json")
        val files = JSONObject()
        for (f in eligibleFiles(app)) {
            val xml = scrub(f.readText())
            files.put(f.name, Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
        out.writeText(JSONObject().put("exportedAt", System.currentTimeMillis()).put("files", files).toString())
        out.absolutePath
    }.getOrNull()

    /** APK içindəki default ayarları yalnız mövcud olmayan fayllara yazır */
    @Synchronized
    fun seedIfNeeded(context: Context) {
        val app = context.applicationContext
        val marker = app.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean(MARKER_KEY, false)) return
        val json = runCatching {
            app.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        runCatching {
            val files = JSONObject(json).getJSONObject("files")
            val dir = prefsDir(app).apply { mkdirs() }
            val names = files.keys()
            while (names.hasNext()) {
                val name = names.next()
                val target = File(dir, name)
                if (target.exists()) continue // bu maşında artıq öz ayarı var — toxunmuruq
                val xml = String(Base64.decode(files.getString(name), Base64.NO_WRAP), Charsets.UTF_8)
                target.writeText(scrub(xml))
            }
            marker.edit().putBoolean(MARKER_KEY, true).apply()
            SelfDiag.log("settings", "default ayarlar tətbiq olundu", mapOf("files" to files.length()))
        }
    }

    /** Mühərrik vəziyyəti və nömrə cihaza bağlıdır — yeni maşında təmiz başlasın */
    private fun scrub(xml: String): String =
        xml.replace("name=\"last_engine_running\" value=\"true\"", "name=\"last_engine_running\" value=\"false\"")
            .replace(Regex("<string name=\\\"plate\\\">[^<]*</string>"), "<string name=\"plate\"></string>")

    /** adb ilə: am start -n az.q07.launcher/.MainActivity --es q07_export_settings 1 */
    fun exportAndToast(context: Context) {
        Thread {
            val path = export(context)
            android.os.Handler(context.mainLooper).post {
                Toast.makeText(
                    context,
                    if (path != null) "Ayarlar yazıldı: $path" else "Ayarları yazmaq alınmadı",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}
