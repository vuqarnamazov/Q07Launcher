package az.q07.launcher.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

data class HomeTarget(val label: String, val component: ComponentName)

/** Dock düymələrinin məntiqi */
object QuickActions {
    private const val WAZE = "com.waze"
    private const val GOOGLE_MAPS = "com.google.android.apps.maps"
    private const val YOUTUBE = "com.google.android.youtube"
    private const val GEMINI = "com.google.android.apps.bard"

    fun start(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun launchPackage(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(context, intent)
    }

    private fun openUrl(context: Context, url: String): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun openNavigation(context: Context) {
        val ok = launchPackage(context, WAZE) ||
            launchPackage(context, GOOGLE_MAPS) ||
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
        if (!ok) toast(context, "Naviqasiya tətbiqi tapılmadı")
    }

    fun openYouTube(context: Context) {
        val ok = launchPackage(context, YOUTUBE) || openUrl(context, "https://m.youtube.com")
        if (!ok) toast(context, "YouTube açıla bilmədi")
    }

    fun openGemini(context: Context) {
        val ok = launchPackage(context, GEMINI) || openUrl(context, "https://gemini.google.com")
        if (!ok) toast(context, "Gemini açıla bilmədi")
    }

    /** Sistemdəki digər HOME launcher-ləri (zavod launcher-i daxil) */
    fun findOtherHomes(context: Context): List<HomeTarget> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo }
            .filter {
                it.packageName != context.packageName &&
                    it.packageName != "com.android.settings" // FallbackHome
            }
            .map { HomeTarget(it.loadLabel(pm).toString(), ComponentName(it.packageName, it.name)) }
            .distinctBy { it.component }
    }

    /** Zavod launcher-ini açır. Q07 Launcher defolt qalır — Home düyməsi bizi geri qaytarır. */
    fun launchHome(context: Context, target: HomeTarget) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(target.component)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!start(context, intent)) toast(context, "${target.label} açıla bilmədi")
    }

    /** Defolt əsas ekranı dəyişmək üçün sistem ayarları */
    fun openHomeSettings(context: Context) {
        val ok = start(context, Intent(Settings.ACTION_HOME_SETTINGS)) ||
            start(context, Intent(Settings.ACTION_SETTINGS))
        if (!ok) toast(context, "Ayarlar açıla bilmədi")
    }

    fun openAppInfo(context: Context, pkg: String) {
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
    }
}
