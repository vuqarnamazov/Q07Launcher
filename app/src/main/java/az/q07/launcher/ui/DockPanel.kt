package az.q07.launcher.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppEntry
import az.q07.launcher.data.AppRepository
import az.q07.launcher.data.AppSettings
import az.q07.launcher.data.ForegroundApp
import az.q07.launcher.data.HomeTarget
import az.q07.launcher.data.LauncherPrefs
import az.q07.launcher.data.MediaRepo
import az.q07.launcher.data.QuickActions
import az.q07.launcher.data.WeatherRepo
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "Hər slot öz rəngi" rejimi üçün neon rənglər */
private val slotGlows = listOf(
    Color(0xFF00E676), Color(0xFFB388FF), Color(0xFFFF5252),
    Color(0xFF40C4FF), Color(0xFFFFAB40), Color(0xFF00E5FF)
)

/** Gecə (sistem gecə rejimi və ya 20:00–07:00) — neon işıq zəiflədilir */
@Composable
private fun rememberGlowIntensity(): Float {
    val night = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val hour by produceState(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        while (true) { delay(60_000); value = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    }
    val isNight = night || hour >= 20 || hour < 7
    return if (AppSettings.nightDim && isNight) 0.55f else 1f
}

@Composable
fun DockPanel(apps: List<AppEntry>, vertical: Boolean, resumeTick: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var homeChoices by remember { mutableStateOf<List<HomeTarget>?>(null) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val byKey = remember(apps) { apps.associateBy { it.key } }

    // Media (çalan mahnı) — tək paylaşılan mənbə
    DisposableEffect(Unit) {
        MediaRepo.acquire(context)
        onDispose { MediaRepo.release() }
    }
    val np by MediaRepo.state.collectAsState()

    val onFactory: () -> Unit = {
        val targets = QuickActions.findOtherHomes(context)
        when (targets.size) {
            0 -> QuickActions.openHomeSettings(context)
            1 -> QuickActions.launchHome(context, targets.first())
            else -> homeChoices = targets
        }
    }

    // Neon menyu: seçilmiş slot (toxunan kimi aktiv olur və seçili qalır)
    var selectedSlot by rememberSaveable { mutableIntStateOf(0) }
    val count = AppSettings.dockSlotCount.coerceIn(3, LauncherPrefs.DOCK_SLOTS)
    val slotApps = List(count) { i -> byKey[LauncherPrefs.dockSlots.getOrElse(i) { "" }] }

    // Launcher-ə qayıdanda son açılan tətbiqin slotunu işıqlandır
    LaunchedEffect(resumeTick, slotApps.map { it?.packageName }) {
        val pkg = withContext(Dispatchers.IO) { ForegroundApp.lastPackage(context) } ?: return@LaunchedEffect
        val idx = slotApps.indexOfFirst { it?.packageName == pkg }
        if (idx >= 0) selectedSlot = idx
    }

    val glowMode = AppSettings.glowMode
    val baseGlow = if (glowMode == "accent") CarColors.Accent else DefaultGlow
    val slotItems = slotApps.mapIndexed { i, app ->
        val isPlayingHere = app != null && np.active && np.packageName == app.packageName
        CarMenuItem(
            title = app?.let { LauncherPrefs.displayLabel(it.key, it.label) } ?: LauncherPrefs.slotNames[i],
            icon = if (app == null) Icons.Filled.Add else null,
            bitmap = app?.icon,
            subtitle = when {
                app == null -> "Toxun və təyin et"
                isPlayingHere && np.title.isNotBlank() -> (if (np.playing) "▶ " else "❚❚ ") + np.title
                else -> null
            },
            glow = if (glowMode == "slot") slotGlows[i % slotGlows.size] else null,
            playing = isPlayingHere && np.playing,
            hasDoubleTap = app != null && (
                app.packageName == "com.waze" ||                       // iki toxunuş → Evə marşrut
                    (np.controller != null && (isPlayingHere || i == 1)) // iki toxunuş → oynat/pauza
                )
        )
    }
    val onSlotSelected: (Int) -> Unit = { i ->
        selectedSlot = i
        val app = slotApps.getOrNull(i)
        if (app != null) AppRepository.launch(context, app) else pickingSlot = i
    }
    val onSlotDouble: (Int) -> Unit = { i ->
        selectedSlot = i
        val app = slotApps.getOrNull(i)
        if (app?.packageName == "com.waze") {
            val ok = QuickActions.start(context, Intent(Intent.ACTION_VIEW, Uri.parse("waze://?favorite=home&navigate=yes")))
            if (!ok) AppRepository.launch(context, app)
        } else {
            MediaRepo.togglePlay()
        }
    }
    val onSlotLongClick: (Int) -> Unit = { i -> pickingSlot = i }
    val intensity = rememberGlowIntensity()

    PanelSurface(modifier) {
        if (vertical) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ClockBlock(big = true)
                GlowingFlowNavigationColumn(
                    items = slotItems,
                    selectedIndex = selectedSlot,
                    onItemSelected = onSlotSelected,
                    onItemLongClick = onSlotLongClick,
                    onItemDoubleClick = onSlotDouble,
                    glowColor = baseGlow,
                    intensity = intensity,
                    itemHeight = when (count) { 3, 4 -> 68.dp; 5 -> 58.dp; else -> 50.dp },
                    spacing = if (count >= 6) 6.dp else 8.dp
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DockButton(
                        label = "", subtitle = null, bitmap = null,
                        vector = Icons.Filled.Settings, tint = CarColors.TextSecondary,
                        modifier = Modifier.height(64.dp),
                        onClick = { settingsOpen = true }
                    )
                    DockButton(
                        label = "Zavod menyusu", subtitle = null, bitmap = null,
                        vector = Icons.Filled.Home, tint = CarColors.Warning,
                        modifier = Modifier.weight(1f).height(64.dp),
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
                GlowingFlowNavigationBar(
                    items = slotItems,
                    selectedIndex = selectedSlot,
                    onItemSelected = onSlotSelected,
                    onItemLongClick = onSlotLongClick,
                    onItemDoubleClick = onSlotDouble,
                    glowColor = baseGlow,
                    intensity = intensity,
                    modifier = Modifier.weight(4f)
                )
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
            title = "${LauncherPrefs.slotNames[slot]} slotu üçün tətbiq seç",
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
    val context = LocalContext.current
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            // Saniyə göstərilmirsə dəqiqədə bir yenilə — daha az iş
            delay(if (AppSettings.showSeconds && big) 1000L - value % 1000L else 60_000L - value % 60_000L)
        }
    }
    // Saatın altında cari temperatur (paylaşılan hava mənbəyi)
    DisposableEffect(Unit) {
        WeatherRepo.acquire(context)
        onDispose { WeatherRepo.release() }
    }
    val weather by WeatherRepo.state.collectAsState()

    val locale = remember { Locale.forLanguageTag("az-AZ") }
    val timeFmt = remember { SimpleDateFormat("HH:mm", locale) }
    val secFmt = remember { SimpleDateFormat("ss", locale) }
    val dateFmt = remember(big) { SimpleDateFormat(if (big) "EEEE, d MMMM" else "EEE, d MMM", locale) }
    val date = Date(now)

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = timeFmt.format(date),
                fontSize = if (big) 60.sp else 44.sp,
                fontWeight = FontWeight.Light,
                color = CarColors.TextPrimary
            )
            if (big && AppSettings.showSeconds) {
                Text(
                    text = ":" + secFmt.format(date),
                    fontSize = 26.sp,
                    color = CarColors.TextSecondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                )
            }
        }
        val temp = if (weather.ok) {
            "  ·  " + WeatherRepo.describe(weather.code).first + " " + Math.round(weather.temp) + "°C"
        } else ""
        Text(
            text = dateFmt.format(date).replaceFirstChar { it.titlecase(locale) } + temp,
            fontSize = if (big) 17.sp else 14.sp,
            color = CarColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
