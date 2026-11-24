package az.q07.launcher.data

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

data class OtaUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val changelog: String,
    val apkUrl: String
)

object GitHubLicenseAndOtaManager {

    // Asimmetrik lisenziya (ECDSA P-256): APK-da YALNIZ açıq açar var.
    // Gizli açar yalnız sahibdədir (Q07_Admin_Keygen.html). APK tam sökülsə belə aktivasiya kodu yaradıla bilməz.
    private const val PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtTewp5XYqT5AaaUTPiYtyMilGYW94LoODEFLZadHHAgkaZ5h4BSxmZXgt7XMQmiMPDWuO16rrMqL9oI8Whw5Ww=="

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

    // 2. Oflayn aktivasiya imzasını (Base64) açıq açarla yoxlayır.
    //    Sahib keygen-də sorğu kodunu gizli açarla imzalayır → bu imza yalnız həmin koda (həmin VIN-ə) yarayır.
    private fun verifyOfflineSignature(requestCode: String, signatureB64: String): Boolean {
        return try {
            val keyBytes = android.util.Base64.decode(PUBLIC_KEY_B64, android.util.Base64.DEFAULT)
            val pub = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes))
            val sigRaw = android.util.Base64.decode(signatureB64.trim(), android.util.Base64.DEFAULT)
            val der = if (sigRaw.size == 64) p1363ToDer(sigRaw) else sigRaw // WebCrypto (r||s) → DER
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(requestCode.trim().uppercase().toByteArray(Charsets.UTF_8))
                verify(der)
            }
        } catch (_: Exception) {
            false
        }
    }

    // WebCrypto imzası 64 baytdır (r||s); Java DER gözləyir — çeviririk.
    private fun p1363ToDer(sig: ByteArray): ByteArray {
        fun trim(b: ByteArray): ByteArray {
            var i = 0; while (i < b.size - 1 && b[i].toInt() == 0) i++
            var r = b.copyOfRange(i, b.size)
            if (r[0].toInt() and 0x80 != 0) r = byteArrayOf(0) + r
            return r
        }
        val r = trim(sig.copyOfRange(0, 32)); val sPart = trim(sig.copyOfRange(32, 64))
        val content = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, sPart.size.toByte()) + sPart
        return byteArrayOf(0x30, content.size.toByte()) + content
    }

    // Bizim imza sertifikatının SHA-256 izi — yalnız bu açarla imzalanmış APK keçərlidir.
    // Kimsə launcheri sökub (kilidi çıxarıb) öz açarı ilə yenidən imzalasa, iz uyğun gəlməyəcək.
    private const val OWNER_CERT_SHA256 = "7315A442DD20F32AC1D8272BB5EF02EF091F31D531C6B11190D3279608B1CCEF"

    @Volatile private var sigChecked = false
    @Volatile private var sigValid = true

    // Təhlükəsiz tərəf: imzanı OXUYUB BAŞQA açar olduğunu dəqiq görəndə bloklayır.
    // Oxuya bilməsə və ya uyğun gəlsə, sahibi öz maşınında bloklamır (fail-open).
    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun hasOwnerSignature(context: Context): Boolean {
        if (sigChecked) return sigValid
        sigChecked = true
        sigValid = try {
            val pm = context.packageManager
            val md = MessageDigest.getInstance("SHA-256")
            val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val s = info.signingInfo
                when {
                    s == null -> null
                    s.hasMultipleSigners() -> s.apkContentsSigners
                    else -> s.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            if (sigs.isNullOrEmpty()) true // oxunmadı → bloklama
            else sigs.any {
                md.digest(it.toByteArray()).joinToString("") { b -> "%02X".format(b) }.equals(OWNER_CERT_SHA256, ignoreCase = true)
            }
        } catch (_: Exception) {
            true // yoxlama mümkün olmadı → sahibi bloklamayaq
        }
        SelfDiag.log("license", if (sigValid) "imza yoxlanıldı: OK" else "imza UYĞUN DEYİL — sındırılmış nüsxə")
        return sigValid
    }

    // 3. Yerli yaddaşda aktiv olub-olmadığını yoxlayır
    fun isLocallyActivated(context: Context): Boolean {
        if (!hasOwnerSignature(context)) return false // yenidən imzalanmış (sındırılmış) nüsxə — açılmır
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val reqCode = getVehicleRequestCode(context)
        // Onlayn təsdiq bu maşının kodu ilə yadda saxlanır: prefs başqa maşına köçürülsə, kod uyğun gəlməyəcək
        if (prefs.getString(KEY_REMOTE_APPROVED, null) == reqCode) return true
        // Oflayn imza da yalnız bu maşının VIN-i üçün keçərlidir (başqa maşında kod fərqli olur)
        val savedSig = prefs.getString(KEY_OFFLINE_TOKEN, null) ?: return false
        return verifyOfflineSignature(reqCode, savedSig)
    }

    // 4. Sahibin verdiyi oflayn aktivasiya imzasını yoxlayır və saxlayır
    fun verifyManualCode(context: Context, inputCode: String): Boolean {
        if (!hasOwnerSignature(context)) return false
        val reqCode = getVehicleRequestCode(context)
        val clean = inputCode.trim()
        return if (verifyOfflineSignature(reqCode, clean)) {
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
                                .edit().putString(KEY_REMOTE_APPROVED, reqCode).apply()
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
