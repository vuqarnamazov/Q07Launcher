package az.q07.launcher.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import az.q07.launcher.BuildConfig
import az.q07.launcher.data.AdbControl
import az.q07.launcher.data.AppSettings
import az.q07.launcher.data.ForegroundApp
import az.q07.launcher.data.HomeMode
import az.q07.launcher.data.WeatherRepo
import az.q07.launcher.data.LauncherPrefs
import az.q07.launcher.data.ObdMonitor
import az.q07.launcher.data.QuickActions
import az.q07.launcher.modules.ModuleRegistry
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private enum class Section(val title: String, val icon: ImageVector) {
    Look("Görünüş", Icons.Filled.Palette),
    Dock("Dock / Neon menyu", Icons.Filled.Tune),
    Home("Əsas ekran", Icons.Filled.Home),
    Modules("Modullar", Icons.Filled.ViewModule),
    Car("Maşın / OBD", Icons.Filled.DirectionsCar),
    Net("Şəbəkə: Telefon + OBD", Icons.Filled.Wifi),
    Weather("Hava", Icons.Filled.WbSunny),
    Perms("İcazələr", Icons.Filled.Security),
    Diag("ADB / Diaqnostika", Icons.Filled.Build),
    Update("Yeniləmə", Icons.Filled.Refresh),
    About("Haqqında", Icons.Filled.Info)
}

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    var section by remember { mutableStateOf(Section.Look) }

    ScaledDialog(onDismissRequest = onClose) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CarColors.Background)
                .border(1.dp, CarColors.Border, RoundedCornerShape(24.dp))
        ) {
            // Sol menyu
            Column(
                Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(CarColors.Panel)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ayarlar", fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Bağla", tint = CarColors.TextPrimary) }
                }
                Spacer(Modifier.height(8.dp))
                GlowingFlowNavigationColumn(
                    items = Section.entries.map { CarMenuItem(title = it.title, icon = it.icon) },
                    selectedIndex = section.ordinal,
                    onItemSelected = { section = Section.entries[it] },
                    itemHeight = 50.dp,
                    spacing = 6.dp
                )
            }
            // Məzmun
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(section.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
                when (section) {
                    Section.Look -> LookSection()
                    Section.Dock -> DockSection()
                    Section.Home -> HomeSection()
                    Section.Modules -> ModulesSection()
                    Section.Car -> CarSection()
                    Section.Net -> {
                        Label(
                            "İnternet telefondan, OBD lokal Wi-Fi-dan",
                            "OBD socket-i yalnız OBD şəbəkəsinə bağlanır — launcher və sistem interneti telefonun hotspot-u ilə işləyir."
                        )
                        NetworkSetupForm()
                    }
                    Section.Weather -> WeatherSection()
                    Section.Perms -> PermsSection()
                    Section.Diag -> AdbSection()
                    Section.Update -> UpdateSection()
                    Section.About -> AboutSection()
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, hint: String? = null) {
    Column {
        Text(text, fontSize = 18.sp, color = CarColors.TextPrimary)
        if (hint != null) Text(hint, fontSize = 14.sp, color = CarColors.TextSecondary)
    }
}

@Composable
private fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sel) CarColors.Accent else CarColors.PanelAlt)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(o, fontSize = 16.sp, color = if (sel) androidx.compose.ui.graphics.Color.Black else CarColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Label(text, hint) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LookSection() {
    // ---- 3D maşın: nömrə ----
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { az.q07.launcher.data.CarLook.load(ctx) }
    val savedPlate by az.q07.launcher.data.CarLook.plate.collectAsState()
    var plateText by remember(savedPlate) { mutableStateOf(savedPlate) }
    Label("Maşının nömrəsi", "3D maşının ön və arxa nömrə lövhəsində görünür (məs. 10-AB-123)")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = plateText, onValueChange = { plateText = it.uppercase().take(12) },
            singleLine = true, modifier = Modifier.width(320.dp)
        )
        OutlinedButton(onClick = { az.q07.launcher.data.CarLook.setPlate(ctx, plateText) }) { Text("Yadda saxla") }
    }
    Spacer(Modifier.height(16.dp))
    Label("Mövzu", "Launcher-in bütün rəngləri dəyişir")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        az.q07.launcher.ui.theme.LauncherTheme.entries.forEach { t ->
            val c = az.q07.launcher.ui.theme.getThemeColors(t)
            val sel = AppSettings.themeIndex == t.ordinal
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(width = 96.dp, height = 60.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(c.background)
                        .border(if (sel) 3.dp else 1.dp, if (sel) CarColors.Accent else c.border,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { AppSettings.update { themeIndex = t.ordinal } },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Aa", fontSize = 20.sp, color = c.textPrimary)
                }
                Text(t.title, fontSize = 13.sp, color = if (sel) CarColors.TextPrimary else CarColors.TextSecondary)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Label("Vurğu rəngi", "Orijinal mövzuda istifadə olunur")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSettings.accents.forEachIndexed { i, (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            if (i == AppSettings.accentIndex) 4.dp else 0.dp,
                            CarColors.TextPrimary, CircleShape
                        )
                        .clickable { AppSettings.update { accentIndex = i } }
                )
                Text(name, fontSize = 13.sp, color = CarColors.TextSecondary)
            }
        }
    }
    SwitchRow("Saatda saniyələr", null, AppSettings.showSeconds) { v -> AppSettings.update { showSeconds = v } }

    Label("İnterfeys ölçüsü", "Avtomatik miqyas 2560×1600 ekran üçün; buradan bir az böyüt/kiçilt")
    val scales = listOf(85, 90, 100, 110, 120)
    Chips(scales.map { "$it%" }, scales.indexOf(AppSettings.uiScalePercent)) { i ->
        AppSettings.update { uiScalePercent = scales[i] }
    }

    Label("Tətbiq şəbəkəsi — sütun sayı")
    val cols = listOf(3, 4, 5, 6)
    Chips(cols.map { "$it" }, cols.indexOf(AppSettings.gridColumns)) { i ->
        AppSettings.update { gridColumns = cols[i] }
    }
}

@Composable
private fun DockSection() {
    val context = LocalContext.current
    Label("Slot sayı")
    val counts = listOf(3, 4, 5, 6)
    Chips(counts.map { "$it" }, counts.indexOf(AppSettings.dockSlotCount)) { i ->
        AppSettings.update { dockSlotCount = counts[i] }
    }

    Label("Slotların sırası", "▲▼ ilə yerini dəyiş. Uzun basaraq dock-da başqa tətbiq seçmək olar.")
    val pm = context.packageManager
    repeat(AppSettings.dockSlotCount) { i ->
        val comp = LauncherPrefs.dockSlots.getOrElse(i) { "" }
        val appName = remember(comp) {
            android.content.ComponentName.unflattenFromString(comp)?.let { c ->
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull()
            } ?: "— boş —"
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CarColors.Panel)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${i + 1}.", fontSize = 17.sp, color = CarColors.TextSecondary)
            Spacer(Modifier.width(10.dp))
            Text(appName, fontSize = 17.sp, color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { LauncherPrefs.moveSlot(i, -1) }, enabled = i > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, "Yuxarı")
            }
            IconButton(onClick = { LauncherPrefs.moveSlot(i, 1) }, enabled = i < AppSettings.dockSlotCount - 1) {
                Icon(Icons.Filled.KeyboardArrowDown, "Aşağı")
            }
        }
    }

    Label("Neon işığın rəngi")
    val modes = listOf("neon" to "Neon mavi", "accent" to "Vurğu rəngi", "slot" to "Hər slot öz rəngi")
    Chips(modes.map { it.second }, modes.indexOfFirst { it.first == AppSettings.glowMode }) { i ->
        AppSettings.update { glowMode = modes[i].first }
    }
    SwitchRow("Gecə işığı zəiflət", "20:00–07:00 və ya sistem gecə rejimində neon işıq yumşalır", AppSettings.nightDim) { v ->
        AppSettings.update { nightDim = v }
    }
    SwitchRow("Toxunuş səsi və vibrasiya", null, AppSettings.haptics) { v -> AppSettings.update { haptics = v } }
    SwitchRow(
        "Orta panel: neon tablar",
        "Söndürsən, bütün kartlar alt-alta göstərilir (daha çox resurs işlədir)",
        AppSettings.middleTabs
    ) { v -> AppSettings.update { middleTabs = v } }

    val usage = ForegroundApp.hasAccess(context)
    Label(
        "Son açılan tətbiqin slotunu işıqlandır",
        if (usage) "✓ İcazə var" else "✗ İcazə yoxdur — kompüterdən bir dəfə:"
    )
    if (!usage) Text("adb shell appops set az.q07.launcher GET_USAGE_STATS allow", fontSize = 14.sp, color = CarColors.Warning)
    Text(
        "İki dəfə toxunma: Waze slotu → Evə marşrut, musiqi slotu → oynat/pauza. Barmağı menyu boyunca sürüşdürüb buraxmaqla da seçmək olar.",
        fontSize = 14.sp, color = CarColors.TextSecondary
    )
}

@Composable
private fun HomeSection() {
    val home = az.q07.launcher.data.rememberHomeController()
    Label(
        if (home.isDefault) "Hazırda defolt: Q07 Launcher ✓" else "Hazırda defolt: zavod launcher-i",
        "Home düyməsi hansı ekranı açsın — istədiyin vaxt dəyiş. Eyni düymə dock-un aşağısında da var."
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = home.makeDefault, enabled = !home.isDefault) { Text("Q07 Launcher-i defolt et") }
        OutlinedButton(onClick = home.restoreFactory, enabled = home.isDefault) { Text("Zavod launcher-inə qaytar") }
    }
    Text(
        "«Defolt et» basanda sistem soruşur — təsdiqlə. Pəncərə çıxmasa, sistemin əsas ekran ayarı açılır.",
        fontSize = 14.sp, color = CarColors.TextSecondary
    )
    Label("Kompüterdən (ehtiyat)")
    Text(HomeMode.ADB_SET_DEFAULT, fontSize = 14.sp, color = CarColors.Warning)
    Text(HomeMode.ADB_RESTORE, fontSize = 14.sp, color = CarColors.Warning)
}

@Composable
private fun ModulesSection() {
    Label("Orta paneldəki kartlar", "Açıb-söndür və ▲▼ ilə sıranı dəyiş")
    val list = ModuleRegistry.allOrdered()
    list.forEachIndexed { index, m ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CarColors.Panel)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(m.icon, null, tint = m.accent)
            Spacer(Modifier.width(12.dp))
            Text(m.title, fontSize = 17.sp, color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { move(list.map { it.id }, index, -1) }, enabled = index > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, "Yuxarı")
            }
            IconButton(onClick = { move(list.map { it.id }, index, 1) }, enabled = index < list.lastIndex) {
                Icon(Icons.Filled.KeyboardArrowDown, "Aşağı")
            }
            Switch(
                checked = m.id !in AppSettings.disabledModules,
                onCheckedChange = { on ->
                    AppSettings.update {
                        if (on) disabledModules.remove(m.id) else if (m.id !in disabledModules) disabledModules.add(m.id)
                    }
                }
            )
        }
    }
    OutlinedButton(onClick = {
        AppSettings.update { moduleOrder.clear(); disabledModules.clear() }
    }) { Text("Standart sıraya qaytar") }

    Label("Dock", "Dock slotlarını sıfırla — maşındakı tətbiqlərdən yenidən seçilir")
    val context = LocalContext.current
    OutlinedButton(onClick = {
        repeat(LauncherPrefs.DOCK_SLOTS) { LauncherPrefs.setDockSlot(it, null) }
        QuickActions.toast(context, "Slotlar boşaldıldı — uzun basıb tətbiq seç")
    }) { Text("Dock slotlarını boşalt") }
}

private fun move(ids: List<String>, index: Int, delta: Int) {
    val m = ids.toMutableList()
    val j = index + delta
    if (j !in m.indices) return
    val x = m.removeAt(index)
    m.add(j, x)
    AppSettings.update { moduleOrder.clear(); moduleOrder.addAll(m) }
}

@SuppressLint("MissingPermission")
@Composable
private fun CarSection() {
    Label("OBD adapteri (ELM327)", "Q07-OBD tətbiqindəki kimi: temperaturlar, hibrid batareya, gərginlik, sürət")
    val modes = listOf("off" to "Söndürülüb", "wifi" to "Wi-Fi", "bt" to "Bluetooth")
    Chips(modes.map { it.second }, modes.indexOfFirst { it.first == AppSettings.obdMode }) { i ->
        AppSettings.update { obdMode = modes[i].first }
        ObdMonitor.restart()
    }

    when (AppSettings.obdMode) {
        "wifi" -> {
            var host by remember { mutableStateOf(AppSettings.obdHost) }
            var port by remember { mutableStateOf(AppSettings.obdPort.toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("IP ünvanı") }, singleLine = true,
                    modifier = Modifier.weight(2f))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = {
                AppSettings.update {
                    obdHost = host.trim()
                    obdPort = port.toIntOrNull() ?: 35000
                }
                ObdMonitor.restart()
            }) { Text("Yadda saxla və qoşul") }
            Text(
                "Head unit-i adapterin Wi-Fi şəbəkəsinə qoş (adətən «WiFi_OBDII»). İnternet SIM ilə qalır.",
                fontSize = 14.sp, color = CarColors.TextSecondary
            )
        }
        "bt" -> {
            val devices = remember {
                runCatching { BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() }.getOrNull().orEmpty()
            }
            Text(
                if (AppSettings.obdBtName.isNotBlank()) "Seçilib: ${AppSettings.obdBtName}" else "Adapter seçilməyib",
                fontSize = 16.sp, color = CarColors.TextPrimary
            )
            if (devices.isEmpty()) {
                Text("Qoşulmuş (pair) cihaz yoxdur — əvvəlcə maşının Bluetooth ayarlarında adapteri qoş (PIN adətən 1234).",
                    fontSize = 14.sp, color = CarColors.TextSecondary)
            }
            devices.forEach { d ->
                val name = runCatching { d.name }.getOrNull() ?: d.address
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (d.address == AppSettings.obdBtMac) CarColors.PanelAlt else CarColors.Panel)
                        .clickable {
                            AppSettings.update { obdBtMac = d.address; obdBtName = name }
                            ObdMonitor.restart()
                        }
                        .padding(14.dp)
                ) {
                    Text(name, fontSize = 17.sp, color = CarColors.TextPrimary, modifier = Modifier.weight(1f))
                    Text(d.address, fontSize = 14.sp, color = CarColors.TextSecondary)
                }
            }
        }
    }

    Label("Yenilənmə sürəti (sürət/dövr/pedal hər keçiddə, qalanları növbə ilə)")
    val intervals = listOf(0, 50, 300, 1000)
    val names = listOf("Maksimum", "Sürətli", "Normal", "Qənaətli")
    Chips(names, intervals.indexOf(AppSettings.obdIntervalMs)) { i ->
        AppSettings.update { obdIntervalMs = intervals[i] }
    }

    Label("OBD Skan Yaddaşı (OBD-siz Rejim)", "OBD qoşulu ikən öyrənilən ekran/xassə yolları — adapter olmadan da oxunur")
    ObdLearnedMemoryCard()
}

@Composable
private fun WeatherSection() {
    val context = LocalContext.current
    Label("Şəhər", "Hava kartı bu şəhərin proqnozunu göstərir (Open-Meteo, internet lazımdır)")
    AppSettings.cities.chunked(5).forEach { row ->
        Chips(row.map { it.name }, row.indexOf(AppSettings.city)) { i ->
            AppSettings.update { cityIndex = AppSettings.cities.indexOf(row[i]) }
            WeatherRepo.cityChanged(context)
        }
    }
}

@Composable
private fun PermsSection() {
    val context = LocalContext.current
    val mediaOk = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    Label("Media («İndi çalır»)", if (mediaOk) "✓ İcazə var" else "✗ İcazə yoxdur")
    Label("Kompüterdən bir dəfəlik ADB əmrləri:")
    listOf(
        "adb shell cmd notification allow_listener az.q07.launcher/az.q07.launcher.media.MediaListenerService",
        "adb shell appwidget grantbind --package az.q07.launcher --user 0",
        "adb shell pm grant az.q07.launcher android.permission.RECORD_AUDIO",
        "adb shell pm grant az.q07.launcher android.permission.ACCESS_FINE_LOCATION",
        "adb shell pm grant az.q07.launcher android.car.permission.CAR_SPEED",
        "adb shell appops set az.q07.launcher GET_USAGE_STATS allow"
    ).forEach { Text(it, fontSize = 14.sp, color = CarColors.Warning) }
    OutlinedButton(onClick = {
        QuickActions.start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }) { Text("Tətbiq icazələrini aç") }
    OutlinedButton(onClick = { QuickActions.openHomeSettings(context) }) { Text("Defolt əsas ekran ayarı") }
}

@Composable
private fun AdbSection() {
    val context = LocalContext.current
    var adb by remember { mutableStateOf(AdbControl.isEnabled(context)) }
    var always by remember { mutableStateOf(AdbControl.isAlways(context)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val ips = remember { AdbControl.ipv4Addresses() }

    // «Həmişə aktiv» rejimi ADB-ni arxa planda yandıra bilir — vəziyyəti yeniləyirik
    LaunchedEffect(Unit) {
        while (true) {
            adb = AdbControl.isEnabled(context)
            always = AdbControl.isAlways(context)
            kotlinx.coroutines.delay(3000)
        }
    }

    Label(
        if (adb) "ADB: aktiv ✓" else "ADB: söndürülüb",
        "Kompüterdən launcher-i yeniləmək, jurnal və ekran görüntüsü almaq üçün. USB kabeli qoşulu olmalıdır."
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = {
            val target = !adb
            val ok = AdbControl.setEnabled(context, target)
            adb = AdbControl.isEnabled(context)
            msg = when {
                ok -> if (target) "ADB açıldı ✓ — kompüterdə: adb devices" else "ADB söndürüldü"
                target -> "Sistem avtomatik icazə vermədi — «Developer seçimləri» düyməsi ilə ekranı aç və USB debugging-i işə sal."
                else -> "Sistem avtomatik icazə vermədi — «Developer seçimləri»ndən əl ilə söndür."
            }
        }) { Text(if (adb) "ADB-ni söndür" else "ADB-ni aktiv et") }
        OutlinedButton(onClick = {
            if (!AdbControl.openDeveloperSettings(context)) {
                msg = "Developer seçimləri açılmadı — sistem ayarlarından tap."
            }
        }) { Text("Developer seçimləri") }
    }
    SwitchRow(
        "Həmişə aktiv",
        "Söndürülsə də avtomatik yenidən yandırılır — launcher hər açılışda yoxlayır (reboot-dan sonra da).",
        always
    ) { on ->
        always = on
        AdbControl.setAlways(context, on)
        adb = AdbControl.isEnabled(context)
        msg = if (on) "ADB həmişə aktiv ✓" else "Avtomatik aktiv söndürüldü"
    }
    msg?.let { Text(it, fontSize = 15.sp, color = CarColors.Accent) }

    Label(
        "Şəbəkə üzərindən (root tələb edir)",
        if (ips.isEmpty()) "Wi-Fi ünvanı tapılmadı — maşın şəbəkəyə qoşulmalıdır"
        else "Kompüter maşının Wi-Fi şəbəkəsində olsun, sonra:"
    )
    ips.forEach { Text("adb connect $it:${AdbControl.PORT}", fontSize = 14.sp, color = CarColors.Warning) }

    Label("Kompüterdən (əl ilə)", "Düymə icazə ala bilməsə, kompüterdən bir dəfə bunları işə sal:")
    Text("adb shell settings put global adb_enabled 1", fontSize = 14.sp, color = CarColors.Warning)
    Text("adb tcpip ${AdbControl.PORT}", fontSize = 14.sp, color = CarColors.Warning)
    if (!AdbControl.hasSecureWrite(context)) {
        Text(
            "Qeyd: WRITE_SECURE_SETTINGS icazəsi yoxdur — düymə sistemi məcbur edə bilmir, Developer seçimlərini açır.",
            fontSize = 13.sp, color = CarColors.TextSecondary
        )
    }
}

/**
 * Yeniləmə: serverdəki (GitHub Releases) ən son buraxılışı yoxlayır,
 * varsa APK-nı endirib quraşdırıcını açır.
 */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<az.q07.launcher.data.OtaUpdateInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Label(
        "Q07 Launcher ${BuildConfig.VERSION_NAME}",
        "Versiya kodu ${BuildConfig.VERSION_CODE} · server: github.com/vuqarnamazov/Q07Launcher/releases"
    )

    OutlinedButton(enabled = !checking, onClick = {
        checking = true
        info = null
        message = null
        scope.launch {
            val found = az.q07.launcher.data.GitHubLicenseAndOtaManager
                .checkForOtaUpdate(BuildConfig.VERSION_CODE)
            checking = false
            info = found
            if (found == null) message = "Ən son versiyadır ✓"
        }
    }) { Text(if (checking) "Yoxlanılır…" else "Yeniləməni yoxla") }

    info?.let { u ->
        Label("Yeni versiya var: ${u.latestVersionName}", "Bu yeniliklər gəlir:")
        val items = u.changelogItems.ifEmpty { u.changelog.split(" · ").filter { it.isNotBlank() } }
        items.forEach { Text("• $it", fontSize = 15.sp, color = CarColors.TextPrimary) }
        OutlinedButton(onClick = {
            az.q07.launcher.data.GitHubLicenseAndOtaManager
                .downloadAndInstallApk(context, u.apkUrl)
        }) { Text("Endir və quraşdır") }
    }

    message?.let { Text(it, fontSize = 16.sp, color = CarColors.Green) }
    Label(
        "Necə işləyir",
        "Yeni APK GitHub-da «Releases» bölməsinə tag «build-<nömrə>» ilə yüklənir. " +
            "Bütün maşınlar açılışda avtomatik yoxlayır; bu düymə ilə də əl ilə yoxlamaq olar."
    )
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current

    Label("Q07 Launcher ${BuildConfig.VERSION_NAME}", "Versiya kodu ${BuildConfig.VERSION_CODE} · Changan Qiyuan Q07 · 2560×1600")
    Label(
        "Öz-özünə diaqnostika",
        if (az.q07.launcher.data.SelfDiag.safeMode) "Təhlükəsiz rejim AKTİV (ard-arda çökmə) — kokpit söndürülüb"
        else "Jurnal yazılır: Android/data/az.q07.launcher/files/diag — kompüter qoşulanda avtomatik götürülür"
    )
    if (az.q07.launcher.data.SelfDiag.safeMode) {
        androidx.compose.material3.OutlinedButton(onClick = { az.q07.launcher.data.SelfDiag.clearSafeMode() }) {
            Text("Normal rejimə qayıt")
        }
    }
    val dm = context.resources.displayMetrics
    Label("Ekran", "${dm.widthPixels}×${dm.heightPixels} px · ${dm.densityDpi} dpi")
    Label("Mənbə kodu", "github.com/vuqarnamazov/Q07Launcher")
}
