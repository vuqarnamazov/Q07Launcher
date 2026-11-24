package az.q07.launcher.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import az.q07.launcher.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class OtaUpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val changelog: String,
    val apkUrl: String
)

/**
 * Uzaqdan yeniləmə (OTA): repo kökündəki version.json-u yoxlayır.
 * {"versionCode": 52, "versionName": "1.51", "changelog": "...", "apkUrl": "https://github.com/.../Q07Launcher.apk"}
 * versionCode quraşdırılmışdan böyükdürsə bildiriş göstərilir; APK endirilir, imzası yoxlanılır, quraşdırıcı açılır.
 */
object OtaUpdater {
    private const val VERSION_URL = "https://raw.githubusercontent.com/vuqarnamazov/Q07Launcher/main/version.json"
    private const val FILE_NAME = "Q07_Launcher_Update.apk"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    private val _update = MutableStateFlow<OtaUpdateInfo?>(null)
    val update: StateFlow<OtaUpdateInfo?> = _update
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    @Volatile private var lastCheck = 0L
    @Volatile private var dismissedCode = 0

    /** Fon iş parçacığında çağır. force=false olduqda 6 saatda bir yoxlayır */
    fun check(force: Boolean = false): OtaUpdateInfo? {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_EVERY_MS) return _update.value
        lastCheck = now
        return try {
            val c = (URL("$VERSION_URL?t=$now").openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000; readTimeout = 7000; useCaches = false
            }
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val code = j.optInt("versionCode", 0)
            val url = j.optString("apkUrl", "")
            val info = if (code > BuildConfig.VERSION_CODE && url.startsWith("https://")) OtaUpdateInfo(
                code, j.optString("versionName", code.toString()), j.optString("changelog", "Yeni təkmilləşdirmələr"), url
            ) else null
            if (info != null && (force || info.latestVersionCode != dismissedCode)) _update.value = info
            if (info == null) _update.value = null
            SelfDiag.log("ota", if (info != null) "yeni versiya var: ${info.latestVersionName}" else "son versiyadır", mapOf("uzaq" to code, "bu" to BuildConfig.VERSION_CODE))
            info
        } catch (e: Exception) {
            null
        }
    }

    /** "Sonra" — bu versiya üçün bildirişi bu sessiyada göstərmə */
    fun dismiss() {
        dismissedCode = _update.value?.latestVersionCode ?: 0
        _update.value = null
    }

    /** APK-nı DownloadManager ilə endirir, sonra imza və versiyanı yoxlayıb quraşdırıcını açır */
    fun downloadAndInstall(context: Context, info: OtaUpdateInfo) {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val dest = File(dir, FILE_NAME)
        if (dest.exists()) dest.delete()
        _status.value = "Yüklənir…"
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Q07 Launcher yenilənir")
            .setDescription("v${info.latestVersionName} yüklənir…")
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { app.unregisterReceiver(this) }
                install(app, dest, info)
            }
        }
        app.registerReceiver(rcv, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        SelfDiag.log("ota", "endirmə başladı", mapOf("url" to info.apkUrl))
    }

    @Suppress("DEPRECATION")
    private fun install(context: Context, file: File, info: OtaUpdateInfo) {
        if (!file.exists() || file.length() < 100_000) {
            _status.value = "Yükləmə alınmadı"; SelfDiag.log("ota", "fayl yoxdur/kiçikdir"); return
        }
        val pm = context.packageManager
        // Eyni paket, daha yeni versiya və EYNİ imza olmalıdır — yoxsa Android "tətbiq quraşdırılmadı" deyəcək
        val arc = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        fun sig(p: android.content.pm.PackageInfo?) = p?.signatures?.firstOrNull()?.toByteArray()
            ?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
        val problem = when {
            arc == null -> "APK oxunmadı"
            arc.packageName != context.packageName -> "başqa tətbiqin APK-sıdır"
            arc.longVersionCode <= BuildConfig.VERSION_CODE -> "APK köhnədir (${arc.longVersionCode})"
            sig(arc) != sig(mine) -> "APK başqa açarla imzalanıb — quraşdırıla bilməz"
            else -> null
        }
        if (problem != null) {
            _status.value = "Yeniləmə dayandırıldı: $problem"
            SelfDiag.log("ota", "rədd edildi: $problem"); return
        }
        _status.value = "Quraşdırma pəncərəsi açılır…"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            _status.value = "Quraşdırıcı açılmadı: ${it.message}"
            SelfDiag.log("ota", "quraşdırıcı xətası: ${it.message}")
        }
        SelfDiag.log("ota", "quraşdırıcı açıldı", mapOf("versiya" to info.latestVersionName))
    }
}
