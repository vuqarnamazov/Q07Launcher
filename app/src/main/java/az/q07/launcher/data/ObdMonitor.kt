package az.q07.launcher.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Changan Qiyuan Q07 üçün OBD-II PID kataloqu.
 *
 * Maşının skanından (22.11.2026): CAN 11-bit 500 kbit/s, 7 ECU cavab verir:
 *   7E8 ECM (mühərrik) · 7EA HPCM (hibrid idarə) · 7EB DMCM (elektromotor)
 *   7EC BECM (yüksək gərginlikli batareya) · 7ED · 7EE VTM · 7EF BCC
 * Hər göstərici yalnız ÖZ blokundan oxunur (əvvəl cavablar qarışırdı).
 *
 * @param ecu cavab verən blokun başlığı (7E8…7EF); sorğu ünvanı = ecu - 8
 * @param rate 1 = hər dövrə, N = hər N-ci dövrə, 0 = bir dəfə
 */
data class ObdPid(
    val key: String,
    val name: String,
    val unit: String,
    val cmd: String,
    val group: String,
    val ecu: String = "7E8",
    val offset: Int = 0,
    val len: Int = 1,
    val scale: Double = 1.0,
    val add: Double = 0.0,
    val decimals: Int = 0,
    val warn: Double? = null,
    val isTemp: Boolean = false,
    val rate: Int = 1
)

object ObdPids {
    const val G_TEMP = "Temperaturlar"
    const val G_HYBRID = "Batareya və elektrik"
    const val G_DRIVE = "Hərəkət və mühərrik"
    const val G_AIR = "Hava, yanacaq, qarışıq"
    const val G_DIST = "Yürüş və vaxt"
    val groups = listOf(G_TEMP, G_HYBRID, G_DRIVE, G_AIR, G_DIST)

    val all = listOf(
        // ── Temperaturlar ──
        ObdPid("ambient", "Çöl temperaturu", "°C", "0146", G_TEMP, add = -40.0, isTemp = true, rate = 5),
        ObdPid("coolant", "Soyuducu maye", "°C", "0105", G_TEMP, add = -40.0, warn = 105.0, isTemp = true, rate = 3),
        ObdPid("intake", "Giriş havası", "°C", "010F", G_TEMP, add = -40.0, isTemp = true, rate = 3),
        ObdPid("catalyst", "Katalizator", "°C", "013C", G_TEMP, len = 2, scale = 0.1, add = -40.0, warn = 900.0, isTemp = true, rate = 5),

        // ── Batareya və elektrik ──
        ObdPid("soc", "Hibrid batareya (SOC)", "%", "015B", G_HYBRID, ecu = "7EC", scale = 100.0 / 255.0, decimals = 1, rate = 2),
        ObdPid("soc_hpcm", "SOC (hibrid blok)", "%", "015B", G_HYBRID, ecu = "7EA", scale = 100.0 / 255.0, decimals = 1, rate = 5),
        ObdPid("voltage", "12V şəbəkə", "V", "0142", G_HYBRID, len = 2, scale = 0.001, decimals = 2, rate = 3),
        ObdPid("voltage_becm", "12V (batareya bloku)", "V", "0142", G_HYBRID, ecu = "7EC", len = 2, scale = 0.001, decimals = 2, rate = 5),

        // ── Hərəkət və mühərrik ──
        ObdPid("speed", "Sürət", "km/s", "010D", G_DRIVE),
        ObdPid("rpm", "Mühərrik dövrü", "rpm", "010C", G_DRIVE, len = 2, scale = 0.25),
        ObdPid("load", "Mühərrik yükü", "%", "0104", G_DRIVE, scale = 100.0 / 255.0),
        ObdPid("torque", "Faktiki tork", "%", "0162", G_DRIVE, add = -125.0),
        ObdPid("torque_ref", "İstinad torku", "Nm", "0163", G_DRIVE, len = 2, rate = 0),
        ObdPid("throttle", "Drossel", "%", "0111", G_DRIVE, scale = 100.0 / 255.0),
        ObdPid("throttle_rel", "Nisbi drossel", "%", "0145", G_DRIVE, scale = 100.0 / 255.0, rate = 2),
        ObdPid("throttle_cmd", "Drossel əmri", "%", "014C", G_DRIVE, scale = 100.0 / 255.0, rate = 2),
        ObdPid("pedal", "Qaz pedalı", "%", "0149", G_DRIVE, scale = 100.0 / 255.0),
        ObdPid("timing", "Alışma bucağı", "°", "010E", G_DRIVE, scale = 0.5, add = -64.0, decimals = 1, rate = 2),
        ObdPid("runtime", "Mühərrik işləmə vaxtı", "san", "011F", G_DRIVE, len = 2, rate = 5),

        // ── Hava, yanacaq, qarışıq ──
        ObdPid("fuel", "Yanacaq səviyyəsi", "%", "012F", G_AIR, scale = 100.0 / 255.0, rate = 10),
        ObdPid("map", "Kollektor təzyiqi", "kPa", "010B", G_AIR, rate = 2),
        ObdPid("baro", "Atmosfer təzyiqi", "kPa", "0133", G_AIR, rate = 10),
        ObdPid("lambda", "Lambda (əmr)", "λ", "0144", G_AIR, len = 2, scale = 1.0 / 32768.0, decimals = 3, rate = 3),
        ObdPid("stft", "Qısa yanacaq korreksiyası", "%", "0106", G_AIR, scale = 100.0 / 128.0, add = -100.0, decimals = 1, rate = 3),
        ObdPid("ltft", "Uzun yanacaq korreksiyası", "%", "0107", G_AIR, scale = 100.0 / 128.0, add = -100.0, decimals = 1, rate = 10),

        // ── Yürüş və vaxt ──
        ObdPid("dist_clr", "Xəta silinəndən yürüş", "km", "0131", G_DIST, len = 2, rate = 20),
        ObdPid("dist_mil", "Xəta lampası ilə yürüş", "km", "0121", G_DIST, len = 2, rate = 20),
        ObdPid("warmups", "Isınma dövrləri", "", "0130", G_DIST, rate = 20),
        ObdPid("mil_time", "Xəta lampası ilə vaxt", "dəq", "014D", G_DIST, len = 2, rate = 20)
    )

    val byKey = all.associateBy { it.key }
}

data class ObdState(
    val status: String = "Söndürülüb",
    val connected: Boolean = false,
    val values: Map<String, Double> = emptyMap(),
    val unsupported: Set<String> = emptySet(),
    val vin: String? = null,
    val adapterVolt: String? = null,
    val updatedAt: Long = 0,
    /** Diaqnostika: son xam cavab / socket yolu */
    val detail: String = ""
)

private class ElmLink(val input: InputStream, val output: OutputStream, val closeable: Closeable) {
    fun send(cmd: String, timeoutMs: Long = 2500): String {
        while (input.available() > 0) input.read() // köhnə qalıqları at
        output.write((cmd + "\r").toByteArray())
        output.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw java.io.IOException("Adapter bağlantını kəsdi")
                if (c.toChar() == '>') return sb.toString()
                sb.append(c.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        throw java.io.IOException("Adapter cavab vermədi ($cmd)")
    }

    fun close() = runCatching { closeable.close() }
}

/** ELM327 (Wi-Fi və ya Bluetooth) ilə canlı oxuma. Ekranda kart görünəndə işləyir. */
object ObdMonitor {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var users = 0

    private fun log(msg: String) { android.util.Log.i("Q07OBD", msg) }

    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state

    private var permanent = false

    /** Proses yaşadıqca daim işləsin (bölmədən asılı olmadan) */
    @Synchronized
    fun ensureRunning() {
        if (permanent) return
        permanent = true
        acquire()
    }

    @Synchronized
    fun acquire() {
        users++
        if (job?.isActive != true) job = scope.launch { loop() }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            job?.cancel()
            job = null
        }
    }

    /** Ayarlar dəyişəndə yenidən qoşul */
    @Synchronized
    fun restart() {
        job?.cancel()
        job = if (users > 0) scope.launch { loop() } else null
    }

    @SuppressLint("MissingPermission")
    private fun open(): ElmLink = when (AppSettings.obdMode) {
        "wifi" -> {
            // Socket birbaşa OBD şəbəkəsinə bağlanır (DualNetworkManager)
            val s = DualNetworkManager.openObdSocket(AppSettings.obdHost, AppSettings.obdPort, 4000)
            ElmLink(s.getInputStream(), s.getOutputStream(), s)
        }
        "bt" -> {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: throw IllegalStateException("Bluetooth yoxdur")
            if (!adapter.isEnabled) throw IllegalStateException("Bluetooth söndürülüb")
            if (AppSettings.obdBtMac.isBlank()) throw IllegalStateException("Ayarlarda adapter seçilməyib")
            adapter.cancelDiscovery()
            val sock = adapter.getRemoteDevice(AppSettings.obdBtMac)
                .createRfcommSocketToServiceRecord(SPP_UUID)
            sock.connect()
            ElmLink(sock.inputStream, sock.outputStream, sock)
        }
        else -> throw IllegalStateException("OBD söndürülüb (Ayarlar → OBD)")
    }

    /**
     * ATH1 + ATS0 cavabını ECU-lara görə ayırır: "7E8034105361" → {"7E8": "410536"}.
     * Yalnız tək-kadr cavablar — bütün PID-lərimiz ≤7 baytdır.
     */
    private fun splitByEcu(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        raw.split('\r', '\n').forEach { line0 ->
            val line = line0.replace(" ", "").trim().uppercase()
            if (line.length < 7 || !line.startsWith("7E")) return@forEach
            val hdr = line.substring(0, 3)
            val pci = line.substring(3, 5).toIntOrNull(16) ?: return@forEach
            if (pci > 7) return@forEach
            val data = line.substring(5)
            if (data.length >= pci * 2 && hdr !in out) out[hdr] = data.substring(0, pci * 2)
        }
        return out
    }

    private fun decode(pid: ObdPid, data: String): Double? {
        val head = "4" + pid.cmd.substring(1)
        if (!data.startsWith(head)) return null
        val bytes = data.substring(head.length)
        val start = pid.offset * 2
        val end = start + pid.len * 2
        if (bytes.length < end) return null
        var v = 0L
        for (k in 0 until pid.len) v = v * 256 + bytes.substring(start + k * 2, start + k * 2 + 2).toLong(16)
        return v * pid.scale + pid.add
    }

    /** Sorğu ünvanı: cavab başlığı - 8 (7E8 → 7E0, 7EC → 7E4 …) */
    private fun requestHeader(ecu: String): String =
        Integer.toHexString(ecu.toInt(16) - 8).uppercase()

    @Volatile
    private var countSuffixOk = true
    @Volatile
    private var lastRaw = ""
    @Volatile
    private var verbose = true

    /** Bir PID-i yalnız öz blokundan oxu (fiziki ünvan + cavab sayı «1» → gözləmədən qayıdır) */
    private fun query(link: ElmLink, pid: ObdPid, currentHeader: Array<String>): Double? {
        val req = requestHeader(pid.ecu)
        if (currentHeader[0] != req) { link.send("ATSH$req"); currentHeader[0] = req }
        var raw = link.send(if (countSuffixOk) pid.cmd + "1" else pid.cmd, 2000)
        if (countSuffixOk && raw.contains("?")) { // adapter cavab sayını tanımır
            countSuffixOk = false
            raw = link.send(pid.cmd, 2000)
        }
        lastRaw = "${pid.cmd}@${pid.ecu} → ${raw.replace('\r', ' ').replace('\n', ' ').trim()}"
        if (verbose) log(lastRaw)
        val compact = raw.uppercase().replace(" ", "")
        if ("NODATA" in compact || "ERROR" in compact) return null
        val data = splitByEcu(raw)[pid.ecu] ?: return null
        return decode(pid, data)
    }

    /** VIN (0902, çox-kadrlı) — qoşulanda bir dəfə */
    private fun readVin(link: ElmLink, currentHeader: Array<String>): String? = runCatching {
        if (currentHeader[0] != "7E0") { link.send("ATSH7E0"); currentHeader[0] = "7E0" }
        val raw = link.send("0902", 3000)
        val hex = StringBuilder()
        raw.split('\r', '\n').map { it.replace(" ", "").trim().uppercase() }
            .filter { it.startsWith("7E8") && it.length > 7 }
            .forEach { line ->
                val pci = line[3]
                when (pci) {
                    '1' -> hex.append(line.substring(7).drop(6)) // "10 14" + "49 02 01" → VIN baytları
                    '2' -> hex.append(line.substring(5))
                }
            }
        val chars = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
            .filter { it in 0x30..0x5A }.map { it.toChar() }.joinToString("")
        chars.takeIf { it.length >= 11 }?.take(17)
    }.getOrNull()

    private suspend fun loop() {
        while (true) {
            if (AppSettings.obdMode == "off" && runCatching { DualNetworkManager.findSystemObdNetwork() }.getOrNull() != null) {
                AppSettings.update { obdMode = "wifi" } // maşın artıq OBD Wi-Fi-dadır
            }
            if (AppSettings.obdMode == "off") {
                _state.value = ObdState(status = "Söndürülüb — Ayarlar → OBD")
                delay(3000)
                continue
            }
            var link: ElmLink? = null
            try {
                _state.value = _state.value.copy(status = "Qoşulur…", connected = false)
                log("open mode=${AppSettings.obdMode} host=${AppSettings.obdHost}:${AppSettings.obdPort}")
                link = open()
                log("socket ok (${DualNetworkManager.lastSocketInfo})")
                log("ATZ → " + link.send("ATZ", 4000).trim())
                delay(600)
                // Başlıqlar AÇIQ (ATH1) — cavabın hansı blokdan gəldiyini bilmək üçün; Q07: CAN 11-bit 500k (ATSP6)
                for (c in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATAT2", "ATSP6")) log("$c → " + link.send(c).trim())
                val hdr = arrayOf("")
                countSuffixOk = true
                val adapterV = runCatching { link.send("ATRV").trim() }.getOrNull()
                val vin = readVin(link, hdr)
                log("VIN=$vin volt=$adapterV")
                verbose = true
                _state.value = _state.value.copy(
                    status = "Qoşuldu ✓ — oxunur…", connected = true, vin = vin, adapterVolt = adapterV,
                    detail = "socket: ${DualNetworkManager.lastSocketInfo}"
                )

                val values = _state.value.values.toMutableMap()
                val unsupported = mutableSetOf<String>()
                val misses = HashMap<String, Int>()
                val lastRead = HashMap<String, Long>()
                var cycle = 0
                // Sürətli göstəricilər (rate=1) hər keçiddə; qalanları vaxta görə növbə ilə,
                // bir keçiddə ən çox 3 yavaş sorğu — beləliklə ekran ~0.15–0.3 san-dən bir yenilənir.
                val fast = ObdPids.all.filter { it.rate == 1 }.sortedBy { it.ecu }
                val slow = ObdPids.all.filter { it.rate != 1 }
                var slowIdx = 0
                var lastEmit = 0L
                fun handle(pid: ObdPid, v: Double?) {
                    lastRead[pid.key] = System.currentTimeMillis()
                    if (v == null) {
                        // 3 dəfə ardıcıl cavab yoxdursa — bu maşında yoxdur
                        val m = (misses[pid.key] ?: 0) + 1
                        misses[pid.key] = m
                        if (m >= 3) { unsupported.add(pid.key); values.remove(pid.key) }
                    } else { values[pid.key] = v; misses.remove(pid.key) }
                }
                // 1-ci dövr: hamısını bir dəfə oxu ki, ekran dərhal dolsun
                for (pid in ObdPids.all.sortedBy { it.ecu }) handle(pid, query(link, pid, hdr))
                log("1-ci dövr: ${values.size} dəyər, cavabsız: ${misses.keys}")
                verbose = false
                while (true) {
                    // launcher arxada olanda da oxu (yan panel, qayıdanda təzə dəyər) — sadəcə daha seyrək
                    if (!AppVisibility.visible && !SidePanel.showing) delay(700)
                    if (cycle % 600 == 0 && cycle > 0) { unsupported.clear(); misses.clear() }
                    for (pid in fast) if (pid.key !in unsupported) handle(pid, query(link, pid, hdr))
                    // yavaş göstəricilər: rate × 400 ms-dən bir
                    val now = System.currentTimeMillis()
                    var budget = 3
                    var scanned = 0
                    while (budget > 0 && scanned < slow.size) {
                        val pid = slow[slowIdx % slow.size]
                        slowIdx++; scanned++
                        if (pid.key in unsupported) continue
                        val due = when (pid.rate) {
                            0 -> !values.containsKey(pid.key)
                            else -> now - (lastRead[pid.key] ?: 0L) >= pid.rate * 400L
                        }
                        if (!due) continue
                        handle(pid, query(link, pid, hdr)); budget--
                    }
                    val t = System.currentTimeMillis()
                    if (t - lastEmit >= 120) {
                        lastEmit = t
                        _state.value = _state.value.copy(
                            status = if (values.isEmpty()) "Qoşuldu, amma maşın cavab vermir (kontakt açıqdır?)"
                                     else "Qoşuldu ✓ · ${values.size} göstərici",
                            connected = true, values = values.toMap(),
                            unsupported = unsupported.toSet(), updatedAt = t,
                            detail = lastRaw
                        )
                    }
                    cycle++
                    val pause = AppSettings.obdIntervalMs.toLong()
                    if (pause > 0) delay(pause) else yield()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                link?.close()
                throw e
            } catch (e: Throwable) {
                log("XƏTA: $e  socket=${DualNetworkManager.lastSocketInfo}")
                _state.value = _state.value.copy(
                    detail = DualNetworkManager.lastSocketInfo,
                    status = "Xəta: ${e.message ?: e.javaClass.simpleName} — 5 san. sonra yenidən",
                    connected = false
                )
                link?.close()
                delay(5000)
                continue
            }
        }
    }

    fun format(pid: ObdPid, v: Double): String =
        if (pid.decimals == 0) Math.round(v).toString() else "%.${pid.decimals}f".format(v)
}
