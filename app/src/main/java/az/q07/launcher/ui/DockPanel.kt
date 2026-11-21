package az.q07.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppEntry
import az.q07.launcher.data.AppRepository
import az.q07.launcher.data.AppSettings
import az.q07.launcher.data.HomeTarget
import az.q07.launcher.data.LauncherPrefs
import az.q07.launcher.data.QuickActions
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val slotNames = listOf("Naviqasiya", "Musiqi", "Video", "Brauzer / AI")
private val slotColors = listOf(CarColors.Green, CarColors.Accent, CarColors.YouTube, CarColors.Purple)

@Composable
fun DockPanel(apps: List<AppEntry>, vertical: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var homeChoices by remember { mutableStateOf<List<HomeTarget>?>(null) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val byKey = remember(apps) { apps.associateBy { it.key } }

    val onFactory: () -> Unit = {
        val targets = QuickActions.findOtherHomes(context)
        when (targets.size) {
            0 -> QuickActions.openHomeSettings(context)
            1 -> QuickActions.launchHome(context, targets.first())
            else -> homeChoices = targets
        }
    }

    @Composable
    fun Slots(itemModifier: (Int) -> Modifier) {
        repeat(LauncherPrefs.DOCK_SLOTS) { i ->
            val app = byKey[LauncherPrefs.dockSlots.getOrElse(i) { "" }]
            DockButton(
                label = app?.let { LauncherPrefs.displayLabel(it.key, it.label) } ?: slotNames[i],
                subtitle = if (app == null) "Toxun və təyin et" else null,
                bitmap = app?.icon,
                vector = if (app == null) Icons.Filled.Add else null,
                tint = slotColors[i],
                modifier = itemModifier(i),
                onLongClick = { pickingSlot = i },
                onClick = { if (app != null) AppRepository.launch(context, app) else pickingSlot = i }
            )
        }
    }

    PanelSurface(modifier) {
        if (vertical) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ClockBlock(big = true)
                Spacer(Modifier.height(4.dp))
                Slots { Modifier.fillMaxWidth().height(68.dp) }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DockButton(
                        label = "", subtitle = null, bitmap = null,
                        vector = Icons.Filled.Settings, tint = CarColors.TextSecondary,
                        modifier = Modifier.height(68.dp),
                        onClick = { settingsOpen = true }
                    )
                    DockButton(
                        label = "Zavod menyusu", subtitle = null, bitmap = null,
                        vector = Icons.Filled.Home, tint = CarColors.Warning,
                        modifier = Modifier.weight(1f).height(68.dp),
                        onLongClick = { QuickActions.openHomeSettings(context) },
                        onClick = onFactory
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClockBlock(big = false)
                Slots { Modifier.weight(1f).fillMaxHeight() }
                DockButton(
                    label = "Ayarlar", subtitle = null, bitmap = null,
                    vector = Icons.Filled.Settings, tint = CarColors.TextSecondary,
                    modifier = Modifier.weight(0.8f).fillMaxHeight(),
                    onClick = { settingsOpen = true }
                )
                DockButton(
                    label = "Zavod", subtitle = null, bitmap = null,
                    vector = Icons.Filled.Home, tint = CarColors.Warning,
                    modifier = Modifier.weight(0.8f).fillMaxHeight(),
                    onLongClick = { QuickActions.openHomeSettings(context) },
                    onClick = onFactory
                )
            }
        }
    }

    if (settingsOpen) SettingsScreen(onClose = { settingsOpen = false })

    pickingSlot?.let { slot ->
        AppPickerDialog(
            title = "${slotNames[slot]} slotu üçün tətbiq seç",
            apps = apps.filterNot { LauncherPrefs.isHidden(it.key) },
            onPick = { LauncherPrefs.setDockSlot(slot, it.component); pickingSlot = null },
            onClear = { LauncherPrefs.setDockSlot(slot, null); pickingSlot = null },
            onDismiss = { pickingSlot = null }
        )
    }

    homeChoices?.let { list ->
        AlertDialog(
            onDismissRequest = { homeChoices = null },
            title = { Text("Hansı əsas ekrana qayıdaq?") },
            text = {
                Column {
                    list.forEach { target ->
                        TextButton(
                            onClick = {
                                homeChoices = null
                                QuickActions.launchHome(context, target)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(target.label, fontSize = 18.sp) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    homeChoices = null
                    QuickActions.openHomeSettings(context)
                }) { Text("Defolt ayarı") }
            },
            dismissButton = {
                TextButton(onClick = { homeChoices = null }) { Text("Bağla") }
            }
        )
    }
}

@Composable
private fun ClockBlock(big: Boolean) {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000L - value % 1000L)
        }
    }
    val locale = remember { Locale.forLanguageTag("az-AZ") }
    val timeFmt = remember { SimpleDateFormat("HH:mm", locale) }
    val secFmt = remember { SimpleDateFormat("ss", locale) }
    val dateFmt = remember { SimpleDateFormat(if (big) "EEEE, d MMMM yyyy" else "EEE, d MMM", locale) }
    val date = Date(now)

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = timeFmt.format(date),
                fontSize = if (big) 64.sp else 44.sp,
                fontWeight = FontWeight.Light,
                color = CarColors.TextPrimary
            )
            if (big && AppSettings.showSeconds) {
                Text(
                    text = ":" + secFmt.format(date),
                    fontSize = 28.sp,
                    color = CarColors.TextSecondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp)
                )
            }
        }
        Text(
            text = dateFmt.format(date).replaceFirstChar { it.titlecase(locale) },
            fontSize = if (big) 18.sp else 14.sp,
            color = CarColors.TextSecondary
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockButton(
    label: String,
    subtitle: String?,
    bitmap: ImageBitmap?,
    vector: ImageVector?,
    tint: Color,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CarColors.PanelAlt)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap, label, Modifier.size(40.dp))
            } else if (vector != null) {
                Icon(vector, label, tint = tint, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                label, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                color = CarColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = CarColors.TextSecondary, maxLines = 1)
            }
        }
    }
}
