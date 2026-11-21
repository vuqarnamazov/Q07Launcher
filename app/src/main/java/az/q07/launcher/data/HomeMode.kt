package az.q07.launcher.data

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Defolt əsas ekran (HOME) idarəsi.
 * Standart: zavod launcher-i (com.incall.apps.sdalauncher) əsasdır.
 * Düymə ilə Q07 Launcher defolt edilir və ya zavoda qaytarılır.
 */
object HomeMode {
    const val ADB_SET_DEFAULT = "adb shell cmd role add-role-holder android.app.role.HOME az.q07.launcher"
    const val ADB_RESTORE =
        "adb shell cmd package set-home-activity com.incall.apps.sdalauncher/com.incall.apps.model_main.MainActivity"

    private fun alias(c: Context) = ComponentName(c.packageName, "az.q07.launcher.HomeAlias")

    fun isAliasEnabled(c: Context): Boolean =
        c.packageManager.getComponentEnabledSetting(alias(c)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Hazırda Q07 Launcher defolt əsas ekrandırmı */
    fun isDefault(c: Context): Boolean {
        val role = runCatching {
            c.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        }.getOrDefault(false)
        if (role) return true
        val ri = c.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )
        return ri?.activityInfo?.packageName == c.packageName
    }

    fun setAlias(c: Context, enabled: Boolean) {
        c.packageManager.setComponentEnabledSetting(
            alias(c),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /** Zavod launcher-inə qaytar: HOME rolunu sil və zavod ekranını aç */
    fun restoreFactory(c: Context) {
        setAlias(c, false)
        QuickActions.findOtherHomes(c).firstOrNull()?.let { QuickActions.launchHome(c, it) }
    }
}

/** Düymələr üçün: cari vəziyyət + "defolt et" və "zavoda qaytar" əməliyyatları */
class HomeController(
    val isDefault: Boolean,
    val makeDefault: () -> Unit,
    val restoreFactory: () -> Unit,
    val toggle: () -> Unit
)

@Composable
fun rememberHomeController(refreshKey: Any? = null): HomeController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDefault by remember { mutableStateOf(HomeMode.isDefault(context)) }
    LaunchedEffect(refreshKey) { isDefault = HomeMode.isDefault(context) }

    // Sistemin "Defolt əsas ekran" seçim ekranına ehtiyat yol
    val fallback: () -> Unit = {
        val ok = QuickActions.start(context, Intent(Settings.ACTION_HOME_SETTINGS))
        if (!ok) QuickActions.toast(context, "Sistem seçimi açılmadı. Kompüterdən: ${HomeMode.ADB_SET_DEFAULT}")
    }

    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = HomeMode.isDefault(context)
        if (isDefault) QuickActions.toast(context, "Q07 Launcher defolt əsas ekran oldu ✓")
        else fallback()
    }

    val makeDefault: () -> Unit = {
        HomeMode.setAlias(context, true)
        scope.launch {
            delay(400) // paket menecerinin dəyişikliyi qəbul etməsi üçün
            val rm = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
            val launched = rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && runCatching {
                roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
            }.isSuccess
            if (!launched) fallback()
        }
    }
    val restoreFactory: () -> Unit = {
        HomeMode.restoreFactory(context)
        isDefault = false
        QuickActions.toast(context, "Zavod launcher-i defolt əsas ekrandır")
    }

    return HomeController(
        isDefault = isDefault,
        makeDefault = makeDefault,
        restoreFactory = restoreFactory,
        toggle = { if (isDefault) restoreFactory() else makeDefault() }
    )
}
