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

    /**
     * [returnToLauncher] — gedişat bitəndən sonra launcher öne qaytarılır (paneldə qalmasın).
     * [stepDelayOverrideMs] — addımlar arası gecikməni qısaltmaq üçün (mühərrik əmri: 400 ms).
     * [onResult] — gedişat BİTDİKDƏ çağırılır: (uğurlu, mesaj, ən azı bir basış baş verdi).
     * Qaytarılan dəyər: gedişat qəbul olundu (false = başlamadı, [status] səbəbi göstərir).
     */
    fun play(
        context: Context,
        fn: LearnedFunction,
        returnToLauncher: Boolean = false,
        stepDelayOverrideMs: Long? = null,
        /** false = element tapılmasa köhnə koordinata toxunma (mühərrik əmri üçün) */
        allowCoordTap: Boolean = true,
        onResult: ((success: Boolean, message: String, clicked: Boolean) -> Unit)? = null
    ): Boolean {
        val ctx = context.applicationContext
        if (_playing.value) {
            _status.value = "Başqa gedişat icra olunur"
            onResult?.invoke(false, _status.value!!, false)
            return false
        }
        val svc = FactoryBridge.service
        if (svc == null) {
            _status.value = "Xüsusi imkan xidməti aktiv deyil"
            onResult?.invoke(false, _status.value!!, false)
            return false
        }
        LearnedFunctionsRepository.markRun(fn.id)
        job = scope.launch {
            _playing.value = true
            var failed: String? = null
            var clicked = false
            android.util.Log.i("Q07TT", "səssiz icra başladı: ${fn.name} (${fn.steps.size} addım)")
            try {
                for ((i, s) in fn.steps.withIndex()) {
                    _status.value = "${fn.name} — ${i + 1}/${fn.steps.size}: ${s.label()}"
                    android.util.Log.i("Q07TT", "addım ${i + 1}/${fn.steps.size}: ${s.type} ${s.label()} pkg=${s.packageName}")
                    val ok = when (s.type) {
                        FlowStep.Type.WINDOW -> openWindow(ctx, s)
                        FlowStep.Type.CLICK -> click(ctx, svc, s, allowCoordTap)
                    }
                    if (!ok) { failed = "Addım ${i + 1} alınmadı: ${s.label()}"; break }
                    if (s.type == FlowStep.Type.CLICK) clicked = true
                    if (i < fn.steps.lastIndex) delay(stepDelayOverrideMs ?: fn.stepDelayMs)
                }
            } finally {
                _playing.value = false
            }
            val message = failed ?: "✓ ${fn.name} icra olundu"
            _status.value = message
            android.util.Log.i("Q07TT", if (failed == null) "səssiz icra bitdi: $message" else "səssiz icra alınmadı: $failed")
            // Nəticə launcher-ə qayıtmazdan ƏVVƏL bildirilir ki, düymə vəziyyəti düzgün yenilənsin
            onResult?.invoke(failed == null, message, clicked)
            SelfDiag.log("scanner", "gedişat icrası", mapOf("name" to fn.name, "result" to (failed ?: "ok")))
            // Uğursuzluqda pəncərə açıq qalır — istifadəçi düyməni əl ilə basa bilsin (skaner öyrənsin)
            if (returnToLauncher && failed == null) {
                delay(150)
                QuickActions.launchPackage(ctx, "az.q07.launcher")
            }
        }
        return true
    }

    fun stop() { job?.cancel(); _playing.value = false; _status.value = "Dayandırıldı" }

    private suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; delay(150) }
        return cond()
    }

    private fun onScreen(svc: DriveModeService, pkg: String) = svc.scannerRoots().any { it.packageName == pkg }

    private suspend fun openWindow(ctx: Context, s: FlowStep): Boolean {
        if (FullVehicleScannerManager.foregroundPackage == s.packageName && FullVehicleScannerManager.foregroundClass == s.className) return true
        val launched = (s.className.isNotBlank() &&
            QuickActions.start(
                ctx,
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(s.packageName, s.className))
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )) || QuickActions.launchPackage(ctx, s.packageName)
        if (!launched) return false
        return waitFor(5000) { FullVehicleScannerManager.foregroundPackage == s.packageName }
    }

    /**
     * Səssiz icra üçün tətbiqi birbaşa ön plana çıxarır.
     * [QuickActions.launchPackage] yan-panel ölçüləri ilə açır və pəncərə launcherin arxasında qala bilir —
     * replay zamanı hədəf ekran görünməlidir, əks halda element tapılmır.
     */
    private fun launchForeground(ctx: Context, s: FlowStep): Boolean {
        // CLICK addımındakı className düymənin sinfidir, activity deyil — yalnız paket launch intent-i istifadə olunur
        val intent = ctx.packageManager.getLaunchIntentForPackage(s.packageName)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(s.packageName)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        val res = runCatching { ctx.startActivity(intent) }
        android.util.Log.i("Q07TT", "launch ${s.packageName} -> ${res.isSuccess} ${res.exceptionOrNull()?.javaClass?.simpleName ?: ""}")
        return res.isSuccess
    }

    private suspend fun click(ctx: Context, svc: DriveModeService, s: FlowStep, allowCoordTap: Boolean): Boolean {
        if (!onScreen(svc, s.packageName)) {
            if (!launchForeground(ctx, s)) return false
            if (!waitFor(5000) { onScreen(svc, s.packageName) }) return false
            delay(400)
        }
        var node: AccessibilityNodeInfo? = null
        waitFor(6000) { node = findNode(svc, s); node != null }
        android.util.Log.i("Q07TT", "node ${s.label()} -> ${node != null} (roots=${svc.scannerRoots().map { it.packageName }.distinct()})")
        val n = node
        if (n != null) delay(250)
        if (n != null) {
            val clicked = generateSequence(n) { it.parent }.firstOrNull { it.isClickable }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (clicked) return true
            val r = Rect().also { n.getBoundsInScreen(it) }
            svc.scannerTap(r.exactCenterX(), r.exactCenterY()); return true
        }
        // element tapılmadı — qeydə alınmış koordinata toxun (yalnız icazə verilibsə)
        if (allowCoordTap && s.bounds.size == 4 && s.bounds[2] > s.bounds[0] && s.bounds[3] > s.bounds[1]) {
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
