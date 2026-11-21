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
        if (!WindowLauncher.launch(context, app.component, area)) {
            QuickActions.toast(context, "${app.label} açıla bilmədi")
        }
    }
}
