package az.q07.launcher.macro

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import az.q07.launcher.data.FactoryBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Makronu addım-addım səssiz icra edir (accessibility node və ya jest ilə). */
object MacroPlayer {

    private const val STEP_GAP_MS = 700L
    private const val NODE_TIMEOUT_MS = 3000L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun play(context: Context, macro: Macro, onFinish: (Boolean, String) -> Unit = { _, _ -> }): Boolean {
        if (running) {
            onFinish(false, "Artıq makro icra olunur")
            return false
        }
        val svc = FactoryBridge.service
        if (svc == null) {
            onFinish(false, "Accessibility xidməti aktiv deyil")
            return false
        }
        if (macro.steps.isEmpty()) {
            onFinish(false, "Makro boşdur")
            return false
        }
        running = true
        scope.launch {
            var message = "✓ «${macro.name}» icra olundu"
            var ok = true
            for ((i, step) in macro.steps.withIndex()) {
                _status.value = "Addım ${i + 1}/${macro.steps.size}: ${step.label()}"
                val node = MacroAccessibilityHook.awaitNode(svc, step, NODE_TIMEOUT_MS)
                if (node == null) {
                    ok = false
                    message = "Addım ${i + 1} alınmadı: ${step.label()}"
                    break
                }
                click(svc, node, step)
                delay(STEP_GAP_MS)
            }
            running = false
            _status.value = message
            android.util.Log.i("Q07MACRO", message)
            onFinish(ok, message)
        }
        return true
    }

    private fun click(svc: az.q07.launcher.data.DriveModeService, node: AccessibilityNodeInfo, step: MacroStep) {
        val target = generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: node
        val clicked = runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (clicked) return
        val r = Rect()
        node.getBoundsInScreen(r)
        val x = if (!r.isEmpty) r.exactCenterX() else step.bounds.getOrNull(0)?.plus(step.bounds[2])?.div(2f) ?: return
        val y = if (!r.isEmpty) r.exactCenterY() else step.bounds.getOrNull(1)?.plus(step.bounds[3])?.div(2f) ?: return
        svc.macroTap(x, y)
    }
}
