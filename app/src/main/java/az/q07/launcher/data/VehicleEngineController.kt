package az.q07.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EngineCommandResult(val success: Boolean, val message: String, val isEngineRunning: Boolean)

object VehicleEngineController {
    private const val PREFS_NAME = "q07_engine_control_prefs"
    private const val KEY_CUSTOM_ENGINE_PATH = "custom_learned_engine_path"
    private const val KEY_LAST_ENGINE_STATE = "last_engine_running"

    private val _isEngineRunning = MutableStateFlow(false)
    val isEngineRunning = _isEngineRunning.asStateFlow()

    fun init(context: Context) {
        _isEngineRunning.value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_LAST_ENGINE_STATE, false)
    }

    fun saveCustomEnginePath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOM_ENGINE_PATH, path.trim()).apply()
    }

    fun getCustomEnginePath(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_CUSTOM_ENGINE_PATH, "") ?: ""

    /** Cari ötürmə vəziyyəti (P, R, N, D …) — oxunmursa null */
    fun currentGear(): String? = CarApi.gearName(CarApi.read(CarApi.GEAR_SELECTION) ?: CarApi.read(CarApi.CURRENT_GEAR))

    @SuppressLint("PrivateApi")
    fun toggleEngine(context: Context, targetStart: Boolean): EngineCommandResult {
        // TƏHLÜKƏSİZLİK: yalnız P (park) vəziyyətində icazə verilir; oxunmursa da bloklanır
        val gear = currentGear()
        if (gear != "P") {
            return EngineCommandResult(false, "Yalnız P (park) vəziyyətində mümkündür. Hazırkı: ${gear ?: "naməlum"}", _isEngineRunning.value)
        }
        val stateInt = if (targetStart) 1 else 0
        val actionLabel = if (targetStart) "Mühərrik işə salınır (START)..." else "Mühərrik söndürülür (STOP)..."
        val channels = mutableListOf<String>()

        // TƏHLÜKƏSİZLİK: yalnız skanerdən öyrədilmiş konkret yol işlədilir.
        // Təxmini Changan broadcast-ları və naməlum sistem açarları çıxarılıb —
        // onlar maşının güc sisteminə gözlənilməz təsir edə bilərdi.
        val customPath = getCustomEnginePath(context)
        if (customPath.isBlank()) {
            return EngineCommandResult(false, "Öyrədilmiş mühərrik yolu yoxdur. Əvvəlcə Skanerdən tapıb \"Yol\" bölməsinə yazın.", _isEngineRunning.value)
        }
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAST_ENGINE_STATE, targetStart).apply()
        return EngineCommandResult(true, "$actionLabel [${channels.joinToString(", ")}]", targetStart)
    }
}
