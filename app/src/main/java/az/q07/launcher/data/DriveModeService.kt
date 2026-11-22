package az.q07.launcher.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sürüş rejimi (Qaz pedalı həssaslığı: Komfort / Standart / İdman).
 * Changan SDK üçüncü tərəf tətbiqlərə yazmağa icazə vermir, ona görə:
 *  1) zavod «Sürüş» səhifəsi ekrandan KƏNARDA (görünməyən freeform pəncərədə) açılır,
 *  2) bu xüsusi imkan (accessibility) xidməti oradakı lazımi düyməni basır,
 *  3) launcher dərhal öndə qalır — istifadəçi menyunu görmür.
 */
object DriveModeController {
    const val PKG = "com.incall.apps.vehiclesetting"
    /** 0 = Komfort, 1 = Standart, 2 = İdman (zavod ekranındakı sıra ilə) */
    val names = listOf("Komfort", "Standart", "Sport")

    private val _current = MutableStateFlow<Int?>(null)
    val current: StateFlow<Int?> = _current
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    @Volatile internal var pending: Int? = null
    @Volatile internal var readOnly = false
    @Volatile internal var startedAt = 0L
    @Volatile internal var service: DriveModeService? = null

    internal fun reportCurrent(i: Int) { _current.value = i }
    internal fun finish(msg: String?) { _busy.value = false; _message.value = msg; pending = null; readOnly = false }

    fun isEnabled(context: Context): Boolean {
        if (service != null) return true
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.contains("${context.packageName}/") && list.contains("DriveModeService")
    }

    /** idx = null → yalnız hazırkı rejimi oxu */
    fun request(context: Context, idx: Int?) {
        if (!isEnabled(context)) {
            _message.value = "Xüsusi imkan icazəsi lazımdır"
            openFactoryScreen(context, visible = true)
            return
        }
        if (_busy.value && idx == null) return
        pending = idx
        readOnly = idx == null
        startedAt = System.currentTimeMillis()
        _busy.value = true
        _message.value = null
        openFactoryScreen(context, visible = false)
        // ilişib qalmasın
        Handler(Looper.getMainLooper()).postDelayed({
            if (_busy.value && System.currentTimeMillis() - startedAt >= 6000) {
                finish(if (readOnly) null else "Rejim dəyişmədi (zavod ekranı cavab vermədi)")
            }
        }, 6500)
    }

    fun openFactoryScreen(context: Context, visible: Boolean) {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        // görünməz: pəncərə ekranın sağ kənarından kənarda
        val bounds = if (visible) Rect(w / 2, 0, w, h) else Rect(w + 50, 0, w + 50 + w * 3 / 4, h)
        val opts = ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle()
        val deep = Intent(Intent.ACTION_VIEW, Uri.parse("changan://sda/hmi/vehiclesetting"))
            .setPackage(PKG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(deep, opts) }.isSuccess
        if (!ok) runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).setComponent(ComponentName(PKG, "$PKG.ui.activity.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts
            )
        }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

class DriveModeService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var clickedAt = 0L

    override fun onServiceConnected() { DriveModeController.service = this }
    override fun onDestroy() { DriveModeController.service = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val c = DriveModeController
        val tabs = findTabs() ?: return
        val sel = tabs.indexOfFirst { it.isSelected || it.parent?.isSelected == true }
        if (sel >= 0) c.reportCurrent(sel)
        if (!c.readOnly && c.pending == null) return
        if (System.currentTimeMillis() - c.startedAt > 6000) return
        if (c.readOnly) { done(null); return }
        val target = c.pending ?: return
        if (sel == target) { done("${c.names[target]} rejimi seçildi"); return }
        if (System.currentTimeMillis() - clickedAt < 700) return
        clickedAt = System.currentTimeMillis()
        val node = tabs.getOrNull(target) ?: return
        val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        Log.i("Q07DRIVE", "click target=$target ok=$ok sel=$sel")
        if (!ok) {
            val r = Rect(); node.getBoundsInScreen(r)
            tap(r.exactCenterX(), r.exactCenterY())
        }
        // nəticəni yoxla
        main.postDelayed({
            val t2 = findTabs()
            val s2 = t2?.indexOfFirst { it.isSelected || it.parent?.isSelected == true } ?: -1
            if (s2 >= 0) c.reportCurrent(s2)
            if (c.pending == target) done(if (s2 == target) "${c.names[target]} rejimi seçildi" else "Maşın rejimi qəbul etmədi")
        }, 600)
    }

    /** «Qaz pedalı həssaslığı» sırasındakı 3 sekmə, soldan sağa */
    private fun findTabs(): List<AccessibilityNodeInfo>? {
        val roots = buildList {
            windows?.forEach { w -> w.root?.let { add(it) } }
            rootInActiveWindow?.let { add(it) }
        }.filter { it.packageName == DriveModeController.PKG }
        for (root in roots) {
            val mask = root.findAccessibilityNodeInfosByViewId("${DriveModeController.PKG}:id/v_tab_drive_mode_mask").firstOrNull()
                ?: continue
            val mr = Rect(); mask.getBoundsInScreen(mr)
            val tabs = root.findAccessibilityNodeInfosByViewId("${DriveModeController.PKG}:id/tv_tab_content")
                .filter { val r = Rect(); it.getBoundsInScreen(r); r.centerY() in mr.top..mr.bottom }
                .sortedBy { val r = Rect(); it.getBoundsInScreen(r); r.left }
            if (tabs.size >= 3) return tabs.take(3)
        }
        return null
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null) }
    }

    private fun done(msg: String?) {
        DriveModeController.finish(msg)
        // zavod pəncərəsini bağla, launcher öndə qalsın
        main.postDelayed({
            runCatching {
                startActivity(Intent(this, az.q07.launcher.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }, 150)
    }
}
