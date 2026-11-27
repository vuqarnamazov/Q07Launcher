package az.q07.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.PathControl
import az.q07.launcher.data.PathControlGroup
import az.q07.launcher.data.VehicleControlBindings
import az.q07.launcher.scanner.FlowPlayer
import az.q07.launcher.scanner.LearnedFunction
import az.q07.launcher.scanner.LearnedFunctionsRepository
import az.q07.launcher.scanner.TtLearning
import az.q07.launcher.ui.theme.CarColors

/**
 * «İdarəetmə» səhifəsi — 15.4" ekrana scroll etmədən sığan 3 sütunlu kompakt struktur.
 *
 * Hər idarə bir toxunuşla işləyir («tt»):
 *  - yol bağlanmayıbsa → öyrənmə sessiyası açılır (maşında hədəf düyməni bir dəfə bas),
 *  - yol bağlanıbsa → səssiz icra (menyu açılmır).
 * Uzun basış — bağlanmış yolu silir.
 */
@Composable
fun VehicleControlPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val functions by LearnedFunctionsRepository.items.collectAsState()
    val status by TtLearning.status.collectAsState()
    val pending by TtLearning.pending.collectAsState()

    LaunchedEffect(Unit) { LearnedFunctionsRepository.init(context) }

    val bound = remember(tick, functions) {
        VehicleControlBindings.controls.associate { it.id to VehicleControlBindings.boundFunction(context, it.id) }
    }
    val boundCount = bound.values.count { it != null }

    fun execute(control: PathControl, fn: LearnedFunction) {
        // Səssiz icra: menyu açılmır, nəticə FlowPlayer.status-da görünür
        FlowPlayer.play(
            context = context,
            fn = fn,
            returnToLauncher = false,
            stepDelayOverrideMs = 400,
            allowCoordTap = false
        )
    }

    fun onTap(control: PathControl) {
        val fn = bound[control.id]
        if (fn != null) {
            TtLearning.cancel()
            execute(control, fn)
        } else {
            TtLearning.start(context, control)
        }
        tick++
    }

    fun onDelete(control: PathControl) {
        VehicleControlBindings.unbind(context, control.id)
        tick++
    }

    Column(modifier.fillMaxSize()) {
        // başlıq zolağı
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("İDARƏETMƏ", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
                Text(
                    "tt · toxun: öyrən/icra et · hədəfi 2 san bas və saxla · uzun basış: yolu sil",
                    fontSize = 12.sp, color = CarColors.TextSecondary
                )
            }
            status?.let { s ->
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(CarColors.Accent.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(s, fontSize = 12.sp, color = CarColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
            }
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (boundCount > 0) CarColors.Green.copy(alpha = 0.16f) else CarColors.PanelAlt)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "$boundCount / ${VehicleControlBindings.controls.size} bağlı",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (boundCount > 0) CarColors.Green else CarColors.TextSecondary
                )
            }
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupBlock(
                    listOf(PathControlGroup.ENGINE, PathControlGroup.LIGHTS),
                    bound, pending?.controlId,
                    onTap = ::onTap, onDelete = ::onDelete,
                    modifier = Modifier.weight(1f)
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupBlock(
                    listOf(PathControlGroup.DOORS, PathControlGroup.CLIMATE),
                    bound, pending?.controlId,
                    onTap = ::onTap, onDelete = ::onDelete,
                    modifier = Modifier.weight(1f)
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupBlock(
                    listOf(PathControlGroup.WINDOWS),
                    bound, pending?.controlId,
                    onTap = ::onTap, onDelete = ::onDelete,
                    modifier = Modifier.weight(1f)
                )
                StorageCard(Modifier.height(120.dp))
            }
        }
    }
}

/** Bir və ya bir neçə qrupu sütunda yerləşdirir — siyahı scroll etmir, hündürlüyü bölüşür. */
@Composable
private fun GroupBlock(
    groups: List<PathControlGroup>,
    bound: Map<String, LearnedFunction?>,
    pendingId: String?,
    onTap: (PathControl) -> Unit,
    onDelete: (PathControl) -> Unit,
    modifier: Modifier = Modifier
) {
    val controls = groups.flatMap { g -> VehicleControlBindings.controls.filter { it.group == g } }
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(CarColors.Panel)
            .border(1.dp, CarColors.Border, RoundedCornerShape(18.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            groups.joinToString(" · ") { it.titleAz.uppercase() },
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Accent,
            modifier = Modifier.padding(start = 4.dp)
        )
        controls.forEach { control ->
            ControlTile(
                control = control,
                function = bound[control.id],
                learning = pendingId == control.id,
                onTap = { onTap(control) },
                onDelete = { onDelete(control) },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}

/** Kompakt idarə kartı: ad + maskalanmış yol + vəziyyət nişanı. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlTile(
    control: PathControl,
    function: LearnedFunction?,
    learning: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasPath = function != null
    val borderColor = when {
        learning -> CarColors.Accent
        hasPath -> CarColors.Green.copy(alpha = 0.5f)
        else -> CarColors.Border
    }
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (learning) CarColors.Accent.copy(alpha = 0.12f) else CarColors.PanelAlt)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onTap, onLongClick = { if (hasPath) onDelete() })
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(
                    when {
                        learning -> CarColors.Accent
                        hasPath -> CarColors.Green
                        else -> CarColors.TextSecondary.copy(alpha = 0.4f)
                    }
                )
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                control.title, fontSize = 14.sp, color = CarColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    learning -> "hədəfi 2 san bas və saxla…"
                    hasPath -> VehicleControlBindings.maskedPath(function)
                    else -> "toxun → öyrən"
                },
                fontSize = 10.sp,
                fontFamily = if (hasPath) FontFamily.Monospace else FontFamily.Default,
                color = if (hasPath) CarColors.Accent else CarColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        learning -> CarColors.Accent.copy(alpha = 0.2f)
                        hasPath -> CarColors.Green.copy(alpha = 0.16f)
                        else -> CarColors.Panel.copy(alpha = 0.8f)
                    }
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                if (hasPath) "İcra" else "Öyrən",
                fontSize = 11.sp,
                color = if (hasPath) CarColors.Green else CarColors.TextSecondary
            )
        }
    }
}

/** Sağ sütunun altını dolduran qısa məlumat kartı. */
@Composable
private fun StorageCard(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(CarColors.Panel)
            .border(1.dp, CarColors.Border, RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Yaddaş", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
        Text("Yollar AES-256 ilə şifrələnib", fontSize = 11.sp, color = CarColors.Green)
        Text(
            "Açar Android Keystore-da saxlanılır; fayl açıq mətn deyil.",
            fontSize = 11.sp, color = CarColors.TextSecondary
        )
    }
}
