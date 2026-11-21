package az.q07.launcher.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * OBD-II PID tərifi. Dəyər = (seçilmiş baytlar × scale) + add
 * Q07-OBD tətbiqindəki standart parametrlərlə eynidir.
 */
data class ObdPid(
    val key: String,
    val name: String,
    val unit: String,
    val cmd: String,
    val offset: Int = 0,
    val len: Int = 1,
    val scale: Double = 1.0,
    val add: Double = 0.0,
    val decimals: Int = 0,
    val warn: Double? = null,
    val isTemp: Boolean = false
)

object ObdPids {
    val all = listOf(
        ObdPid("speed", "Sürət", "km/s", "010D"),
        ObdPid("ambient", "Çöl temperaturu", "°C", "0146", add = -40.0, isTemp = true),
        ObdPid("coolant", "Soyuducu maye", "°C", "0105", add = -40.0, warn = 105.0, isTemp = true),
        ObdPid("coolant2", "Soyuducu maye 2", "°C", "0167", offset = 2, add = -40.0, warn = 105.0, isTemp = true),
        ObdPid("intake", "Giriş havası", "°C", "010F", add = -40.0, isTemp = true),
        ObdPid("oil", "Motor yağı", "°C", "015C", add = -40.0, warn = 130.0, isTemp = true),
        ObdPid("soc", "Hibrid batareya", "%", "015B", scale = 100.0 / 255.0),
        ObdPid("voltage", "Gərginlik", "V", "0142", len = 2, scale = 0.001, decimals = 1),
        ObdPid("rpm", "Mühərrik dövrü", "rpm", "010C", len = 2, scale = 0.25),
        ObdPid("load", "Mühərrik yükü", "%", "0104", scale = 100.0 / 255.0),
        ObdPid("fuel", "Yanacaq", "%", "012F", scale = 100.0 / 255.0)
    )
}

data class ObdState(
    val status: String = "Söndürülüb",
    val connected: Boolean = false,
    val values: Map<String, Double> = emptyMap(),
    val unsupported: Set<String> = emptySet(),
    val updatedAt: Long = 0
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
                Thread.sleep(10)
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

    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state

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
            val s = Socket()
            s.connect(InetSocketAddress(AppSettings.obdHost, AppSettings.obdPort), 4000)
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

    private fun parse(pid: ObdPid, raw: String): Double? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        if ("NODATA" in clean || "ERROR" in clean || "?" in clean) return null
        val head = "4" + pid.cmd.substring(1)          // 010D -> 410D
        val i = clean.indexOf(head)
        if (i < 0) return null
        val data = clean.substring(i + head.length)
        val start = pid.offset * 2
        val end = start + pid.len * 2
        if (data.length < end) return null
        var v = 0L
        for (k in 0 until pid.len) {
            v = v * 256 + data.substring(start + k * 2, start + k * 2 + 2).toLong(16)
        }
        return v * pid.scale + pid.add
    }

    private suspend fun loop() {
        while (true) {
            if (AppSettings.obdMode == "off") {
                _state.value = ObdState(status = "Söndürülüb — Ayarlar → OBD")
                delay(3000)
                continue
            }
            var link: ElmLink? = null
            try {
                _state.value = _state.value.copy(status = "Qoşulur…", connected = false)
                link = open()
                link.send("ATZ", 4000)
                delay(800)
                for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) link.send(c)
                link.send("0100", 8000) // protokolu avtomatik tap
                _state.value = _state.value.copy(status = "Qoşuldu ✓", connected = true)

                val unsupported = mutableSetOf<String>()
                var cycle = 0
                while (true) {
                    if (cycle % 30 == 0) unsupported.clear() // vaxtaşırı yenidən yoxla (mühərrik işə düşə bilər)
                    val values = _state.value.values.toMutableMap()
                    for (pid in ObdPids.all) {
                        if (pid.key in unsupported) continue
                        val v = parse(pid, link.send(pid.cmd))
                        if (v == null) { unsupported.add(pid.key); values.remove(pid.key) }
                        else values[pid.key] = v
                    }
                    _state.value = ObdState(
                        status = "Qoşuldu ✓", connected = true, values = values,
                        unsupported = unsupported.toSet(), updatedAt = System.currentTimeMillis()
                    )
                    cycle++
                    delay(AppSettings.obdIntervalMs.toLong())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                link?.close()
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
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
