package az.q07.launcher.ui

import az.q07.launcher.data.GitHubLicenseAndOtaManager
import az.q07.launcher.data.LicenseManager
import az.q07.launcher.data.LicenseStatus
import az.q07.launcher.data.OtaUpdateInfo
import az.q07.launcher.data.QuickActions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aktivasiya kilidi: lisenziya olmadan launcher açılmır.
 * Cihaz kodu göstərilir, sahibin verdiyi imza kodu daxil edilir.
 */
@Composable
fun ActivationLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val requestCode = remember { LicenseManager.shortDeviceCode(context) }
    val status = remember { LicenseManager.status(context) }

    var inputCode by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var statusText by remember {
        mutableStateOf(
            when (status) {
                LicenseStatus.WRONG_SIGNATURE ->
                    "APK sahibin imza açarı ilə imzalanmayıb. Düzgün açarla yığılmış APK quraşdırın."
                LicenseStatus.TAMPERED -> "Debugger aşkarlandı — proqram açılmır."
                else -> "Aktivasiya kodu tələb olunur."
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E14)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.width(560.dp).padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Q07 LAUNCHER AKTİVASİYASI", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58A6FF))
                Text(
                    "Proqram bu maşına bağlıdır. Cihaz kodunu sahibə göndərin və aldığınız aktivasiya kodunu aşağıya yazın.",
                    fontSize = 14.sp, color = Color(0xFF8B949E)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(12.dp)).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(requestCode, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3FB950))
                    OutlinedButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Q07_ID", requestCode))
                        Toast.makeText(context, "Kod kopyalandı!", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Kopyala", color = Color.White)
                    }
                }

                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { inputCode = it; isError = false },
                    label = { Text("Aktivasiya kodu (8 rəqəm)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = statusText,
                    color = if (isError) Color(0xFFF85149) else Color(0xFF8B949E),
                    fontSize = 13.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (LicenseManager.activate(context, inputCode)) {
                                onUnlocked()
                            } else {
                                isError = true
                                statusText = "Kod yanlışdır və ya bu maşına aid deyil!"
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("Kodla Aç")
                    }

                    OutlinedButton(
                        onClick = {
                            val sum = LicenseManager.status(context)
                            if (sum == LicenseStatus.ACTIVATED) onUnlocked()
                            else {
                                isError = true
                                statusText = when (sum) {
                                    LicenseStatus.WRONG_SIGNATURE -> "APK imzası uyğun deyil."
                                    LicenseStatus.TAMPERED -> "Debugger aşkarlandı."
                                    else -> "Hələ aktivasiya yoxdur."
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("Yoxla", color = Color(0xFF58A6FF))
                    }
                }

                // Q07 defolt əsas ekran olduğuna görə maşın kilid ekranında ilişib qalmasın
                OutlinedButton(
                    onClick = { QuickActions.findOtherHomes(context).firstOrNull()?.let { QuickActions.launchHome(context, it) } },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Zavod ekranına keç")
                }
            }
        }
    }
}

@Composable
fun OtaUpdateBanner(updateInfo: OtaUpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var downloading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni Versiya Mövcuddur: ${updateInfo.latestVersionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Yeniliklər:", fontSize = 14.sp)
                val items = updateInfo.changelogItems.ifEmpty { listOf(updateInfo.changelog) }
                items.forEach { Text("• $it", fontSize = 14.sp) }
            }
        },
        confirmButton = {
            Button(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    Toast.makeText(context, "Yeniləmə yüklənir, bildiriş panelini izləyin...", Toast.LENGTH_LONG).show()
                    GitHubLicenseAndOtaManager.downloadAndInstallApk(context, updateInfo.apkUrl)
                    onDismiss()
                }
            ) {
                Text(if (downloading) "Yüklənir..." else "İndi Yenilə")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Sonra") }
        }
    )
}
