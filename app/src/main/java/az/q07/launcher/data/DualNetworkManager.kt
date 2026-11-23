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
    /** Həm «WiFi-OBDII», həm «WiFi_OBDII» (və oxşarlar) — Q07-də adapter «WiFi-OBDII» adlanır */
    const val DEFAULT_OBD = "WiFi?OBD*"

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
    var obdSsid by mutableStateOf(DEFAULT_OBD)
        private set
    var obdPass by mutableStateOf("")
        private set
    var autoStart by mutableStateOf(false)
        private set

    /**
     * Wi-Fi prioriteti (maşının Wi-Fi modulu eyni anda yalnız BİR şəbəkəyə qoşula bilir):
     *  "obd"   → Wi-Fi həmişə OBD-də saxlanılır, internet maşının SIM-i ilə
     *  "phone" → Wi-Fi telefonun hotspot-unda (internet), OBD Wi-Fi istifadə olunmur
     */
    var wifiPriority by mutableStateOf("obd")
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
        obdSsid = prefs.getString("obdSsid", DEFAULT_OBD)!!.let { if (it == "WiFi_OBDII" || it == "WiFi-OBDII") DEFAULT_OBD else it }
        obdPass = prefs.getString("obdPass", "")!!
        autoStart = prefs.getBoolean("auto", true)
        wifiPriority = prefs.getString("prio", "obd")!!
        startMonitoring()
        if (hotspotSsid.isNotBlank()) connectToPhoneHotspot(hotspotSsid, hotspotPass)
        // OBD: «Cihaza qoşulsun?» pəncərəsi artıq avtomatik çıxmır.
        // Sistem özü OBD Wi-Fi-dadırsa — səssiz istifadə et; yoxdursa yalnız «Qoş» düyməsi basılanda soruşulur.
        if (autoStart && wifiPriority == "obd" && obdSsid.isNotBlank() && findSystemObdNetwork() != null) connectToObd(obdSsid, obdPass)
    }

    fun setPriority(p: String) {
        wifiPriority = p
        prefs.edit().putString("prio", p).apply()
        if (p == "obd") connectToObd() else {
            disconnectObd()
            if (hotspotSsid.isNotBlank()) connectToPhoneHotspot()
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
        if (obdWanted && wifiPriority == "obd") return // Wi-Fi OBD-də saxlanılır
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
        // Sistem artıq OBD-dədirsə — dərhal istifadə et (specifier sorğusu yenə də verilir ki, bağlantı SAXLANILSIN)
        findSystemObdNetwork()?.let { net ->
            obdNetwork = net
            obdConnected = true
            obdStatus = "Qoşulub ✓"
            if (AppSettings.obdMode != "wifi") AppSettings.update { obdMode = "wifi" }
            ObdMonitor.restart()
            // sistem artıq OBD-dədir: təsdiq pəncərəsi çıxarmamaq üçün əlavə sorğu vermirik
            obdWanted = true
            return
        }
        obdWanted = true
        // adapter görünmürsə (maşın söndürülüb) — sorğu verib təsdiq pəncərəsi çıxarma, sakitcə gözlə
        if (!obdVisibleInScan(ssid)) {
            releaseRequest()
            obdStatus = "OBD adapteri görünmür"
            retryCount = maxOf(retryCount, 3)
            scheduleRetry("OBD adapteri görünmür")
            return
        }
        releaseRequest()
        retryCount = 0

        val spec = WifiNetworkSpecifier.Builder().apply {
            if (ssid.contains('*') || ssid.contains('?')) setSsidPattern(PatternMatcher(toAndroidGlob(ssid), PatternMatcher.PATTERN_SIMPLE_GLOB))
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
                retryCount = 0
                main.post {
                    obdConnected = true
                    obdStatus = "Qoşulub ✓"
                    // OBD şəbəkəsi var → oxuma rejimi mütləq Wi-Fi olsun (ayarlar sıfırlansa belə)
                    if (AppSettings.obdMode != "wifi") AppSettings.update { obdMode = "wifi" }
                    ObdMonitor.restart()
                    // Çip iki şəbəkəni saxlamırsa hotspot düşüb — vəziyyəti yoxla
                    main.postDelayed({ refreshPhoneState() }, 2500)
                }
            }

            override fun onUnavailable() {
                obdNetwork = null
                main.post {
                    obdConnected = false
                    obdCallback = null
                    scheduleRetry("Adapter tapılmadı (maşın sönülü ola bilər)")
                }
            }

            override fun onLost(network: Network) {
                obdNetwork = null
                main.post {
                    obdConnected = false
                    scheduleRetry("Bağlantı itdi")
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

    /**
     * İstifadəçi nümunəsi (* = istənilən mətn, ? = bir simvol) → Android PatternMatcher simple glob
     * (orada «.» = bir simvol, «.*» = istənilən mətn, «\\.» = nöqtənin özü).
     */
    fun toAndroidGlob(p: String): String = buildString {
        p.forEach { ch ->
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                '.' -> append("\\.")
                else -> append(ch)
            }
        }
    }

    /** SSID-i glob nümunəsi ilə yoxla (* və ?) */
    fun ssidMatches(pattern: String, ssid: String?): Boolean {
        if (ssid == null) return false
        if (!pattern.contains('*') && !pattern.contains('?')) return ssid == pattern
        return PatternMatcher(toAndroidGlob(pattern), PatternMatcher.PATTERN_SIMPLE_GLOB).match(ssid)
    }

    /** Sistem hazırda adapterin Wi-Fi-ına qoşuludursa, həmin şəbəkəni qaytar */
    @SuppressLint("MissingPermission")
    fun findSystemObdNetwork(): Network? {
        @Suppress("DEPRECATION")
        val ssid = runCatching { wifi.connectionInfo?.ssid?.trim('"') }.getOrNull()
        if (!ssidMatches(obdSsid, ssid)) return null
        return findNetworkForHost("192.168.0.10")
    }

    /** Ünvanı öz alt şəbəkəsində olan Wi-Fi şəbəkəsini tap (məs. 192.168.0.x) */
    @Suppress("DEPRECATION")
    private fun findNetworkForHost(host: String): Network? {
        val prefix = host.substringBeforeLast('.') + "."
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                cm.getLinkProperties(n)?.linkAddresses?.any { it.address.hostAddress?.startsWith(prefix) == true } == true
        }
    }

    /** İstifadəçi «Ayır» basanda: OBD-ni burax, Wi-Fi telefona qayıtsın */
    fun disconnectObd() {
        obdWanted = false
        main.removeCallbacksAndMessages(RETRY_TOKEN)
        releaseRequest()
        obdNetwork = null
        obdConnected = false
        obdStatus = "Qoşulmayıb"
        if (hotspotSsid.isNotBlank()) main.postDelayed({ reconnectHotspot() }, 2000)
    }

    private fun releaseRequest() {
        obdCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        obdCallback = null
    }

    private val RETRY_TOKEN = Any()
    private var retryCount = 0

    /** OBD itəndə özü yenidən qoşulur (5 san → 15 san → 30 san…), istifadəçidən heç nə istəmədən */
    /** Son Wi-Fi skanında OBD şəbəkəsi var? (skan nəticəsi oxuna bilmirsə — true, köhnə davranış) */
    private fun obdVisibleInScan(ssid: String): Boolean = runCatching {
        val wm = wifi
        @Suppress("DEPRECATION") wm.startScan()
        val results = wm.scanResults
        if (results.isNullOrEmpty()) return@runCatching true
        val rx = Regex(toAndroidGlob(ssid))
        results.any { r -> r.SSID == ssid || rx.matches(r.SSID) }
    }.getOrDefault(true)

    private fun scheduleRetry(reason: String) {
        if (!obdWanted || wifiPriority != "obd") { obdStatus = reason; return }
        retryCount++
        val delay = when { retryCount <= 2 -> 5_000L; retryCount <= 5 -> 15_000L; else -> 30_000L }
        obdStatus = "$reason — ${delay / 1000} san sonra yenidən"
        main.removeCallbacksAndMessages(RETRY_TOKEN)
        main.postAtTime({ if (obdWanted) connectToObd() }, RETRY_TOKEN, android.os.SystemClock.uptimeMillis() + delay)
    }

    /**
     * OBD socket-i: birbaşa obdNetwork üzərindən (bindProcessToNetwork YOX).
     * obdNetwork yoxdursa — adi socket (istifadəçi OBD-yə əl ilə qoşulubsa).
     */
    /** Aktiv VPN (məs. ZeroTier) — VPN bütün tətbiqləri tutursa OBD-yə yol bağlana bilər */
    fun activeVpnName(): String? {
        val vpn = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: return null
        return cm.getLinkProperties(vpn)?.interfaceName ?: "VPN"
    }

    /** Son qoşulma cəhdinin izahı (ekranda və logcat-da göstərilir) */
    @Volatile
    var lastSocketInfo: String = ""

    /**
     * OBD socket-i. Bir neçə yol sınanır:
     *  1) OBD Wi-Fi şəbəkəsinə bindSocket  2) həmin şəbəkənin socketFactory-si  3) adi socket.
     * VPN (ZeroTier və s.) «bypass»-a icazə vermirsə 1–2 EPERM verir — onda 3 sınanır.
     */
    fun openObdSocket(host: String, port: Int, timeoutMs: Int = 4000): Socket {
        val net = obdNetwork ?: findNetworkForHost(host)
        val errors = mutableListOf<String>()
        if (net != null) {
            try {
                val s = Socket()
                net.bindSocket(s)
                s.connect(InetSocketAddress(host, port), timeoutMs)
                lastSocketInfo = "bind ✓"
                return s
            } catch (e: Exception) { errors += "bind: ${e.message ?: e.javaClass.simpleName}" }
            try {
                val s = net.socketFactory.createSocket()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                lastSocketInfo = "factory ✓"
                return s
            } catch (e: Exception) { errors += "factory: ${e.message ?: e.javaClass.simpleName}" }
        } else errors += "OBD Wi-Fi şəbəkəsi tapılmadı"
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            lastSocketInfo = "plain ✓"
            return s
        } catch (e: Exception) { errors += "plain: ${e.message ?: e.javaClass.simpleName}" }
        val vpn = activeVpnName()
        lastSocketInfo = errors.joinToString(" | ")
        android.util.Log.w("Q07OBD", "socket failed: $lastSocketInfo vpn=$vpn")
        throw java.io.IOException(
            when {
                net == null -> "Maşın OBD Wi-Fi-a qoşulu deyil"
                vpn != null -> "Adapter cavab vermir (VPN $vpn aktivdir)"
                else -> "Adapter cavab vermir"
            }
        )
    }
}
