package az.q07.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Hər maşın üçün fərdi lisenziya — VIN-ə bağlı.
 *
 * - Maşının VIN-i oxunur (sistem xüsusiyyətləri, avtomobil xidməti və ya OBD 09 02).
 * - Aktivasiya kodu = SHA-256("Q07-VIN|" + VIN)-in ilk 20 simvolu (VIN özü heç yerə göndərilmir).
 * - Lisenziya = sahibin gizli açarı ilə imzalanmış "Q07L|v1|<kod>" (ECDSA P-256).
 *   Tətbiqdə yalnız açıq açar var — imzanı yalnız sahib yarada bilər.
 * - Lisenziya GitHub-dan (licenses/<kod>.lic) avtomatik yüklənir.
 * - Başqa maşına köçürüləndə: VIN və cihaz ID-si dəyişir → lisenziya keçərsizdir → launcher kilidlənir.
 */
object License {
    private const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2xRZQ4BuyJTtINj1E1+ZN3+nFR3XFXrGz3sw7I+0IBWe1lg+6O0LZQPXWqCiFZXEuvKud02Vc3lgVsTdA9K9Gg=="
    private const val LIC_URL = "https://raw.githubusercontent.com/vuqarnamazov/Q07Launcher/main/licenses/"

    enum class Status { CHECKING, OK, NO_VIN, NOT_ACTIVATED, OTHER_CAR }

    data class State(
        val status: Status = Status.CHECKING,
        val code: String? = null,
        val vinMasked: String? = null,
        val source: String? = null,
        val message: String? = null
    ) {
        val ok get() = status == Status.OK
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val ok: Boolean get() = _state.value.ok

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    private lateinit var app: Context

    fun codeFor(vin: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("Q07-VIN|" + vin.trim().uppercase()).toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(20)
    }

    fun verify(code: String, sigB64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update("Q07L|v1|$code".toByteArray())
            verify(Base64.decode(sigB64.trim(), Base64.DEFAULT))
        }
    }.getOrDefault(false)

    @SuppressLint("HardwareIds")
    private fun deviceId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "?"

    private fun prefs() = app.getSharedPreferences("q07_license", Context.MODE_PRIVATE)

    private fun mask(vin: String) = if (vin.length >= 8) vin.take(3) + "•••••••••" + vin.takeLast(4) else vin

    /** Tətbiq açılanda bir dəfə çağırılır */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val p = prefs()
        val code = p.getString("code", null)
        val sig = p.getString("sig", null)
        val dev = p.getString("dev", null)
        // Bu cihazda əvvəl təsdiqlənibsə — dərhal açılır (VIN oxunmasını gözləmədən)
        if (code != null && sig != null && dev == deviceId(app) && verify(code, sig)) {
            _state.value = State(Status.OK, code, p.getString("vinMasked", null), p.getString("src", null))
        }
        scope.launch { watch() }
    }

    /** VIN-i oxu, lisenziyanı yoxla; uğursuz olsa təkrar et */
    private suspend fun watch() {
        var tries = 0
        while (true) {
            val (vin, src) = readVin()
            if (vin != null) {
                check(vin, src)
                if (_state.value.ok || _state.value.status == Status.OTHER_CAR) {
                    // VIN təsdiqləndi — sonra arabir yenidən yoxla (köçürməyə qarşı)
                    delay(30 * 60_000L); continue
                }
            } else if (!_state.value.ok) {
                _state.value = _state.value.copy(status = Status.NO_VIN,
                    message = "Maşının VIN-i oxunur… (OBD adapteri qoşulmalıdır)")
            }
            tries++
            delay(if (tries < 20) 5_000L else 60_000L)
        }
    }

    /** "Yenidən yoxla" düyməsi */
    fun retry() { scope.launch { val (vin, src) = readVin(); if (vin != null) check(vin, src) } }

    private fun check(vin: String, src: String?) {
        val code = codeFor(vin)
        val p = prefs()
        val known = p.getString("code", null)
        if (known != null && known != code && _state.value.ok) {
            // Lisenziya başqa VIN üçündür — köçürülmüş tətbiq
            SelfDiag.log("license", "VIN uyğun gəlmir — kilid")
            p.edit().clear().apply()
            _state.value = State(Status.OTHER_CAR, code, mask(vin), src,
                "Bu launcher başqa maşın üçün qeydiyyatdan keçib")
            return
        }
        val sig = p.getString("sig", null)?.takeIf { known == code && verify(code, it) } ?: fetch(code)
        if (sig != null && verify(code, sig)) {
            p.edit().putString("code", code).putString("sig", sig).putString("dev", deviceId(app))
                .putString("vinMasked", mask(vin)).putString("src", src).apply()
            if (!_state.value.ok) SelfDiag.log("license", "aktivdir ()")
            _state.value = State(Status.OK, code, mask(vin), src)
        } else {
            _state.value = State(Status.NOT_ACTIVATED, code, mask(vin), src,
                "Bu maşın üçün lisenziya yoxdur. Aktivasiya kodunu sahibinə göndərin.")
        }
    }

    private fun fetch(code: String): String? = runCatching {
        val c = URL("$LIC_URL$code.lic?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        if (c.responseCode != 200) return null
        c.inputStream.bufferedReader().use { it.readText() }.trim()
    }.getOrNull()

    // ---------------- VIN mənbələri ----------------
    private val VIN_RE = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    private fun readVin(): Pair<String?, String?> {
        sysProp()?.let { return it to "sistem" }
        carService()?.let { return it to "avtomobil xidməti" }
        ObdMonitor.state.value.vin?.trim()?.uppercase()?.takeIf { VIN_RE.matches(it) }?.let { return it to "OBD" }
        return null to null
    }

    @SuppressLint("PrivateApi")
    private fun sysProp(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        listOf(
            "persist.vendor.vin", "persist.sys.vin", "persist.vin", "ro.vin", "sys.vin", "vendor.vin",
            "persist.sys.car.vin", "persist.vendor.car.vin", "ro.vendor.vin", "persist.incall.vin", "persist.sys.vehicle.vin"
        ).firstNotNullOfOrNull { k -> (get.invoke(null, k) as? String)?.trim()?.uppercase()?.takeIf { VIN_RE.matches(it) } }
    }.getOrNull()

    /** Android Automotive CarPropertyManager INFO_VIN (icazə verilməsə — keçilir) */
    private fun carService(): String? = runCatching {
        val carCls = Class.forName("android.car.Car")
        val car = carCls.getMethod("createCar", Context::class.java).invoke(null, app) ?: return null
        val mgr = carCls.getMethod("getCarManager", String::class.java).invoke(car, "property") ?: return null
        val v = mgr.javaClass.getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(mgr, String::class.java, 0x11100100, 0)
        val value = v?.javaClass?.getMethod("getValue")?.invoke(v) as? String
        runCatching { carCls.getMethod("disconnect").invoke(car) }
        value?.trim()?.uppercase()?.takeIf { VIN_RE.matches(it) }
    }.getOrNull()
}
