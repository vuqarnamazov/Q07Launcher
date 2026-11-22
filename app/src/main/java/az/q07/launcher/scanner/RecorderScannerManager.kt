package az.q07.launcher.scanner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import az.q07.launcher.data.FactoryBridge
import az.q07.launcher.data.SelfDiag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * İzləmə Skaneri.
 * Düymə ilə aktiv edilir → [ScannerForegroundService] işə düşür, `DriveModeService` (xüsusi imkan xidməti)
 * hadisələri bura ötürür. Maşın hərəkətdə də, dayanmışkən də:
 *  - kliklər (text, contentDescription, viewIdResName, className, packageName, boundsInScreen),
 *  - pəncərə keçidləri (packageName, className)
 * zaman ardıcıllığı ilə sessiyaya yığılır. Sessiya bitəndə (launcher-ə qayıdış və ya fasilə) hər klik ayrıca
 * FUNKSİYA, bütün ardıcıllıq isə GEDİŞAT kimi [RecordedFlowRepository]-yə yazılır.
 */
object RecorderScannerManager {
    private const val PREFS = "scanner"
    private const val IDLE_COMMIT_MS = 12_000L
    private const val MAX_STEPS = 40

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _motion = MutableStateFlow(MotionState.UNKNOWN)
    val motion: StateFlow<MotionState> = _motion

    private val _speedKmh = MutableStateFlow<Double?>(null)
    val speedKmh: StateFlow<Double?> = _speedKmh

    /** cari (hələ bitməmiş) sessiyanın addımları */
    private val _session = MutableStateFlow<List<FlowStep>>(emptyList())
    val session: StateFlow<List<FlowStep>> = _session

    private val _recordedThisRun = MutableStateFlow(0)
    val recordedThisRun: StateFlow<Int> = _recordedThisRun

    /** Ön plandakı pəncərə — skaner sönülü olsa da izlənir (FlowPlayer üçün lazımdır) */
    @Volatile var foregroundPackage: String? = null
        private set
    @Volatile var foregroundClass: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val activityCache = HashMap<String, Boolean>()
    private val labelCache = HashMap<String, String>()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        RecordedFlowRepository.init(context)
        LearnedFunctionsRepository.init(context)
        _active.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("active", false)
    }

    /** Launcher açılanda: əvvəl aktiv idisə xidməti bərpa et (məs. maşın yenidən işə salınandan sonra) */
    fun restoreIfNeeded(context: Context) {
        init(context)
        if (_active.value) startService(context)
    }

    fun toggle(context: Context) = setActive(context, !_active.value)

    fun setActive(context: Context, on: Boolean) {
        init(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", on).apply()
        if (on) {
            _recordedThisRun.value = 0
            SelfDiag.ensureAccessibility(context.applicationContext)
            _active.value = true
            startService(context)
            SelfDiag.log("scanner", "izləmə aktiv")
        } else {
            commitSession("söndürüldü")
            _active.value = false
            runCatching { context.stopService(Intent(context, ScannerForegroundService::class.java)) }
            SelfDiag.log("scanner", "izləmə sönülü")
        }
    }

    private fun startService(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ScannerForegroundService::class.java))
        }.onFailure { SelfDiag.log("scanner", "xidmət başlamadı: $it") }
    }

    fun isAccessibilityReady(context: Context) = FactoryBridge.isEnabled(context)

    internal fun updateMotion(speed: Double?) {
        _speedKmh.value = speed
        _motion.value = when {
            speed == null -> MotionState.UNKNOWN
            speed > 3.0 -> MotionState.MOVING
            else -> MotionState.STOPPED
        }
    }

    // ───────────────────────── hadisələr (DriveModeService-dən) ─────────────────────────

    fun onEvent(ctx: Context, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString().orEmpty()
                if (isActivity(ctx, pkg, cls)) { foregroundPackage = pkg; foregroundClass = cls }
                else if (pkg != foregroundPackage && pkg != "com.android.systemui") { foregroundPackage = pkg }
                if (shouldRecord(ctx)) onWindow(ctx, pkg, cls)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (shouldRecord(ctx)) onClick(ctx, event, pkg)
        }
    }

    private fun shouldRecord(ctx: Context): Boolean =
        _active.value && FactoryBridge.job == null && !FlowPlayer.playing.value && appContext != null

    private fun onWindow(ctx: Context, pkg: String, cls: String) {
        if (pkg == ctx.packageName) { commitSession("launcher-ə qayıdış"); return }
        if (!isActivity(ctx, pkg, cls)) return
        val last = _session.value.lastOrNull { it.type == FlowStep.Type.WINDOW }
        if (last != null && last.packageName == pkg && last.className == cls && _session.value.last() == last) return
        add(FlowStep(FlowStep.Type.WINDOW, pkg, cls, motion = _motion.value))
    }

    private fun onClick(ctx: Context, event: AccessibilityEvent, pkg: String) {
        if (pkg == ctx.packageName) return // launcher-in öz düymələri gedişat deyil
        val src: AccessibilityNodeInfo? = runCatching { event.source }.getOrNull()
        val r = Rect().also { rect -> src?.getBoundsInScreen(rect) }
        val text = (src?.text?.toString() ?: event.text?.joinToString(" ")).orEmpty().trim().take(80)
        val desc = (src?.contentDescription ?: event.contentDescription)?.toString().orEmpty().trim().take(80)
        val vid = src?.viewIdResourceName.orEmpty()
        val cls = (src?.className ?: event.className)?.toString().orEmpty()
        // mətn/ad yoxdursa — ilk mənalı uşaq düyünün mətnini götür (məs. ikon+yazı olan düymə)
        val childText = if (text.isBlank() && desc.isBlank() && src != null) firstChildLabel(src) else ""
        add(
            FlowStep(
                type = FlowStep.Type.CLICK, packageName = pkg, className = cls,
                text = text.ifBlank { childText }, contentDescription = desc, viewIdResName = vid,
                bounds = intArrayOf(r.left, r.top, r.right, r.bottom), motion = _motion.value
            )
        )
    }

    private fun firstChildLabel(n: AccessibilityNodeInfo): String {
        fun walk(x: AccessibilityNodeInfo?, d: Int): String? {
            if (x == null || d > 4) return null
            x.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            x.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            for (i in 0 until x.childCount) walk(x.getChild(i), d + 1)?.let { return it }
            return null
        }
        return walk(n, 0)?.trim()?.take(80).orEmpty()
    }

    private fun add(step: FlowStep) {
        _session.value = _session.value + step
        main.removeCallbacks(idleCommit)
        if (_session.value.size >= MAX_STEPS) commitSession("limit") else main.postDelayed(idleCommit, IDLE_COMMIT_MS)
    }

    private val idleCommit = Runnable { commitSession("fasilə") }

    /** Sessiyanı bitir və bazaya yaz */
    @Synchronized
    fun commitSession(reason: String) {
        main.removeCallbacks(idleCommit)
        val steps = _session.value
        _session.value = emptyList()
        if (steps.isEmpty()) return
        val ctx = appContext ?: return
        var n = 0
        // 1) hər klik — ayrıca tək addımlı funksiya
        steps.filter { it.type == FlowStep.Type.CLICK }.forEach { s ->
            RecordedFlowRepository.record(RecordedFlow.Kind.FUNCTION, listOf(s), "${appLabel(ctx, s.packageName)}: ${s.label()}", s.motion)
            n++
        }
        // 2) bütün ardıcıllıq — gedişat
        if (steps.size >= 2) {
            RecordedFlowRepository.record(RecordedFlow.Kind.FLOW, steps, flowName(ctx, steps), dominantMotion(steps)); n++
        } else if (steps.size == 1 && steps[0].type == FlowStep.Type.WINDOW) {
            val s = steps[0]
            RecordedFlowRepository.record(RecordedFlow.Kind.FUNCTION, steps, "${appLabel(ctx, s.packageName)}: ${s.label()}", s.motion); n++
        }
        _recordedThisRun.value += n
        SelfDiag.log("scanner", "sessiya yazıldı ($reason)", mapOf("steps" to steps.size, "records" to n))
    }

    private fun dominantMotion(steps: List<FlowStep>): MotionState = when {
        steps.any { it.motion == MotionState.MOVING } -> MotionState.MOVING
        steps.any { it.motion == MotionState.STOPPED } -> MotionState.STOPPED
        else -> MotionState.UNKNOWN
    }

    private fun flowName(ctx: Context, steps: List<FlowStep>): String {
        val app = appLabel(ctx, steps.first().packageName)
        val clicks = steps.filter { it.type == FlowStep.Type.CLICK }.map { it.label() }
        val tail = if (clicks.isEmpty()) steps.last().label() else clicks.take(4).joinToString(" → ") + if (clicks.size > 4) " …" else ""
        return "$app: $tail"
    }

    fun appLabel(ctx: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    private fun isActivity(ctx: Context, pkg: String, cls: String): Boolean {
        if (cls.isBlank()) return false
        val k = "$pkg/$cls"
        return activityCache.getOrPut(k) {
            runCatching { ctx.packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true }.getOrDefault(false)
        }
    }
}
