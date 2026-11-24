package az.q07.launcher.ui

import az.q07.launcher.data.*

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SlotAppPreset(
    val id: String,
    val label: String,
    val packages: List<String>,
    val accentColor: Color
)

object MultiSlotWindowController {
    private const val WINDOWING_MODE_FREEFORM = 5

    val AVAILABLE_APPS = listOf(
        SlotAppPreset("waze", "Waze", listOf("com.waze"), Color(0xFF0284C7)),
        SlotAppPreset("youtube", "YouTube", listOf("com.google.android.youtube", "app.revanced.android.youtube", "com.teamsmart.videomanager.tv"), Color(0xFFDC2626)),
        SlotAppPreset("spotify", "Musiqi", listOf("com.spotify.music", "com.android.music"), Color(0xFF16A34A)),
        SlotAppPreset("settings", "Ayarlar", listOf("com.android.settings"), Color(0xFF475569))
    )

    @SuppressLint("PrivateApi")
    fun launchAppIntoFrame(context: Context, preset: SlotAppPreset, frameRect: Rect): Boolean {
        val intent = preset.packages.firstNotNullOfOrNull { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg)
        }
        if (intent == null) {
            Toast.makeText(context, "${preset.label} maşında tapılmadı!", Toast.LENGTH_SHORT).show()
            return false
        }

        try {
            Settings.Global.putInt(context.contentResolver, "enable_freeform_support", 1)
            Settings.Global.putInt(context.contentResolver, "force_resizable_activities", 1)
        } catch (_: Exception) { }

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )

        return try {
            val options = ActivityOptions.makeBasic()
            options.setLaunchBounds(frameRect)
            val setMode = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType
            )
            setMode.invoke(options, WINDOWING_MODE_FREEFORM)
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Çərçivə açılmadı: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}

@Composable
fun MultiAppLastPageScreen(onBackToMain: () -> Unit) {
    var splitCount by remember { mutableIntStateOf(2) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF1F5F9))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Çoxlu Mini-Pəncərə Paneli",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "Hər çərçivənin daxilində açmaq istədiyiniz proqramı seçin",
                    fontSize = 12.sp,
                    color = Color(0xFF475569)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = splitCount == 2,
                    onClick = { splitCount = 2 },
                    label = { Text("2 Çərçivə") }
                )
                FilterChip(
                    selected = splitCount == 3,
                    onClick = { splitCount = 3 },
                    label = { Text("3 Çərçivə") }
                )
                Button(
                    onClick = onBackToMain,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("Əsas Ekran", color = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            for (slotIndex in 1..splitCount) {
                AppContainerFrame(
                    slotNumber = slotIndex,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun AppContainerFrame(
    slotNumber: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var frameBoundsInWindow by remember { mutableStateOf(Rect(0, 0, 500, 400)) }
    var activeAppLabel by remember { mutableStateOf<String?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xE6FFFFFF)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(2.dp, Color(0xFF38BDF8)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Çərçivə #$slotNumber" + (activeAppLabel?.let { " ($it)" } ?: ""),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MultiSlotWindowController.AVAILABLE_APPS.forEach { app ->
                    Button(
                        onClick = {
                            val ok = MultiSlotWindowController.launchAppIntoFrame(
                                context = context,
                                preset = app,
                                frameRect = frameBoundsInWindow
                            )
                            if (ok) activeAppLabel = app.label
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = app.accentColor),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(34.dp)
                    ) {
                        Text(app.label, fontSize = 11.sp, color = Color.White, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF8FAFC), RoundedCornerShape(14.dp))
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInWindow()
                        frameBoundsInWindow = Rect(
                            b.left.toInt(),
                            b.top.toInt(),
                            b.right.toInt(),
                            b.bottom.toInt()
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (activeAppLabel == null)
                        "Yuxarıdan proqram seçin — kiçildilmiş formada bu çərçivədə açılacaq"
                    else
                        "$activeAppLabel bu çərçivəyə yerləşdirildi",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}
