package az.q07.scanner.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import az.q07.scanner.FullVehicleScannerManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Q07 Skaner-in öz xüsusi imkan xidməti (launcher-dən asılı deyil).
 * Ad launcher kodu ilə uyğunluq üçün saxlanıb: skaner hadisələri buradan gəlir.
 */
class DriveModeService : AccessibilityService() {
    override fun onServiceConnected() {
        FactoryBridge.service = this
        FullVehicleScannerManager.init(applicationContext)
        SelfDiag.log("service", "Skaner xidməti qoşuldu")
        // Tam qeyd: xidmət işlədikcə (sistem onu daim saxlayır) maşındakı hər şey yazılır
        runCatching { az.q07.scanner.rec.TamQeyd.start(applicationContext) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            runCatching { az.q07.scanner.rec.TamQeyd.onAccessibility(event) }
            runCatching { FullVehicleScannerManager.onEvent(this, event) }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        FactoryBridge.service = null
        runCatching { az.q07.scanner.rec.TamQeyd.stop() }
        super.onDestroy()
    }

    fun scannerRoots(): List<AccessibilityNodeInfo> = buildList {
        windows?.forEach { w -> w.root?.let { add(it) } }
        rootInActiveWindow?.let { add(it) }
    }

    fun scannerTap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null) }
    }
}

/** Launcher-dəki FactoryBridge-in sadə əvəzi: yalnız xidmət istinadı */
object FactoryBridge {
    @Volatile var service: DriveModeService? = null
    /** Skaner tətbiqində zavod idarəetmə işi yoxdur */
    val job: Any? get() = null

    fun isEnabled(context: Context): Boolean {
        val me = ComponentName(context, DriveModeService::class.java).flattenToString()
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.equals(me, true) }
    }
}

object QuickActions {
    fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    fun launchPackage(context: Context, pkg: String): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(context, i)
    }
}

object AppSettings {
    fun init(context: Context) {}
}

/** Sadə jurnal: Android/data/az.q07.scanner/files/diag/events.jsonl */
object SelfDiag {
    private var dir: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) { dir = context.getExternalFilesDir("diag")?.apply { mkdirs() } }

    @Synchronized
    fun log(type: String, msg: String, extra: Map<String, Any?> = emptyMap()) {
        val d = dir ?: return
        runCatching {
            val f = File(d, "events.jsonl")
            if (f.length() > 2_000_000) { File(d, "events.old.jsonl").delete(); f.renameTo(File(d, "events.old.jsonl")) }
            val o = JSONObject().put("t", fmt.format(Date())).put("type", type).put("msg", msg)
            extra.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
            f.appendText(o.toString() + "\n")
        }
    }

    /** WRITE_SECURE_SETTINGS (adb ilə verilib) varsa, öz xidmətini aktiv saxla */
    fun ensureAccessibility(context: Context) {
        runCatching {
            val me = ComponentName(context, DriveModeService::class.java).flattenToString()
            val cur = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (cur.split(':').any { it.equals(me, true) }) return
            val next = if (cur.isBlank()) me else "$cur:$me"
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            log("selfheal", "xidmət yenidən aktiv edildi")
        }
    }
}
