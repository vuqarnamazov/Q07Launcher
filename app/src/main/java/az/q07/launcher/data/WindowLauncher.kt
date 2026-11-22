package az.q07.launcher.data

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect

/**
 * Tətbiqləri ayrı pəncərələrdə (freeform) açır.
 * Q07-də freeform aktivdir (enable_freeform_support=1, force_resizable_activities=1 — yoxlanılıb 22.11.2026).
 * ActivityOptions.setLaunchBounds → sistem tətbiqi verilən çərçivədə pəncərə kimi açır.
 */
object WindowLauncher {
    enum class Area(val title: String) {
        FULL("Tam ekran"),
        BESIDE_DOCK("Launcher-in sağında"),
        LEFT_HALF("Sol yarıda"),
        RIGHT_HALF("Sağ yarıda"),
        CENTER("Ortada pəncərə")
    }

    /** Kompakt rejimdə launcher panelinin ekran eninə nisbəti */
    const val DOCK_FRACTION = 0.26f

    /** 2-ci səhifədə dock-un sağ kənarı (ekran px) — tətbiq bunun sağında açılır */
    @Volatile var dockRightPx: Int = 0

    fun bounds(context: Context, area: Area): Rect? {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        return when (area) {
            Area.FULL -> null
            Area.BESIDE_DOCK -> Rect(if (dockRightPx > 0) dockRightPx else (w * DOCK_FRACTION).toInt(), 0, w, h)
            Area.LEFT_HALF -> Rect(0, 0, w / 2, h)
            Area.RIGHT_HALF -> Rect(w / 2, 0, w, h)
            Area.CENTER -> Rect((w * 0.15f).toInt(), (h * 0.08f).toInt(), (w * 0.85f).toInt(), (h * 0.92f).toInt())
        }
    }

    fun launch(context: Context, component: ComponentName, area: Area): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (area == Area.FULL) intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val b = bounds(context, area)
        return try {
            if (b != null) {
                val opts = ActivityOptions.makeBasic().setLaunchBounds(b)
                forceFreeform(opts)
                context.startActivity(intent, opts.toBundle())
            } else {
                context.startActivity(intent)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Tətbiqin köhnə tam ekran tapşırığı varsa, sistem bounds-u nəzərə almır.
     * Gizli setLaunchWindowingMode(FREEFORM=5) ilə pəncərə rejimi məcbur edilir.
     */
    private fun forceFreeform(opts: ActivityOptions) {
        runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                ActivityOptions::class.java, opts, "setLaunchWindowingMode", 5
            )
        }.onFailure { android.util.Log.w("Q07WIN", "windowing mode: $it") }
    }

    /** Launcher-dən adi açılış: kompakt rejimdə tətbiq launcher-in sağında pəncərədə açılır */
    fun defaultArea(): Area = if (AppSettings.compactMode) Area.BESIDE_DOCK else Area.FULL
}
