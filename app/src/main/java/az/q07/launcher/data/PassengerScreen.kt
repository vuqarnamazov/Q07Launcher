package az.q07.launcher.data

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Sərnişin ekranı (Q07: ikinci HDMI ekran, 1920×1080, displayId 1).
 * Tətbiqlər ActivityOptions.setLaunchDisplayId ilə ora göndərilir (maşında yoxlanıb: Waze açıldı).
 */
object PassengerScreen {

    /** Əsas ekrandan başqa ən böyük ictimai ekran (kiçik HUD/cihaz ekranları və gizli virtual ekranlar sayılmır) */
    fun display(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRIVATE) == 0 }
            .filter { val p = android.graphics.Point(); it.getRealSize(p); p.x >= 1280 && p.y >= 700 }
            .maxByOrNull { val p = android.graphics.Point(); it.getRealSize(p); p.x * p.y }
    }

    fun available(context: Context) = display(context) != null

    /** Tətbiqi sərnişin ekranında aç. Uğursuz olsa xəta mətni qaytarır */
    fun launch(context: Context, component: ComponentName): String? {
        val d = display(context) ?: return "Sərnişin ekranı tapılmadı"
        return try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId)
            context.startActivity(i, opts.toBundle())
            SelfDiag.log("passenger", "göndərildi: ${component.packageName} → ekran ${d.displayId}")
            null
        } catch (t: Throwable) {
            SelfDiag.log("passenger", "alınmadı: ${t.javaClass.simpleName} ${t.message}")
            "Göndərmək alınmadı: ${t.message}"
        }
    }

    /** Sərnişin ekranını öz əsas ekranına qaytar (orada açılan tətbiqləri arxaya keçir) */
    fun goHome(context: Context): String? {
        val d = display(context) ?: return "Sərnişin ekranı tapılmadı"
        return try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i, ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).toBundle())
            null
        } catch (t: Throwable) { "Alınmadı: ${t.message}" }
    }
}
