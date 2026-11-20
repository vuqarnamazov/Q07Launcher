package az.q07.launcher.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat

object ReminderScheduler {
    const val CHANNEL_ID = "q07_reminders"
    const val EXTRA_ID = "note_id"
    const val EXTRA_TEXT = "note_text"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Xatırlatmalar", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun schedule(context: Context, note: Note, delayMs: Long) {
        val at = System.currentTimeMillis() + delayMs
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, note))
        NotesStore.setReminder(note.id, at)
    }

    fun cancel(context: Context, note: Note) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pendingIntent(context, note))
        NotesStore.setReminder(note.id, null)
    }

    private fun pendingIntent(context: Context, note: Note): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_ID, note.id)
            .putExtra(EXTRA_TEXT, note.text)
        return PendingIntent.getBroadcast(
            context,
            note.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Xatırlatma vaxtı çatanda bildiriş + toast göstərir */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0L)
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT) ?: return

        ReminderScheduler.createChannel(context)
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Xatırlatma")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        context.getSystemService(android.app.NotificationManager::class.java)
            .notify(id.toInt(), notification)

        Toast.makeText(context, "⏰ $text", Toast.LENGTH_LONG).show()

        NotesStore.init(context.applicationContext)
        NotesStore.setReminder(id, null)
    }
}
