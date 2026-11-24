package az.q07.launcher.ui

import az.q07.launcher.data.VehicleEngineController

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EngineControlWidget(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VehicleEngineController.init(context) }

    val isRunning by VehicleEngineController.isEngineRunning.collectAsState()
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showPathConfigDialog by remember { mutableStateOf(false) }
    var customPathInput by remember { mutableStateOf(VehicleEngineController.getCustomEnginePath(context)) }

    val ringColor by animateColorAsState(
        targetValue = if (isRunning) Color(0xFF10B981) else Color(0xFF0284C7),
        label = "engineRing"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xE6FFFFFF)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.5.dp, ringColor.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isRunning) Color(0xFFECFDF5) else Color(0xFFF0F9FF),
                border = BorderStroke(3.dp, ringColor),
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { showConfirmDialog = true }
                    )
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(if (isRunning) "STOP" else "START", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = ringColor)
                    Text("ENGINE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) "Mühərrik / Sistem: AKTİV" else "Mühərrik / Sistem: SÖNÜLÜ",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)
                )
                Text(
                    text = if (isRunning) "READY rejimi işləkdir" else "İşə salmaq üçün toxunun (yalnız P vəziyyətində)",
                    fontSize = 12.sp, color = Color(0xFF64748B)
                )
            }

            TextButton(onClick = { showPathConfigDialog = true }) {
                Text("Yol", fontSize = 12.sp, color = Color(0xFF0284C7))
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(if (isRunning) "Mühərriki Söndürmək?" else "Mühərriki İşə Salmaq?") },
            text = {
                Text(
                    if (isRunning) "Avtomobilin mühərrikini və güc sistemini söndürmək istədiyinizə əminsiniz?"
                    else "Changan Qiyuan Q07 mühərrikini / READY rejimini işə salmaq istəyirsiniz?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val res = VehicleEngineController.toggleEngine(context, !isRunning)
                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFDC2626) else Color(0xFF10B981))
                ) { Text(if (isRunning) "Bəli, Söndür" else "Bəli, İşə Sal", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { showConfirmDialog = false }) { Text("Ləğv Et") } }
        )
    }

    if (showPathConfigDialog) {
        AlertDialog(
            onDismissRequest = { showPathConfigDialog = false },
            title = { Text("Mühərrik Sistem Yolu") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sistem Skanerindən tapdığınız konkret açar və ya broadcast yolunu buraya yapışdırın:", fontSize = 13.sp)
                    OutlinedTextField(
                        value = customPathInput,
                        onValueChange = { customPathInput = it },
                        placeholder = { Text("Məs: GLOBAL:changan_engine_state") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    VehicleEngineController.saveCustomEnginePath(context, customPathInput)
                    Toast.makeText(context, "Mühərrik yolu yadda saxlanıldı!", Toast.LENGTH_SHORT).show()
                    showPathConfigDialog = false
                }) { Text("Yadda Saxla") }
            },
            dismissButton = { TextButton(onClick = { showPathConfigDialog = false }) { Text("Bağla") } }
        )
    }
}
