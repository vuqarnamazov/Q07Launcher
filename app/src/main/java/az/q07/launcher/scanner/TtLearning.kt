package az.q07.launcher.scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import az.q07.launcher.data.FactoryBridge
import az.q07.launcher.data.PathControl
import az.q07.launcher.data.VehicleControlBindings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * «tt» — 1 toxunuşlu yol öyrənmə.
 *
 * Səhifədə idarəyə toxunulur → sessiya açılır → maşın ekranındakı hədəf düymə bir dəfə basılır →
 * xüsusi imkan xidməti həmin klik addımını tutur, yeni öyrənilmiş funksiya kimi saxlanır və
 * idarəyə bağlanır. Menyu açılmır: növbəti toxunuş həmin yolu səssiz icra edir.
 *
 * Öyrənilmiş yollar [LearnedFunctionsRepository] vasitəsilə AES-256 ilə şifrələnmiş faylda saxlanır.
 */
object TtLearning {

    data class Pending(val controlId: String, val title: String, val at: Long = System.currentTimeMillis())

    private const val TIMEOUT_MS = 30_000L

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private const val TAG = "Q07TT"

    private val main = Handler(Looper.getMainLooper())

    private val timeout = Runnable {
        if (_pending.value != null) {
            _pending.value = null
            _status.value = "Öyrənmə vaxtı bitdi — yenidən toxunun"
        }
    }

    /** İdarə üçün öyrənmə sessiyasını başladır. Qaytarılan: sessiya açıldı. */
    fun start(context: Context, control: PathControl): Boolean {
        val ctx = context.applicationContext
        FullVehicleScannerManager.init(ctx)
        if (!FactoryBridge.isEnabled(ctx)) {
            _status.value = "Xüsusi imkan xidməti aktiv deyil — öyrənmə mümkün deyil"
            return false
        }
        // Öyrənmə yalnız fon rejimində işləyir
        if (FullVehicleScannerManager.mode.value == FullVehicleScannerManager.Mode.OFF) {
            FullVehicleScannerManager.setMode(ctx, FullVehicleScannerManager.Mode.BACKGROUND)
        }
        _pending.value = Pending(control.id, control.title)
        _status.value = "«${control.title}»: maşın ekranında hədəf düyməni bir dəfə bas"
        android.util.Log.i(TAG, "tt öyrənmə başladı: ${control.id} (${control.title})")
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, TIMEOUT_MS)
        return true
    }

    fun cancel() {
        main.removeCallbacks(timeout)
        if (_pending.value != null) _status.value = "Öyrənmə ləğv edildi"
        _pending.value = null
    }

    fun clearStatus() { _status.value = null }

    /**
     * [FullVehicleScannerManager] hər klikdə çağırır.
     * Aktiv sessiya varsa kliki yeni funksiya kimi saxlayır və idarəyə bağlayır.
     */
    fun onClick(context: Context, step: FlowStep): Boolean {
        val p = _pending.value ?: return false
        if (step.packageName == context.packageName) return false
        _pending.value = null
        main.removeCallbacks(timeout)
        val (fn, added) = LearnedFunctionsRepository.add(p.title, listOf(step), null)
        VehicleControlBindings.bind(context, p.controlId, fn.id)
        android.util.Log.i(
            TAG,
            "tt klik tutuldu: ${step.packageName}/${step.className} vid=${step.viewIdResName} " +
                "text=${step.text} -> ${p.controlId} bağlandı (${if (added) "yeni" else "mövcud"}, id=${fn.id})"
        )
        _status.value = (if (added) "✓ «${p.title}» öyrənildi" else "✓ «${p.title}» mövcud yola bağlandı") +
            " · ${fn.name}"
        return true
    }
}
