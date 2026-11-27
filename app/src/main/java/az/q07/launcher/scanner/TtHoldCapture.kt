package az.q07.launcher.scanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import az.q07.launcher.data.FactoryBridge
import kotlin.math.abs

/**
 * «Hədəfi 2 saniyə basıb saxla» seçimi.
 *
 * Öyrənmə sessiyası açıq olduqca ekranın üstündə tamşəffaf toxunuş qatı (accessibility overlay) qoyulur:
 *  - barmaq bir nöqtədə ~2 saniyə qalarsa — həmin nöqtədəki maşın ekranı düyməsi hədəf seçilir
 *    (ekran dəyişməsindən asılı deyil: seçim barmaq hələ basılı ikən edilir),
 *  - qısa toxunuş / sürüşmə olduğu kimi geri ötürülür (`dispatchGesture`) ki, menyuda gediş mümkün olsun.
 *
 * Beləliklə navigasiya gedişləri qeydə alınmır: yalnız uzun basılan düymə yol kimi saxlanır.
 */
object TtHoldCapture {

    private const val TAG = "Q07TT"
    private const val HOLD_MS = 1700L
    private const val MOVE_TOLERANCE_PX = 40f
    private const val BADGE_MS = 1800L
    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 40

    private val main = Handler(Looper.getMainLooper())
    private var svc: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var badge: View? = null
    private var active = false
    private var onTarget: ((FlowStep) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var captured = false

    private val hold = Runnable { captureAt(downX, downY) }

    /** Sessiyanı başladır. `false` — örtük açılmadı (xidmət yoxdur / icazə yoxdur). */
    fun start(onTarget: (FlowStep) -> Unit): Boolean {
        val s = FactoryBridge.service ?: return false
        stop()
        val w = runCatching { s.getSystemService(Context.WINDOW_SERVICE) as WindowManager }.getOrNull() ?: return false
        svc = s
        wm = w
        this.onTarget = onTarget
        active = true
        if (!addOverlay()) {
            stop()
            return false
        }
        Log.i(TAG, "hədəf seçimi açıldı — 2 saniyə basıb saxla")
        return true
    }

    fun stop() {
        active = false
        onTarget = null
        main.removeCallbacks(hold)
        removeOverlay()
        removeBadge()
        svc = null
        wm = null
    }

    fun isActive(): Boolean = active

    // ───────────────────────── toxunuş ─────────────────────────

    private fun handle(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                downAt = SystemClock.elapsedRealtime()
                captured = false
                main.removeCallbacks(hold)
                main.postDelayed(hold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.rawX - downX) > MOVE_TOLERANCE_PX || abs(e.rawY - downY) > MOVE_TOLERANCE_PX) {
                main.removeCallbacks(hold)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(hold)
                if (!captured) forward(downX, downY, e.rawX, e.rawY, SystemClock.elapsedRealtime() - downAt)
                captured = false
            }
        }
        return true
    }

    /** Uzun basış tutuldu: həmin nöqtədəki düyməni hədəf kimi ver. */
    private fun captureAt(x: Float, y: Float) {
        val s = svc ?: return
        val step = stepAt(s, x, y)
        if (step == null) {
            Log.i(TAG, "uzun basış: (${x.toInt()},${y.toInt()}) nöqtəsində düymə tapılmadı")
            return
        }
        captured = true
        active = false
        Log.i(
            TAG,
            "uzun basış seçildi: ${step.packageName}/${step.className} vid=${step.viewIdResName} " +
                "text=${step.text} bounds=${step.bounds.joinToString(",")}"
        )
        main.removeCallbacks(hold)
        removeOverlay()
        onTarget?.invoke(step)
        badge("✓ «${step.label()}» seçildi")
    }

    /** Qısa toxunuş / sürüşməni altdakı tətbiqə ötür. */
    private fun forward(x1: Float, y1: Float, x2: Float, y2: Float, pressMs: Long) {
        val s = svc ?: return
        removeOverlay()
        val path = Path().apply {
            moveTo(x1, y1)
            if (abs(x2 - x1) > 4f || abs(y2 - y1) > 4f) lineTo(x2, y2)
        }
        val dur = pressMs.coerceIn(60L, 600L)
        val ok = runCatching {
            s.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
                null, null
            )
        }.getOrDefault(false)
        if (!ok) Log.w(TAG, "toxunuş ötürülmədi")
        main.postDelayed({ if (active) addOverlay() }, 220)
    }

    // ───────────────────────── örtük ─────────────────────────

    private fun addOverlay(): Boolean {
        if (overlay != null) return true
        val s = svc ?: return false
        val w = wm ?: return false
        val v = object : View(s) {
            override fun onTouchEvent(event: MotionEvent): Boolean = handle(event)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        return runCatching { w.addView(v, lp); overlay = v; true }.getOrElse {
            Log.w(TAG, "örtük açılmadı: $it")
            false
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { wm?.removeView(v) }
    }

    private fun badge(text: String) {
        val s = svc ?: return
        val w = wm ?: return
        removeBadge()
        val tv = TextView(s).apply {
            setText(text)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(30, 16, 30, 16)
            background = GradientDrawable().apply {
                cornerRadius = 26f
                setColor(0xE60F1720.toInt())
                setStroke(2, 0xFF35C6A0.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        runCatching { w.addView(tv, lp); badge = tv }
        main.postDelayed({ removeBadge() }, BADGE_MS)
    }

    private fun removeBadge() {
        val v = badge ?: return
        badge = null
        runCatching { wm?.removeView(v) }
    }

    // ───────────────────────── nöqtədəki düymə ─────────────────────────

    private fun stepAt(s: AccessibilityService, x: Float, y: Float): FlowStep? {
        val roots = buildList {
            runCatching { s.windows }.getOrNull()?.forEach { win -> runCatching { win.root }.getOrNull()?.let { add(it) } }
            runCatching { s.rootInActiveWindow }.getOrNull()?.let { add(it) }
        }
        var best: AccessibilityNodeInfo? = null
        var bestClickable = false
        var bestArea = Long.MAX_VALUE
        var visited = 0
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > MAX_DEPTH || visited >= MAX_NODES) return
            visited++
            val pkg = n.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != s.packageName && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0 &&
                    x >= r.left && x < r.right && y >= r.top && y < r.bottom
                ) {
                    val area = r.width().toLong() * r.height()
                    val clickable = n.isClickable
                    if (best == null || (clickable && !bestClickable) ||
                        (clickable == bestClickable && area < bestArea)
                    ) {
                        best = n
                        bestClickable = clickable
                        bestArea = area
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        roots.forEach { walk(it, 0) }
        val node = best ?: return null
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val text = node.text?.toString()?.trim().orEmpty().take(80)
        val desc = node.contentDescription?.toString()?.trim().orEmpty().take(80)
        val label = if (text.isBlank() && desc.isBlank()) childLabel(node) else ""
        return FlowStep(
            type = FlowStep.Type.CLICK,
            packageName = node.packageName?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            text = text.ifBlank { label },
            contentDescription = desc,
            viewIdResName = node.viewIdResourceName.orEmpty(),
            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            motion = FullVehicleScannerManager.motion.value
        )
    }

    /** Düymənin altındakı mətn (resource-id/mətn öz üzərində olmayanda). */
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
}
