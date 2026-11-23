package az.q07.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Aktivasiya kilidi — maşın zavod ekranı ilə işləməyə davam edir (aşağıdakı düymə) */
@Composable
fun ActivationLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val requestCode = remember { LicenseManager.getVehicleFingerprint(context) }
    var inputCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E14)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.width(620.dp).padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Q07 SİSTEM AKTİVASİYASI", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58A6FF))
                Text(
                    "Bu proqram təminatı konkret avtomobilə bağlıdır. Aktivasiya üçün aşağıdakı Cihaz Kodunu tərtibatçıya göndərin:",
                    fontSize = 16.sp, color = Color(0xFF8B949E)
                )
                Box(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(12.dp)).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(requestCode, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3FB950))
                }
                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { inputCode = it.uppercase(); errorMessage = false },
                    label = { Text("Təsdiq kodu (XXXX-XXXX-XXXX)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage) {
                    Text("Yanlış təsdiq kodu! Kod bu avtomobilə uyğun deyil.", color = Color(0xFFF85149), fontSize = 14.sp)
                }
                Button(
                    onClick = { if (LicenseManager.verifyAndSave(context, inputCode)) onUnlocked() else errorMessage = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("Təsdiqlə və Aç", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = { QuickActions.findOtherHomes(context).firstOrNull()?.let { QuickActions.launchHome(context, it) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Zavod ekranına keç", fontSize = 16.sp) }
            }
        }
    }
}
