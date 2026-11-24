package az.q07.launcher.ui

import az.q07.launcher.data.*

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun PhoneCarTransferScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PhoneCarTransferServer.start(context) }

    val items by PhoneCarTransferServer.items.collectAsState()
    val urls = remember { PhoneCarTransferServer.getAccessUrls() }
    var previewItem by remember { mutableStateOf<TransferItem?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            val name = queryFileName(context, uri) ?: "masin_fayl_${System.currentTimeMillis()}"
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PhoneCarTransferServer.shareFromCarToPhone(context, stream, name)
            }
        }
        if (uris.isNotEmpty()) {
            Toast.makeText(context, "${uris.size} fayl telefona yuklemek ucun hazirlandi!", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF1F5F9))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Telefon - Masin Sekil ve Fayl Transferi",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "Elave proqram olmadan telefonun brauzerinden masina ve ya masindan telefona gonderin",
                    fontSize = 13.sp,
                    color = Color(0xFF475569)
                )
            }
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
            ) {
                Text("Bagla", color = Color.White)
            }
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(0.9f).fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "1. Telefonu Maşına Qoşun",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0284C7)
                    )
                    Text(
                        text = "Telefonunuzu masinin Wi-Fi / Hotspot ve ya ZeroTier sebekesine qosun ve telefonun brauzerinde bu unvani acin:",
                        fontSize = 13.sp,
                        color = Color(0xFF475569)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE0F2FE), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("PIN: ${PhoneCarTransferServer.currentPin}", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFDC2626))
                            if (urls.isEmpty()) {
                                Text("Wi-Fi / Hotspot aktiv deyil", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                            } else {
                                urls.forEach { u ->
                                    Text(u, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = "2. Masindan Telefona Gonder",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                    Text(
                        text = "Masinin yaddasindaki sekil, video ve ya fayli secin — telefonunuzda derhal yukleme duymesi gorunecek:",
                        fontSize = 13.sp,
                        color = Color(0xFF475569)
                    )

                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        Text("Masindan Fayl / Sekil Sec", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1.1f).fillMaxHeight()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Mubadile Qutusu (${items.size} fayl)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    if (items.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Telefondan gonderilen sekiller ve fayllar burada gorunecek.", color = Color(0xFF64748B))
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(items) { item ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        if (item.isImage) {
                                            val bmp = remember(item.filePath) {
                                                BitmapFactory.decodeFile(item.filePath)?.asImageBitmap()
                                            }
                                            if (bmp != null) {
                                                Image(
                                                    bitmap = bmp,
                                                    contentDescription = item.fileName,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (item.direction == "FROM_PHONE") "Telefondan geldi" else "Masindan paylasilib",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.direction == "FROM_PHONE") Color(0xFF0284C7) else Color(0xFF10B981)
                                            )
                                            Text(item.fileName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                            Text("${item.sizeBytes / 1024} KB", fontSize = 12.sp, color = Color(0xFF64748B))
                                        }

                                        if (item.isImage) {
                                            OutlinedButton(onClick = { previewItem = item }) {
                                                Text("Bax")
                                            }
                                        } else {
                                            OutlinedButton(onClick = {
                                                val file = File(item.filePath)
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "*/*")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { context.startActivity(intent) }
                                            }) {
                                                Text("Ac")
                                            }
                                        }

                                        TextButton(onClick = { PhoneCarTransferServer.deleteItem(context, item) }) {
                                            Text("Sil", color = Color(0xFFDC2626))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewItem?.let { imgItem ->
        val bmp = remember(imgItem.filePath) { BitmapFactory.decodeFile(imgItem.filePath)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { previewItem = null },
            title = { Text(imgItem.fileName) },
            text = {
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = imgItem.fileName,
                        modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            },
            confirmButton = {
                Button(onClick = { previewItem = null }) { Text("Bagla") }
            }
        )
    }
}

private fun queryFileName(context: android.content.Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIdx >= 0) return cursor.getString(nameIdx)
    }
    return null
}
