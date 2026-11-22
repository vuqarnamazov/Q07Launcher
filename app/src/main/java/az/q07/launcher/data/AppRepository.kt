package az.q07.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: ImageBitmap
) {
    /** Ayarlarda saxlanan unikal açar */
    val key: String get() = component.flattenToString()
}

object AppRepository {

    /** Açıla bilən bütün tətbiqlər (MAIN + LAUNCHER), ada görə sıralanmış */
    suspend fun loadApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .mapNotNull { ri ->
                runCatching {
                    val ai = ri.activityInfo
                    AppEntry(
                        label = ri.loadLabel(pm).toString(),
                        packageName = ai.packageName,
                        component = ComponentName(ai.packageName, ai.name),
                        icon = ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap()
                    )
                }.getOrNull()
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, app: AppEntry, area: WindowLauncher.Area = WindowLauncher.defaultArea()) {
        RecentApps.record(context, app.key)
        if (!WindowLauncher.launch(context, app.component, area)) {
            SelfDiag.log("launch", "açılmadı: ${app.packageName}")
            QuickActions.toast(context, "${app.label} açıla bilmədi")
        }
    }
}

/** Launcher-dən açılan son tətbiqlər (2-ci səhifə üçün) */
object RecentApps {
    private const val MAX = 24
    val keys = androidx.compose.runtime.mutableStateListOf<String>()
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences("q07_recent", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        keys.clear()
        keys.addAll(prefs(context).getString("keys", "").orEmpty().split("|").filter { it.isNotBlank() })
    }

    fun record(context: Context, key: String) {
        load(context)
        keys.remove(key)
        keys.add(0, key)
        while (keys.size > MAX) keys.removeAt(keys.lastIndex)
        prefs(context).edit().putString("keys", keys.joinToString("|")).apply()
    }

    fun remove(context: Context, key: String) {
        load(context)
        keys.remove(key)
        prefs(context).edit().putString("keys", keys.joinToString("|")).apply()
    }
}
