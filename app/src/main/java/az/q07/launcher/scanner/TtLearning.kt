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
 * «tt» — yol öyrənmə.
 *
 * Səhifədə idarəyə toxunulur → sessiya açılır → maşın ekranında **hədəf düymə 2 saniyə basılıb saxlanılır** →
 * həmin düymə (ekran dəyişməsindən və ya özünün hara apardığından asılı olmayaraq) seçilir,
 * yeni öyrənilmiş funksiya kimi saxlanır və idarəyə bağlanır.
 *
 * Aralıq gedişlər (menyu → alt menyu) qeydə alınmır — yalnız uzun basılan hədəf. Bu, «ilk gediş səhv
 * yazıldı» probleminin qarşısını alır: seçim kliki tutmaqla deyil, basıb-saxlama ilə edilir.
 *
 * Öyrənilmiş yollar [LearnedFunctionsRepository] vasitəsilə AES-256 ilə şifrələnmiş faylda saxlanır.
 */
object TtLearning {

    data class Pending(val controlId: String, val title: String, val at: Long = System.currentTimeMillis())

    private const val TIMEOUT_MS = 45_000L

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private const val TAG = "Q07TT"

    private val main = Handler(Looper.getMainLooper())

    private val timeout = Runnable {
        if (_pending.value != null) {
            _pending.value = null
            TtHoldCapture.stop()
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
        _status.value = "«${control.title}»: maşın ekranındakı hədəf düyməni 2 saniyə basıb saxla"
        android.util.Log.i(TAG, "tt öyrənmə başladı: ${control.id} (${control.title})")
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, TIMEOUT_MS)
        if (!TtHoldCapture.start { step -> onTarget(ctx, step) }) {
            _pending.value = null
            main.removeCallbacks(timeout)
            _status.value = "Hədəf seçimi başlamadı (örtük açılmadı) — xüsusi imkan xidmətini yoxla"
            return false
        }
        return true
    }

    fun cancel() {
        main.removeCallbacks(timeout)
        TtHoldCapture.stop()
        if (_pending.value != null) _status.value = "Öyrənmə ləğv edildi"
        _pending.value = null
    }

    fun clearStatus() { _status.value = null }

    private const val FRESH_PREFS = "q07_tt_fresh"
    private const val KEY_SCHEME = "scheme"

    /**
     * Yol sxemi versiyası: 2 — «2 saniyə basıb saxla» seçimi.
     * 1-ci sxemdə yazılmış bütün tt yolları (ilk kliki tutan səhv məntiq) birdəfəlik silinir.
     */
    private const val SCHEME = 2

    fun freshStartIfNeeded(context: Context) {
        val ctx = context.applicationContext
        val p = ctx.getSharedPreferences(FRESH_PREFS, Context.MODE_PRIVATE)
        if (p.getInt(KEY_SCHEME, 1) >= SCHEME) return
        LearnedFunctionsRepository.init(ctx)
        VehicleStructureRepository.init(ctx)
        val removed = LearnedFunctionsRepository.clearAll()
        VehicleControlBindings.clearAll(ctx)
        VehicleStructureRepository.clearFlows()
        az.q07.launcher.data.VehicleEngineController.setFlow(ctx, true, null)
        az.q07.launcher.data.VehicleEngineController.setFlow(ctx, false, null)
        _pending.value = null
        _status.value = "Köhnə tt yolları silindi — yenidən öyrənin"
        p.edit().putInt(KEY_SCHEME, SCHEME).apply()
        android.util.Log.i(TAG, "tt sıfırdan başladı: $removed köhnə yol silindi, bağlamalar təmizləndi")
    }

    /** [TtHoldCapture] uzun basışla hədəfi seçəndə çağırır. */
    private fun onTarget(context: Context, step: FlowStep): Boolean {
        val p = _pending.value ?: return false
        _pending.value = null
        main.removeCallbacks(timeout)
        val (fn, added) = LearnedFunctionsRepository.add(p.title, listOf(step), null)
        VehicleControlBindings.bind(context, p.controlId, fn.id)
        android.util.Log.i(
            TAG,
            "hədəf bağlandı: ${step.packageName}/${step.className} vid=${step.viewIdResName} " +
                "text=${step.text} -> ${p.controlId} (${if (added) "yeni" else "mövcud"}, id=${fn.id})"
        )
        _status.value = (if (added) "✓ «${p.title}» öyrənildi" else "✓ «${p.title}» mövcud yola bağlandı") +
            " · ${fn.name}"
        return true
    }
}
