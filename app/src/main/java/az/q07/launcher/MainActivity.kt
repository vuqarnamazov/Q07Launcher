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
        applyExtras(intent)
        super.onCreate(savedInstanceState)

        AppSettings.init(applicationContext)
        NotesStore.init(applicationContext)
        LauncherPrefs.init(applicationContext)
        DualNetworkManager.init(applicationContext)
        WidgetStore.init(applicationContext)
        az.q07.launcher.data.SelfDiag.init(applicationContext)
        az.q07.launcher.data.SelfDiag.ensureAccessibility(applicationContext)
        az.q07.launcher.data.ObdMonitor.ensureRunning()
        az.q07.launcher.data.VehicleTelemetryReader.start(applicationContext)
        ReminderScheduler.createChannel(applicationContext)
        // Tam Skaner: bazalar + Fon İzləməsi əvvəl açıq idisə xidməti bərpa et
        az.q07.launcher.scanner.FullVehicleScannerManager.restoreIfNeeded(applicationContext)

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

        // Sorğu kodu jurnala — sahib maşını licenses.json-a əlavə etmək üçün adb ilə oxuya bilsin
        runCatching {
            az.q07.launcher.data.SelfDiag.log("license", "sorğu kodu",
                mapOf("kod" to az.q07.launcher.data.GitHubLicenseAndOtaManager.getVehicleRequestCode(applicationContext)))
        }
        setContent {
            CarLauncherTheme {
                val lic = az.q07.launcher.data.GitHubLicenseAndOtaManager
                var activated by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(lic.isLocallyActivated(applicationContext))
                }
                var ota by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<az.q07.launcher.data.OtaUpdateInfo?>(null)
                }
                // Aktiv launcher bir daha yoxlanılmır/bloklanmır (kod yalnız ilk aktivləşdirmədə lazımdır).
                // Yalnız OTA yeniləmə yoxlanılır.
                androidx.compose.runtime.LaunchedEffect(activated) {
                    if (activated) ota = lic.checkForOtaUpdate(az.q07.launcher.BuildConfig.VERSION_CODE)
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
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        applyExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.visible = true
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
