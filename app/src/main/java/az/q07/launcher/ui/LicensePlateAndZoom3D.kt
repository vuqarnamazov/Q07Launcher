package az.q07.launcher.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object VehiclePlateStorage {
    private const val PREFS = "q07_vehicle_plate_prefs"
    private const val KEY_PLATE = "license_plate_text"

    fun getPlate(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLATE, "99-VR-007") ?: "99-VR-007"

    fun savePlate(context: Context, plate: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLATE, plate.trim().uppercase()).apply()
    }
}

@Composable
fun InteractiveQ07ModelStage(
    modifier: Modifier = Modifier,
    carVisualContent: @Composable (rotationY: Float, plateText: String, onEditPlate: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var rotationY by remember { mutableFloatStateOf(0f) }
    var plateText by remember { mutableStateOf(VehiclePlateStorage.getPlate(context)) }
    var showPlateDialog by remember { mutableStateOf(false) }
    var tempPlateInput by remember { mutableStateOf(plateText) }

    Box(
        modifier = modifier
            // iki barmaqla böyüt/kiçilt (pinch) və bir barmaqla fırlat
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.75f, 2.2f)
                    rotationY = (rotationY + pan.x * 0.35f) % 360f
                }
            }
            // nömrəni dəyişmək üçün: maşına uzun bas; iki barmaqla iki dəfə toxun → 1:1
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        tempPlateInput = plateText
                        showPlateDialog = true
                    },
                    onDoubleTap = {
                        scale = 1f
                        rotationY = 0f
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
            contentAlignment = Alignment.Center
        ) {
            carVisualContent(rotationY, plateText) {
                tempPlateInput = plateText
                showPlateDialog = true
            }
        }
    }

    if (showPlateDialog) {
        AlertDialog(
            onDismissRequest = { showPlateDialog = false },
            title = { Text("Avtomobilin Dövlət Nömrəsi") },
            text = {
                OutlinedTextField(
                    value = tempPlateInput,
                    onValueChange = { tempPlateInput = it.uppercase() },
                    label = { Text("Məs: 99-VR-007") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (tempPlateInput.isNotBlank()) {
                        plateText = tempPlateInput.trim().uppercase()
                        VehiclePlateStorage.savePlate(context, plateText)
                    }
                    showPlateDialog = false
                }) { Text("Buferə Yaz") }
            },
            dismissButton = {
                TextButton(onClick = { showPlateDialog = false }) { Text("Ləğv Et") }
            }
        )
    }
}

@Composable
fun BumperLicensePlateBadge(
    plateText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFF0F172A)),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text("AZ", fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0284C7))
            Text(plateText, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        }
    }
}
