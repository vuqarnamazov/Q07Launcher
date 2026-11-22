package az.q07.launcher.scanner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import az.q07.launcher.data.DriveModeService
import az.q07.launcher.data.FactoryBridge
import az.q07.launcher.data.QuickActions
import az.q07.launcher.data.SelfDiag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Öyrənilmiş funksiya/gedişatı xüsusi imkan xidməti ilə təkrar icra edir.
 * Çoxaddımlı gedişatda addımlar arasında [LearnedFunction.stepDelayMs] gecikmə olur.
 * Element əvvəlcə viewId → mətn → contentDescription ilə axtarılır; tapılmasa, qeydə alınmış koordinata toxunulur.
 */
object FlowPlayer {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    fun play(context: Context, fn: LearnedFunction) {
        val ctx = context.applicationContext
        if (_playing.value) { _status.value = "Başqa gedişat icra olunur"; return }
        val svc = FactoryBridge.service
        if (svc == null) { _status.value = "Xüsusi imkan xidməti aktiv deyil"; return }
        LearnedFunctionsRepository.markRun(fn.id)
        job = scope.launch {
            _playing.value = true
            var failed: String? = null
            try {
                for ((i, s) in fn.steps.withIndex()) {
                    _status.value = "${fn.name} — ${i + 1}/${fn.steps.size}: ${s.label()}"
                    val ok = when (s.type) {
                        FlowStep.Type.WINDOW -> openWindow(ctx, s)
                        FlowStep.Type.CLICK -> click(ctx, svc, s)
                    }
                    if (!ok) { failed = "Addım ${i + 1} alınmadı: ${s.label()}"; break }
                    if (i < fn.steps.lastIndex) delay(fn.stepDelayMs)
                }
            } finally {
                _playing.value = false
            }
            _status.value = failed ?: "✓ ${fn.name} icra olundu"
            SelfDiag.log("scanner", "gedişat icrası", mapOf("name" to fn.name, "result" to (failed ?: "ok")))
        }
    }

    fun stop() { job?.cancel(); _playing.value = false; _status.value = "Dayandırıldı" }

    private suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; delay(150) }
        return cond()
    }

    private fun onScreen(svc: DriveModeService, pkg: String) = svc.scannerRoots().any { it.packageName == pkg }

    private suspend fun openWindow(ctx: Context, s: FlowStep): Boolean {
        if (RecorderScannerManager.foregroundPackage == s.packageName && RecorderScannerManager.foregroundClass == s.className) return true
        val launched = (s.className.isNotBlank() &&
            QuickActions.start(ctx, Intent(Intent.ACTION_MAIN).setComponent(ComponentName(s.packageName, s.className)))) ||
            QuickActions.launchPackage(ctx, s.packageName)
        if (!launched) return false
        return waitFor(5000) { RecorderScannerManager.foregroundPackage == s.packageName }
    }

    private suspend fun click(ctx: Context, svc: DriveModeService, s: FlowStep): Boolean {
        if (!onScreen(svc, s.packageName)) {
            if (!QuickActions.launchPackage(ctx, s.packageName)) return false
            if (!waitFor(5000) { onScreen(svc, s.packageName) }) return false
            delay(400)
        }
        var node: AccessibilityNodeInfo? = null
        waitFor(4000) { node = findNode(svc, s); node != null }
        val n = node
        if (n != null) {
            val clicked = generateSequence(n) { it.parent }.firstOrNull { it.isClickable }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (clicked) return true
            val r = Rect().also { n.getBoundsInScreen(it) }
            svc.scannerTap(r.exactCenterX(), r.exactCenterY()); return true
        }
        // element tapılmadı — qeydə alınmış koordinata toxun
        if (s.bounds.size == 4 && s.bounds[2] > s.bounds[0] && s.bounds[3] > s.bounds[1]) {
            svc.scannerTap((s.bounds[0] + s.bounds[2]) / 2f, (s.bounds[1] + s.bounds[3]) / 2f)
            return true
        }
        return false
    }

    private fun findNode(svc: DriveModeService, s: FlowStep): AccessibilityNodeInfo? {
        val roots = svc.scannerRoots().filter { it.packageName == s.packageName }
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            if (s.viewIdResName.isNotBlank()) {
                val byId = runCatching { root.findAccessibilityNodeInfosByViewId(s.viewIdResName) }.getOrNull().orEmpty()
                candidates += if (s.text.isNotBlank()) byId.filter { matchesText(it, s.text) }.ifEmpty { byId } else byId
            }
            if (candidates.isEmpty() && s.text.isNotBlank()) {
                candidates += runCatching { root.findAccessibilityNodeInfosByText(s.text) }.getOrNull().orEmpty()
                    .filter { matchesText(it, s.text) }
            }
            if (candidates.isEmpty() && s.contentDescription.isNotBlank()) walkDesc(root, s.contentDescription, candidates, 0)
        }
        val visible = candidates.filter { it.isVisibleToUser }.ifEmpty { candidates }
        if (visible.size <= 1 || s.bounds.size != 4) return visible.firstOrNull()
        val cx = (s.bounds[0] + s.bounds[2]) / 2.0; val cy = (s.bounds[1] + s.bounds[3]) / 2.0
        return visible.minByOrNull { val r = Rect().also { b -> it.getBoundsInScreen(b) }; hypot(r.exactCenterX() - cx, r.exactCenterY() - cy) }
    }

    private fun matchesText(n: AccessibilityNodeInfo, t: String): Boolean =
        n.text?.toString()?.trim() == t || n.contentDescription?.toString()?.trim() == t ||
            (0 until n.childCount).any { i -> n.getChild(i)?.text?.toString()?.trim() == t }

    private fun walkDesc(n: AccessibilityNodeInfo?, desc: String, out: MutableList<AccessibilityNodeInfo>, d: Int) {
        if (n == null || d > 30) return
        if (n.contentDescription?.toString()?.trim() == desc) out += n
        for (i in 0 until n.childCount) walkDesc(n.getChild(i), desc, out, d + 1)
    }
}
