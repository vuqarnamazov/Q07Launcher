package az.q07.launcher.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Səs qeydlərini mikrofonla yazır və oxudur.
 * Google səs tanıması olmayan head unit-də də işləyir.
 */
object AudioNotes {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    var isRecording by mutableStateOf(false)
        private set
    var recordingStartedAt by mutableStateOf(0L)
        private set
    var playingPath by mutableStateOf<String?>(null)
        private set

    fun start(context: Context): Boolean {
        if (isRecording) return true
        stopPlayback()
        val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.m4a")
        return try {
            @Suppress("DEPRECATION")
            val r = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            currentFile = file
            isRecording = true
            recordingStartedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            file.delete()
            false
        }
    }

    /** Yazını dayandırır və faylın yolunu qaytarır (çox qısadırsa null) */
    fun stop(): String? {
        val r = recorder ?: return null
        val file = currentFile
        val duration = System.currentTimeMillis() - recordingStartedAt
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        isRecording = false
        currentFile = null
        if (!ok || file == null || duration < 700) {
            file?.delete()
            return null
        }
        return file.absolutePath
    }

    fun togglePlay(path: String) {
        if (playingPath == path) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playingPath = path
        } catch (e: Exception) {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        playingPath = null
    }
}
