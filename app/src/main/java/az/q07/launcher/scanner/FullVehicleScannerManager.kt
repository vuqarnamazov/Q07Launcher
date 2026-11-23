package az.q07.launcher.scanner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import az.q07.launcher.data.DriveModeService
import az.q07.launcher.data.FactoryBridge
import az.q07.launcher.data.SelfDiag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tam Sistem və Gedişat Skaneri.
 *
 * Rejimlər:
 *  - SÖNÜLÜ
 *  - AÇIQ  — skaner ekranı açıq olduqca cari ekranın canlı strukturu göstərilir və öyrənilir
 *  - FON   — [VehicleScannerService] (ön plan xidməti) + `DriveModeService` (xüsusi imkan xidməti)
 *            sürüşdə və dayanmışkən fasiləsiz öyrənir
 *
 * Toplanan: pəncərə dəyişiklikləri, görünən/kliklənən elementlər (ekran xəritəsi), hansı klikin hara apardığı
 * (keçid yolları) və zaman ardıcıllığı ilə gedişatlar. Hamısı [VehicleStructureRepository]-yə yazılır.
 */
object FullVehicleScannerManager {
    enum class Mode { OFF, OPEN, BACKGROUND;
        val label: String get() = when (this) { OFF -> "Sönülü"; OPEN -> "Açıq"; BACKGROUND -> "Fon İzləməsi" }
    }

    private const val PREFS = "full_scanner"
    private const val IDLE_COMMIT_MS = 12_000L
    private const val MAX_STEPS = 40
    private const val MAX_SNAPSHOT_NODES = 300
    private const val SNAPSHOT_MIN_GAP = 1200L
    private const val SYSTEMUI = "com.android.systemui"

    private val _mode = MutableStateFlow(Mode.OFF)
    val mode: StateFlow<Mode> = _mode

    /** skaner ekranı (VehicleScannerActivity) görünürmü — AÇIQ rejim üçün */
    private val _screenVisible = MutableStateFlow(false)
    val screenVisible: StateFlow<Boolean> = _screenVisible

    private val _motion = MutableStateFlow(MotionState.UNKNOWN)
    val motion: StateFlow<MotionState> = _motion
    private val _speedKmh = MutableStateFlow<Double?>(null)
    val speedKmh: StateFlow<Double?> = _speedKmh

    private val _live = MutableStateFlow<LiveScreen?>(null)
    val live: StateFlow<LiveScreen?> = _live

    private val _session = MutableStateFlow<List<FlowStep>>(emptyList())
    val session: StateFlow<List<FlowStep>> = _session

    private val _events = MutableStateFlow(0)
    /** bu işə salınmadan bəri qeydə alınan hadisə sayı */
    val events: StateFlow<Int> = _events

    @Volatile var foregroundPackage: String? = null
        private set
    @Volatile var foregroundClass: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val activityCache = HashMap<String, Boolean>()
    private val labelCache = HashMap<String, String>()

    // cari ekran (yalnız öyrənilən — öz launcher-imiz deyil)
    private var curPkg: String? = null
    private var curCls: String? = null
    private var prevPkg: String? = null
    private var newVisit = false
    private var visitCounted = HashSet<String>()
    private var lastVisible: Set<String> = emptySet()
    private var lastSnapshotAt = 0L
    private var lastSysUiAt = 0L

    private data class PendingClick(val step: FlowStep, val screenId: String, val before: Set<String>, val at: Long)
    private var pending: PendingClick? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        VehicleStructureRepository.init(context)
        LearnedFunctionsRepository.init(context)
        _mode.value = runCatching {
            Mode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", Mode.OFF.name)!!)
        }.getOrDefault(Mode.OFF)
    }

    fun restoreIfNeeded(context: Context) {
        init(context)
        if (_mode.value == Mode.BACKGROUND) startService(context)
    }

    fun setMode(context: Context, m: Mode) {
        init(context)
        val old = _mode.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", m.name).apply()
        if (m != Mode.OFF) SelfDiag.ensureAccessibility(context.applicationContext)
        if (old == Mode.BACKGROUND && m != Mode.BACKGROUND) {
            commitSession("rejim dəyişdi")
            runCatching { context.stopService(Intent(context, VehicleScannerService::class.java)) }
        }
        _mode.value = m
        if (m == Mode.BACKGROUND) { _events.value = 0; startService(context) }
        if (m == Mode.OFF) { commitSession("söndürüldü"); VehicleStructureRepository.flushNow() }
        SelfDiag.log("fullscanner", "rejim: ${m.label}")
    }

    /** Launcher düyməsi: Fon İzləməsi aç / söndür */
    fun toggleBackground(context: Context) =
        setMode(context, if (_mode.value == Mode.BACKGROUND) Mode.OFF else Mode.BACKGROUND)

    fun setScreenVisible(v: Boolean) {
        _screenVisible.value = v
        if (v && isLearning()) main.post { snapshot() }
        if (!v && _mode.value == Mode.OPEN) commitSession("skaner ekranı bağlandı")
    }

    private fun startService(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, VehicleScannerService::class.java)) }
            .onFailure { SelfDiag.log("fullscanner", "xidmət başlamadı: $it") }
    }

    fun isAccessibilityReady(context: Context) = FactoryBridge.isEnabled(context)

    fun isLearning(): Boolean = appContext != null &&
        (_mode.value == Mode.BACKGROUND || (_mode.value == Mode.OPEN && _screenVisible.value)) &&
        FactoryBridge.job == null && !FlowPlayer.playing.value

    /** Sürət: daxili göstəricilər → Car API → OBD */
    fun readSpeed(ctx: Context): Double? {
        val car = runCatching { az.q07.launcher.data.CarApi.init(ctx) }.getOrDefault(false)
        return az.q07.launcher.data.VehicleTelemetryReader.state.value.speedKmh?.toDouble()
            ?: (if (car) (az.q07.launcher.data.CarApi.read(az.q07.launcher.data.CarApi.PERF_VEHICLE_SPEED) as? Number)?.toDouble()?.times(3.6) else null)
            ?: az.q07.launcher.data.ObdMonitor.state.value.takeIf { it.connected }?.values?.get("speed")
    }

    fun updateMotion(speed: Double?) {
        _speedKmh.value = speed
        _motion.value = when { speed == null -> MotionState.UNKNOWN; speed > 3.0 -> MotionState.MOVING; else -> MotionState.STOPPED }
    }

    // ───────────────────────── hadisələr (DriveModeService) ─────────────────────────

    fun onEvent(svc: DriveModeService, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString().orEmpty()
                val isAct = isActivity(svc, pkg, cls)
                if (isAct) { foregroundPackage = pkg; foregroundClass = cls }
                if (isLearning()) onWindow(svc, pkg, cls, isAct)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (isLearning() && (pkg == curPkg || pkg == SYSTEMUI)) scheduleSnapshot(600)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (isLearning()) onClick(svc, event, pkg)
        }
    }

    private fun onWindow(ctx: Context, pkg: String, cls: String, isAct: Boolean) {
        if (pkg == ctx.packageName) {
            commitSession("launcher-ə qayıdış")
            prevPkg = pkg; curPkg = null; curCls = null; pending = null
            return
        }
        if (!isAct) { if (pkg == curPkg || pkg == SYSTEMUI) scheduleSnapshot(400); return }
        if (pkg == curPkg && cls == curCls) return
        val from = curPkg?.let { ScreenNodeMap.idOf(it, curCls.orEmpty()) }
        val to = ScreenNodeMap.idOf(pkg, cls)
        // klikdən sonra ekran dəyişdisə — NAVIGATE keçidi
        pending?.let { p ->
            if (SystemClock.elapsedRealtime() - p.at < 4000 && p.screenId == from && p.step.packageName == curPkg) {
                VehicleStructureRepository.addEdge(p.screenId, to, p.step, NavigationEdge.Kind.NAVIGATE)
            }
        }
        pending = null
        prevPkg = curPkg ?: prevPkg
        val entry = prevPkg != pkg
        curPkg = pkg; curCls = cls
        newVisit = true; visitCounted = HashSet(); lastVisible = emptySet()
        entryFlag = entry
        add(FlowStep(FlowStep.Type.WINDOW, pkg, cls, motion = _motion.value))
        scheduleSnapshot(350)
    }

    private var entryFlag = false

    private fun onClick(ctx: Context, event: AccessibilityEvent, pkg: String) {
        if (pkg == ctx.packageName) return
        val src: AccessibilityNodeInfo? = runCatching { event.source }.getOrNull()
        val r = Rect().also { rect -> src?.getBoundsInScreen(rect) }
        val text = (src?.text?.toString() ?: event.text?.joinToString(" ")).orEmpty().trim().take(80)
        val desc = (src?.contentDescription ?: event.contentDescription)?.toString().orEmpty().trim().take(80)
        val cls = (src?.className ?: event.className)?.toString().orEmpty()
        val childText = if (text.isBlank() && desc.isBlank() && src != null) childLabel(src) else ""
        val step = FlowStep(
            FlowStep.Type.CLICK, pkg, cls, text.ifBlank { childText }, desc, src?.viewIdResourceName.orEmpty(),
            intArrayOf(r.left, r.top, r.right, r.bottom), _motion.value
        )
        add(step)
        val (sPkg, sCls) = screenOf(pkg)
        VehicleStructureRepository.recordClick(sPkg, sCls, step)
        pending = PendingClick(step, ScreenNodeMap.idOf(sPkg, sCls), lastVisible, SystemClock.elapsedRealtime())
        scheduleSnapshot(700)
        _events.value++
    }

    /** klik hansı ekrana aiddir: cari tətbiq ekranı və ya sistem paneli */
    private fun screenOf(pkg: String): Pair<String, String> =
        if (pkg == curPkg && curCls != null) pkg to curCls!! else pkg to if (pkg == SYSTEMUI) "SystemUI" else "Window"

    // ───────────────────────── ekranın strukturu ─────────────────────────

    private val snapshotRunnable = Runnable { snapshot() }

    private fun scheduleSnapshot(delayMs: Long) {
        main.removeCallbacks(snapshotRunnable)
        val wait = maxOf(delayMs, SNAPSHOT_MIN_GAP - (SystemClock.elapsedRealtime() - lastSnapshotAt))
        main.postDelayed(snapshotRunnable, wait.coerceAtLeast(50))
    }

    private fun snapshot() {
        if (!isLearning()) return
        val svc = FactoryBridge.service ?: return
        lastSnapshotAt = SystemClock.elapsedRealtime()
        val roots = runCatching { svc.scannerRoots() }.getOrDefault(emptyList())
        val pkg = curPkg
        val cls = curCls
        if (pkg != null && cls != null) {
            val nodes = collect(roots.filter { it.packageName == pkg })
            if (nodes.isNotEmpty()) {
                val keys = nodes.map { it.key }.toSet()
                val count = keys - visitCounted
                visitCounted.addAll(count)
                VehicleStructureRepository.mergeSnapshot(pkg, cls, nodes, newVisit, entryFlag, count)
                newVisit = false
                val id = ScreenNodeMap.idOf(pkg, cls)
                // klikdən sonra eyni ekranda yeni elementlər açıldısa — STATE keçidi
                pending?.let { p ->
                    val age = SystemClock.elapsedRealtime() - p.at
                    if (p.screenId == id) {
                        val appeared = nodes.filter { it.key !in p.before && it.key != p.step.nodeKey() }
                        if (appeared.isNotEmpty() && p.before.isNotEmpty()) {
                            VehicleStructureRepository.addEdge(id, id, p.step, NavigationEdge.Kind.STATE,
                                appeared.filter { it.isClickable }.ifEmpty { appeared }.take(12).map { it.label() },
                                appeared.map { it.key })
                            pending = null
                        } else if (age > 4000) pending = null
                    } else if (age > 4000) pending = null
                }
                lastVisible = keys
                _live.value = LiveScreen(id, pkg, cls, nodes)
                _events.value++
            }
        }
        // sistem paneli (iqlim zolağı, status) — daha seyrək
        if (SystemClock.elapsedRealtime() - lastSysUiAt > 5000) {
            lastSysUiAt = SystemClock.elapsedRealtime()
            val sys = collect(roots.filter { it.packageName == SYSTEMUI })
            if (sys.isNotEmpty()) VehicleStructureRepository.mergeSnapshot(SYSTEMUI, "SystemUI", sys, false, true, emptySet())
        }
    }

    private fun collect(roots: List<AccessibilityNodeInfo>): List<NodeInfo> {
        val out = LinkedHashMap<String, NodeInfo>()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 30 || out.size >= MAX_SNAPSHOT_NODES) return
            if (n.isVisibleToUser) {
                var text = n.text?.toString()?.trim().orEmpty().take(80)
                val desc = n.contentDescription?.toString()?.trim().orEmpty().take(80)
                if (n.isClickable && text.isBlank() && desc.isBlank()) text = childLabel(n)
                if (n.isClickable || text.isNotBlank() || desc.isNotBlank()) {
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        val b = intArrayOf(r.left, r.top, r.right, r.bottom)
                        val cls = n.className?.toString().orEmpty()
                        val vid = n.viewIdResourceName.orEmpty()
                        val key = nodeKeyOf(vid, text, desc, cls, b)
                        out[key] = NodeInfo(key, text, desc, vid, cls, n.isClickable, b)
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        roots.forEach { walk(it, 0) }
        return out.values.toList()
    }

    private fun childLabel(n: AccessibilityNodeInfo): String {
        fun walk(x: AccessibilityNodeInfo?, d: Int): String? {
            if (x == null || d > 4) return null
            if (d > 0) {
                x.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
                x.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            for (i in 0 until x.childCount) walk(x.getChild(i), d + 1)?.let { return it }
            return null
        }
        return walk(n, 0)?.trim()?.take(80).orEmpty()
    }

    // ───────────────────────── gedişat sessiyası ─────────────────────────

    private fun add(step: FlowStep) {
        _session.value = _session.value + step
        main.removeCallbacks(idleCommit)
        if (_session.value.size >= MAX_STEPS) commitSession("limit") else main.postDelayed(idleCommit, IDLE_COMMIT_MS)
    }

    private val idleCommit = Runnable { commitSession("fasilə") }

    @Synchronized
    fun commitSession(reason: String) {
        main.removeCallbacks(idleCommit)
        val steps = _session.value
        _session.value = emptyList()
        if (steps.isEmpty()) return
        val ctx = appContext ?: return
        steps.filter { it.type == FlowStep.Type.CLICK }.forEach { s ->
            VehicleStructureRepository.recordFlow(RecordedFlow.Kind.FUNCTION, listOf(s), "${appLabel(ctx, s.packageName)}: ${s.label()}", s.motion)
        }
        if (steps.size >= 2) {
            val clicks = steps.filter { it.type == FlowStep.Type.CLICK }.map { it.label() }
            val tail = if (clicks.isEmpty()) steps.last().label() else clicks.take(4).joinToString(" → ") + if (clicks.size > 4) " …" else ""
            val motion = when {
                steps.any { it.motion == MotionState.MOVING } -> MotionState.MOVING
                steps.any { it.motion == MotionState.STOPPED } -> MotionState.STOPPED
                else -> MotionState.UNKNOWN
            }
            VehicleStructureRepository.recordFlow(RecordedFlow.Kind.FLOW, steps, "${appLabel(ctx, steps.first().packageName)}: $tail", motion)
        } else if (steps[0].type == FlowStep.Type.WINDOW) {
            val s = steps[0]
            VehicleStructureRepository.recordFlow(RecordedFlow.Kind.FUNCTION, steps, "${appLabel(ctx, s.packageName)}: ${s.label()}", s.motion)
        }
        SelfDiag.log("fullscanner", "sessiya yazıldı ($reason)", mapOf("steps" to steps.size))
    }

    // ───────────────────────── köməkçilər ─────────────────────────

    fun appLabel(ctx: Context, pkg: String): String = synchronized(labelCache) {
        labelCache.getOrPut(pkg) {
            runCatching { val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg.substringAfterLast('.'))
        }
    }

    fun screenTitle(ctx: Context, s: ScreenNodeMap) = "${appLabel(ctx, s.packageName)} · ${s.shortName}"

    private fun isActivity(ctx: Context, pkg: String, cls: String): Boolean {
        if (cls.isBlank()) return false
        return activityCache.getOrPut("$pkg/$cls") {
            runCatching { ctx.packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true }.getOrDefault(false)
        }
    }
}
