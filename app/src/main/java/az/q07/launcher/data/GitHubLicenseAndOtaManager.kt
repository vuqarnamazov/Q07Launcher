package az.q07.launcher.data

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class OtaUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val changelog: String,
    val apkUrl: String
)

object GitHubLicenseAndOtaManager {

    // DİQQƏT: Q07_Admin_Keygen.html faylındakı SECRET_KEY ilə EYNİ olmalıdır!
    // Repo açıqdır — açar koda yazılmır, GitHub secret Q07_LIC_SECRET-dən yığılma zamanı gəlir (BuildConfig.LIC_K).
    private val SECRET_KEY: String by lazy {
        az.q07.launcher.BuildConfig.LIC_K.mapIndexed { i, v -> ((v xor (0x5A + i * 7)) and 0xFF).toChar() }.joinToString("")
    }

    // GitHub repo raw ünvanları (açıq repo — maşın parolsuz oxuya bilsin; "launcher" reposu gizlidir):
    private const val GITHUB_LICENSES_URL = "https://raw.githubusercontent.com/vuqarnamazov/Q07Launcher/main/licenses.json"
    private const val GITHUB_VERSION_URL = "https://raw.githubusercontent.com/vuqarnamazov/Q07Launcher/main/version.json"

    private const val PREFS_NAME = "q07_github_lic_prefs"
    private const val KEY_OFFLINE_TOKEN = "offline_token"
    private const val KEY_REMOTE_APPROVED = "remote_approved"

    // 1. Maşının VIN + Cihaz ID kimliyini oxuyur
    @SuppressLint("HardwareIds", "PrivateApi")
    @Volatile private var cachedRequestCode: String? = null

    fun getVehicleRequestCode(context: Context): String {
        cachedRequestCode?.let { return it }
        val vin = try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java, String::class.java)
            val keys = listOf("ro.changan.vin", "persist.sys.vin", "ro.vehicle.vin", "persist.vendor.vin")
            keys.firstNotNullOfOrNull { key ->
                val v = get.invoke(null, key, "") as String
                if (v.length >= 10) v.uppercase() else null
            }
        } catch (_: Exception) {
            null
        }

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        val raw = if (vin != null) "$vin-$androidId" else "HW-$androidId"
        val hash = sha256(raw).uppercase()
        return "Q07-${hash.substring(0, 4)}-${hash.substring(4, 8)}".also { cachedRequestCode = it }
    }

    // 2. Oflayn HMAC-SHA256 təsdiq kodunu hesablayır
    fun computeExpectedOfflineCode(requestCode: String): String {
        if (SECRET_KEY.isEmpty()) return "-" // açar yığılmaya düşməyibsə oflayn kod işləmir (yalnız onlayn)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val rawHmac = mac.doFinal(requestCode.trim().uppercase().toByteArray(Charsets.UTF_8))
        val hex = rawHmac.joinToString("") { "%02X".format(it) }
        return "${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
    }

    // 3. Yerli yaddaşda aktiv olub-olmadığını yoxlayır
    fun isLocallyActivated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REMOTE_APPROVED, false)) return true

        val savedToken = prefs.getString(KEY_OFFLINE_TOKEN, null) ?: return false
        val reqCode = getVehicleRequestCode(context)
        return savedToken == computeExpectedOfflineCode(reqCode)
    }

    // 4. Əllə daxil edilən kodu yoxlayır
    fun verifyManualCode(context: Context, inputCode: String): Boolean {
        val reqCode = getVehicleRequestCode(context)
        val expected = computeExpectedOfflineCode(reqCode)
        val clean = inputCode.trim().uppercase()
        return if (clean == expected) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_OFFLINE_TOKEN, clean).apply()
            true
        } else {
            false
        }
    }

    // 5. GitHub-dakı licenses.json faylını yoxlayır (Siz telefonla siyahıya əlavə etmisinizsə açılır)
    suspend fun syncLicenseFromGitHub(context: Context): Boolean = withContext(Dispatchers.IO) {
        val reqCode = getVehicleRequestCode(context)
        try {
            val url = URL("$GITHUB_LICENSES_URL?t=${System.currentTimeMillis()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val approvedArr = json.optJSONArray("approved")
                val blockedArr = json.optJSONArray("blocked")

                // Əgər bloklanıbsa dərhal kilidlə
                if (blockedArr != null) {
                    for (i in 0 until blockedArr.length()) {
                        if (blockedArr.getString(i).equals(reqCode, ignoreCase = true)) {
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().clear().apply()
                            return@withContext false
                        }
                    }
                }

                // Əgər təsdiqlənmişlər siyahısındadırsa avtomatik aktiv et
                if (approvedArr != null) {
                    for (i in 0 until approvedArr.length()) {
                        if (approvedArr.getString(i).equals(reqCode, ignoreCase = true)) {
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(KEY_REMOTE_APPROVED, true).apply()
                            return@withContext true
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return@withContext isLocallyActivated(context)
    }

    // 6. GitHub-dan yeni APK versiyasını (OTA Update) yoxlayır
    suspend fun checkForOtaUpdate(currentVersionCode: Int): OtaUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$GITHUB_VERSION_URL?t=${System.currentTimeMillis()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val remoteCode = json.optInt("versionCode", currentVersionCode)
                val remoteName = json.optString("versionName", "1.0")
                val notes = json.optString("changelog", "Yeni təkmilləşdirmələr")
                val apkUrl = json.optString("apkUrl", "")
                if (remoteCode > currentVersionCode && apkUrl.isNotBlank()) {
                    return@withContext OtaUpdateInfo(true, remoteCode, remoteName, notes, apkUrl)
                }
            }
        } catch (_: Exception) { }
        return@withContext null
    }

    // 7. Yeni APK faylını endirir və Android quraşdırıcısını açır
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

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
