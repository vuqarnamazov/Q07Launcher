package az.q07.launcher.modules

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.Note
import az.q07.launcher.data.NotesStore
import az.q07.launcher.data.QuickActions
import az.q07.launcher.data.ReminderScheduler
import az.q07.launcher.ui.theme.CarColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VoiceNoteModule : LauncherModule {
    override val id = "voice_notes"
    override val title = "Səsli qeyd / xatırlatma"
    override val icon: ImageVector = Icons.Filled.Mic
    override val accent: Color = CarColors.Accent

    @Composable
    override fun Summary() {
        val context = LocalContext.current
        val speech = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val text = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) NotesStore.add(text)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(CarColors.Accent)
                    .clickable {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "az-AZ")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Qeydinizi deyin")
                        try {
                            speech.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            QuickActions.toast(context, "Səs tanıma xidməti tapılmadı (Google app lazımdır)")
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Mic, "Səsli qeyd", tint = Color.Black, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val last = NotesStore.notes.firstOrNull()
                Text(
                    text = last?.text ?: "Hələ qeyd yoxdur. Mikrofona toxun.",
                    fontSize = 17.sp,
                    color = if (last == null) CarColors.TextSecondary else CarColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Cəmi: ${NotesStore.notes.size} qeyd",
                    fontSize = 14.sp,
                    color = CarColors.TextSecondary
                )
            }
        }
    }

    @Composable
    override fun Expanded() {
        var input by remember { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("Yazaraq əlavə et…") },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    NotesStore.add(input)
                    input = ""
                }) {
                    Icon(Icons.Filled.Send, "Əlavə et", tint = CarColors.Accent)
                }
            }
            NotesStore.notes.take(20).forEach { note -> NoteRow(note) }
        }
    }

    @Composable
    private fun NoteRow(note: Note) {
        val context = LocalContext.current
        val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CarColors.Panel)
                .padding(12.dp)
        ) {
            Text(note.text, fontSize = 16.sp, color = CarColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.remindAt?.let { "⏰ " + fmt.format(Date(it)) } ?: fmt.format(Date(note.createdAt)),
                    fontSize = 13.sp,
                    color = if (note.remindAt != null) CarColors.Warning else CarColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                if (note.remindAt == null) {
                    TextButton(onClick = { ReminderScheduler.schedule(context, note, 15 * 60_000L) }) {
                        Icon(Icons.Filled.Alarm, null, modifier = Modifier.size(18.dp))
                        Text(" 15 dəq")
                    }
                    TextButton(onClick = { ReminderScheduler.schedule(context, note, 60 * 60_000L) }) {
                        Text("1 saat")
                    }
                } else {
                    IconButton(onClick = { ReminderScheduler.cancel(context, note) }) {
                        Icon(Icons.Filled.AlarmOff, "Xatırlatmanı ləğv et", tint = CarColors.Warning)
                    }
                }
                IconButton(onClick = {
                    if (note.remindAt != null) ReminderScheduler.cancel(context, note)
                    NotesStore.remove(note.id)
                }) {
                    Icon(Icons.Filled.Delete, "Sil", tint = CarColors.Danger)
                }
            }
        }
    }
}
