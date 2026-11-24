package az.q07.launcher.ui

import az.q07.launcher.data.*

import android.graphics.Rect
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

@Composable
fun MultiAppFramePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var layoutMode by remember { mutableStateOf(MultiAppFrameManager.getLayoutMode(context)) }
    var slots by remember { mutableStateOf(MultiAppFrameManager.getSlots(context)) }
    val frameBoundsMap = remember { mutableStateMapOf<Int, Rect>() }

    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }
    var searchAppQuery by remember { mutableStateOf("") }
    val installedApps = remember { MultiAppFrameManager.getLaunchableApps(context) }

    val activeSlots = remember(slots, layoutMode) {
        slots.take(layoutMode.slotCount)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF1F5F9))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xE6FFFFFF)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Çoxlu Mini-Pəncərə Paneli",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "Çərçivələrə proqram seçin — hər biri öz çərçivəsinin ölçüsündə kiçildilmiş açılacaq",
                        fontSize = 12.sp,
                        color = Color(0xFF475569)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FrameGridLayoutMode.values().forEach { mode ->
                        FilterChip(
                            selected = layoutMode == mode,
                            onClick = {
                                layoutMode = mode
                                MultiAppFrameManager.saveLayoutMode(context, mode)
                            },
                            label = { Text(mode.label, fontSize = 12.sp) }
                        )
                    }

                    Button(
                        onClick = {
                            FreeformWindowLauncher.ensureFreeformEnabled(context)
                            var openedCount = 0
                            activeSlots.forEach { slot ->
                                val bounds = frameBoundsMap[slot.slotIndex]
                                if (bounds != null && slot.packageName.isNotBlank()) {
                                    if (MultiAppFrameManager.launchSlotInExactFrame(context, slot, bounds, fullscreen = false)) {
                                        openedCount++
                                    }
                                }
                            }
                            if (openedCount == 0) {
                                Toast.makeText(context, "Əvvəlcə çərçivələrə quraşdırılmış proqram seçin!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Hamısını Çərçivələrdə Aç", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        if (layoutMode == FrameGridLayoutMode.FOUR_GRID) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    activeSlots.take(2).forEach { slot ->
                        AppFrameSlotCard(
                            slot = slot,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onBoundsMeasured = { rect -> frameBoundsMap[slot.slotIndex] = rect },
                            onSelectApp = { editingSlotIndex = slot.slotIndex; searchAppQuery = "" },
                            onLaunchMini = {
                                val r = frameBoundsMap[slot.slotIndex] ?: Rect(60, 120, 620, 520)
                                MultiAppFrameManager.launchSlotInExactFrame(context, slot, r, fullscreen = false)
                            },
                            onLaunchFullscreen = {
                                val r = frameBoundsMap[slot.slotIndex] ?: Rect(60, 120, 620, 520)
                                MultiAppFrameManager.launchSlotInExactFrame(context, slot, r, fullscreen = true)
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    activeSlots.drop(2).take(2).forEach { slot ->
                        AppFrameSlotCard(
                            slot = slot,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onBoundsMeasured = { rect -> frameBoundsMap[slot.slotIndex] = rect },
                            onSelectApp = { editingSlotIndex = slot.slotIndex; searchAppQuery = "" },
                            onLaunchMini = {
                                val r = frameBoundsMap[slot.slotIndex] ?: Rect(60, 120, 620, 520)
                                MultiAppFrameManager.launchSlotInExactFrame(context, slot, r, fullscreen = false)
                            },
                            onLaunchFullscreen = {
                                val r = frameBoundsMap[slot.slotIndex] ?: Rect(60, 120, 620, 520)
                                MultiAppFrameManager.launchSlotInExactFrame(context, slot, r, fullscreen = true)
                            }
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                activeSlots.forEach { slot ->
                    AppFrameSlotCard(
                        slot = slot,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onBoundsMeasured = { rect -> frameBoundsMap[slot.slotIndex] = rect },
                        onSelectApp = { editingSlotIndex = slot.slotIndex; searchAppQuery = "" },
                        onLaunchMini = {
                            val r = frameBoundsMap[slot.slotIndex] ?: Rect(60, 120, 620, 520)
                            MultiAppFrameManager.launchSlotInExactFrame(context, slot, r, fullscreen = false)
                        },
                        onLaunchFullscreen = {
                            val r = frameBoundsMap[slot.slotIndex] ?: Rect(60, 120, 620, 520)
                            MultiAppFrameManager.launchSlotInExactFrame(context, slot, r, fullscreen = true)
                        }
                    )
                }
            }
        }
    }

    editingSlotIndex?.let { targetIdx ->
        val filteredApps = remember(installedApps, searchAppQuery) {
            if (searchAppQuery.isBlank()) installedApps
            else installedApps.filter { it.label.contains(searchAppQuery, ignoreCase = true) }
        }

        AlertDialog(
            onDismissRequest = { editingSlotIndex = null },
            title = { Text("Çərçivə #${targetIdx + 1} üçün Proqram Seçin") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = searchAppQuery,
                        onValueChange = { searchAppQuery = it },
                        placeholder = { Text("Proqram axtar: Waze, YouTube, Musiqi...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(filteredApps) { app ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        slots = MultiAppFrameManager.updateSlot(context, targetIdx, app.packageName, app.label)
                                        editingSlotIndex = null
                                    }
                                    .padding(6.dp)
                            ) {
                                app.icon?.let { drawable ->
                                    val bmp = remember(app.packageName) { drawable.toBitmap(72, 72).asImageBitmap() }
                                    Image(bitmap = bmp, contentDescription = app.label, modifier = Modifier.size(44.dp))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = app.label,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingSlotIndex = null }) { Text("Bağla") }
            }
        )
    }
}

@Composable
private fun AppFrameSlotCard(
    slot: AppFrameSlot,
    modifier: Modifier = Modifier,
    onBoundsMeasured: (Rect) -> Unit,
    onSelectApp: () -> Unit,
    onLaunchMini: () -> Unit,
    onLaunchFullscreen: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(slot.packageName) {
        runCatching { context.packageManager.getApplicationIcon(slot.packageName) }.getOrNull()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(2.dp, Color(0xFFBAE6FD)),
        modifier = modifier.onGloballyPositioned { coords ->
            val b = coords.boundsInRoot()
            onBoundsMeasured(
                Rect(
                    b.left.toInt() + 8,
                    b.top.toInt() + 48,
                    b.right.toInt() - 8,
                    b.bottom.toInt() - 8
                )
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE0F2FE))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    appIcon?.let { d ->
                        val bmp = remember(slot.packageName) { d.toBitmap(48, 48).asImageBitmap() }
                        Image(bitmap = bmp, contentDescription = slot.appLabel, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        text = slot.appLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onSelectApp,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Dəyiş", fontSize = 11.sp, color = Color(0xFF0284C7))
                    }
                    TextButton(
                        onClick = onLaunchFullscreen,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Tam", fontSize = 11.sp, color = Color(0xFF475569))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF8FAFC))
                    .clickable { onLaunchMini() }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (appIcon != null) {
                        val bmp = remember(slot.packageName) { appIcon.toBitmap(96, 96).asImageBitmap() }
                        Image(bitmap = bmp, contentDescription = slot.appLabel, modifier = Modifier.size(56.dp))
                    }
                    Text(
                        text = slot.appLabel,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Button(
                        onClick = onLaunchMini,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Bu Çərçivədə Kiçik Aç", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
