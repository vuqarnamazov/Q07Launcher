package az.q07.launcher.macro

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import az.q07.launcher.data.DriveModeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AccessibilityService-ə qoşulan makro yazıcısı.
 *
 * Rejim açıqkən istifadəçinin maşın menyusunda etdiyi kliklər addım kimi yazılır.
 * İstifadəçi launcher-ə qayıdanda yazı avtomatik dayanır və makro saxlanılır.
 */
object MacroAccessibilityHook {

    private const val TAG = "Q07MACRO"
    private const val MAX_MS = 60_000L
    private const val MIN_GAP_MS = 250L

    data class State(
        val recording: Boolean = false,
        val steps: List<MacroStep> = emptyList(),
        val message: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var appContext: Context? = null
    private var startedAt = 0L
    private var lastKey = ""
    private var lastAt = 0L

    val recording: Boolean get() = _state.value.recording

    fun start(context: Context): Boolean {
        if (recording) return false
        appContext = context.applicationContext
        startedAt = System.currentTimeMillis()
        lastKey = ""
        lastAt = 0L
        _state.value = State(recording = true, message = "Qeydiyyat: menyuda gəz, düymələrə bas")
        android.util.Log.i(TAG, "makro yazısı başladı")
        return true
    }

    /** Yazını dayandırır və dolu olarsa makronu saxlayır. */
    fun stop(context: Context? = null): Macro? {
        val ctx = context?.applicationContext ?: appContext
        val s = _state.value
        if (!s.recording) return null
        val steps = s.steps.filterNot { it.text.contains("Dayandır", true) || it.desc.contains("Dayandır", true) }
        _state.value = State(recording = false, steps = emptyList(), message = if (steps.isEmpty()) "Heç bir addım yazılmadı" else "Saxlanıldı")
        appContext = null
        android.util.Log.i(TAG, "makro yazısı bitdi: ${steps.size} addım")
        if (ctx == null || steps.isEmpty()) return null
        MacroStore.init(ctx)
        val name = "Makro ${steps.last().label().take(24)}"
        return MacroStore.add(name, steps)
    }

    fun cancel() {
        if (!recording) return
        _state.value = State(message = "Ləğv edildi")
        appContext = null
    }

    /** DriveModeService.onAccessibilityEvent-dən çağırılır. */
    fun onEvent(service: DriveModeService, event: AccessibilityEvent) {
        if (!recording) return
        val ctx = appContext
        if (System.currentTimeMillis() - startedAt > MAX_MS) {
            if (ctx != null) stop(ctx)
            return
        }
        // Launcher-ə qayıdış — yazını avtomatik bitir (ilk addımdan sonra)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg == service.packageName && _state.value.steps.isNotEmpty() && ctx != null) {
                stop(ctx)
            }
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val step = stepOf(event) ?: return
        // Ilk addımdan sonra öz tətbiqimizin kliklərini yazmırıq (Dayandır, Sil və s.)
        if (step.packageName == service.packageName && _state.value.steps.isNotEmpty()) return
        val now = System.currentTimeMillis()
        if (step.key() == lastKey && now - lastAt < MIN_GAP_MS) return
        lastKey = step.key()
        lastAt = now
        _state.value = _state.value.copy(steps = _state.value.steps + step)
        android.util.Log.i(TAG, "addım ${_state.value.steps.size}: ${step.label()} [${step.packageName}]")
    }

    private fun stepOf(event: AccessibilityEvent): MacroStep? {
        val src = event.source
        val pkg = event.packageName?.toString().orEmpty().ifBlank { src?.packageName?.toString().orEmpty() }
        if (pkg.isBlank()) return null
        val text = event.text?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty()
            .ifBlank { src?.text?.toString().orEmpty() }
            .trim()
        val desc = src?.contentDescription?.toString().orEmpty().trim()
        val viewId = src?.viewIdResourceName.orEmpty()
        val cls = (src?.className?.toString() ?: event.className?.toString().orEmpty())
        val rect = Rect()
        src?.getBoundsInScreen(rect)
        if (text.isBlank() && desc.isBlank() && viewId.isBlank() && rect.isEmpty) return null
        return MacroStep(
            packageName = pkg,
            className = cls,
            text = text,
            desc = desc,
            viewId = viewId,
            bounds = listOf(rect.left, rect.top, rect.right, rect.bottom)
        )
    }

    /** Replay üçün: ekranda addımı tapır. */
    suspend fun awaitNode(service: DriveModeService, step: MacroStep, timeoutMs: Long = 3000): AccessibilityNodeInfo? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val roots = runCatching { service.macroRoots() }.getOrDefault(emptyList())
            findIn(roots, step)?.let { return it }
            kotlinx.coroutines.delay(200)
        }
        return null
    }

    private fun findIn(roots: List<AccessibilityNodeInfo>, step: MacroStep): AccessibilityNodeInfo? {
        val scoped = roots.filter { it.packageName?.toString() == step.packageName }.ifEmpty { roots }
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 30) return
            all += n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        scoped.forEach { walk(it, 0) }
        if (all.isEmpty()) return null
        fun matches(n: AccessibilityNodeInfo, byId: Boolean, byText: Boolean, byDesc: Boolean): Boolean {
            if (byId && step.viewId.isNotBlank() && n.viewIdResourceName == step.viewId) return true
            if (byText && step.text.isNotBlank() && n.text?.toString()?.trim() == step.text) return true
            if (byDesc && step.desc.isNotBlank() && n.contentDescription?.toString()?.trim() == step.desc) return true
            return false
        }
        return all.firstOrNull { it.isClickable && matches(it, true, true, true) }
            ?: all.firstOrNull { matches(it, true, true, true) }
            ?: all.firstOrNull { matches(it, false, true, false) }
            ?: all.firstOrNull { matches(it, false, false, true) }
            ?: all.minByOrNull { nodeDistance(it, step) }?.takeIf { nodeDistance(it, step) < 90 }
    }

    private fun nodeDistance(n: AccessibilityNodeInfo, step: MacroStep): Int {
        if (step.bounds.size < 4) return Int.MAX_VALUE
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return Int.MAX_VALUE
        val cx = (step.bounds[0] + step.bounds[2]) / 2f
        val cy = (step.bounds[1] + step.bounds[3]) / 2f
        return (kotlin.math.abs(r.exactCenterX() - cx) + kotlin.math.abs(r.exactCenterY() - cy)).toInt()
    }
}
