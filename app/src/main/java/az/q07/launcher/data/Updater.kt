package az.q07.launcher.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import az.q07.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub-dan yeniləmə: son buraxılışı yoxlayır, APK-nı yükləyir, imzasını yoxlayır
 * (yalnız bizim açarla imzalanmış APK qəbul edilir) və sistem quraşdırıcısı ilə qurur.
 * Lisenziya ayrıca saxlanır — yeniləmədən sonra maşın aktiv qalır.
 */
object Updater {
    private const val API = "https://api.github.com/repos/vuqarnamazov/Q07Launcher/releases/latest"
    const val ACTION_RESULT = "az.q07.launcher.UPDATE_RESULT"

    data class Info(val build: Int, val url: String?, val size: Long, val error: String? = null) {
        val newer get() = url != null && build > BuildConfig.CI_BUILD
    }

    suspend fun check(): Info = withContext(Dispatchers.IO) {
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val n = o.getString("tag_name").substringAfter("build-").toIntOrNull() ?: 0
            val assets = o.getJSONArray("assets")
            var url: String? = null; var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); size = a.optLong("size"); break }
            }
            Info(n, url, size)
        } catch (e: Exception) {
            Info(0, null, 0, "Yoxlamaq alınmadı: internet yoxdur")
        }
    }

    /** Yüklə + imzanı yoxla + quraşdır. Xəta mətni qaytarır (null = quraşdırma başladı) */
    suspend fun install(context: Context, info: Info, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val url = info.url ?: return@withContext "Yükləmə linki yoxdur"
        val file = File(context.cacheDir, "update.apk")
        try {
            var conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10000; conn.readTimeout = 20000
            // GitHub başqa hosta yönləndirir
            if (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location"); conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val r = input.read(buf); if (r < 0) break
                        out.write(buf, 0, r); done += r
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext "Yükləmə alınmadı: ${e.message}"
        }
        if (!sameSigner(context, file)) {
            file.delete()
            SelfDiag.log("update", "imza uyğun deyil — quraşdırılmadı")
            return@withContext "Təhlükəsizlik: yeni APK bizim açarla imzalanmayıb — quraşdırılmadı"
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            return@withContext "Quraşdırma icazəsi yoxdur (Ayarlar → Naməlum mənbələr → Q07 Launcher)"
        }
        runCatching {
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_RESULT)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
            SelfDiag.log("update", "quraşdırma başladı: build ${info.build}")
            null
        }.getOrElse { "Quraşdırma alınmadı: ${it.message}" }
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.apkContentsSigners
        val other = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
            ?: return false
        val pkgOk = pm.getPackageArchiveInfo(apk.path, 0)?.packageName == context.packageName
        pkgOk && mine.map { it.toCharsString() }.toSet() == other.map { it.toCharsString() }.toSet()
    }.getOrDefault(false)
}

/** Sistem quraşdırıcısının cavabı: təsdiq lazımdırsa pəncərəni açır */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> SelfDiag.log("update", "uğurla quruldu")
            else -> SelfDiag.log("update", "uğursuz: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
