package az.q07.launcher.data

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

class SmartWindowOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: TextView? = null
    private var currentMode = WindowDisplayMode.FULLSCREEN
    private var appName = "Tətbiq"
    private var lastTapTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modeName = intent?.getStringExtra("MODE") ?: WindowDisplayMode.FULLSCREEN.name
        appName = intent?.getStringExtra("APP_NAME") ?: FreeformWindowLauncher.lastLaunchedName
        currentMode = runCatching { WindowDisplayMode.valueOf(modeName) }.getOrDefault(WindowDisplayMode.FULLSCREEN)
        showOrUpdateOverlay()
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrUpdateOverlay() {
        if (windowManager == null) windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (floatingView == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(28, 16, 28, 16)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC0F172A"))
                    cornerRadius = 32f
                    setStroke(2, Color.parseColor("#38BDF8"))
                }
            }
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.END; x = 40; y = 90 }

            var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
            tv.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { initX = params.x; initY = params.y; touchX = e.rawX; touchY = e.rawY; dragging = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (touchX - e.rawX).toInt(); val dy = (e.rawY - touchY).toInt()
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            dragging = true; params.x = initX + dx; params.y = initY + dy
                            windowManager?.updateViewLayout(tv, params)
                        }; true
                    }
                    MotionEvent.ACTION_UP -> { if (!dragging) handleTap(); true }
                    else -> false
                }
            }
            floatingView = tv
            windowManager?.addView(tv, params)
        }

        floatingView?.text = if (currentMode == WindowDisplayMode.FULLSCREEN)
            "↙ $appName: Kiçik Pəncərə"
        else
            "⛶ $appName (Tam ekran üçün 2 dəfə vur)"
    }

    private fun handleTap() {
        val now = SystemClock.elapsedRealtime()
        if (currentMode == WindowDisplayMode.FULLSCREEN) {
            FreeformWindowLauncher.launchAppWithMode(applicationContext, FreeformWindowLauncher.lastLaunchedPackages, WindowDisplayMode.MINI_WINDOW, appName)
        } else {
            if (now - lastTapTime < 420L) {
                FreeformWindowLauncher.launchAppWithMode(applicationContext, FreeformWindowLauncher.lastLaunchedPackages, WindowDisplayMode.FULLSCREEN, appName)
                lastTapTime = 0L
            } else {
                lastTapTime = now
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
    }
}
