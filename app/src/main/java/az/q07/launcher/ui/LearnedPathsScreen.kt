package az.q07.launcher.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.PathControl
import az.q07.launcher.data.PathControlGroup
import az.q07.launcher.data.VehicleControlBindings
import az.q07.launcher.scanner.FlowPlayer
import az.q07.launcher.scanner.LearnedFunction
import az.q07.launcher.scanner.LearnedFunctionsRepository
import az.q07.launcher.ui.theme.CarColors

/**
 * «Öyrənilmiş Yollar» paneli — bütün idarələr bir yerdə:
 * mühərrik, işıqlar, qapılar, kondisioner və pəncərələr üçün Öyrən / Test / Sil / Əvəz et.
 * Yollar maskalanmış formada göstərilir.
 */
@Composable
fun LearnedPathsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var pickerFor by remember { mutableStateOf<PathControl?>(null) }
    var deleteFor by remember { mutableStateOf<PathControl?>(null) }
    var testFor by remember { mutableStateOf<PathControl?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    val functions by LearnedFunctionsRepository.items.collectAsState()

    LaunchedEffect(Unit) { LearnedFunctionsRepository.init(context) }

    val bound = remember(tick, functions) {
        VehicleControlBindings.controls.associate { it.id to VehicleControlBindings.boundFunction(context, it.id) }
    }
    val boundCount = bound.values.count { it != null }

    fun runTest(control: PathControl) {
        val fn = bound[control.id]
        if (fn == null) {
            info = "«${control.title}» üçün yol bağlanmayıb — əvvəlcə Öyrən"
            return
        }
        if (control.group == PathControlGroup.ENGINE) {
            testFor = control
            return
        }
        val started = FlowPlayer.play(
            context = context,
            fn = fn,
            returnToLauncher = false,
            stepDelayOverrideMs = 400,
            allowCoordTap = false
        ) { _, message, _ -> info = message }
        if (!started) info = FlowPlayer.status.value ?: "Gedişat başlamadı"
        else info = "«${control.title}» test edilir…"
    }

    ScaledDialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CarColors.Background)
                .border(1.dp, CarColors.Border, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Başlıq
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Öyrənilmiş Yollar", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
                    Text(
                        "Hər idarəyə skanerdən öyrənilmiş gedişatı bağla — sonra bir toxunuşla səssiz icra",
                        fontSize = 13.sp, color = CarColors.TextSecondary
                    )
                }
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (boundCount > 0) CarColors.Green.copy(alpha = 0.16f) else CarColors.PanelAlt)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "$boundCount / ${VehicleControlBindings.controls.size} bağlanıb",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = if (boundCount > 0) CarColors.Green else CarColors.TextSecondary
                    )
                }
                Spacer(Modifier.width(10.dp))
                PathButton("Skaneri aç", CarColors.Accent) { openScannerScreen(context) }
                Spacer(Modifier.width(8.dp))
                PathButton("Bağla", CarColors.Danger) { onClose() }
            }

            info?.let { Text(it, fontSize = 14.sp, color = CarColors.Warning) }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PathControlGroup.entries.forEach { group ->
                    item(key = "hdr_${group.name}") {
                        Text(
                            group.titleAz.uppercase(),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Accent,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                        )
                    }
                    items(
                        VehicleControlBindings.controls.filter { it.group == group },
                        key = { it.id }
                    ) { control ->
                        ControlRow(
                            control = control,
                            function = bound[control.id],
                            onLearn = { pickerFor = control },
                            onTest = { runTest(control) },
                            onReplace = { pickerFor = control },
                            onDelete = { deleteFor = control }
                        )
                    }
                }
            }
        }
    }

    // Funksiya seçimi (Öyrən / Əvəz et)
    pickerFor?.let { control ->
        FunctionPickerDialog(
            control = control,
            functions = functions,
            onDismiss = { pickerFor = null },
            onPick = { fn ->
                VehicleControlBindings.bind(context, control.id, fn.id)
                pickerFor = null
                tick++
                info = "«${control.title}» → «${fn.name}» bağlandı"
            }
        )
    }

    // Silmə təsdiqi
    deleteFor?.let { control ->
        ScaledAlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Yolu silmək?") },
            text = {
                Text(
                    "«${control.title}» üçün bağlanmış yol silinəcək. Öyrənilmiş gedişat skanerin siyahısında qalır — " +
                        "lazım olsa yenidən bağlaya bilərsən."
                )
            },
            confirmButton = {
                PathButton("Sil", CarColors.Danger) {
                    VehicleControlBindings.unbind(context, control.id)
                    deleteFor = null
                    tick++
                    info = "«${control.title}» yolu silindi"
                }
            },
            dismissButton = { PathButton("Ləğv et", CarColors.TextSecondary) { deleteFor = null } }
        )
    }

    // Mühərrik testi təsdiqi
    testFor?.let { control ->
        ScaledAlertDialog(
            onDismissRequest = { testFor = null },
            title = { Text("Mühərrik testi") },
            text = { Text("«${control.title}» əmri maşında real olaraq icra olunacaq. Davam edilsin?") },
            confirmButton = {
                PathButton("Test et", CarColors.Green) {
                    val fn = bound[control.id]
                    testFor = null
                    if (fn == null) {
                        info = "«${control.title}» üçün yol bağlanmayıb"
                        return@PathButton
                    }
                    val started = FlowPlayer.play(
                        context = context,
                        fn = fn,
                        returnToLauncher = false,
                        stepDelayOverrideMs = 400,
                        allowCoordTap = false
                    ) { _, message, _ -> info = message }
                    info = if (started) "«${control.title}» test edilir…"
                    else (FlowPlayer.status.value ?: "Gedişat başlamadı")
                }
            },
            dismissButton = { PathButton("Ləğv et", CarColors.TextSecondary) { testFor = null } }
        )
    }
}

@Composable
private fun ControlRow(
    control: PathControl,
    function: LearnedFunction?,
    onLearn: () -> Unit,
    onTest: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit
) {
    val hasPath = function != null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CarColors.Panel)
            .border(1.dp, if (hasPath) CarColors.Green.copy(alpha = 0.45f) else CarColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(if (hasPath) CarColors.Green else CarColors.TextSecondary.copy(alpha = 0.4f))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(control.title, fontSize = 16.sp, color = CarColors.TextPrimary)
            Text(
                if (hasPath) "${function?.name} · ${VehicleControlBindings.maskedPath(function)}"
                else "Yol bağlanmayıb",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (hasPath) CarColors.Accent else CarColors.TextSecondary,
                maxLines = 1
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PathButton("Öyrən", CarColors.Green) { onLearn() }
            PathButton("Test", CarColors.Accent, enabled = hasPath) { onTest() }
            PathButton("Əvəz et", CarColors.Warning) { onReplace() }
            PathButton("Sil", CarColors.Danger, enabled = hasPath) { onDelete() }
        }
    }
}

@Composable
private fun FunctionPickerDialog(
    control: PathControl,
    functions: List<LearnedFunction>,
    onDismiss: () -> Unit,
    onPick: (LearnedFunction) -> Unit
) {
    val context = LocalContext.current
    ScaledDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxHeight(0.85f)
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(20.dp))
                .background(CarColors.Panel)
                .border(1.dp, CarColors.Border, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("«${control.title}» üçün yol seç", fontSize = 20.sp, color = CarColors.TextPrimary)
                    Text(
                        if (functions.isEmpty()) "Hələ öyrənilmiş gedişat yoxdur — skaneri aç və maşında düymələri bas"
                        else "Skanerdə öyrənilmiş funksiya/gedişatlar",
                        fontSize = 13.sp, color = CarColors.TextSecondary
                    )
                }
                PathButton("Skaneri aç", CarColors.Accent) { openScannerScreen(context) }
                Spacer(Modifier.width(8.dp))
                PathButton("Bağla", CarColors.Danger) { onDismiss() }
            }
            if (functions.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Skaner → «Götür və Əlavə Et» ilə gedişat yaz, sonra buraya qayıt.",
                        fontSize = 15.sp, color = CarColors.TextSecondary
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(functions.sortedByDescending { it.pinned }, key = { it.id }) { fn ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(CarColors.PanelAlt)
                                .clickable { onPick(fn) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(fn.name, fontSize = 16.sp, color = CarColors.TextPrimary)
                                Text(
                                    "${fn.steps.size} addım · ${fn.runs} icra" + (if (fn.pinned) " · 📌" else ""),
                                    fontSize = 12.sp, color = CarColors.TextSecondary
                                )
                            }
                            Text("Seç", fontSize = 14.sp, color = CarColors.Accent)
                        }
                    }
                }
            }
        }
    }
}

/** Kiçik, miqyaslanan düymə — SettingsScreen-dəki çiplər üslubunda. */
@Composable
private fun PathButton(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = if (enabled) color else CarColors.TextSecondary.copy(alpha = 0.5f)
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.copy(alpha = 0.14f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, color = c, maxLines = 1)
    }
}
