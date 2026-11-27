package az.q07.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import az.q07.launcher.scanner.FlowPlayer
import az.q07.launcher.scanner.LearnedFunction
import az.q07.launcher.scanner.LearnedFunctionsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
    private const val KEY_START_FLOW = "start_flow_id"
    private const val KEY_STOP_FLOW = "stop_flow_id"

    private const val FLOW_TIMEOUT_MS = 25_000L

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

    fun saveCustomEnginePath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_CUSTOM_ENGINE_PATH, path.trim()).apply()
    }

    fun getCustomEnginePath(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_ENGINE_PATH, "") ?: ""

    /** Skanerdən öyrənilmiş «işə sal» / «söndür» gedişatı (null = seçilməyib) */
    fun getFlowId(context: Context, start: Boolean): String? =
        prefs(context).getString(if (start) KEY_START_FLOW else KEY_STOP_FLOW, "")?.ifBlank { null }

    fun setFlow(context: Context, start: Boolean, flowId: String?) {
        prefs(context).edit().putString(if (start) KEY_START_FLOW else KEY_STOP_FLOW, flowId.orEmpty()).apply()
    }

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

        // 1) Skanerdən öyrənilmiş gedişat — yoxdursa seçim istənmir, özü tapılır
        LearnedFunctionsRepository.init(context)
        var fn = learnedFlow(context, targetStart)
        if (fn == null) {
            EngineFlowBinding.autoAssign(context, targetStart)
            fn = learnedFlow(context, targetStart)
        }
        if (fn != null) {
            if (FlowPlayer.playing.value) {
                return EngineCommandResult(false, "Başqa gedişat icra olunur — bir az gözlə", _isEngineRunning.value)
            }
            var attempt = playFlow(context, fn)
            // Panel/ekran hələ hazır deyildisə heç bir basış baş vermir — bir dəfə təkrar sına.
            // Basış baş veribsə təkrar etmirik (düymə açar kimidir, iki dəfə basmaq geri qaytarardı).
            if (!attempt.first && !attempt.third) {
                delay(900)
                attempt = playFlow(context, fn)
            }
            val (ok, message) = attempt
            if (!ok) {
                return EngineCommandResult(
                    false,
                    "$actionLabel alınmadı — düymə ekranda tapılmadı. Pəncərə açıq qaldı: səhifəni əl ilə aç və düyməni bir dəfə bas.",
                    _isEngineRunning.value
                )
            }
            _isEngineRunning.value = targetStart
            prefs(context).edit().putBoolean(KEY_LAST_ENGINE_STATE, targetStart).apply()
            return EngineCommandResult(true, "$actionLabel [gedişat: ${fn.name}]", targetStart)
        }

        // 2) Əl ilə yazılmış yol
        val customPath = getCustomEnginePath(context)
        if (customPath.isBlank()) {
            return EngineCommandResult(
                false,
                "Enerji ekranında «Aç / Söndür» düyməsi görünmür. Səhifəni bir dəfə əl ilə açıb düyməni bas — skaner onu yazacaq.",
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

    /** Bağlanmış gedişat (silinibsə null) */
    private fun learnedFlow(context: Context, start: Boolean): LearnedFunction? {
        val id = getFlowId(context, start) ?: return null
        return LearnedFunctionsRepository.items.value.firstOrNull { it.id == id }
    }

    /** Gedişatı icra edib bitməsini gözləyir: (uğurlu, mesaj, basış baş verdi) */
    private suspend fun playFlow(
        context: Context,
        fn: LearnedFunction
    ): Triple<Boolean, String, Boolean> =
        withTimeoutOrNull(FLOW_TIMEOUT_MS) {
            suspendCancellableCoroutine<Triple<Boolean, String, Boolean>> { cont ->
                val accepted = FlowPlayer.play(
                    context,
                    fn,
                    returnToLauncher = true,
                    stepDelayOverrideMs = 400,
                    // Element görünmürsə köhnə koordinata toxunmuruq — səhv düyməyə düşürdü
                    allowCoordTap = false
                ) { ok, message, clicked ->
                    if (cont.isActive) cont.resume(Triple(ok, message, clicked))
                }
                if (!accepted && cont.isActive) {
                    cont.resume(Triple(false, FlowPlayer.status.value ?: "Gedişat başlamadı", false))
                }
            }
        } ?: Triple(false, "Gedişat vaxtında bitmədi", false)
}
