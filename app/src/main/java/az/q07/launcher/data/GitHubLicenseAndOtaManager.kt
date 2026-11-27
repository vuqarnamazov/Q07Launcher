package az.q07.launcher.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class OtaUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val changelog: String,
    val apkUrl: String,
    /** Release mətnindəki hər yenilik ayrıca sətir kimi */
    val changelogItems: List<String> = emptyList()
)

/**
 * Yalnız OTA (versiya yoxlama + APK quraşdırma).
 *
 * Server = GitHub Releases: https://github.com/vuqarnamazov/Q07Launcher/releases
 * Yeni versiya elan olunanda tag «build-<versiya kodu>» olmalıdır (məs. build-84)
 * və içinə app-release.apk yüklənməlidir. Tətbiq həmin elanı oxuyub yeniləyir.
 *
 * Lisenziya/aktivasiya (VIN kodu, licenses.json, ECDSA imza, sertifikat yoxlaması)
 * tamamilə çıxarılıb: launcher heç bir maşına, VIN-ə və ya onlayn siyahıya bağlı deyil.
 */
object GitHubLicenseAndOtaManager {

    // GitHub Releases API — ən son buraxılış
    private const val GITHUB_RELEASES_API =
        "https://api.github.com/repos/vuqarnamazov/Q07Launcher/releases/latest"

    /**
     * Yeni APK versiyasını yoxlayır.
     * Elanın tag-ı «build-84» kimidirsə, rəqəm cari versiya kodundan böyükdürsə və
     * elanın içində .apk varsa — yeniləmə məlumatı qaytarır.
     */
    suspend fun checkForOtaUpdate(currentVersionCode: Int): OtaUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$GITHUB_RELEASES_API?t=${System.currentTimeMillis()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Q07Launcher")
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name", "")
                val remoteCode = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: 0
                val items = parseChangelog(json.optString("body", ""))
                val notes = items.joinToString(" · ").ifBlank { "Yeni təkmilləşdirmələr" }.take(1200)
                val apkUrl = pickApkUrl(json)
                if (remoteCode > currentVersionCode && apkUrl != null) {
                    return@withContext OtaUpdateInfo(
                        true, remoteCode, "build #$remoteCode", notes, apkUrl, items
                    )
                }
            }
        } catch (_: Exception) { }
        return@withContext null
    }

    /** Release mətnini sətir-sətir yeniliklərə bölür (markdown işarələri təmizlənir) */
    private fun parseChangelog(body: String): List<String> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("![") && !it.startsWith("|") }
            .map { it.replace(Regex("^#{1,6}\\s*"), "") }
            .map { it.replace(Regex("^([*+•-])\\s+"), "") }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "") }
            .map { it.replace("**", "").replace("__", "").replace("`", "").trim() }
            .filter { it.length > 2 }
            .take(15)
            .toList()

    /** Elanın faylları arasından APK-nı seçir (prioritet: app-release.apk) */
    private fun pickApkUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
            if (name.equals("app-release.apk", ignoreCase = true)) return url
            if (fallback == null && name.contains("launcher", ignoreCase = true)) fallback = url
        }
        return fallback ?: assets.optJSONObject(0)?.let { a ->
            val n = a.optString("name", "")
            if (n.endsWith(".apk", true)) a.optString("browser_download_url") else null
        }
    }

    // Yeni APK faylını endirir və Android quraşdırıcısını açır
    fun downloadAndInstallApk(context: Context, apkUrl: String) {
        val fileName = "Q07_Launcher_Update.apk"
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Q07 Launcher Yenilənir")
            .setDescription("Yeni versiya yüklənir...")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    installApkFile(context, destFile)
                }
            }
        }
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installApkFile(context: Context, file: File) {
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}
