package az.q07.launcher.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
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
import kotlin.math.abs

data class Tyres(val fl: String, val fr: String, val rl: String, val rr: String, val unit: String, val at: Long)

sealed class FactoryJob {
    /** index = null → yalnız oxu */
    data class Control(val control: VehicleControl, val index: Int?) : FactoryJob()
    object ReadHome : FactoryJob()
}

/**
 * Zavod tətbiqləri ilə körpü:
 *  1) launcher görüntüsü örtük kimi qoyulur — istifadəçi zavod menyusunu görmür,
 *  2) altda zavod ekranı açılır, xidmət lazımi idarəetməni dəyişir / dəyəri oxuyur,
 *  3) launcher önə qayıdır, örtük götürülür.
 */
object FactoryBridge {
    const val VS = "com.incall.apps.vehiclesetting"
    const val HOME = "com.incall.apps.sdalauncher"

    private val _current = MutableStateFlow<Map<VehicleControl, Int>>(emptyMap())
    val current: StateFlow<Map<VehicleControl, Int>> = _current
    private val _labels = MutableStateFlow<Map<VehicleControl, List<String>>>(emptyMap())
    val labels: StateFlow<Map<VehicleControl, List<String>>> = _labels
    private val _tyres = MutableStateFlow<Tyres?>(null)
    val tyres: StateFlow<Tyres?> = _tyres
    private val _busy = MutableStateFlow<VehicleControl?>(null)
    val busyControl: StateFlow<VehicleControl?> = _busy
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

    internal fun report(c: VehicleControl, i: Int) { if (_current.value[c] != i) _current.value = _current.value + (c to i) }
    internal fun reportLabels(c: VehicleControl, l: List<String>) { if (_labels.value[c] != l) _labels.value = _labels.value + (c to l) }
    internal fun reportTyres(t: Tyres) { _tyres.value = t }
    internal fun setMessage(m: String?) { _message.value = m }
    internal fun setBusy(c: VehicleControl?) { _busy.value = c }

    fun isEnabled(context: Context): Boolean {
        if (service != null) return true
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.contains("${context.packageName}/") && list.contains("DriveModeService")
    }

    fun enqueue(activity: Activity, j: FactoryJob) {
        if (!isEnabled(activity)) { _message.value = "Xüsusi imkan xidməti aktiv deyil"; return }
        if (queue.any { it == j } || job == j) return
        activityRef = java.lang.ref.WeakReference(activity)
        // eyni idarəetmə üçün köhnə əmri yenisi əvəz edir
        if (j is FactoryJob.Control) queue.removeAll { it is FactoryJob.Control && it.control == j.control }
        queue.addLast(j)
        if (job == null) next()
    }

    private fun next() {
        val j = queue.removeFirstOrNull() ?: run { _working.value = false; return }
        val act = activityRef?.get() ?: run { queue.clear(); _working.value = false; return }
        job = j
        _working.value = true
        _busy.value = (j as? FactoryJob.Control)?.takeIf { it.index != null }?.control
        _message.value = null
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
            service?.startPolling()
            main.postDelayed({
                if (job === j) finish(j, if (j is FactoryJob.Control && j.index != null) "Alınmadı — yenidən bas" else null)
            }, 9000)
        }
        if (src.width() <= 0 || src.height() <= 0) { go(null); return }
        val bmp = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(act.window, src, bmp, { r -> go(if (r == PixelCopy.SUCCESS) bmp else null) }, main)
        }.onFailure { go(null) }
    }

    private fun launchTarget(context: Context, j: FactoryJob) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        val intent = when (j) {
            is FactoryJob.Control -> Intent(Intent.ACTION_VIEW, Uri.parse("changan://sda/hmi/vehiclesetting")).setPackage(VS)
            FactoryJob.ReadHome -> Intent(Intent.ACTION_MAIN).setComponent(ComponentName(HOME, "com.incall.apps.model_main.MainActivity"))
        }.addFlags(flags)
        runCatching { context.startActivity(intent) }.onFailure {
            // URI işləməsə — birbaşa MainActivity
            runCatching {
                context.startActivity(Intent().setComponent(ComponentName(VS, "$VS.ui.activity.MainActivity")).addFlags(flags))
            }.onFailure { finish(j, "Zavod ekranı açılmadı") }
        }
    }

    internal fun finish(j: FactoryJob, msg: String?) {
        if (job !== j) return
        SelfDiag.log("factory", msg ?: "oxundu", mapOf("job" to j.toString(), "ms" to (System.currentTimeMillis() - startedAt)))
        job = null
        _busy.value = null
        if (msg != null) _message.value = msg
        val ctx = activityRef?.get() ?: service
        runCatching {
            ctx?.startActivity(Intent(ctx, az.q07.launcher.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        main.postDelayed({
            if (queue.isEmpty()) service?.hideCover()
            next()
        }, 450)
    }
}

class DriveModeService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var actedAt = 0L
    private var cover: ImageView? = null
    private var navAt = 0L
    private var navTries = 0
    private var lastFg: String? = null

    override fun onServiceConnected() { FactoryBridge.service = this }
    override fun onDestroy() { hideCover(); SidePanel.hide(this); FactoryBridge.service = null; super.onDestroy() }
    override fun onInterrupt() {}

    // ---------------- örtük ----------------
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

    // ---------------- hadisələr ----------------
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString()
            if (p != null && p != lastFg) { lastFg = p; SelfDiag.log("fg", p) }
            if (FactoryBridge.job == null && p != null) SidePanel.onForeground(this, p)
            if (p == FactoryBridge.HOME) homeGuard(event.className?.toString())
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && FactoryBridge.job == null) {
            main.removeCallbacks(windowsCheck); main.postDelayed(windowsCheck, 250)
        }
        val pkg = event?.packageName?.toString()
        if (pkg != FactoryBridge.VS && pkg != FactoryBridge.HOME) return
        process()
    }

    // ---------------- Q07 defolt ikən zavod ekranının özbaşına önə çıxmasına qarşı ----------------
    // Qapı açılıb-bağlananda / sistem düyməsində SystemUI zavod launcher-ini birbaşa açır
    // (START ... sdalauncher/model_main.MainActivity from com.android.systemui). Q07 defoltdursa geri qayıdırıq.
    private var defaultCheckedAt = 0L
    private var isDefaultCached = false
    private val guardHits = ArrayDeque<Long>()

    private val windowsCheck = Runnable { SidePanel.onWindowsChanged(this) }
    private var lastGuardAt = 0L

    private fun homeGuard(cls: String?) {
        if (cls == null || !cls.contains("model_main")) return
        if (FactoryBridge.job != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastGuardAt < 1500) return
        // freeform tətbiq açılanda Changan sistemi zavod launcher-ini arxa fona özü gətirir — o zaman toxunmuruq
        if (now - WindowLauncher.lastLaunchAt < 5_000 || SidePanel.freeformOnTop(this)) return
        if (now - QuickActions.factoryOpenedAt < 20_000) return
        if (now - defaultCheckedAt > 5_000) {
            isDefaultCached = runCatching { HomeMode.isDefault(this) }.getOrDefault(false); defaultCheckedAt = now
        }
        if (!isDefaultCached) return
        while (guardHits.isNotEmpty() && now - guardHits.first() > 30_000) guardHits.removeFirst()
        if (guardHits.size >= 4) return // dövrəyə düşməsin
        guardHits.addLast(now); lastGuardAt = now
        SelfDiag.log("homeguard", "zavod ekranı özbaşına açıldı → Q07-yə qayıdış")
        main.postDelayed({
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }.onFailure {
                runCatching {
                    startActivity(Intent(this, az.q07.launcher.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }, 250)
    }

    private val poller = object : Runnable {
        override fun run() {
            if (FactoryBridge.job == null) return
            process()
            main.postDelayed(this, 350)
        }
    }

    fun startPolling() {
        navTries = 0; navAt = 0L; actedAt = 0L
        main.removeCallbacks(poller)
        main.postDelayed(poller, 400)
    }

    private fun roots(): List<AccessibilityNodeInfo> = buildList {
        windows?.forEach { w -> w.root?.let { add(it) } }
        rootInActiveWindow?.let { add(it) }
    }

    private fun vsRoot() = roots().firstOrNull { it.packageName == FactoryBridge.VS }

    /** Zavod «Sürüş» səhifəsi artıq görünürsə — örtüksüz, birbaşa dəyiş */
    fun tryDirect(control: VehicleControl, index: Int): Boolean {
        if (FactoryBridge.job != null) return false
        val root = vsRoot() ?: return false
        if (readState(root, control) == null && control.kind == VehicleControl.Kind.TABS) return false
        if (find(root, control) == null) return false
        FactoryBridge.setBusy(control)
        act(root, control, index)
        main.postDelayed({
            val s = vsRoot()?.let { readState(it, control) }
            FactoryBridge.setBusy(null)
            FactoryBridge.setMessage(if (s == index) "${VehicleQuickControlHelper.label(control, index)} seçildi" else "Maşın qəbul etmədi")
        }, 800)
        return true
    }

    private fun process() {
        val all = roots()
        all.firstOrNull { it.packageName == FactoryBridge.VS }?.let { r ->
            VehicleControl.values().forEach { c -> readState(r, c)?.let { FactoryBridge.report(c, it) } }
            SelfDiag.snapshotUi("vehiclesetting", r)
        }
        all.firstOrNull { it.packageName == FactoryBridge.HOME }?.let { readTyres(it); SelfDiag.snapshotUi("sdalauncher", it) }

        val j = FactoryBridge.job ?: return
        when (j) {
            FactoryJob.ReadHome -> if (FactoryBridge.tyres.value?.let { System.currentTimeMillis() - it.at < 3000 } == true) {
                FactoryBridge.finish(j, null)
            }
            is FactoryJob.Control -> {
                val root = all.firstOrNull { it.packageName == FactoryBridge.VS } ?: return
                val found = find(root, j.control)
                if (found == null || !found.isVisibleToUser) { navigate(root); return }
                val target = j.index ?: run { FactoryBridge.finish(j, null); return }
                if (isBlocked(root, j.control)) {
                    FactoryBridge.finish(j, "${j.control.title}: maşın indi dəyişməyə icazə vermir (P-də yoxla)"); return
                }
                val state = readState(root, j.control)
                if (state == target) {
                    FactoryBridge.finish(j, "${VehicleQuickControlHelper.label(j.control, target)} seçildi"); return
                }
                if (System.currentTimeMillis() - actedAt < 900) return
                actedAt = System.currentTimeMillis()
                act(root, j.control, target)
                main.postDelayed({
                    if (FactoryBridge.job !== j) return@postDelayed
                    val s2 = vsRoot()?.let { readState(it, j.control) }
                    if (s2 != null) FactoryBridge.report(j.control, s2)
                    if (s2 == target) FactoryBridge.finish(j, "${VehicleQuickControlHelper.label(j.control, target)} seçildi")
                }, 700)
            }
        }
    }

    // ---------------- axtarış / oxuma ----------------
    private fun id(x: String) = "${FactoryBridge.VS}:id/$x"
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    /** idarəetmənin əsas düyünü (sekmə sırası / sürgü maskası) */
    private fun find(root: AccessibilityNodeInfo, c: VehicleControl): AccessibilityNodeInfo? = when (c.kind) {
        VehicleControl.Kind.TABS -> tabs(root, c).firstOrNull()
        else -> c.sliderMaskId?.let { root.findAccessibilityNodeInfosByViewId(id(it)).firstOrNull() }
    }

    private fun tabs(root: AccessibilityNodeInfo, c: VehicleControl): List<AccessibilityNodeInfo> {
        // 1) id ilə; 2) tapılmasa — əvvəl öyrənilmiş başlıq mətni ilə (zavod yeniləməsində id dəyişə bilər)
        val byId = root.findAccessibilityNodeInfosByViewId(id(c.anchorId)).firstOrNull()
        byId?.text?.toString()?.let { SelfDiag.learnTitle(c, it) }
        val anchor = byId ?: SelfDiag.learnedTitle(c)?.let { t ->
            root.findAccessibilityNodeInfosByText(t).firstOrNull { it.text?.toString() == t }
                ?.also { SelfDiag.log("selfheal", "${c.name}: id tapılmadı, başlıq mətni ilə tapıldı") }
        } ?: return emptyList()
        val a = bounds(anchor)
        val below = root.findAccessibilityNodeInfosByViewId(id("tv_tab_content"))
            .filter { val r = bounds(it); r.top >= a.bottom - 4 && r.top <= a.bottom + 260 }
        // yalnız başlığın altındakı İLK sıra (eyni hündürlük), seçim sayı qədər
        val firstTop = below.minOfOrNull { bounds(it).top } ?: return emptyList()
        val list = below.filter { abs(bounds(it).top - firstTop) < 24 }
            .sortedBy { bounds(it).left }
            .take(c.options.size)
        if (list.isNotEmpty()) FactoryBridge.reportLabels(c, list.map { it.text?.toString().orEmpty() })
        return list
    }

    /** «Value, 35%» → 35 (sürgünün yanında) */
    private fun sliderPercent(root: AccessibilityNodeInfo, c: VehicleControl): Int? {
        val mask = c.sliderMaskId?.let { root.findAccessibilityNodeInfosByViewId(id(it)).firstOrNull() } ?: return null
        val m = bounds(mask)
        var best: Int? = null
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 30 || best != null) return
            val desc = n.contentDescription?.toString().orEmpty()
            if (desc.startsWith("Value")) {
                val r = bounds(n)
                if (r.centerY() in (m.top - 70)..(m.bottom + 20)) best = Regex("(\\d+)").find(desc)?.value?.toIntOrNull()
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return best
    }

    private fun readState(root: AccessibilityNodeInfo, c: VehicleControl): Int? = when (c.kind) {
        VehicleControl.Kind.TABS -> tabs(root, c).indexOfFirst { it.isSelected || it.parent?.isSelected == true }.takeIf { it >= 0 }
        else -> sliderPercent(root, c)?.let { p -> c.percents.indices.minByOrNull { abs(c.percents[it] - p) } }
    }

    // ---------------- hərəkət ----------------
    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        val ok = generateSequence(n) { it.parent }.firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!ok) { val r = bounds(n); tap(r.exactCenterX(), r.exactCenterY()) }
        return ok
    }

    /** Sürgü üzərindəki maska kliklənəndirsə — zavod onu bloklayıb (məs. sürüş zamanı) */
    private fun isBlocked(root: AccessibilityNodeInfo, c: VehicleControl): Boolean {
        if (c.kind == VehicleControl.Kind.TABS) return false
        val mask = c.sliderMaskId?.let { root.findAccessibilityNodeInfosByViewId(id(it)).firstOrNull() } ?: return false
        return mask.isClickable
    }

    private fun sliderNode(root: AccessibilityNodeInfo, c: VehicleControl): AccessibilityNodeInfo? {
        val sid = c.sliderMaskId?.removePrefix("v_")?.removeSuffix("_mask") ?: return null
        return root.findAccessibilityNodeInfosByViewId(id(sid)).firstOrNull()
    }

    private fun act(root: AccessibilityNodeInfo, c: VehicleControl, index: Int) {
        when (c.kind) {
            VehicleControl.Kind.TABS -> tabs(root, c).getOrNull(index)?.let { clickNode(it) }
            else -> {
                val pct = c.percents.getOrNull(index) ?: 50
                val slider = sliderNode(root, c)
                // 1) əlçatanlıq API ilə dəqiq dəyər (dəstəklənirsə)
                val ri = slider?.rangeInfo
                val ok = if (slider != null && ri != null) {
                    val v = ri.min + (ri.max - ri.min) * pct / 100f
                    slider.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v) }
                    )
                } else false
                // 2) əks halda barmaqla sürüşdür: cari düymədən hədəf nöqtəyə
                if (!ok) dragSlider(root, c, slider, pct)
            }
        }
        Log.i("Q07DRIVE", "act $c -> $index")
    }

    private fun dragSlider(root: AccessibilityNodeInfo, c: VehicleControl, slider: AccessibilityNodeInfo?, pct: Int) {
        val base = slider ?: c.sliderMaskId?.let { root.findAccessibilityNodeInfosByViewId(id(it)).firstOrNull() } ?: return
        val r = bounds(base)
        val pad = r.height() * 0.78f
        val y = r.top + r.height() * 0.42f
        val cur = sliderPercent(root, c) ?: 50
        val fromX = r.left + pad + cur / 100f * (r.width() - 2 * pad)
        val toX = r.left + pad + pct / 100f * (r.width() - 2 * pad)
        val p = Path().apply { moveTo(fromX, y); lineTo(toX, y) }
        runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 450)).build(), null, null)
        }
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null) }
    }

    /** «Sürüş» səhifəsinə keç; idarəetmə görünmürsə sürüşdür */
    private fun navigate(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - navAt < 900) return
        navAt = now
        navTries++
        val onDrive = root.findAccessibilityNodeInfosByViewId(id("v_tab_drive_mode_mask")).isNotEmpty() ||
            root.findAccessibilityNodeInfosByViewId(id("tv_title_power_steering")).isNotEmpty()
        if (!onDrive) {
            val menu = root.findAccessibilityNodeInfosByViewId(id("item_menu_txt")).sortedBy { bounds(it).top }
            val drive = menu.firstOrNull { val t = it.text?.toString().orEmpty(); t.startsWith("Sürüş") || t.contains("驾驶") || t.startsWith("Driv") }
                ?: menu.getOrNull(3)
            drive?.let { clickNode(it) }
            Log.i("Q07DRIVE", "nav -> Sürüş")
            return
        }
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 25) return
            if (n.isScrollable && bounds(n).left > 400) scrollables += n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        val action = if (navTries % 4 < 2) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        scrollables.forEach { it.performAction(action) }
        Log.i("Q07DRIVE", "scroll n=${scrollables.size} try=$navTries")
    }

    private fun readTyres(root: AccessibilityNodeInfo) {
        fun t(x: String) = root.findAccessibilityNodeInfosByViewId("${FactoryBridge.HOME}:id/$x").firstOrNull()?.text?.toString()
        val fl = t("tv_left_top_tyre_pressure") ?: return
        val fr = t("tv_right_top_tyre_pressure") ?: return
        val rl = t("tv_left_bottom_tyre_pressure") ?: return
        val rr = t("tv_right_bottom_tyre_pressure") ?: return
        FactoryBridge.reportTyres(Tyres(fl, fr, rl, rr, t("tv_left_top_unit") ?: "bar", System.currentTimeMillis()))
    }
}
