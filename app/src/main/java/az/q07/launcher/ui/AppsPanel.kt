package az.q07.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppEntry
import az.q07.launcher.data.AppRepository
import az.q07.launcher.data.LauncherPrefs
import az.q07.launcher.data.QuickActions
import az.q07.launcher.ui.theme.CarColors

@Composable
fun AppsPanel(apps: List<AppEntry>?, columns: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }

    PanelSurface(modifier) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Apps, null, tint = CarColors.Accent, modifier = Modifier.size(28.dp))
                Text(
                    "  Tətbiqlər",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CarColors.TextPrimary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${apps?.count { !LauncherPrefs.isHidden(it.key) } ?: 0}",
                    fontSize = 16.sp, color = CarColors.TextSecondary
                )
                IconButton(onClick = { showHidden = !showHidden }) {
                    Icon(
                        if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = "Gizlədilənləri göstər",
                        tint = if (showHidden) CarColors.Warning else CarColors.TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Axtar…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (apps == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val q = query.trim()
                val filtered = apps
                    .filter { showHidden || !LauncherPrefs.isHidden(it.key) }
                    .filter {
                        q.isEmpty() ||
                            LauncherPrefs.displayLabel(it.key, it.label).contains(q, ignoreCase = true) ||
                            it.label.contains(q, ignoreCase = true)
                    }
                    .sortedBy { LauncherPrefs.displayLabel(it.key, it.label).lowercase() }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.key }) { app ->
                        AppTile(
                            app = app,
                            dimmed = LauncherPrefs.isHidden(app.key),
                            onClick = { AppRepository.launch(context, app) },
                            onLongClick = { menuFor = app }
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { app -> AppActionsDialog(app) { menuFor = null } }
}

/** Uzun basma menyusu: ad dəyiş, gizlət, dock-a əlavə et, tətbiq məlumatı */
@Composable
private fun AppActionsDialog(app: AppEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(LauncherPrefs.displayLabel(app.key, app.label)) }
    val hidden = LauncherPrefs.isHidden(app.key)

    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Image(app.icon, null, Modifier.size(56.dp)) },
        title = { Text(LauncherPrefs.displayLabel(app.key, app.label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (renaming) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Yeni ad (orijinal: ${app.label})") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row {
                        TextButton(onClick = {
                            LauncherPrefs.rename(app.key, newName); onDismiss()
                        }) { Text("Yadda saxla") }
                        TextButton(onClick = {
                            LauncherPrefs.rename(app.key, null); onDismiss()
                        }) { Text("Orijinal ad") }
                    }
                } else {
                    // Çoxpəncərə: tətbiqi ayrı pəncərədə aç (bir neçə tətbiq eyni anda)
                    listOf(
                        az.q07.launcher.data.WindowLauncher.Area.CENTER,
                        az.q07.launcher.data.WindowLauncher.Area.LEFT_HALF,
                        az.q07.launcher.data.WindowLauncher.Area.RIGHT_HALF,
                        az.q07.launcher.data.WindowLauncher.Area.BESIDE_DOCK
                    ).forEach { area ->
                        MenuItem("🗗 ${area.title} aç") {
                            AppRepository.launch(context, app, area); onDismiss()
                        }
                    }
                    MenuItem("Adını dəyiş") { renaming = true }
                    MenuItem(if (hidden) "Siyahıda göstər" else "Siyahıdan gizlət") {
                        LauncherPrefs.setHidden(app.key, !hidden); onDismiss()
                    }
                    repeat(LauncherPrefs.DOCK_SLOTS) { i ->
                        MenuItem("Dock-da ${i + 1}-ci slota qoy") {
                            LauncherPrefs.setDockSlot(i, app.component); onDismiss()
                        }
                    }
                    MenuItem("Tətbiq məlumatı") {
                        QuickActions.openAppInfo(context, app.packageName); onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Bağla") } }
    )
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 17.sp, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppEntry, dimmed: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.4f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = LauncherPrefs.displayLabel(app.key, app.label),
            fontSize = 14.sp,
            color = CarColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
