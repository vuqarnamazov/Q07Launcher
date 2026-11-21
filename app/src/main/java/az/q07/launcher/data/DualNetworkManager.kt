package az.q07.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.InetSocketAddress
import java.net.Socket

/**
 * İkili şəbəkə meneceri:
 *  • İnternet  → telefonun Wi-Fi Hotspot-u (WifiNetworkSuggestion, sistemin əsas Wi-Fi-ı)
 *  • OBD2      → WiFi_OBDII / V-LINK adapteri (WifiNetworkSpecifier, NET_CAPABILITY_INTERNET OLMADAN)
 *
 * bindProcessToNetwork İSTİFADƏ OLUNMUR. OBD socket-i birbaşa obdNetwork-a bağlanır,
 * ona görə launcher-in və sistemin bütün interneti telefonun Wi-Fi-ı ilə gedir.
 *
 * Qeyd: Android 11-də STA+STA (eyni anda iki Wi-Fi) rəsmi dəstəklənmir. Çip saxlaya bilmirsə,
 * OBD qoşulanda hotspot müvəqqəti düşə bilər — onLost-da hotspot avtomatik bərpa olunur.
 */
object DualNetworkManager {
    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val cm get() = app.getSystemService(ConnectivityManager::class.java)
    private val wifi get() = app.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    // ── İstifadəçi ayarları (bir dəfə daxil edilir) ──
    var hotspotSsid by mutableStateOf("")
        private set
    var hotspotPass by mutableStateOf("")
        private set
    var obdSsid by mutableStateOf("WiFi_OBDII")
        private set
    var obdPass by mutableStateOf("")
        private set
    var autoStart by mutableStateOf(false)
        private set

    // ── Canlı vəziyyət (UI üçün) ──
    var phoneConnected by mutableStateOf(false)
        private set
    var phoneInternet by mutableStateOf(false)
        private set
    var obdConnected by mutableStateOf(false)
        private set
    var obdStatus by mutableStateOf("Qoşulmayıb")
        private set
    var lastMessage by mutableStateOf("")
        private set

    /** OBD şəbəkəsi — socket-lər yalnız bunun üzərindən açılır */
    @Volatile
    var obdNetwork: Network? = null
        private set

    private var suggestion: WifiNetworkSuggestion? = null
    private var obdCallback: ConnectivityManager.NetworkCallback? = null
    private var monitoring = false
    private var obdWanted = false

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("q07_dualnet", Context.MODE_PRIVATE)
        hotspotSsid = prefs.getString("hsSsid", "")!!
        hotspotPass = prefs.getString("hsPass", "")!!
        obdSsid = prefs.getString("obdSsid", "WiFi_OBDII")!!
        obdPass = prefs.getString("obdPass", "")!!
        autoStart = prefs.getBoolean("auto", false)
        startMonitoring()
        if (autoStart) {
            if (hotspotSsid.isNotBlank()) connectToPhoneHotspot(hotspotSsid, hotspotPass)
            if (obdSsid.isNotBlank()) connectToObd(obdSsid, obdPass)
        }
    }

    fun saveSettings(hsSsid: String, hsPass: String, oSsid: String, oPass: String, auto: Boolean) {
        hotspotSsid = hsSsid.trim(); hotspotPass = hsPass
        obdSsid = oSsid.trim(); obdPass = oPass; autoStart = auto
        prefs.edit()
            .putString("hsSsid", hotspotSsid).putString("hsPass", hotspotPass)
            .putString("obdSsid", obdSsid).putString("obdPass", obdPass)
            .putBoolean("auto", autoStart)
            .apply()
    }

    // ─────────────────────── Telefon hotspot (internet) ───────────────────────

    /**
     * Telefonun hotspot-unu sistemə prioritet internet şəbəkəsi kimi tanıdır.
     * İlk dəfə sistem «Təklif olunan şəbəkələrə icazə verilsin?» bildirişi göstərə bilər — təsdiqlə.
     */
    fun connectToPhoneHotspot(ssid: String = hotspotSsid, password: String = hotspotPass): Boolean {
        if (ssid.isBlank()) { lastMessage = "Telefon hotspot SSID-i daxil edilməyib (Ayarlar → Şəbəkə)"; return false }
        val b = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setPriority(Int.MAX_VALUE / 2)            // digər təkliflərdən öndə
            .setIsAppInteractionRequired(false)
            .setIsMetered(true)                         // telefon interneti — ölçülən
        if (password.isNotEmpty()) b.setWpa2Passphrase(password)
        val s = b.build()

        suggestion?.let { runCatching { wifi.removeNetworkSuggestions(listOf(it)) } }
        val status = wifi.addNetworkSuggestions(listOf(s))
        suggestion = s
        lastMessage = when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> "Telefon hotspot-u sistemə əlavə edildi — avtomatik qoşulacaq"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "Hotspot artıq əlavə olunub"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                "Sistem təklifləri bloklayıb — Wi-Fi ayarlarında icazə ver"
            else -> "Hotspot əlavə olunmadı (kod $status)"
        }
        if (!wifi.isWifiEnabled) lastMessage += " · Wi-Fi söndürülüb, yandır"
        refreshPhoneState()
        return status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
    }

    /** Hazırda telefona qoşuluyuqmu (SSID oxumaq üçün məkan icazəsi lazımdır) */
    @SuppressLint("MissingPermission")
    private fun refreshPhoneState() {
        val ssid = runCatching {
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.ssid?.trim('"')
        }.getOrNull()
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        phoneConnected = hotspotSsid.isNotBlank() && ssid == hotspotSsid
        phoneInternet = phoneConnected && onWifi &&
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** Sistemin əsas (default) şəbəkəsini izləyir; hotspot düşəndə yenidən bağlanır */
    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { refreshPhoneState() } }
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                main.post { refreshPhoneState() }
            }
            override fun onLost(network: Network) {
                main.post {
                    refreshPhoneState()
                    // Hotspot itdi → təklifi yenidən ver ki, sistem ona geri qoşulsun
                    if (hotspotSsid.isNotBlank()) main.postDelayed({ reconnectHotspot() }, 3000)
                }
            }
        })
    }

    private fun reconnectHotspot() {
        refreshPhoneState()
        if (!phoneConnected && hotspotSsid.isNotBlank()) {
            connectToPhoneHotspot()
            lastMessage = "Telefon hotspot-una yenidən qoşulma cəhdi…"
        }
    }

    // ─────────────────────── OBD (lokal, internetsiz) ───────────────────────

    /**
     * OBD adapterinə WifiNetworkSpecifier ilə qoşulur (INTERNET qabiliyyəti olmadan).
     * SSID «*» ilə bitirsə prefiks kimi axtarılır (məs. «WiFi_OBD*», «V-LINK*»).
     * Sistem bir dəfə «Cihaza qoşulsun?» pəncərəsi göstərəcək — launcher açıq olmalıdır.
     */
    fun connectToObd(ssid: String = obdSsid, password: String = obdPass) {
        if (ssid.isBlank()) { obdStatus = "OBD SSID daxil edilməyib"; return }
        disconnectObd()
        obdWanted = true

        val spec = WifiNetworkSpecifier.Builder().apply {
            if (ssid.endsWith("*")) setSsidPattern(PatternMatcher(ssid.dropLast(1), PatternMatcher.PATTERN_PREFIX))
            else setSsid(ssid)
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // lokal cihaz
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                obdNetwork = network
                main.post {
                    obdConnected = true
                    obdStatus = "Qoşulub ✓"
                    ObdMonitor.restart()
                    // Çip iki şəbəkəni saxlamırsa hotspot düşüb — vəziyyəti yoxla
                    main.postDelayed({ refreshPhoneState() }, 2500)
                }
            }

            override fun onUnavailable() {
                obdNetwork = null
                main.post {
                    obdConnected = false
                    obdStatus = "Tapılmadı və ya imtina edildi"
                    obdCallback = null
                    reconnectHotspot()
                }
            }

            override fun onLost(network: Network) {
                obdNetwork = null
                main.post {
                    obdConnected = false
                    obdStatus = "Bağlantı itdi"
                    // OBD itəndə sistem internet Wi-Fi-na qayıtsın
                    reconnectHotspot()
                }
            }
        }
        obdCallback = cb
        obdStatus = "Axtarılır… (sistem pəncərəsində təsdiqlə)"
        try {
            cm.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            obdCallback = null
            obdStatus = "Xəta: ${e.message}"
        }
    }

    fun disconnectObd() {
        obdWanted = false
        obdCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        obdCallback = null
        obdNetwork = null
        obdConnected = false
        obdStatus = "Qoşulmayıb"
    }

    /**
     * OBD socket-i: birbaşa obdNetwork üzərindən (bindProcessToNetwork YOX).
     * obdNetwork yoxdursa — adi socket (istifadəçi OBD-yə əl ilə qoşulubsa).
     */
    fun openObdSocket(host: String, port: Int, timeoutMs: Int = 4000): Socket {
        val net = obdNetwork
        val s = Socket()
        if (net != null) net.bindSocket(s)
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return s
    }
}
