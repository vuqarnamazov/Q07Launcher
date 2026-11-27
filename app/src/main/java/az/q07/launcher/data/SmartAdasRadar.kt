package az.q07.launcher.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Radarda görünən yaxın Q07 — L2 klub protokolu (BLE xidməti F007) ilə tapılır. */
data class AdasContact(
    val plate: String,
    val rssi: Int,
    val distanceM: Int,
    /** İstiqamət BLE-də yoxdur — nömrədən törənən sabit bucaq (təxmini) */
    val bearingDeg: Int,
    val lastSeenMs: Long
)

/**
 * Smart ADAS Radar — L2 layihəsindən Q07 Launcher-ə köçürülüb.
 *
 * Yaxınlıqdakı Q07-lər eyni BLE xidmətini (0000F007-…) yayımlayır; hər maşın öz dövlət nömrəsini
 * service data kimi verir. Radər RSSI ilə təxmini məsafəni hesablayır və 10 km radius əvəzinə
 * real BLE əhatəsini (≈150 m) göstərir. Şəbəkə/internet tələb etmir.
 */
object SmartAdasRadar {

    private val SERVICE_UUID: ParcelUuid =
        ParcelUuid(UUID.fromString("0000F007-0000-1000-8000-00805F9B34FB"))
    private const val PREFS = "q07_adas_radar"
    private const val KEY_INVISIBLE = "invisible"
    private const val STALE_MS = 8_000L
    private const val MAX_DISTANCE_M = 200

    private val _contacts = MutableStateFlow<List<AdasContact>>(emptyList())
    val contacts: StateFlow<List<AdasContact>> = _contacts

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    @Volatile var invisible: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pruneJob: Job? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var users = 0

    fun requiredPermissions(): Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Ekranda görünəndə çağırılır (neçə komponent istifadə edirsə, sayılır). */
    @Synchronized
    fun acquire(context: Context) {
        val app = context.applicationContext
        invisible = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_INVISIBLE, false)
        users++
        if (_active.value) return
        if (!hasPermissions(app)) {
            _message.value = "BLE üçün məkan icazəsi lazımdır"
            return
        }
        start(app)
    }

    /** İcazə verildikdən sonra radəri başlat (istifadəçi sayını artırmadan). */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (_active.value) return
        if (!hasPermissions(context)) return
        if (users == 0) users = 1
        start(context.applicationContext)
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    @Synchronized
    fun setInvisible(context: Context, value: Boolean) {
        invisible = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_INVISIBLE, value).apply()
        if (value) {
            stopAdvertising()
            _contacts.value = emptyList()
        } else {
            runCatching { startAdvertising(context.applicationContext) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun start(context: Context) {
        _message.value = null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _message.value = "Bluetooth söndürülüb"
            return
        }
        runCatching { startAdvertising(context) }
        runCatching {
            scanner = adapter.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    onAdvertisement(result)
                }
                override fun onScanFailed(errorCode: Int) {
                    _message.value = "BLE skan xətası ($errorCode)"
                }
            }
            scanCallback = cb
            scanner?.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb
            )
        }.onFailure { _message.value = "BLE skan başlamadı: ${it.javaClass.simpleName}" }

        _active.value = true
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                delay(2000)
                val now = System.currentTimeMillis()
                val fresh = _contacts.value.filter { now - it.lastSeenMs < STALE_MS }
                if (fresh.size != _contacts.value.size) _contacts.value = fresh
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(context: Context) {
        if (invisible) return
        val plate = CarLook.plate.value.ifBlank { "Q07" }.take(12)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val adv = adapter.bluetoothLeAdvertiser ?: run {
            _message.value = "Bu cihaz BLE yayımını dəstəkləmir (yalnız qəbul)"
            return
        }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_UUID)
            .addServiceData(SERVICE_UUID, plate.toByteArray(Charsets.UTF_8))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                _message.value = "BLE yayımı alınmadı ($errorCode) — yalnız qəbul"
            }
        }
        advCallback = cb
        adv.startAdvertising(settings, data, cb)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        runCatching { if (advertiser != null && advCallback != null) advertiser?.stopAdvertising(advCallback) }
        advertiser = null
        advCallback = null
    }

    private fun onAdvertisement(result: ScanResult?) {
        if (invisible) return
        val data = result?.scanRecord?.getServiceData(SERVICE_UUID) ?: return
        val plate = String(data, Charsets.UTF_8).trim().trim('\u0000')
        if (plate.isBlank() || plate == CarLook.plate.value) return
        val rssi = result.rssi
        val distance = estimateDistanceMeters(rssi)
        val bearing = ((plate.hashCode() and 0x7fffffff) % 360)
        val now = System.currentTimeMillis()
        val updated = _contacts.value.filterNot { it.plate == plate } +
            AdasContact(plate, rssi, distance, bearing, now)
        _contacts.value = updated.sortedBy { it.distanceM }.take(12)
    }

    /** RSSI → təxmini məsafə (log-distance model, TxPower -59 dBm @1 m, n = 2). */
    private fun estimateDistanceMeters(rssi: Int): Int {
        val meters = 10.0.pow((-59.0 - rssi) / 20.0)
        return meters.toInt().coerceIn(1, MAX_DISTANCE_M)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun stop() {
        runCatching { if (scanner != null && scanCallback != null) scanner?.stopScan(scanCallback) }
        scanner = null
        scanCallback = null
        stopAdvertising()
        pruneJob?.cancel()
        pruneJob = null
        _contacts.value = emptyList()
        _active.value = false
    }

    /** Radar üzərində blipin yerini hesablamaq üçün (ui qatı istifadə edir) */
    fun blipOffset(contact: AdasContact, radiusPx: Float): Pair<Float, Float> {
        val angle = Math.toRadians(contact.bearingDeg.toDouble())
        val r = (contact.distanceM.toFloat() / MAX_DISTANCE_M) * radiusPx
        return (cos(angle).toFloat() * r) to (sin(angle).toFloat() * r)
    }
}
