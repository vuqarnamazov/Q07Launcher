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
import az.q07.launcher.data.LauncherPrefs
import az.q07.launcher.data.NotesStore
import az.q07.launcher.data.ReminderScheduler
import az.q07.launcher.data.WidgetStore
import az.q07.launcher.ui.LauncherScreen
import az.q07.launcher.ui.theme.CarLauncherTheme

class MainActivity : ComponentActivity() {

    // Tətbiq quraşdırılanda/silinəndə artır → tətbiq siyahısı yenilənir
    private var appsVersion by mutableIntStateOf(0)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            appsVersion++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppSettings.init(applicationContext)
        NotesStore.init(applicationContext)
        LauncherPrefs.init(applicationContext)
        WidgetStore.init(applicationContext)
        ReminderScheduler.createChannel(applicationContext)

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

        setContent {
            CarLauncherTheme {
                LauncherScreen(appsVersion = appsVersion)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { WidgetStore.host.startListening() }
    }

    override fun onStop() {
        runCatching { WidgetStore.host.stopListening() }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }
}
