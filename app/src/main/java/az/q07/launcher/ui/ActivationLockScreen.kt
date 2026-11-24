package az.q07.launcher.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.LicenseManager
import az.q07.launcher.data.QuickActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aktivasiya kilidi — iki yol:
 *  1) Onlayn: sahib kodu GitHub-dakı licenses.json "approved" siyahısına əlavə edir → ekran özü açılır (30 san. bir yoxlayır)
 *  2) Oflayn: sahibin telefonundakı Q07_Admin_Keygen.html-dən alınan XXXX-XXXX-XXXX kodu
 * Maşın heç vaxt bağlı qalmır: "Zavod ekranına keç".
 */
@Composable
fun ActivationLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestCode = remember { LicenseManager.getVehicleFingerprint(context) }
    var inputCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Təsdiq gözlənilir…") }
    var isError by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    suspend fun online(manual: Boolean) {
        checking = true
        val r = withContext(Dispatchers.IO) { LicenseManager.syncFromGitHub(context) }
        checking = false
        when (r) {
            LicenseManager.Remote.APPROVED -> onUnlocked()
            LicenseManager.Remote.BLOCKED -> { isError = true; status = "Bu maşın üçün lisenziya bloklanıb." }
            LicenseManager.Remote.NOT_LISTED -> { isError = manual; status = "Hələ onlayn təsdiq verilməyib. Kodu tərtibatçıya göndərin." }
            LicenseManager.Remote.NO_NETWORK -> { isError = manual; status = "İnternet yoxdur — oflayn kodla açın və ya şəbəkəyə qoşulun." }
        }
    }

    // Açılan kimi və sonra hər 30 saniyədən bir GitHub-u yoxla
    LaunchedEffect(Unit) {
        while (true) { online(manual = false); delay(30_000) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0E14)), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.width(640.dp).padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Q07 LAUNCHER AKTİVASİYASI", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58A6FF))
                Text("Bu proqram avtomobilin VIN koduna bağlıdır. Aktivasiya üçün aşağıdakı Cihaz Kodunu tərtibatçıya göndərin:",
                    fontSize = 15.sp, color = Color(0xFF8B949E))
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(12.dp)).padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(requestCode, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3FB950))
                    OutlinedButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Q07_ID", requestCode))
                        Toast.makeText(context, "Kod kopyalandı", Toast.LENGTH_SHORT).show()
                    }) { Text("Kopyala", color = Color.White) }
                }
                if (LicenseManager.offlineAvailable) {
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { inputCode = it.uppercase(); isError = false },
                        label = { Text("Oflayn təsdiq kodu (XXXX-XXXX-XXXX)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(if (checking) "GitHub-dan təsdiq yoxlanılır…" else status,
                    color = if (isError) Color(0xFFF85149) else Color(0xFF8B949E), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    if (LicenseManager.offlineAvailable) {
                        Button(
                            onClick = {
                                if (LicenseManager.verifyAndSave(context, inputCode)) onUnlocked()
                                else { isError = true; status = "Kod yanlışdır və ya bu maşına aid deyil!" }
                            },
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(54.dp)
                        ) { Text("Kodla aç", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    OutlinedButton(
                        onClick = { scope.launch { online(manual = true) } },
                        enabled = !checking,
                        modifier = Modifier.weight(1f).height(54.dp)
                    ) { Text("Onlayn yoxla", fontSize = 17.sp, color = Color(0xFF58A6FF)) }
                }
                OutlinedButton(
                    onClick = { QuickActions.findOtherHomes(context).firstOrNull()?.let { QuickActions.launchHome(context, it) } },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("Zavod ekranına keç", fontSize = 16.sp) }
            }
        }
    }
}
