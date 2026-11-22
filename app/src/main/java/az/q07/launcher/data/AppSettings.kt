package az.q07.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

data class City(val name: String, val lat: Double, val lon: Double)

/** Bütün istifadəçi ayarları. Compose state-dir: dəyişiklik dərhal ekrana düşür. */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    val accents = listOf(
        "Mavi" to Color(0xFF3DA9FC),
        "Yaşıl" to Color(0xFF00D1A0),
        "Narıncı" to Color(0xFFFF9F40),
        "Bənövşəyi" to Color(0xFF8E7CFF),
        "Qırmızı" to Color(0xFFFF5A5F),
        "Qızılı" to Color(0xFFE8C25A)
    )

    val cities = listOf(
        City("Bakı", 40.4093, 49.8671),
        City("Sumqayıt", 40.5897, 49.6686),
        City("Gəncə", 40.6828, 46.3606),
        City("Mingəçevir", 40.7640, 47.0595),
        City("Şəki", 41.1919, 47.1706),
        City("Quba", 41.3611, 48.5134),
        City("Lənkəran", 38.7529, 48.8516),
        City("Şamaxı", 40.6314, 48.6414),
        City("Naxçıvan", 39.2089, 45.4122)
    )

    // Görünüş
    var accentIndex by mutableIntStateOf(0)
    var showSeconds by mutableStateOf(true)
    var uiScalePercent by mutableIntStateOf(100)   // avtomatik miqyasa nisbətən
    var gridColumns by mutableIntStateOf(4)

    // Neon menyu / dock
    var haptics by mutableStateOf(true)             // toxunuş səsi + vibrasiya
    var dockSlotCount by mutableIntStateOf(4)       // 3..6
    var glowMode by mutableStateOf("neon")          // neon | accent | slot
    var nightDim by mutableStateOf(true)            // gecə işığı zəiflət
    var middleTabs by mutableStateOf(true)          // orta panel: tablar (true) və ya siyahı
    var selectedTab by mutableStateOf("car")

    // Çoxpəncərə: kompakt rejimdə launcher solda dar panel olur, tətbiqlər sağda pəncərədə açılır
    var compactMode by mutableStateOf(false)

    // Modullar
    val moduleOrder = mutableStateListOf<String>()
    val disabledModules = mutableStateListOf<String>()

    // OBD (ELM327)
    var obdMode by mutableStateOf("wifi")           // off | wifi | bt  (Q07: WiFi-OBDII standartdır)
    var obdHost by mutableStateOf("192.168.0.10")
    var obdPort by mutableIntStateOf(35000)
    var obdBtMac by mutableStateOf("")
    var obdBtName by mutableStateOf("")
    var obdIntervalMs by mutableIntStateOf(50)

    // Hava
    var cityIndex by mutableIntStateOf(0)

    val accentColor: Color get() = accents.getOrElse(accentIndex) { accents[0] }.second
    val city: City get() = cities.getOrElse(cityIndex) { cities[0] }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("q07_settings", Context.MODE_PRIVATE)
        prefs.run {
            accentIndex = getInt("accent", 0)
            showSeconds = getBoolean("seconds", true)
            uiScalePercent = getInt("uiScale", 100)
            gridColumns = getInt("gridCols", 4)
            moduleOrder.clear()
            moduleOrder.addAll(getString("modOrder", "")!!.split(",").filter { it.isNotBlank() })
            disabledModules.clear()
            disabledModules.addAll(getString("modOff", "")!!.split(",").filter { it.isNotBlank() })
            obdMode = getString("obdMode", "wifi")!!
            obdHost = getString("obdHost", "192.168.0.10")!!
            obdPort = getInt("obdPort", 35000)
            obdBtMac = getString("obdBtMac", "")!!
            obdBtName = getString("obdBtName", "")!!
            obdIntervalMs = getInt("obdInterval2", 50)
            cityIndex = getInt("city", 0)
            haptics = getBoolean("haptics", true)
            dockSlotCount = getInt("dockSlots", 4).coerceIn(3, 6)
            glowMode = getString("glowMode", "neon")!!
            nightDim = getBoolean("nightDim", true)
            middleTabs = getBoolean("middleTabs", true)
            selectedTab = getString("tab", "car")!!
            compactMode = getBoolean("compact", false)
        }
    }

    /** Dəyişikliyi et və yadda saxla: AppSettings.update { showSeconds = false } */
    fun update(block: AppSettings.() -> Unit) {
        block()
        save()
    }

    private fun save() {
        prefs.edit()
            .putInt("accent", accentIndex)
            .putBoolean("seconds", showSeconds)
            .putInt("uiScale", uiScalePercent)
            .putInt("gridCols", gridColumns)
            .putString("modOrder", moduleOrder.joinToString(","))
            .putString("modOff", disabledModules.joinToString(","))
            .putString("obdMode", obdMode)
            .putString("obdHost", obdHost)
            .putInt("obdPort", obdPort)
            .putString("obdBtMac", obdBtMac)
            .putString("obdBtName", obdBtName)
            .putInt("obdInterval2", obdIntervalMs)
            .putInt("city", cityIndex)
            .putBoolean("haptics", haptics)
            .putInt("dockSlots", dockSlotCount)
            .putString("glowMode", glowMode)
            .putBoolean("nightDim", nightDim)
            .putBoolean("middleTabs", middleTabs)
            .putString("tab", selectedTab)
            .putBoolean("compact", compactMode)
            .apply()
    }
}
