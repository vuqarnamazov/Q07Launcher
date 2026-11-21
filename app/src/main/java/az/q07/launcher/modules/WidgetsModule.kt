package az.q07.launcher.modules

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import az.q07.launcher.data.HostedWidget
import az.q07.launcher.data.QuickActions
import az.q07.launcher.data.ScaledFrame
import az.q07.launcher.data.WidgetStore
import az.q07.launcher.ui.theme.CarColors

/** Zavod vidcetləri: enerji, avtomobil məlumatı, hava, naviqasiya, Bluetooth, musiqi… */
object WidgetsModule : LauncherModule {
    override val id = "system_widgets"
    override val title = "Maşın vidcetləri"
    override val shortTitle = "Vidcet"
    override val icon: ImageVector = Icons.Filled.DirectionsCar
    override val accent: Color = CarColors.Green

    private const val ADB_GRANT = "adb shell appwidget grantbind --package az.q07.launcher --user 0"

    @Composable
    override fun Summary() {
        val context = LocalContext.current
        var picking by remember { mutableStateOf(false) }
        var pendingId by remember { mutableStateOf(-1) }

        val configure = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == Activity.RESULT_OK) WidgetStore.add(pendingId)
            else WidgetStore.host.deleteAppWidgetId(pendingId)
            pendingId = -1
        }

        fun finishAdd(id: Int) {
            val info = WidgetStore.manager.getAppWidgetInfo(id)
            val conf = info?.configure
            if (conf != null) {
                pendingId = id
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(conf)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                try {
                    configure.launch(intent)
                } catch (e: Exception) {
                    WidgetStore.add(id) // konfiqurasiya açılmırsa, birbaşa əlavə et
                    pendingId = -1
                }
            } else {
                WidgetStore.add(id)
            }
        }

        val bind = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            val id = pendingId
            if (res.resultCode == Activity.RESULT_OK) finishAdd(id)
            else {
                WidgetStore.host.deleteAppWidgetId(id)
                pendingId = -1
                QuickActions.toast(context, "İcazə verilmədi. Kartı açıb ADB əmrinə bax.")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WidgetStore.widgets.forEach { w -> HostedWidgetView(w) }

            OutlinedButton(onClick = { picking = true }) {
                Icon(Icons.Filled.Add, null)
                Text(" Vidcet əlavə et")
            }
        }

        if (picking) {
            ProviderPicker(
                onDismiss = { picking = false },
                onPick = { provider ->
                    picking = false
                    val id = WidgetStore.host.allocateAppWidgetId()
                    if (WidgetStore.manager.bindAppWidgetIdIfAllowed(id, provider.provider)) {
                        finishAdd(id)
                    } else {
                        pendingId = id
                        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                        try {
                            bind.launch(intent)
                        } catch (e: Exception) {
                            WidgetStore.host.deleteAppWidgetId(id)
                            pendingId = -1
                            QuickActions.toast(context, "Sistem icazə pəncərəsi yoxdur — ADB əmrini işlət")
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun HostedWidgetView(w: HostedWidget) {
        val context = LocalContext.current
        val info = remember(w.id) { WidgetStore.manager.getAppWidgetInfo(w.id) } ?: return
        // Launcher UI-nin miqyası (2.5K ekranda ≈ 2.0)
        val scale = LocalDensity.current.density / context.resources.displayMetrics.density

        AndroidView(
            factory = { ctx ->
                ScaledFrame(ctx).apply {
                    this.scale = scale
                    val hv = WidgetStore.host.createView(ctx.applicationContext, w.id, info)
                    hv.setAppWidget(w.id, info)
                    addView(hv)
                }
            },
            update = { it.scale = scale },
            modifier = Modifier
                .fillMaxWidth()
                .height(w.heightDp.dp)
                .clip(RoundedCornerShape(16.dp))
        )
    }

    @Composable
    private fun ProviderPicker(onDismiss: () -> Unit, onPick: (AppWidgetProviderInfo) -> Unit) {
        val context = LocalContext.current
        val pm = context.packageManager
        val providers = remember {
            WidgetStore.manager.installedProviders
                .map { it to (it.loadLabel(pm) ?: it.provider.className) }
                .sortedBy { it.first.provider.packageName + it.second }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Vidcet seç (${providers.size})") },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(providers, key = { it.first.provider.flattenToString() }) { (p, label) ->
                        val appLabel = runCatching {
                            pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString()
                        }.getOrDefault(p.provider.packageName)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(p) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(label, fontSize = 18.sp, color = CarColors.TextPrimary)
                            Text(
                                "$appLabel · ${p.minWidth}×${p.minHeight}",
                                fontSize = 13.sp, color = CarColors.TextSecondary
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Bağla") } }
        )
    }

    @Composable
    override fun Expanded() {
        val context = LocalContext.current
        val pm = context.packageManager
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (WidgetStore.widgets.isEmpty()) {
                Text("Hələ vidcet yoxdur.", fontSize = 14.sp, color = CarColors.TextSecondary)
            }
            WidgetStore.widgets.forEachIndexed { index, w ->
                val label = remember(w.id) {
                    WidgetStore.manager.getAppWidgetInfo(w.id)?.loadLabel(pm) ?: "Vidcet ${w.id}"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CarColors.Panel)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 15.sp, color = CarColors.TextPrimary)
                        Text("Hündürlük: ${w.heightDp}", fontSize = 12.sp, color = CarColors.TextSecondary)
                    }
                    TextButton(onClick = { WidgetStore.resize(w.id, -20) }) { Text("−") }
                    TextButton(onClick = { WidgetStore.resize(w.id, 20) }) { Text("+") }
                    IconButton(onClick = { WidgetStore.move(w.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Yuxarı")
                    }
                    IconButton(
                        onClick = { WidgetStore.move(w.id, 1) },
                        enabled = index < WidgetStore.widgets.lastIndex
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Aşağı")
                    }
                    IconButton(onClick = { WidgetStore.remove(w.id) }) {
                        Icon(Icons.Filled.Delete, "Sil", tint = CarColors.Danger)
                    }
                }
            }
            Text(
                "İcazə pəncərəsi çıxmasa, kompüterdən bir dəfə işlət:",
                fontSize = 13.sp, color = CarColors.TextSecondary
            )
            Text(ADB_GRANT, fontSize = 13.sp, color = CarColors.Warning)
        }
    }
}
