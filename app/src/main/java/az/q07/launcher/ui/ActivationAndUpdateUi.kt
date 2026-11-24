package az.q07.launcher.ui

import az.q07.launcher.data.GitHubLicenseAndOtaManager
import az.q07.launcher.data.OtaUpdateInfo
import az.q07.launcher.data.QuickActions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ActivationLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestCode = remember { GitHubLicenseAndOtaManager.getVehicleRequestCode(context) }

    var inputCode by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Təsdiq gözlənilir...") }
    var isError by remember { mutableStateOf(false) }
    var isCheckingOnline by remember { mutableStateOf(false) }

    // Ekran açılanda avtomatik GitHub-da təsdiqlənib-təsdiqlənmədiyini yoxlayır
    LaunchedEffect(Unit) {
        isCheckingOnline = true
        val approved = GitHubLicenseAndOtaManager.syncLicenseFromGitHub(context)
        isCheckingOnline = false
        if (approved) onUnlocked()
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
                    "Bu proqram avtomobilin VIN koduna bağlıdır. Aktivasiya üçün aşağıdakı Cihaz Kodunu tərtibatçıya göndərin:",
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
                    label = { Text("Oflayn aktivasiya kodu (sahibdən — yapışdırın)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (isCheckingOnline) "Serverdən təsdiq yoxlanılır..." else statusText,
                    color = if (isError) Color(0xFFF85149) else Color(0xFF8B949E),
                    fontSize = 13.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (GitHubLicenseAndOtaManager.verifyManualCode(context, inputCode)) {
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
                            scope.launch {
                                isCheckingOnline = true
                                val ok = GitHubLicenseAndOtaManager.syncLicenseFromGitHub(context)
                                isCheckingOnline = false
                                if (ok) onUnlocked() else {
                                    isError = true
                                    statusText = "Hələ onlayn təsdiq verilməyib."
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("Onlayn Yoxla", color = Color(0xFF58A6FF))
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
        title = { Text("Yeni Versiya Mövcuddur: v${updateInfo.latestVersionName}") },
        text = { Text("Yeniliklər:\n${updateInfo.changelog}") },
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
