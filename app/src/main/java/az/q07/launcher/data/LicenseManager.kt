package az.q07.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import az.q07.launcher.BuildConfig
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * VIN-ə bağlı lisenziya (istifadəçinin sistemi: Q07-XXXX-XXXX sorğu kodu → HMAC-SHA256 təsdiq kodu).
 * Gizli açar koda yazılmır: GitHub secret Q07_LIC_SECRET-dən yığılma zamanı gəlir (BuildConfig.LIC_K).
 * Açar hələ əlavə edilməyibsə (boşdursa) — kilid işləmir ki, sahib maşında bağlı qalmasın.
 */
object LicenseManager {
    private const val PREFS_NAME = "q07_secure_lic_prefs"
    private const val KEY_SAVED_CODE = "activation_token"

    private val secret: String by lazy {
        BuildConfig.LIC_K.mapIndexed { i, v -> ((v xor (0x5A + i * 7)) and 0xFF).toChar() }.joinToString("")
    }

    /** Kilid həmişə aktivdir: onlayn təsdiq (licenses.json) açarsız da işləyir */
    val enforced: Boolean get() = true

    /** Oflayn kod (keygen) yalnız GitHub secret Q07_LIC_SECRET yığılmaya daxil edilibsə işləyir */
    val offlineAvailable: Boolean get() = secret.isNotEmpty()

    // GitHub-dakı açıq siyahılar (repo kökündə). Telefonla redaktə: github.com/vuqarnamazov/Q07Launcher/edit/main/licenses.json
    private const val LICENSES_URL = "https://raw.githubusercontent.com/vuqarnamazov/Q07Launcher/main/licenses.json"
    private const val KEY_REMOTE_APPROVED = "remote_approved"

    @Volatile private var cachedFp: String? = null
    @Volatile private var cachedOk: Boolean? = null

    /** 1. Maşının VIN-i + ANDROID_ID → Q07-XXXX-XXXX */
    @SuppressLint("HardwareIds", "PrivateApi")
    fun getVehicleFingerprint(context: Context): String {
        cachedFp?.let { return it }
        val vin = try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java, String::class.java)
            val keys = listOf("ro.changan.vin", "persist.sys.vin", "ro.vehicle.vin", "persist.vendor.vin")
            keys.firstNotNullOfOrNull { key ->
                val v = get.invoke(null, key, "") as String
                if (v.length >= 10) v.uppercase() else null
            }
        } catch (e: Exception) {
            null
        }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        val rawIdentity = if (vin != null) "$vin-$androidId" else "HW-$androidId"
        val hash = sha256(rawIdentity).uppercase()
        return "Q07-${hash.substring(0, 4)}-${hash.substring(4, 8)}".also { cachedFp = it }
    }

    /** 2. Sorğu koduna uyğun təsdiq kodu (keygen.html ilə eyni alqoritm) */
    fun generateExpectedActivationCode(requestCode: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val rawHmac = mac.doFinal(requestCode.trim().uppercase().toByteArray(Charsets.UTF_8))
        val hex = rawHmac.joinToString("") { "%02X".format(it) }
        return "${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
    }

    private fun normalize(s: String): String {
        val raw = s.trim().uppercase().filter { it.isLetterOrDigit() }
        return if (raw.length == 12) "${raw.substring(0, 4)}-${raw.substring(4, 8)}-${raw.substring(8, 12)}" else s.trim().uppercase()
    }

    /** 3. Daxil edilən kodu yoxla və saxla */
    fun verifyAndSave(context: Context, inputCode: String): Boolean {
        if (!offlineAvailable) return false
        val expected = generateExpectedActivationCode(getVehicleFingerprint(context))
        val clean = normalize(inputCode)
        return if (clean == expected) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_SAVED_CODE, clean).apply()
            cachedOk = true
            SelfDiag.log("license", "aktivləşdirildi")
            true
        } else {
            SelfDiag.log("license", "yanlış təsdiq kodu")
            false
        }
    }

    /** 4. Lisenziya aktivdirmi (nəticə yaddaşda saxlanır — tez-tez çağırılır) */
    fun isActivated(context: Context): Boolean {
        cachedOk?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remote = prefs.getString(KEY_REMOTE_APPROVED, null) == getVehicleFingerprint(context)
        val saved = prefs.getString(KEY_SAVED_CODE, null)
        val offline = offlineAvailable && saved != null && saved == generateExpectedActivationCode(getVehicleFingerprint(context))
        val ok = remote || offline
        cachedOk = ok
        return ok
    }

    enum class Remote { APPROVED, BLOCKED, NOT_LISTED, NO_NETWORK }

    /**
     * GitHub licenses.json: {"approved":[...], "blocked":[...]}
     * approved → onlayn aktivləşir (internet olmadan da yadda qalır); blocked → bütün aktivasiya silinir.
     * Şəbəkə yoxdursa heç nə dəyişmir.
     */
    fun syncFromGitHub(context: Context): Remote {
        val code = getVehicleFingerprint(context)
        val body = try {
            val c = (java.net.URL("$LICENSES_URL?t=${System.currentTimeMillis()}").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 7000; readTimeout = 7000; useCaches = false
            }
            if (c.responseCode != 200) return Remote.NO_NETWORK
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return Remote.NO_NETWORK
        }
        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return Remote.NO_NETWORK
        fun has(key: String): Boolean {
            val a = json.optJSONArray(key) ?: return false
            return (0 until a.length()).any { a.optString(it).trim().equals(code, ignoreCase = true) }
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when {
            has("blocked") -> {
                prefs.edit().clear().apply(); cachedOk = false
                SelfDiag.log("license", "GitHub: bloklanıb — kilidləndi", mapOf("kod" to code))
                Remote.BLOCKED
            }
            has("approved") -> {
                prefs.edit().putString(KEY_REMOTE_APPROVED, code).apply(); cachedOk = true
                SelfDiag.log("license", "GitHub: onlayn təsdiqləndi", mapOf("kod" to code))
                Remote.APPROVED
            }
            else -> Remote.NOT_LISTED
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
