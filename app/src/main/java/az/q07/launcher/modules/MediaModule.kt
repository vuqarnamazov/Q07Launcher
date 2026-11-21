package az.q07.launcher.modules

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import az.q07.launcher.data.QuickActions
import az.q07.launcher.media.MediaListenerService
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class NowPlaying(
    val permission: Boolean = true,
    val active: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val appLabel: String = "",
    val playing: Boolean = false,
    val art: Bitmap? = null,
    val controller: MediaController? = null
)

/** Hazırda çalan musiqi/video (istənilən tətbiqdən) + idarə düymələri */
object MediaModule : LauncherModule {
    override val id = "media"
    override val title = "İndi çalır"
    override val icon: ImageVector = Icons.Filled.MusicNote
    override val accent: Color = CarColors.Purple

    private const val ADB_CMD =
        "adb shell cmd notification allow_listener az.q07.launcher/az.q07.launcher.media.MediaListenerService"

    private fun hasPermission(context: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun read(context: Context): NowPlaying {
        if (!hasPermission(context)) return NowPlaying(permission = false)
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            val sessions = msm.getActiveSessions(ComponentName(context, MediaListenerService::class.java))
            // Çalanı üstün tut, yoxdursa ilk sessiya
            val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
                ?: return NowPlaying()
            val md = c.metadata
            val pm = context.packageManager
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
            }.getOrDefault(c.packageName)
            NowPlaying(
                active = true,
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                appLabel = label,
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                controller = c
            )
        } catch (e: SecurityException) {
            NowPlaying(permission = false)
        }
    }

    @Composable
    private fun rememberNowPlaying(): NowPlaying {
        val context = LocalContext.current
        val state by produceState(NowPlaying()) {
            while (true) {
                value = withContext(Dispatchers.Default) { read(context.applicationContext) }
                delay(1000)
            }
        }
        return state
    }

    @Composable
    override fun Summary() {
        val context = LocalContext.current
        val np = rememberNowPlaying()

        if (!np.permission) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Media görmək üçün icazə lazımdır (bir dəfəlik).",
                    fontSize = 15.sp, color = CarColors.TextSecondary
                )
                OutlinedButton(onClick = {
                    val ok = QuickActions.start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    if (!ok) QuickActions.toast(context, "Ayar yoxdur — ADB əmrini işlət (kartı aç)")
                }) { Text("İcazə ver") }
            }
            return
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CarColors.Panel),
                contentAlignment = Alignment.Center
            ) {
                val art = np.art
                if (art != null) {
                    Image(art.asImageBitmap(), null, Modifier.size(72.dp), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Filled.MusicNote, null, tint = CarColors.Purple, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (np.active) np.title.ifBlank { np.appLabel } else "Heç nə çalmır",
                    fontSize = 18.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (np.active) listOf(np.artist, np.appLabel).filter { it.isNotBlank() }.joinToString(" · ") else "",
                    fontSize = 14.sp, color = CarColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            val tc = np.controller?.transportControls
            IconButton(onClick = { tc?.skipToPrevious() }, enabled = tc != null) {
                Icon(Icons.Filled.SkipPrevious, "Əvvəlki", tint = CarColors.TextPrimary, modifier = Modifier.size(32.dp))
            }
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(CarColors.Purple),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { if (np.playing) tc?.pause() else tc?.play() }, enabled = tc != null) {
                    Icon(
                        if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (np.playing) "Pauza" else "Oynat",
                        tint = Color.Black, modifier = Modifier.size(32.dp)
                    )
                }
            }
            IconButton(onClick = { tc?.skipToNext() }, enabled = tc != null) {
                Icon(Icons.Filled.SkipNext, "Növbəti", tint = CarColors.TextPrimary, modifier = Modifier.size(32.dp))
            }
        }
    }

    @Composable
    override fun Expanded() {
        val np = rememberNowPlaying()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!np.permission) {
                Text("Ayar ekranı yoxdursa, kompüterdən bu əmri işlət:", fontSize = 14.sp, color = CarColors.TextSecondary)
                Text(ADB_CMD, fontSize = 13.sp, color = CarColors.Warning)
            } else {
                Text(
                    "Bütün media tətbiqləri (radio, Bluetooth, Yandex Music, SmartTube…) buradan idarə olunur.",
                    fontSize = 14.sp, color = CarColors.TextSecondary
                )
            }
        }
    }
}
