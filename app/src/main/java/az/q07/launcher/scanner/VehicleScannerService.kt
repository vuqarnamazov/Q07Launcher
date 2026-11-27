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
import az.q07.launcher.ui.VehicleScannerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fon İzləməsi: ön plan xidməti (Android 11 — foregroundServiceType="dataSync").
 * Prosesi canlı saxlayır, sürəti oxuyub Hərəkətdə/Dayanıb vəziyyətini yeniləyir; hadisələri `DriveModeService` toplayır.
 */
class VehicleScannerService : Service() {
    companion object {
        const val CHANNEL = "full_scanner"
        const val NOTIF_ID = 7308
        const val ACTION_STOP = "az.q07.launcher.fullscanner.STOP"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loop: Job? = null
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FullVehicleScannerManager.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Tam Skaner — fon izləməsi", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FullVehicleScannerManager.setMode(this, FullVehicleScannerManager.Mode.OFF)
            stopSelf(); return START_NOT_STICKY
        }
        val n = build("Fon izləməsi başladı")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        while (scope.isActive) {
            val speed = FullVehicleScannerManager.readSpeed(this)
            FullVehicleScannerManager.updateMotion(speed)
            val m = FullVehicleScannerManager
            val r = VehicleStructureRepository
            val k = SystemKnowledgeReport.refresh(this)
            val text = if (!m.isAccessibilityReady(this)) "⚠ Xüsusi imkan xidməti aktiv deyil — heç nə öyrənilmir"
            else "${m.motion.value.label}${speed?.let { " · ${it.toInt()} km/s" } ?: ""}" +
                " · ${r.screens.value.size} ekran · ${r.edges.value.size} yol · ${r.flows.value.size} gedişat" +
                " · bilik ${k.knownButtons}/${k.buttons} düymə, çatışmır ${k.missing}"
            if (text != lastText) {
                lastText = text
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text))
            }
            delay(2000)
        }
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VehicleScannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VehicleScannerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Skaner: Fon İzləməsi")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Söndür", stop)
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        FullVehicleScannerManager.commitSession("xidmət dayandı")
        VehicleStructureRepository.flushNow()
        super.onDestroy()
    }
}
