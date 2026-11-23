package az.q07.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2-ci səhifədən açılan proqramın SOLUNDA launcher menyusunu saxlayan yan panel.
 * Changan sistemi üçüncü tərəf tətbiqləri həmişə tam ekran açır (freeform yalnız zavod tətbiqlərinə),
 * ona görə panel xüsusi imkan qatı kimi proqramın üstündə, solda göstərilir.
 */
object SidePanel {
    /** 2-ci səhifədən açılmış paketlər — bunlar önə gələndə panel görünür */
    val panelPackages = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile var showing = false
        private set

    private var view: View? = null
    private var lpRef: WindowManager.LayoutParams? = null
    private var fullHeight = 0
    private var panelTop = 0
    private val main = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    @Volatile private var lastPkg: String? = null

    /** Launcher-dən açılmış tətbiq hazırda sağda freeform pəncərədə görünürmü */
    fun freeformOnTop(service: DriveModeService): Boolean = runCatching {
        val r = android.graphics.Rect()
        service.windows.any { w ->
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
            w.getBoundsInScreen(r)
            val p = w.root?.packageName?.toString()
            p != null && p != service.packageName && p in panelPackages && r.left > 100
        }
    }.getOrDefault(false)

    fun onForeground(service: DriveModeService, pkg: String) {
        if (pkg != "com.android.systemui" && !pkg.startsWith("com.incall.apps.hmi")) lastPkg = pkg
        when {
            // freeform açılanda launcher-in özünün də hadisəsi gəlir — pəncərə hələ sağdadırsa panel qalır
            pkg == service.packageName || pkg == FactoryBridge.HOME ->
                if (freeformOnTop(service)) show(service) else hide(service)
            // Changan freeform pəncərəsi açılanda sistem arxadakı launcher-i gizlədir (səth silinir, sol tərəf qara qalır),
            // ona görə sol panel həmişə xüsusi imkan qatı kimi çəkilir
            pkg in panelPackages -> show(service)
            pkg == "com.android.systemui" || pkg.startsWith("com.incall.apps.hmi") -> Unit
            else -> if (!freeformOnTop(service)) hide(service)
        }
    }

    /** Pəncərələr dəyişəndə (freeform bağlandı/açıldı) paneli yenilə */
    fun onWindowsChanged(service: DriveModeService) {
        if (freeformOnTop(service)) { show(service); fitAboveKeyboard(service); return }
        if (showing && lastPkg !in panelPackages) hide(service) else fitAboveKeyboard(service)
    }

    /**
     * Klaviatura (sistem IME pəncərəsi) bütün en boyu açılır — panel onun üstünü örtməsin deyə
     * klaviatura görünəndə panel qısalır (klaviaturanın üstündə bitir), bağlananda əvvəlki ölçüyə qayıdır.
     */
    fun fitAboveKeyboard(service: DriveModeService) {
        val v = view ?: return
        val lp = lpRef ?: return
        val imeTop = runCatching {
            val r = android.graphics.Rect()
            service.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { it.getBoundsInScreen(r); r.top }
        }.getOrNull()
        val target = if (imeTop != null && imeTop < panelTop + fullHeight) (imeTop - panelTop - 8).coerceAtLeast(0) else fullHeight
        if (lp.height == target) return
        lp.height = target
        v.visibility = if (target < 120) View.INVISIBLE else View.VISIBLE
        runCatching { (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp) }
    }

    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    fun show(service: DriveModeService) {
        if (!LicenseManager.isActivated(service)) return
        if (view != null) return
        val dm = service.resources.displayMetrics
        val width = if (WindowLauncher.dockRightPx > 0) WindowLauncher.dockRightPx else (dm.widthPixels * 0.24f).toInt()
        // Q07: üst status zolağı 96px, alt iqlim zolağı 1440-dan aşağı
        val top = (dm.heightPixels * 0.06f).toInt()
        val height = (dm.heightPixels * 0.84f).toInt()
        val s = width / 300f // panel daxili miqyas

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * s).toInt(), (16 * s).toInt(), (16 * s).toInt(), (16 * s).toInt())
            background = GradientDrawable().apply {
                setColor(0xF20E1218.toInt()); cornerRadii = floatArrayOf(0f, 0f, 28 * s, 28 * s, 28 * s, 28 * s, 0f, 0f)
                setStroke(2, 0x33FFFFFF)
            }
        }
        val clock = TextView(service).apply {
            setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_PX, 52 * s); typeface = Typeface.DEFAULT_BOLD
        }
        val date = TextView(service).apply { setTextColor(0xFF9AA4B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * s) }
        val live = TextView(service).apply {
            setTextColor(0xFF3DDC97.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_PX, 17 * s)
            setPadding(0, (8 * s).toInt(), 0, (8 * s).toInt())
        }
        root.addView(clock); root.addView(date); root.addView(live)

        val list = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val pm = service.packageManager
        val keys = LauncherPrefs.dockSlots.filter { it.isNotBlank() }.take(AppSettings.dockSlotCount)
        for (k in keys) {
            val cmp = ComponentName.unflattenFromString(k) ?: continue
            val label = runCatching { pm.getActivityInfo(cmp, 0).loadLabel(pm).toString() }.getOrNull() ?: continue
            val icon = runCatching { pm.getActivityIcon(cmp) }.getOrNull()
            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * s).toInt(), (10 * s).toInt(), (10 * s).toInt(), (10 * s).toInt())
                background = GradientDrawable().apply { setColor(0xFF1A2029.toInt()); cornerRadius = 16 * s }
                setOnClickListener {
                    panelPackages.add(cmp.packageName)
                    runCatching {
                        service.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(cmp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            row.addView(ImageView(service).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams((40 * s).toInt(), (40 * s).toInt()))
            row.addView(TextView(service).apply {
                text = label; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_PX, 18 * s)
                setPadding((12 * s).toInt(), 0, 0, 0); maxLines = 1
            })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * s).toInt() })
        }
        root.addView(ScrollView(service).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val back = TextView(service).apply {
            text = "◀  Q07 Launcher"; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * s); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (14 * s).toInt(), 0, (14 * s).toInt())
            background = GradientDrawable().apply { setColor(0xFF2D6BFF.toInt()); cornerRadius = 22 * s }
            setOnClickListener {
                hide(service)
                runCatching {
                    service.startActivity(Intent(service, az.q07.launcher.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }
        root.addView(back, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * s).toInt() })

        val lp = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = top }
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (runCatching { wm.addView(root, lp) }.isFailure) return
        view = root
        lpRef = lp; fullHeight = height; panelTop = top
        fitAboveKeyboard(service)
        showing = true

        val fmtT = SimpleDateFormat("HH:mm", Locale.getDefault())
        val fmtD = SimpleDateFormat("EEEE, d MMMM", Locale("az"))
        val r = object : Runnable {
            override fun run() {
                clock.text = fmtT.format(Date()); date.text = fmtD.format(Date())
                val v = ObdMonitor.state.value.values
                fun f(k: String, d: Int = 0) = v[k]?.let { if (d == 0) it.toInt().toString() else String.format(Locale.US, "%.${d}f", it) } ?: "—"
                live.text = "${f("speed")} km/s   ·   ${f("rpm")} rpm\nSOC ${f("soc", 1)}%   ·   ${f("voltage", 1)} V   ·   ${f("coolant")}°C"
                main.postDelayed(this, 1000)
            }
        }
        ticker = r; r.run()
    }

    fun hide(service: DriveModeService) {
        val v = view ?: return
        view = null; showing = false; lpRef = null
        ticker?.let { main.removeCallbacks(it) }; ticker = null
        runCatching { (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
    }
}
