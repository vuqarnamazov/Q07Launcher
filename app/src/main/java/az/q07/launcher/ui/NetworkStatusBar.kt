package az.q07.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import az.q07.launcher.data.AppSettings
import az.q07.launcher.data.DualNetworkManager
import az.q07.launcher.data.QuickActions
import az.q07.launcher.ui.theme.CarColors

/**
 * Launcher ekranında daim görünən zolaq: Telefon Wi-Fi (internet) və OBD statusu + «Qoş» düymələri.
 */
@Composable
fun NetworkStatusBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var setupOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    // SSID oxumaq və OBD-ni tapmaq üçün məkan icazəsi lazımdır
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) QuickActions.toast(context, "Məkan icazəsi olmadan Wi-Fi adı oxunmur")
        pending?.invoke(); pending = null
    }
    fun withLocation(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) action() else { pending = action; locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }

    val dm = DualNetworkManager
    val obdData by az.q07.launcher.data.ObdMonitor.state.collectAsState()
    // Wi-Fi qoşulub, amma məlumat gəlmirsə — bunu açıq göstər
    val obdText = when {
        !dm.obdConnected -> dm.obdStatus
        obdData.values.isNotEmpty() -> "${obdData.values.size} göstərici ✓"
        obdData.status.startsWith("Xəta") -> obdData.status.removePrefix("Xəta: ").substringBefore(" — 5")
        else -> obdData.status
    }
    val obdColor = when {
        !dm.obdConnected -> CarColors.Danger
        obdData.values.isNotEmpty() -> CarColors.Green
        else -> CarColors.Warning
    }
    val phoneColor = when {
        dm.phoneInternet -> CarColors.Green
        dm.phoneConnected -> CarColors.Warning
        else -> CarColors.Danger
    }
    val phoneText = when {
        dm.hotspotSsid.isBlank() -> "Təyin edilməyib"
        dm.phoneInternet -> "Qoşulub · internet var"
        dm.phoneConnected -> "Qoşulub · internet yoxlanır"
        else -> "Qoşulmayıb"
    }

    // Dar paneldə (böyük ölçüdə) göstəricilər alt-alta düzülür — heç biri itmir
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val narrow = maxWidth < 440.dp
        val chips: @Composable (Modifier) -> Unit = { chipMod ->
            StatusChip(
                icon = Icons.Filled.PhoneAndroid,
                title = "Telefon Wi-Fi",
                status = phoneText,
                color = phoneColor,
                action = if (dm.phoneConnected) null else "Qoş",
                modifier = chipMod,
                onLongClick = { setupOpen = true },
                onAction = {
                    if (dm.hotspotSsid.isBlank()) setupOpen = true
                    else withLocation { dm.connectToPhoneHotspot(); QuickActions.toast(context, dm.lastMessage) }
                }
            )
            StatusChip(
                icon = Icons.Filled.DirectionsCar,
                title = "OBD",
                status = obdText,
                color = obdColor,
                action = if (dm.obdConnected) "Ayır" else "Qoş",
                modifier = chipMod,
                onLongClick = { setupOpen = true },
                onAction = {
                    if (dm.obdConnected) dm.disconnectObd()
                    else if (dm.obdSsid.isBlank()) setupOpen = true
                    else withLocation {
                        if (AppSettings.obdMode != "wifi") AppSettings.update { obdMode = "wifi" }
                        dm.connectToObd()
                    }
                }
            )
        }
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { chips(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chips(Modifier.weight(1f))
            }
        }
    }

    if (setupOpen) NetworkSetupDialog(onDismiss = { setupOpen = false })
}

@Composable
private fun StatusChip(
    icon: ImageVector,
    title: String,
    status: String,
    color: Color,
    action: String?,
    modifier: Modifier,
    onLongClick: () -> Unit,
    onAction: () -> Unit
) {
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CarColors.PanelAlt)
            .clickable(onClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = CarColors.TextSecondary, maxLines = 1)
            Text(
                status, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        if (action != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.2f))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text(action, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color) }
        }
    }
}

/** Telefon hotspot və OBD adapterinin adı/şifrəsi — bir dəfə daxil edilir */
@Composable
fun NetworkSetupForm(onSaved: (() -> Unit)? = null) {
    val dm = DualNetworkManager
    var hs by remember { mutableStateOf(dm.hotspotSsid) }
    var hp by remember { mutableStateOf(dm.hotspotPass) }
    var os by remember { mutableStateOf(dm.obdSsid) }
    var op by remember { mutableStateOf(dm.obdPass) }
    var auto by remember { mutableStateOf(dm.autoStart) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Telefon Hotspot (internet)", fontSize = 17.sp, color = CarColors.TextPrimary)
        OutlinedTextField(hs, { hs = it }, label = { Text("Hotspot adı (SSID)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hp, { hp = it }, label = { Text("Şifrə") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("OBD2 adapteri (lokal, internetsiz)", fontSize = 17.sp, color = CarColors.TextPrimary)
        OutlinedTextField(os, { os = it }, label = { Text("Adapter adı — standart: WiFi?OBD* (WiFi-OBDII / WiFi_OBDII)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(op, { op = it }, label = { Text("Adapter şifrəsi (açıqdırsa boş qoy)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("Wi-Fi prioriteti", fontSize = 17.sp, color = CarColors.TextPrimary)
        Text(
            "Maşının Wi-Fi modulu eyni anda yalnız bir şəbəkəyə qoşulur.",
            fontSize = 13.sp, color = CarColors.TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("obd" to "OBD (internet SIM-dən)", "phone" to "Telefon hotspot").forEach { (k, label) ->
                val sel = dm.wifiPriority == k
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) CarColors.Accent else CarColors.PanelAlt)
                        .clickable { dm.setPriority(k) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) { Text(label, fontSize = 15.sp, color = if (sel) Color.Black else CarColors.TextPrimary) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Launcher açılanda avtomatik qoşul", fontSize = 16.sp, color = CarColors.TextPrimary,
                modifier = Modifier.weight(1f))
            Switch(auto, { auto = it })
        }
        Text(
            "«*» istənilən mətn, «?» istənilən bir simvol deməkdir. OBD qoşulanda sistem bir dəfə təsdiq pəncərəsi göstərir.",
            fontSize = 13.sp, color = CarColors.TextSecondary
        )
        TextButton(onClick = {
            dm.saveSettings(hs, hp, os, op, auto)
            onSaved?.invoke()
        }) { Text("Yadda saxla") }
    }
}

@Composable
private fun NetworkSetupDialog(onDismiss: () -> Unit) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Şəbəkə: Telefon + OBD") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { NetworkSetupForm(onSaved = onDismiss) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Bağla") } }
    )
}

/** Bütün bölmələrdə görünən canlı maşın zolağı (sürət, dövr, SOC, 12V, soyuducu) */
@Composable
fun LiveCarStrip(modifier: Modifier = Modifier) {
    val obd by az.q07.launcher.data.ObdMonitor.state.collectAsState()
    if (obd.values.isEmpty()) return
    val v = obd.values
    fun f(k: String, d: Int = 0) = v[k]?.let { if (d == 0) it.toInt().toString() else String.format(java.util.Locale.US, "%.${d}f", it) } ?: "—"
    val items = listOf(
        "km/s" to f("speed"), "rpm" to f("rpm"), "SOC %" to f("soc", 1), "12V" to f("voltage", 1), "Soyuducu °C" to f("coolant")
    )
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(CarColors.PanelAlt)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        items.forEach { (label, value) ->
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(value, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = CarColors.TextPrimary, maxLines = 1)
                Text(label, fontSize = 10.sp, color = CarColors.TextSecondary, maxLines = 1)
            }
        }
    }
}
