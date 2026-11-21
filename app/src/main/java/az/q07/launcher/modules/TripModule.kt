package az.q07.launcher.modules

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import az.q07.launcher.ui.theme.CarColors

/** GPS səfər kompüteri: sürət, istiqamət (kompas), hündürlük, səfər məsafəsi və vaxtı */
object TripModule : LauncherModule {
    override val id = "trip"
    override val title = "Səfər (GPS)"
    override val shortTitle = "Səfər"
    override val icon: ImageVector = Icons.Filled.Explore
    override val accent: Color = CarColors.Green

    private var speedKmh by mutableDoubleStateOf(0.0)
    private var bearing by mutableStateOf<Float?>(null)
    private var altitude by mutableStateOf<Double?>(null)
    private var tripMeters by mutableDoubleStateOf(0.0)
    private var tripStart by mutableStateOf(System.currentTimeMillis())
    private var maxKmh by mutableDoubleStateOf(0.0)
    private var hasFix by mutableStateOf(false)
    private var users by mutableIntStateOf(0)
    private var last: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            hasFix = true
            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
            if (speedKmh > maxKmh) maxKmh = speedKmh
            if (loc.hasBearing() && speedKmh > 3) bearing = loc.bearing
            if (loc.hasAltitude()) altitude = loc.altitude
            last?.let { prev ->
                val d = prev.distanceTo(loc)
                // GPS "titrəməsini" sayma
                if (d > 3 && loc.accuracy < 40) tripMeters += d
            }
            last = loc
        }

        @Deprecated("Android 11 üçün lazımdır")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { hasFix = false }
    }

    private fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun start(c: Context) {
        if (!hasPermission(c)) return
        val lm = c.getSystemService(LocationManager::class.java)
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        }
    }

    private fun stop(c: Context) {
        runCatching { c.getSystemService(LocationManager::class.java).removeUpdates(listener) }
    }

    private fun direction(b: Float?): String {
        if (b == null) return "—"
        val names = listOf("Şm", "ŞmŞ", "Ş", "CŞ", "C", "CQ", "Q", "ŞmQ")
        return names[((b + 22.5f) / 45f).toInt() % 8] + " ${Math.round(b)}°"
    }

    @Composable
    private fun Tracking() {
        val context = LocalContext.current.applicationContext
        DisposableEffect(Unit) {
            users++
            if (users == 1) start(context)
            onDispose {
                users--
                if (users == 0) stop(context)
            }
        }
    }

    @Composable
    override fun Summary() {
        val context = LocalContext.current
        var granted by androidx.compose.runtime.remember { mutableStateOf(hasPermission(context)) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            if (it) start(context.applicationContext)
        }
        if (!granted) {
            OutlinedButton(onClick = { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                Text("GPS icazəsi ver")
            }
            return
        }
        Tracking()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Sürət", if (hasFix) Math.round(speedKmh).toString() else "—", "km/s", Modifier.weight(1f))
            Stat("İstiqamət", direction(bearing), "", Modifier.weight(1f))
            Stat("Səfər", "%.1f".format(tripMeters / 1000), "km", Modifier.weight(1f))
        }
    }

    @Composable
    override fun Expanded() {
        val mins = (System.currentTimeMillis() - tripStart) / 60_000
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Hündürlük", altitude?.let { Math.round(it).toString() } ?: "—", "m", Modifier.weight(1f))
                Stat("Maks. sürət", Math.round(maxKmh).toString(), "km/s", Modifier.weight(1f))
                Stat("Vaxt", "${mins / 60}:%02d".format(mins % 60), "", Modifier.weight(1f))
            }
            OutlinedButton(onClick = {
                tripMeters = 0.0; maxKmh = 0.0; tripStart = System.currentTimeMillis()
            }) { Text("Səfəri sıfırla") }
        }
    }

    @Composable
    private fun Stat(label: String, value: String, unit: String, modifier: Modifier) {
        Column(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(CarColors.Panel)
                .padding(12.dp)
        ) {
            Text(label, fontSize = 13.sp, color = CarColors.TextSecondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = CarColors.TextPrimary)
                if (unit.isNotEmpty()) Text(" $unit", fontSize = 13.sp, color = CarColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}
