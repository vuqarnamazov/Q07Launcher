package az.q07.launcher.scanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import az.q07.launcher.MainActivity
import az.q07.launcher.data.CarApi
import az.q07.launcher.data.ObdMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * İzləmə rejimi açıq olduqca işləyən ön plan xidməti (bildirişlə).
 * Prosesi canlı saxlayır, hər 2 saniyədən bir sürəti oxuyub "Hərəkətdə / Dayanıb" vəziyyətini yeniləyir.
 * Hadisələrin özünü `DriveModeService` (xüsusi imkan xidməti) toplayır.
 */
class ScannerForegroundService : Service() {
    companion object {
        const val CHANNEL = "scanner"
        const val NOTIF_ID = 7307
        const val ACTION_STOP = "az.q07.launcher.scanner.STOP"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loop: Job? = null
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RecorderScannerManager.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "İzləmə Skaneri", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RecorderScannerManager.setActive(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        val n = build("İzləmə başladı")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIF_ID, n)
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val carOk = runCatching { CarApi.init(this) }.getOrDefault(false)
        while (scope.isActive) {
            val speed = (if (carOk) (CarApi.read(CarApi.PERF_VEHICLE_SPEED) as? Number)?.toDouble()?.times(3.6) else null)
                ?: ObdMonitor.state.value.takeIf { it.connected }?.values?.get("speed")
            RecorderScannerManager.updateMotion(speed)
            val acc = RecorderScannerManager.isAccessibilityReady(this)
            val text = if (!acc) "⚠ Xüsusi imkan xidməti aktiv deyil — hərəkətlər yazılmır"
            else "${RecorderScannerManager.motion.value.label}" +
                (speed?.let { " · ${it.toInt()} km/s" } ?: "") +
                " · sessiya: ${RecorderScannerManager.session.value.size} addım" +
                " · yazıldı: ${RecorderScannerManager.recordedThisRun.value}" +
                " · baza: ${RecordedFlowRepository.flows.value.size}"
            if (text != lastText) {
                lastText = text
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text))
            }
            delay(2000)
        }
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScannerForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Skaner İzləməsi: Aktiv")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Söndür", stop)
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        RecorderScannerManager.commitSession("xidmət dayandı")
        super.onDestroy()
    }
}
