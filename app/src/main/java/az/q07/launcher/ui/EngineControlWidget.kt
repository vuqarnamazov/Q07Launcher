package az.q07.launcher.ui

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.VehicleEngineController

/**
 * Mühərrik / READY idarəsi — TƏK düymə.
 *
 * Başqa düymə, siyahı və ya seçim yoxdur: basırsan → işə düşür, bir daha basırsan → sönür.
 * Yol [VehicleEngineController] içində saxlanılan əl ilə yazılmış yoldur.
 * Yalnız P vəziyyətində.
 */
@Composable
fun EngineControlWidget(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val isRunning by VehicleEngineController.isEngineRunning.collectAsState()
    val busy by VehicleEngineController.busy.collectAsState()

    LaunchedEffect(Unit) { VehicleEngineController.init(context) }

    val ringColor by animateColorAsState(
        targetValue = if (isRunning) Color(0xFF10B981) else Color(0xFF0284C7),
        label = "engineRing"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xE6FFFFFF)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.5.dp, ringColor.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isRunning) "Mühərrik: AKTİV" else "Mühərrik: SÖNÜLÜ",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)
            )

            // TƏK düymə. İcra bitənə qədər kilidlidir — iki dəfə basıb açıb-bağlamaq olmur.
            Surface(
                onClick = {
                    val target = !VehicleEngineController.isEngineRunning.value
                    VehicleEngineController.toggleEngine(context, target) { res ->
                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(18.dp),
                color = if (isRunning) Color(0xFFFEF2F2) else Color(0xFFECFDF5),
                border = BorderStroke(2.dp, ringColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (busy) "…" else if (isRunning) "OFF" else "ON",
                        fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = ringColor
                    )
                    Text(
                        text = if (busy) "  İCRA" else "  MÜHƏRRİK",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569)
                    )
                }
            }

            Text(
                text = if (busy) "Əmr icra olunur…" else "Bas: yandır / söndür · Yalnız P",
                fontSize = 11.sp, color = Color(0xFF64748B), textAlign = TextAlign.Center
            )
        }
    }
}
