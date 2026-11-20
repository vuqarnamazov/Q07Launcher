package az.q07.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.SmartDisplay
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.HomeTarget
import az.q07.launcher.data.QuickActions
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DockPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var homeChoices by remember { mutableStateOf<List<HomeTarget>?>(null) }

    PanelSurface(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ClockBlock()
            Spacer(Modifier.height(8.dp))

            DockButton("Naviqasiya", Icons.Filled.Navigation, CarColors.Green) {
                QuickActions.openNavigation(context)
            }
            DockButton("YouTube", Icons.Filled.SmartDisplay, CarColors.YouTube) {
                QuickActions.openYouTube(context)
            }
            DockButton("Gemini", Icons.Filled.AutoAwesome, CarColors.Purple) {
                QuickActions.openGemini(context)
            }

            Spacer(Modifier.weight(1f))

            // Zavod launcher-inə təhlükəsiz qayıdış.
            // Uzun basma → "Defolt əsas ekran" ayarları.
            DockButton(
                label = "Zavod menyusu",
                icon = Icons.Filled.Home,
                tint = CarColors.Warning,
                onLongClick = { QuickActions.openHomeSettings(context) }
            ) {
                val targets = QuickActions.findOtherHomes(context)
                when (targets.size) {
                    0 -> QuickActions.openHomeSettings(context)
                    1 -> QuickActions.launchHome(context, targets.first())
                    else -> homeChoices = targets
                }
            }
        }
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
                        ) {
                            Text(target.label, fontSize = 18.sp)
                        }
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
private fun ClockBlock() {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000L - value % 1000L)
        }
    }
    val locale = remember { Locale.forLanguageTag("az-AZ") }
    val timeFmt = remember { SimpleDateFormat("HH:mm", locale) }
    val secFmt = remember { SimpleDateFormat("ss", locale) }
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM yyyy", locale) }
    val date = Date(now)

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = timeFmt.format(date),
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                color = CarColors.TextPrimary
            )
            Text(
                text = ":" + secFmt.format(date),
                fontSize = 28.sp,
                color = CarColors.TextSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 14.dp)
            )
        }
        Text(
            text = dateFmt.format(date).replaceFirstChar { it.titlecase(locale) },
            fontSize = 18.sp,
            color = CarColors.TextSecondary
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockButton(
    label: String,
    icon: ImageVector,
    tint: Color,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CarColors.PanelAlt)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary)
    }
}
