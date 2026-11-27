package az.q07.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import android.provider.Settings
import android.util.Base64
import az.q07.launcher.BuildConfig
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

enum class LicenseStatus {
    /** Bu cihaz üçün keçərli aktivasiya var */
    ACTIVATED,
    /** Hələ aktivləşdirilməyib */
    NOT_ACTIVATED,
    /** APK sahibin açarı ilə imzalanmayıb (yenidən imzalanmış nüsxə) */
    WRONG_SIGNATURE,
    /** Debugger qoşulub — açılmır */
    TAMPERED
}

/**
 * Oflayn, cihaza bağlı lisenziya.
 *
 * - Qısa cihaz kodu VIN (varsa) + ANDROID_ID-dən SHA-256 ilə çıxarılır: 8 rəqəm (XXXX XXXX).
 * - Aktivasiya kodu üç formada qəbul olunur:
 *   1) 8 rəqəmli qısa kod (XXXX XXXX) — HMAC-SHA256(SHORT_SECRET, «Q07N:» + cihaz kodu).
 *      Telefondakı «Q07 Admin» tətbiqi bunu yaradır; əl ilə yazmaq üçün qısadır.
 *   2) Köhnə 12 simvolluq hex kod (XXXX-XXXX-XXXX) — əvvəlki aktivasiyalar pozulmasın.
 *   3) Uzun ECDSA P-256 imzası — köhnə aktivasiyalar üçün (gizli açar sahibdədir).
 * - Kod başqa maşına köçürülsə imza uyğun gəlmir → açılmır.
 * - APK başqa açarla yenidən imzalansa, sertifikat izi uyğun gəlmir → açılmır.
 * - Hər açılışda imza yenidən yoxlanılır; prefs faylını kopyalamaq kömək etmir.
 */
object LicenseManager {

    // APK-da olan açıq açar (SPKI DER, Base64). Gizli açar repoda saxlanmır.
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtTewp5XYqT5AaaUTPiYtyMilGYW94LoODEFLZadHHAgkaZ5h4BSxmZXgt7XMQmiMPDWuO16rrMqL9oI8Whw5Ww=="

    // Sahibin release imza sertifikatının SHA-256 izi.
    private const val OWNER_CERT_SHA256 =
        "7315A442DD20F32AC1D8272BB5EF02EF091F31D531C6B11190D3279608B1CCEF"

    // Qısa kod üçün simmetrik açar (admin tətbiqi ilə eyni). 12 simvolluq kod bu açarla hesablanır.
    private const val SHORT_SECRET = "Q07_SPj4waCMwptlVbH0rXzmbDwXE6h0OYsTZcvZyY7e"

    private const val PREFS = "q07_license"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE = "device"
    private const val KEY_AT = "activated_at"

    @Volatile private var cachedDeviceCode: String? = null
    @Volatile private var cachedShortDeviceCode: String? = null
    @Volatile private var cachedRawId: String? = null

    /** Cihazın xam identifikatoru (VIN + ANDROID_ID) */
    @SuppressLint("HardwareIds", "PrivateApi")
    private fun rawId(context: Context): String {
        cachedRawId?.let { return it }
        val vin = try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java, String::class.java)
            val keys = listOf(
                "ro.changan.vin", "persist.sys.vin", "ro.vehicle.vin",
                "persist.vendor.vin", "persist.changan.vin", "ro.vin"
            )
            keys.firstNotNullOfOrNull { key ->
                val v = get.invoke(null, key, "") as String
                if (v.length >= 10) v.uppercase() else null
            }
        } catch (_: Exception) {
            null
        }
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"
        return (if (vin != null) "$vin-$androidId" else "HW-$androidId").also { cachedRawId = it }
    }

    /** Köhnə cihaz kodu: Q07-XXXX-XXXX (köhnə aktivasiyaların yoxlanışı üçün) */
    fun deviceCode(context: Context): String {
        cachedDeviceCode?.let { return it }
        val hash = sha256(rawId(context)).uppercase()
        return "Q07-${hash.substring(0, 4)}-${hash.substring(4, 8)}".also { cachedDeviceCode = it }
    }

    /** Ekranda göstərilən qısa cihaz kodu: 8 rəqəm, «XXXX XXXX» */
    fun shortDeviceCode(context: Context): String {
        cachedShortDeviceCode?.let { return it }
        val b = MessageDigest.getInstance("SHA-256")
            .digest(("Q07S:" + rawId(context)).toByteArray(Charsets.UTF_8))
        return formatDigits(numberFrom(b) % 100_000_000L).also { cachedShortDeviceCode = it }
    }

    fun status(context: Context): LicenseStatus {
        // Release APK sahibin açarı ilə imzalanmalıdır
        if (!BuildConfig.DEBUG) {
            if (Debug.isDebuggerConnected()) return LicenseStatus.TAMPERED
            if (!hasOwnerSignature(context)) return LicenseStatus.WRONG_SIGNATURE
        }
        return if (isActivated(context)) LicenseStatus.ACTIVATED else LicenseStatus.NOT_ACTIVATED
    }

    /** Yadda saxlanmış aktivasiya bu cihaz üçün keçərlidirmi? */
    fun isActivated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null) ?: return false
        // Kod hər dəfə cari cihaz koduna görə yoxlanılır: prefs başqa cihaza köçürülsə işləməz
        val legacy = deviceCode(context)
        return verifyShortCode(shortDeviceCode(context), token) ||
            verifyLegacyShortCode(legacy, token) ||
            verifySignature(legacy, token)
    }

    /** İstifadəçinin daxil etdiyi kodu yoxlayır və düzgündürsə saxlayır. */
    fun activate(context: Context, input: String): Boolean {
        if (!BuildConfig.DEBUG && !hasOwnerSignature(context)) return false
        val token = input.trim()
        if (token.isEmpty()) return false
        val short = shortDeviceCode(context)
        val legacy = deviceCode(context)
        val ok = verifyShortCode(short, token) ||
            verifyLegacyShortCode(legacy, token) ||
            verifySignature(legacy, token)
        if (!ok) {
            SelfDiag.log("license", "aktivasiya uğursuz", mapOf("kod" to short))
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE, short)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        SelfDiag.log("license", "aktivasiya OK", mapOf("kod" to short))
        return true
    }

    fun logDeviceCode(context: Context) {
        runCatching {
            SelfDiag.log("license", "cihaz kodu", mapOf("kod" to shortDeviceCode(context)))
        }
    }

    // ---------------- qısa kod ----------------

    /**
     * 8 rəqəmli qısa kod: HMAC-SHA256(SHORT_SECRET, «Q07N:» + cihaz kodu) → ilk 4 bayt → 8 rəqəm.
     * Format: XXXX XXXX (boşluq, tire və s. nəzərə alınmır; yalnız rəqəmlər).
     * Telefonda kodu oxuyub başqa maşına əl ilə yazmaq üçün.
     */
    fun shortCode(requestCode: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(
            javax.crypto.spec.SecretKeySpec(
                SHORT_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"
            )
        )
        val digits = requestCode.filter { it.isDigit() }
        val b = mac.doFinal(("Q07N:" + digits).toByteArray(Charsets.UTF_8))
        return formatDigits(numberFrom(b) % 100_000_000L)
    }

    private fun verifyShortCode(requestCode: String, input: String): Boolean {
        val given = input.filter { it.isDigit() }
        if (given.length != 8) return false
        val expected = shortCode(requestCode).filter { it.isDigit() }
        var diff = 0
        for (i in 0 until 8) if (given[i] != expected[i]) diff++
        return diff == 0
    }

    /** Köhnə 12 simvolluq hex kodun yoxlanışı (əvvəlki aktivasiyalar üçün) */
    private fun verifyLegacyShortCode(requestCode: String, input: String): Boolean {
        val given = input.uppercase().filter { it.isLetterOrDigit() }
        if (given.length != 12) return false
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(
            javax.crypto.spec.SecretKeySpec(
                SHORT_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"
            )
        )
        val hex = mac.doFinal(requestCode.trim().uppercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.uppercase()
        val expected = hex.substring(0, 12)
        var diff = 0
        for (i in 0 until 12) if (given[i] != expected[i]) diff++
        return diff == 0
    }

    private fun numberFrom(b: ByteArray): Long {
        var n = 0L
        for (i in 0 until 4) n = (n shl 8) or (b[i].toLong() and 0xff)
        return n
    }

    private fun formatDigits(n: Long): String {
        val d = "%08d".format(n)
        return "${d.substring(0, 4)} ${d.substring(4)}"
    }

    // ---------------- imza yoxlanışı ----------------

    /** Aktivasiya imzası: həmin cihaz kodunun ECDSA P-256 (SHA-256) imzası. */
    private fun verifySignature(requestCode: String, signatureB64: String): Boolean {
        return try {
            val keyBytes = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val sigRaw = Base64.decode(signatureB64.trim(), Base64.DEFAULT)
            // WebCrypto 64 bayt (r||s) verir, Java DER gözləyir
            val der = if (sigRaw.size == 64) p1363ToDer(sigRaw) else sigRaw
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(requestCode.trim().uppercase().toByteArray(Charsets.UTF_8))
                verify(der)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun p1363ToDer(sig: ByteArray): ByteArray {
        fun trim(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i].toInt() == 0) i++
            var r = b.copyOfRange(i, b.size)
            if (r[0].toInt() and 0x80 != 0) r = byteArrayOf(0) + r
            return r
        }
        val r = trim(sig.copyOfRange(0, 32))
        val sPart = trim(sig.copyOfRange(32, 64))
        val content = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, sPart.size.toByte()) + sPart
        return byteArrayOf(0x30, content.size.toByte()) + content
    }

    @Volatile private var sigChecked = false
    @Volatile private var sigValid = false

    /** APK gerçək sahib sertifikatı ilə imzalanıbmı? */
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
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            if (sigs.isNullOrEmpty()) false
            else sigs.any { sig ->
                md.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
                    .equals(OWNER_CERT_SHA256, ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
        SelfDiag.log(
            "license",
            if (sigValid) "imza yoxlanıldı: OK" else "imza UYĞUN DEYİL — APK sahibin açarı ilə imzalanmayıb"
        )
        return sigValid
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
