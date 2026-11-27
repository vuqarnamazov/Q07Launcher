package az.q07.launcher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.macro.Macro
import az.q07.launcher.macro.MacroAccessibilityHook
import az.q07.launcher.macro.MacroPlayer
import az.q07.launcher.macro.MacroStore
import az.q07.launcher.ui.theme.CarLauncherTheme

/** «Sürətli Makrolar» — 3-4 addımlı menyu gedişini yazıb bir düymə ilə icra edir. */
class MacroActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CarLauncherTheme { MacroScreen(onClose = { finish() }) } }
    }
}

private val Bg = Color(0xFF0B1220)
private val Card = Color(0xFF16233A)
private val Accent = Color(0xFF33CCFF)
private val Danger = Color(0xFFFF5C5C)
private val Ok = Color(0xFF10B981)

@Composable
fun MacroScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        MacroStore.init(context)
        MacroAccessibilityHook.state.value.let { }
    }
    val macros by MacroStore.items.collectAsState()
    val rec by MacroAccessibilityHook.state.collectAsState()
    val playing by MacroPlayer.status.collectAsState()
    var info by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sürətli Makrolar", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "  ${macros.size} makro",
                fontSize = 13.sp, color = Color(0xFF7F8EA3), modifier = Modifier.weight(1f)
            )
            Text(
                "Bağla", fontSize = 14.sp, color = Accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable { onClose() }.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        // Yazma idarəsi
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                text = if (rec.recording) "● YAZILIR (${rec.steps.size} addım)" else "● Yeni makro yaz",
                color = if (rec.recording) Danger else Ok,
                modifier = Modifier.weight(1f)
            ) {
                if (rec.recording) {
                    val m = MacroAccessibilityHook.stop(context)
                    info = if (m == null) "Addım yazılmadı" else "Saxlanıldı: ${m.name} (${m.steps.size} addım)"
                } else {
                    MacroAccessibilityHook.start(context)
                    info = "İndi menyuda gəz və düymələrə bas. Launcher-ə qayıdanda yazı özü bitir."
                }
            }
        }

        (info ?: rec.message ?: playing)?.let {
            Text(it, fontSize = 13.sp, color = Color(0xFFB9C6D8))
        }

        if (macros.isEmpty()) {
            Text(
                "Hələ makro yoxdur. «Yeni makro yaz» bas, maşın menyusunda 3-4 addım gəz, sonra launcher-ə qayıt — makro avtomatik saxlanılacaq.",
                fontSize = 13.sp, color = Color(0xFF7F8EA3)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(macros, key = { it.id }) { m -> MacroRow(m) { msg -> info = msg } }
            }
        }

        Text(
            "Makronu launcher ekranından başladın ki, ilk addım (tətbiq/ menyu açılışı) da yazılsın.",
            fontSize = 12.sp, color = Color(0xFF5E6C80)
        )
    }
}

@Composable
private fun MacroRow(m: Macro, onInfo: (String?) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card)
            .border(1.dp, Color(0xFF23324D), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(
                "${m.steps.size} addım · " + m.steps.joinToString(" → ") { it.label() }.take(70),
                fontSize = 11.sp, color = Color(0xFF7F8EA3)
            )
        }
        Text(
            "▶ İcra et", fontSize = 14.sp, color = Accent,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                val accepted = MacroPlayer.play(context, m) { _, msg -> onInfo(msg) }
                onInfo(if (accepted) "İcra olunur…" else MacroPlayer.status.value ?: "Başlamadı")
            }.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Text(
            "Sil", fontSize = 14.sp, color = Danger,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                MacroStore.delete(m.id); onInfo("«${m.name}» silindi")
            }.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ActionButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f))
            .border(1.5.dp, color, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color) }
}
