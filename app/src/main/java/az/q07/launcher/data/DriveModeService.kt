package az.q07.launcher.data

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Zavod «Sürüş» səhifəsindəki sekmə qrupları (soldan sağa sıra ilə) */
enum class TabGroup(val maskId: String, val names: List<String>) {
    PEDAL("v_tab_drive_mode_mask", listOf("Komfort", "Standart", "Sport")),
    SUSPENSION("v_tab_suspension_strength_mask", listOf("Comfort", "Standart", "Sport", "Sport+", "Smart"))
}

data class Tyres(val fl: String, val fr: String, val rl: String, val rr: String, val unit: String, val at: Long)

sealed class FactoryJob {
    /** index = null → yalnız oxu */
    data class Tab(val group: TabGroup, val index: Int?) : FactoryJob()
    object ReadHome : FactoryJob()
}

/**
 * Zavod tətbiqləri ilə körpü (Changan SDK üçüncü tərəfə bağlıdır, ona görə xüsusi imkan xidməti ilə):
 *  1) launcher-in ekran görüntüsü üstə «örtük» kimi qoyulur — istifadəçi heç nə görmür,
 *  2) altda zavod ekranı açılır, xidmət lazımi düyməni basır / dəyəri oxuyur,
 *  3) launcher yenidən önə gəlir, örtük götürülür.
 */
object FactoryBridge {
    const val VS = "com.incall.apps.vehiclesetting"
    const val HOME = "com.incall.apps.sdalauncher"

    private val _current = MutableStateFlow<Map<TabGroup, Int>>(emptyMap())
    val current: StateFlow<Map<TabGroup, Int>> = _current
    private val _tyres = MutableStateFlow<Tyres?>(null)
    val tyres: StateFlow<Tyres?> = _tyres
    private val _busy = MutableStateFlow<TabGroup?>(null)
    /** hansı qrup dəyişdirilir (UI üçün); ReadHome üçün null qalır */
    val busyGroup: StateFlow<TabGroup?> = _busy
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<FactoryJob>()
    @Volatile internal var job: FactoryJob? = null
    @Volatile internal var startedAt = 0L
    @Volatile internal var service: DriveModeService? = null
    private var activityRef: java.lang.ref.WeakReference<Activity>? = null

    internal fun report(group: TabGroup, i: Int) { _current.value = _current.value + (group to i) }
    internal fun reportTyres(t: Tyres) { _tyres.value = t }

    fun isEnabled(context: Context): Boolean {
        if (service != null) return true
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.contains("${context.packageName}/") && list.contains("DriveModeService")
    }

    fun enqueue(activity: Activity, j: FactoryJob) {
        if (!isEnabled(activity)) { _message.value = "Xüsusi imkan xidməti aktiv deyil"; return }
        if (queue.any { it == j } || job == j) return
        activityRef = java.lang.ref.WeakReference(activity)
        queue.addLast(j)
        if (job == null) next()
    }

    private fun next() {
        val j = queue.removeFirstOrNull() ?: run { _working.value = false; return }
        val act = activityRef?.get() ?: run { queue.clear(); _working.value = false; return }
        job = j
        _working.value = true
        _busy.value = (j as? FactoryJob.Tab)?.takeIf { it.index != null }?.group
        _message.value = null
        // bir kadr gözlə ki «dəyişdirilir…» yazısı da örtüyə düşsün
        main.postDelayed({ snapshotAndLaunch(act, j) }, 60)
    }

    private fun snapshotAndLaunch(act: Activity, j: FactoryJob) {
        val frame = Rect()
        act.window.decorView.getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2); act.window.decorView.getLocationOnScreen(loc)
        val src = Rect(frame).apply { offset(-loc[0], -loc[1]) }
        val go = { bmp: Bitmap? ->
            service?.showCover(bmp, frame)
            startedAt = System.currentTimeMillis()
            launchTarget(act, j)
            main.postDelayed({ if (job === j) finish(j, if (j is FactoryJob.Tab && j.index != null) "Zavod ekranı cavab vermədi" else null) }, 6000)
        }
        if (src.width() <= 0 || src.height() <= 0) { go(null); return }
        val bmp = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(act.window, src, bmp, { r -> go(if (r == PixelCopy.SUCCESS) bmp else null) }, main)
        }.onFailure { go(null) }
    }

    private fun launchTarget(context: Context, j: FactoryJob) {
        val intent = when (j) {
            is FactoryJob.Tab -> Intent(Intent.ACTION_VIEW, Uri.parse("changan://sda/hmi/vehiclesetting")).setPackage(VS)
            FactoryJob.ReadHome -> Intent(Intent.ACTION_MAIN).setComponent(ComponentName(HOME, "com.incall.apps.model_main.MainActivity"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { finish(j, "Zavod ekranı açılmadı") }
    }

    /** xidmət işi bitirəndə çağırır */
    internal fun finish(j: FactoryJob, msg: String?) {
        if (job !== j) return
        job = null
        _busy.value = null
        if (msg != null) _message.value = msg
        val ctx = activityRef?.get() ?: service
        runCatching {
            ctx?.startActivity(Intent(ctx, az.q07.launcher.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        // launcher tam önə gələndən sonra örtüyü götür
        main.postDelayed({
            if (queue.isEmpty()) service?.hideCover()
            next()
        }, 450)
    }
}

class DriveModeService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var clickedAt = 0L
    private var cover: ImageView? = null

    override fun onServiceConnected() { FactoryBridge.service = this }
    override fun onDestroy() { hideCover(); SidePanel.hide(this); FactoryBridge.service = null; super.onDestroy() }
    override fun onInterrupt() {}

    fun showCover(bmp: Bitmap?, frame: Rect) {
        if (cover != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            if (bmp != null) setImageBitmap(bmp) else setBackgroundColor(0xFF0B0F14.toInt())
        }
        val lp = WindowManager.LayoutParams(
            frame.width(), frame.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START; x = frame.left; y = frame.top }
        runCatching { wm.addView(v, lp); cover = v }
    }

    fun hideCover() {
        val v = cover ?: return
        cover = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ön plandakı tətbiq dəyişəndə yan paneli göstər/gizlət
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && FactoryBridge.job == null) {
            event.packageName?.toString()?.let { SidePanel.onForeground(this, it) }
        }
        val pkg = event?.packageName?.toString()
        if (pkg != FactoryBridge.VS && pkg != FactoryBridge.HOME) return
        val roots = buildList {
            windows?.forEach { w -> w.root?.let { add(it) } }
            rootInActiveWindow?.let { add(it) }
        }
        // passiv oxuma: zavod ekranları görünəndə dəyərləri yenilə
        var tabsSeen: Map<TabGroup, List<AccessibilityNodeInfo>> = emptyMap()
        roots.firstOrNull { it.packageName == FactoryBridge.VS }?.let { r ->
            tabsSeen = TabGroup.values().associateWith { findTabs(r, it) }.filterValues { it.isNotEmpty() }
            tabsSeen.forEach { (g, tabs) -> selected(tabs).takeIf { it >= 0 }?.let { FactoryBridge.report(g, it) } }
        }
        roots.firstOrNull { it.packageName == FactoryBridge.HOME }?.let { r -> readTyres(r) }

        val j = FactoryBridge.job ?: return
        when (j) {
            FactoryJob.ReadHome -> if (FactoryBridge.tyres.value?.let { System.currentTimeMillis() - it.at < 3000 } == true) {
                FactoryBridge.finish(j, null)
            }
            is FactoryJob.Tab -> {
                val tabs = tabsSeen[j.group] ?: return
                val sel = selected(tabs)
                val target = j.index
                if (target == null) { FactoryBridge.finish(j, null); return }
                if (sel == target) { FactoryBridge.finish(j, "${j.group.names[target]} seçildi"); return }
                if (System.currentTimeMillis() - clickedAt < 700) return
                clickedAt = System.currentTimeMillis()
                val node = tabs.getOrNull(target) ?: return
                val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                Log.i("Q07DRIVE", "click ${j.group} target=$target ok=$ok sel=$sel")
                main.postDelayed({
                    if (FactoryBridge.job !== j) return@postDelayed
                    val r = (windows?.mapNotNull { it.root } ?: emptyList()).firstOrNull { it.packageName == FactoryBridge.VS }
                    val s2 = r?.let { selected(findTabs(it, j.group)) } ?: -1
                    if (s2 >= 0) FactoryBridge.report(j.group, s2)
                    FactoryBridge.finish(j, if (s2 == target) "${j.group.names[target]} seçildi" else "Maşın qəbul etmədi")
                }, 700)
            }
        }
    }

    private fun selected(tabs: List<AccessibilityNodeInfo>) =
        tabs.indexOfFirst { it.isSelected || it.parent?.isSelected == true }

    private fun findTabs(root: AccessibilityNodeInfo, g: TabGroup): List<AccessibilityNodeInfo> {
        val mask = root.findAccessibilityNodeInfosByViewId("${FactoryBridge.VS}:id/${g.maskId}").firstOrNull() ?: return emptyList()
        val mr = Rect(); mask.getBoundsInScreen(mr)
        return root.findAccessibilityNodeInfosByViewId("${FactoryBridge.VS}:id/tv_tab_content")
            .filter { val r = Rect(); it.getBoundsInScreen(r); r.centerY() in mr.top..mr.bottom }
            .sortedBy { val r = Rect(); it.getBoundsInScreen(r); r.left }
            .takeIf { it.size == g.names.size } ?: emptyList()
    }

    private fun readTyres(root: AccessibilityNodeInfo) {
        fun t(id: String) = root.findAccessibilityNodeInfosByViewId("${FactoryBridge.HOME}:id/$id").firstOrNull()?.text?.toString()
        val fl = t("tv_left_top_tyre_pressure") ?: return
        val fr = t("tv_right_top_tyre_pressure") ?: return
        val rl = t("tv_left_bottom_tyre_pressure") ?: return
        val rr = t("tv_right_bottom_tyre_pressure") ?: return
        val unit = t("tv_left_top_unit") ?: "bar"
        FactoryBridge.reportTyres(Tyres(fl, fr, rl, rr, unit, System.currentTimeMillis()))
    }
}
