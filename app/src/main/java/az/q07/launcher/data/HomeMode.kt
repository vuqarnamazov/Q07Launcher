package az.q07.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Q07 Launcher-in əsas ekran (HOME) olub-olmaması.
 * Standart: SÖNÜLÜ — zavod launcher-i (com.incall.apps.sdalauncher) əsas qalır.
 */
object HomeMode {
    private fun alias(c: Context) = ComponentName(c.packageName, "az.q07.launcher.HomeAlias")

    fun isEnabled(c: Context): Boolean =
        c.packageManager.getComponentEnabledSetting(alias(c)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Əsas ekran seçiminə əlavə et və sistem seçim ekranını aç */
    fun enable(c: Context) {
        c.packageManager.setComponentEnabledSetting(
            alias(c), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        val ok = QuickActions.start(c, Intent(Settings.ACTION_HOME_SETTINGS))
        if (!ok) QuickActions.toast(c, "Home düyməsini bas və «Q07 Launcher» seç")
    }

    /** Geri qaytar: HOME rolunu sil və zavod launcher-ini aç */
    fun disable(c: Context) {
        c.packageManager.setComponentEnabledSetting(
            alias(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
        QuickActions.findOtherHomes(c).firstOrNull()?.let { QuickActions.launchHome(c, it) }
    }
}
