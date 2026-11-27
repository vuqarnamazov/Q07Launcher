package az.q07.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import az.q07.launcher.data.AppSettings
import az.q07.launcher.data.AppVisibility
import az.q07.launcher.data.DualNetworkManager
import az.q07.launcher.data.LauncherPrefs
import az.q07.launcher.data.NotesStore
import az.q07.launcher.data.ReminderScheduler
import az.q07.launcher.data.WidgetStore
import az.q07.launcher.ui.LauncherScreen
import az.q07.launcher.ui.theme.CarLauncherTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Tətbiq quraşdırılanda/silinəndə artır → tətbiq siyahısı yenilənir
    private var appsVersion by mutableIntStateOf(0)

    // Hər dəfə launcher-ə qayıdanda artır → son açılan tətbiqin slotu işıqlanır
    private var resumeTick by mutableIntStateOf(0)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            appsVersion++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Başqa maşından götürülmüş default ayarlar — hər şeydən ƏVVƏL (prefs hələ açılmayıb)
        runCatching { az.q07.launcher.data.SettingsSnapshot.seedIfNeeded(applicationContext) }
        // Jurnal hər şeydən əvvəl işə düşməlidir ki, adb ilə göndərilən aktivasiya da yazılsın
        az.q07.launcher.data.SelfDiag.init(applicationContext)
        applyExtras(intent)
        // telefon–maşın fayl kanalı: launcher açılanda işə düşür (PIN yaranır)
        runCatching { az.q07.launcher.data.PhoneCarTransferServer.start(applicationContext) }

        AppSettings.init(applicationContext)
        NotesStore.init(applicationContext)
        LauncherPrefs.init(applicationContext)
        DualNetworkManager.init(applicationContext)
        WidgetStore.init(applicationContext)
        az.q07.launcher.data.SelfDiag.ensureAccessibility(applicationContext)
        // «Həmişə aktiv» ADB rejimi: launcher hər açılanda yoxlanılır
        runCatching { az.q07.launcher.data.AdbControl.applyAlways(applicationContext) }
        az.q07.launcher.data.ObdMonitor.ensureRunning()
        az.q07.launcher.data.VehicleTelemetryReader.start(applicationContext)
        ReminderScheduler.createChannel(applicationContext)
        // Köhnə tt/skan məlumatları (yollar, ekran xəritəsi, bağlamalar) birdəfəlik silinir
        runCatching { az.q07.launcher.data.LegacyCleanup.runIfNeeded(applicationContext) }

        // Geri düyməsi launcher-dən çıxmasın (onBackPressed-in müasir əvəzi)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Heç nə etmirik — launcher ekranda qalır
            }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )

        // Cihaz kodu jurnala — sahib adb ilə oxuyub aktivasiya kodu yarada bilsin
        az.q07.launcher.data.LicenseManager.logDeviceCode(applicationContext)

        // Lisenziya: aktivasiya olmadan launcher açılmır, yalnız kilid ekranı görünür.
        setContent {
            CarLauncherTheme {
                var activated by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(
                        az.q07.launcher.data.LicenseManager.isActivated(applicationContext)
                    )
                }
                var ota by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<az.q07.launcher.data.OtaUpdateInfo?>(null)
                }
                androidx.compose.runtime.LaunchedEffect(activated) {
                    if (activated) ota = az.q07.launcher.data.GitHubLicenseAndOtaManager
                        .checkForOtaUpdate(az.q07.launcher.BuildConfig.VERSION_CODE)
                }
                if (activated) {
                    LauncherScreen(appsVersion = appsVersion, resumeTick = resumeTick)
                    ota?.let { info -> az.q07.launcher.ui.OtaUpdateBanner(info, onDismiss = { ota = null }) }
                } else {
                    az.q07.launcher.ui.ActivationLockScreen(onUnlocked = { activated = true })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    /** adb ilə: am start -n az.q07.launcher/.MainActivity --es q07_plate "90-AA-000" (nömrə repoda saxlanmır) */
    private fun applyExtras(i: android.content.Intent?) {
        i?.getStringExtra("q07_plate")?.let { az.q07.launcher.data.CarLook.setPlate(applicationContext, it) }
        // adb ilə launcher ayarlarını ixrac etmək üçün
        if (i?.hasExtra("q07_export_settings") == true) {
            az.q07.launcher.data.SettingsSnapshot.exportAndToast(applicationContext)
        }
        // adb ilə aktivasiya: am start -n az.q07.launcher/.MainActivity --es q07_license "<kod>"
        i?.getStringExtra("q07_license")?.let {
            runCatching { az.q07.launcher.data.LicenseManager.activate(applicationContext, it) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        applyExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.visible = true
        az.q07.launcher.data.DriveModeService.markShown(applicationContext)
        az.q07.launcher.data.SelfDiag.ensureAccessibility(applicationContext)
        runCatching { WidgetStore.host.startListening() }
    }

    override fun onStop() {
        AppVisibility.visible = false
        runCatching { WidgetStore.host.stopListening() }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }
}
