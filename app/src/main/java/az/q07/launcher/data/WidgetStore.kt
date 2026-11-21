package az.q07.launcher.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class HostedWidget(val id: Int, val heightDp: Int)

/**
 * Zavodun (Changan/Incall) sistem vidcetlərini launcher-də saxlamaq üçün.
 * Q07-də 33 vidcet provayderi var: enerji, avtomobil məlumatı, hava, naviqasiya, Bluetooth, musiqi…
 */
object WidgetStore {
    private const val HOST_ID = 1024

    lateinit var host: AppWidgetHost
        private set
    lateinit var manager: AppWidgetManager
        private set
    private lateinit var prefs: SharedPreferences
    private var density = 1f

    val widgets = mutableStateListOf<HostedWidget>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val app = context.applicationContext
        density = app.resources.displayMetrics.density
        manager = AppWidgetManager.getInstance(app)
        host = AppWidgetHost(app, HOST_ID)
        prefs = app.getSharedPreferences("q07_widgets", Context.MODE_PRIVATE)
        widgets.clear()
        prefs.getString("list", "")!!.split(",").filter { it.contains(":") }.forEach {
            val (id, h) = it.split(":")
            val wid = id.toIntOrNull() ?: return@forEach
            // Silinmiş/etibarsız vidcetləri atla
            if (manager.getAppWidgetInfo(wid) != null) widgets.add(HostedWidget(wid, h.toIntOrNull() ?: 200))
        }
        save()
        ensureCarWidgets()
    }

    fun add(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        // minHeight px-dır → dp-yə çevir (Q07: 160 dpi, 1 px = 1 dp)
        val h = ((info?.minHeight ?: 200) / density).toInt().coerceIn(120, 600)
        widgets.add(HostedWidget(id, h))
        save()
    }

    fun remove(id: Int) {
        widgets.removeAll { it.id == id }
        host.deleteAppWidgetId(id)
        save()
    }

    fun resize(id: Int, deltaDp: Int) {
        val i = widgets.indexOfFirst { it.id == id }
        if (i < 0) return
        widgets[i] = widgets[i].copy(heightDp = (widgets[i].heightDp + deltaDp).coerceIn(80, 900))
        save()
    }

    fun move(id: Int, delta: Int) {
        val i = widgets.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in widgets.indices) return
        val w = widgets.removeAt(i)
        widgets.add(j, w)
        save()
    }

    // ---------- Maşın kartına avtomatik qoyulan zavod vidcetləri ----------

    /** Q07 zavod vidcetləri: Enerji (batareya/yanacaq/məsafə) və Avtomobil məlumatı */
    val AUTO_CAR = listOf(
        ComponentName("com.incall.apps.energy", "com.incall.apps.energy_view.widget.SDA_Widget_EnergyProvider"),
        ComponentName("com.incall.apps.vehiclesetting", "com.incall.apps.vehiclesetting.widget.SDA_Widget_VehicleInfoProvider")
    )

    /** Maşın kartındakı vidcet id-ləri (sıra ilə) */
    val carWidgets = mutableStateListOf<Int>()

    /** Sistem icazəsi lazım olan (hələ bağlanmamış) provayderlər */
    var carBindNeeded by mutableStateOf<List<ComponentName>>(emptyList())

    /** İstifadəçi maşın kartında vidcetləri söndürübsə */
    var carWidgetsEnabled: Boolean
        get() = prefs.getBoolean("car_enabled", true)
        set(v) { prefs.edit().putBoolean("car_enabled", v).apply(); ensureCarWidgets() }

    /** Enerji/Avtomobil vidcetlərini yoxla, yoxdursa yarat (icazə varsa səssiz) */
    fun ensureCarWidgets() {
        if (!::prefs.isInitialized) return
        carWidgets.clear()
        if (!carWidgetsEnabled) { carBindNeeded = emptyList(); return }
        // initialLayout = 0 olan Changan «SDA» vidcetləri yalnız zavod launcher-ində (SDA kart sistemi) işləyir —
        // üçüncü tərəf host-da «Couldn't add widget» verir. Belələrini qoymuruq.
        val installed = runCatching {
            manager.installedProviders.filter { it.initialLayout != 0 }.map { it.provider }.toSet()
        }.getOrDefault(emptySet())
        // Əvvəlki versiyada bağlanmış işləməyən vidcetləri təmizlə
        for (cmp in AUTO_CAR) if (cmp !in installed) {
            val key = "car_" + cmp.flattenToShortString()
            val old = prefs.getInt(key, -1)
            if (old != -1) { runCatching { host.deleteAppWidgetId(old) }; prefs.edit().remove(key).apply() }
        }
        val need = mutableListOf<ComponentName>()
        for (cmp in AUTO_CAR) {
            if (cmp !in installed) continue
            val key = "car_" + cmp.flattenToShortString()
            var id = prefs.getInt(key, -1)
            if (id != -1 && manager.getAppWidgetInfo(id)?.provider != cmp) {
                runCatching { host.deleteAppWidgetId(id) }
                id = -1
            }
            if (id == -1) {
                val newId = host.allocateAppWidgetId()
                val ok = runCatching { manager.bindAppWidgetIdIfAllowed(newId, cmp) }.getOrDefault(false)
                if (ok) {
                    id = newId
                    prefs.edit().putInt(key, id).apply()
                } else {
                    host.deleteAppWidgetId(newId)
                    need += cmp
                }
            }
            if (id != -1) carWidgets.add(id)
        }
        carBindNeeded = need
    }

    /** Sistem icazə pəncərəsindən sonra */
    fun onCarBound(cmp: ComponentName, id: Int, ok: Boolean) {
        if (ok) prefs.edit().putInt("car_" + cmp.flattenToShortString(), id).apply()
        else runCatching { host.deleteAppWidgetId(id) }
        ensureCarWidgets()
    }

    private fun save() {
        prefs.edit().putString("list", widgets.joinToString(",") { "${it.id}:${it.heightDp}" }).apply()
    }
}

/**
 * Vidceti launcher-in miqyasına (2.5K ekranda ×2) uyğun böyüdən konteyner.
 * Uşaq vidcet öz ölçüsündə çəkilir, sonra bütöv şəkildə böyüdülür; toxunuşlar düzgün ötürülür.
 */
class ScaledFrame(context: Context) : FrameLayout(context) {
    var scale: Float = 1f
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = (w / scale).toInt()
        val ch = (h / scale).toInt()
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            c.layout(0, 0, c.measuredWidth, c.measuredHeight)
            c.pivotX = 0f
            c.pivotY = 0f
            c.scaleX = scale
            c.scaleY = scale
        }
    }
}
