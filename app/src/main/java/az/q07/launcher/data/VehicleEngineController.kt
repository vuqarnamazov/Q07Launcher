package az.q07.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EngineCommandResult(val success: Boolean, val message: String, val isEngineRunning: Boolean)

/**
 * Mühərrik idarəsi — TƏK basışla ON/OFF.
 *
 * Düymə sönülüdürsə «işə sal» gedişatını, işləyirsə «söndür» gedişatını özü seçib icra edir.
 * Gedişat asinxron işlədiyi üçün vəziyyət YALNIZ icra uğurlu bitəndə dəyişir:
 *  - uğursuz cəhd düyməni «AKTİV» göstərib sonrakı basışı səhv istiqamətə yönəltmir,
 *  - icra davam edərkən təkrar basışlar udulur (iki dəfə açılıb-bağlanma olmur).
 *
 * TƏHLÜKƏSİZLİK: yalnız P (park) vəziyyətində işləyir; ötürmə oxunmursa bloklanır.
 */
object VehicleEngineController {
    private const val PREFS_NAME = "q07_engine_control_prefs"
    private const val KEY_CUSTOM_ENGINE_PATH = "custom_learned_engine_path"
    private const val KEY_LAST_ENGINE_STATE = "last_engine_running"
    /** Vəziyyət sxeminin versiyası — dəyişəndə saxlanmış vəziyyət sıfırlanır */
    private const val KEY_STATE_REV = "state_rev"
    private const val STATE_REV = 92

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _isEngineRunning = MutableStateFlow(false)
    val isEngineRunning = _isEngineRunning.asStateFlow()

    /** Əmr hazırda icra olunur — düymə bu müddətdə təkrar basışı qəbul etmir */
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        // 1.76: əvvəlki STOP cəhdləri alınmadığı üçün saxlanmış «işləyir» vəziyyəti köhnəlib.
        // Bir dəfəlik sıfırlanır ki, düymə səhv istiqamətə əmr göndərməsin.
        if (p.getInt(KEY_STATE_REV, 0) != STATE_REV) {
            p.edit().putInt(KEY_STATE_REV, STATE_REV).putBoolean(KEY_LAST_ENGINE_STATE, false).apply()
        }
        _isEngineRunning.value = p.getBoolean(KEY_LAST_ENGINE_STATE, false)
    }

    fun getCustomEnginePath(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_ENGINE_PATH, "") ?: ""

    /** Cari ötürmə vəziyyəti (P, R, N, D …) — oxunmursa null */
    fun currentGear(context: Context): String? {
        runCatching { CarApi.init(context) }
        return CarApi.gearName(CarApi.read(CarApi.GEAR_SELECTION) ?: CarApi.read(CarApi.CURRENT_GEAR))
    }

    /**
     * Düymənin TƏK basışı: [targetStart] true → işə sal, false → söndür.
     * Nəticə [onResult] ilə qaytarılır (əsas axında çağırılır).
     */
    @SuppressLint("PrivateApi")
    fun toggleEngine(context: Context, targetStart: Boolean, onResult: (EngineCommandResult) -> Unit = {}) {
        if (_busy.value) {
            onResult(
                EngineCommandResult(
                    false,
                    if (_isEngineRunning.value) "Mühərrik işləyir — əmr icra olunur, gözlə" else "Əmr icra olunur — gözlə",
                    _isEngineRunning.value
                )
            )
            return
        }
        val app = context.applicationContext
        _busy.value = true
        scope.launch {
            val res = try {
                withContext(Dispatchers.IO) { execute(app, targetStart) }
            } catch (t: Throwable) {
                EngineCommandResult(false, "Əmr alınmadı: ${t.message ?: t.javaClass.simpleName}", _isEngineRunning.value)
            }
            _busy.value = false
            onResult(res)
        }
    }

    private suspend fun execute(context: Context, targetStart: Boolean): EngineCommandResult {
        val gear = currentGear(context)
        if (gear != "P") {
            return EngineCommandResult(
                false,
                "Yalnız P (park) vəziyyətində mümkündür. Hazırkı: ${gear ?: "ötürmə oxunmur"}",
                _isEngineRunning.value
            )
        }
        val actionLabel = if (targetStart) "Mühərrik işə salınır (START)..." else "Mühərrik söndürülür (STOP)..."

        // Əl ilə yazılmış yol
        val customPath = getCustomEnginePath(context)
        if (customPath.isBlank()) {
            return EngineCommandResult(
                false,
                "Enerji ekranında «Aç / Söndür» düyməsi görünmür.",
                _isEngineRunning.value
            )
        }
        val stateInt = if (targetStart) 1 else 0
        val channels = mutableListOf<String>()
        try {
            when {
                customPath.contains(":") -> {
                    val p = customPath.split(":")
                    when (p[0].uppercase()) {
                        "GLOBAL" -> Settings.Global.putInt(context.contentResolver, p[1], stateInt)
                        "SYSTEM" -> Settings.System.putInt(context.contentResolver, p[1], stateInt)
                        "SECURE" -> Settings.Secure.putInt(context.contentResolver, p[1], stateInt)
                    }
                    channels.add("Setting(${p[1]})")
                }
                customPath.contains("/") -> {
                    val p = customPath.split("/")
                    context.startActivity(Intent().apply {
                        setClassName(p[0], p[1]); putExtra("engine_start", targetStart); putExtra("state", stateInt)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    channels.add("Activity")
                }
                else -> {
                    context.sendBroadcast(Intent(customPath).apply { putExtra("state", stateInt); putExtra("engine_on", targetStart) })
                    channels.add("Broadcast")
                }
            }
        } catch (e: Exception) {
            return EngineCommandResult(false, "Əmr göndərilmədi: ${e.message}", _isEngineRunning.value)
        }

        _isEngineRunning.value = targetStart
        prefs(context).edit().putBoolean(KEY_LAST_ENGINE_STATE, targetStart).apply()
        return EngineCommandResult(true, "$actionLabel [${channels.joinToString(", ")}]", targetStart)
    }
}
