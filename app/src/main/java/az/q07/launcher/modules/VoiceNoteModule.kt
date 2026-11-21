package az.q07.launcher.modules

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.core.content.ContextCompat
import az.q07.launcher.data.AudioNotes
import az.q07.launcher.data.Note
import az.q07.launcher.data.NotesStore
import az.q07.launcher.data.QuickActions
import az.q07.launcher.data.ReminderScheduler
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VoiceNoteModule : LauncherModule {
    override val id = "voice_notes"
    override val title = "Səsli qeyd / xatırlatma"
    override val shortTitle = "Qeyd"
    override val icon: ImageVector = Icons.Filled.Mic
    override val accent: Color = CarColors.Accent

    @Composable
    override fun Summary() {
        val context = LocalContext.current
        // Google səs tanıması varmı? (Q07-də adətən yoxdur)
        val speechAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }

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

        val toggleRecording: () -> Unit = {
            if (AudioNotes.isRecording) {
                val path = AudioNotes.stop()
                if (path != null) NotesStore.add("", path)
                else QuickActions.toast(context, "Qeyd çox qısa idi")
            } else if (!AudioNotes.start(context)) {
                QuickActions.toast(context, "Mikrofon açıla bilmədi")
            }
        }

        val micPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) toggleRecording()
            else QuickActions.toast(context, "Mikrofon icazəsi verilmədi")
        }

        val elapsed by produceState(0L, AudioNotes.isRecording) {
            while (AudioNotes.isRecording) {
                value = (System.currentTimeMillis() - AudioNotes.recordingStartedAt) / 1000
                delay(250)
            }
            value = 0L
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Əsas düymə: səs yazma (internet və Google tələb etmir)
            RoundButton(
                icon = if (AudioNotes.isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                color = if (AudioNotes.isRecording) CarColors.Danger else CarColors.Accent,
                description = "Səs yaz"
            ) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted || AudioNotes.isRecording) toggleRecording()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }

            if (speechAvailable) {
                Spacer(Modifier.width(10.dp))
                RoundButton(Icons.Filled.KeyboardVoice, CarColors.Green, "Danış, mətnə çevir") {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "az-AZ")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Qeydinizi deyin")
                    try {
                        speech.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        QuickActions.toast(context, "Səs tanıma xidməti tapılmadı")
                    }
                }
            }

            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (AudioNotes.isRecording) {
                    Text(
                        "● Yazılır… %d:%02d".format(elapsed / 60, elapsed % 60),
                        fontSize = 18.sp, color = CarColors.Danger
                    )
                    Text("Dayandırmaq üçün yenidən toxun", fontSize = 14.sp, color = CarColors.TextSecondary)
                } else {
                    val last = NotesStore.notes.firstOrNull()
                    Text(
                        text = when {
                            last == null -> "Hələ qeyd yoxdur. Mikrofona toxun."
                            last.text.isBlank() -> "🎤 Səs qeydi"
                            else -> last.text
                        },
                        fontSize = 17.sp,
                        color = if (last == null) CarColors.TextSecondary else CarColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Cəmi: ${NotesStore.notes.size} qeyd", fontSize = 14.sp, color = CarColors.TextSecondary)
                }
            }
        }
    }

    @Composable
    private fun RoundButton(icon: ImageVector, color: Color, description: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, description, tint = Color.Black, modifier = Modifier.size(34.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                note.audioPath?.let { path ->
                    val playing = AudioNotes.playingPath == path
                    IconButton(onClick = { AudioNotes.togglePlay(path) }) {
                        Icon(
                            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            if (playing) "Dayandır" else "Dinlə",
                            tint = CarColors.Accent
                        )
                    }
                }
                Text(
                    if (note.text.isBlank()) "🎤 Səs qeydi" else note.text,
                    fontSize = 16.sp, color = CarColors.TextPrimary
                )
            }
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
                    if (AudioNotes.playingPath == note.audioPath) AudioNotes.stopPlayback()
                    NotesStore.remove(note.id)
                }) {
                    Icon(Icons.Filled.Delete, "Sil", tint = CarColors.Danger)
                }
            }
        }
    }
}
