package az.q07.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.License
import az.q07.launcher.data.QuickActions
import az.q07.launcher.ui.theme.CarColors

/** Lisenziya yoxdursa launcher əvəzinə göstərilir — maşın zavod ekranı ilə işləməyə davam edir */
@Composable
fun ActivationScreen() {
    val context = LocalContext.current
    val st by License.state.collectAsState()
    Box(Modifier.fillMaxSize().background(CarColors.Background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(28.dp)).background(CarColors.Panel)
                .border(1.dp, CarColors.Border, RoundedCornerShape(28.dp)).padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Q07 Launcher — aktivasiya", fontSize = 30.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary)
            when (st.status) {
                License.Status.CHECKING, License.Status.NO_VIN -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 14.dp))
                    Text(st.message ?: "Maşın yoxlanılır…", fontSize = 18.sp, color = CarColors.TextSecondary)
                }
                License.Status.OTHER_CAR -> Text(
                    "Bu launcher başqa maşın üçün qeydiyyatdan keçib. Hər maşın üçün ayrıca lisenziya lazımdır.",
                    fontSize = 18.sp, color = CarColors.Danger
                )
                License.Status.NOT_ACTIVATED -> Text(
                    "Bu maşın üçün lisenziya tapılmadı. Aşağıdakı aktivasiya kodunu launcher-in sahibinə göndərin.",
                    fontSize = 18.sp, color = CarColors.Warning
                )
                License.Status.OK -> Text("Aktivdir ✓", fontSize = 18.sp, color = CarColors.Green)
            }
            st.vinMasked?.let { Text("VIN: $it", fontSize = 17.sp, color = CarColors.TextSecondary) }
            st.code?.let {
                Text("Aktivasiya kodu", fontSize = 15.sp, color = CarColors.TextSecondary)
                Text(it.chunked(5).joinToString(" "), fontSize = 34.sp, fontFamily = FontFamily.Monospace, color = CarColors.Accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { License.retry() }) { Text("Yenidən yoxla") }
                OutlinedButton(onClick = {
                    QuickActions.findOtherHomes(context).firstOrNull()?.let { QuickActions.launchHome(context, it) }
                }) { Text("Zavod ekranına keç") }
            }
        }
    }
}
