package az.q07.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Launcher-in Ayarlar bölməsindən ADB-ni açmaq/söndürmək üçün köməkçi.
 *
 * WRITE_SECURE_SETTINGS verilibsə sistem ayarını birbaşa yazır; verilməyibsə heç nəyi
 * sındırmadan «Developer seçimləri» ekranını açır və IP ünvanını göstərir ki,
 * istifadəçi əl ilə açsın.
 *
 * «Həmişə aktiv» rejimi yadda saxlanılır: launcher hər açılanda və xidmət işləyərkən
 * ADB sönübsə yenidən yandırılır (reboot-dan sonra da).
 */
object AdbControl {
    private const val ADB_ENABLED = "adb_enabled"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val PREFS = "q07_adb"
    private const val KEY_ALWAYS = "always_on"
    const val PORT = 5555

    fun hasSecureWrite(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    /**
     * ADB-ni açır və ya söndürür.
     * @param on true — aktiv et, false — söndür
     * @return true — vəziyyət istənilən kimi oldu (icazə var idi), false — əl ilə edilməlidir.
     */
    fun setEnabled(context: Context, on: Boolean): Boolean {
        var written = false
        if (hasSecureWrite(context)) {
            written = runCatching {
                Settings.Global.putInt(context.contentResolver, ADB_ENABLED, if (on) 1 else 0)
                // Android 11+ simsiz ADB bayrağı; köhnə sistemdə yalnız USB ADB işləyir
                Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, if (on) 1 else 0)
                isEnabled(context) == on
            }.getOrDefault(false)
        }
        // Root varsa şəbəkə üzərindən ADB portunu da açırıq; root yoxdursa sakit keçirik
        if (on) runCatching {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "setprop service.adb.tcp.port $PORT; stop adbd; start adbd")
            )
        }
        return written
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** «Həmişə aktiv» rejimi açıqdırmı */
    fun isAlways(context: Context): Boolean = prefs(context).getBoolean(KEY_ALWAYS, false)

    fun setAlways(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALWAYS, on).apply()
        if (on) applyAlways(context)
    }

    /**
     * «Həmişə aktiv» açıqdırsa və ADB sönübsə — yenidən yandırır.
     * Launcher açılanda və xidmət işləyərkən dövri çağırılır.
     * @return true — ADB bu çağırışda yenidən yandırıldı
     */
    fun applyAlways(context: Context): Boolean {
        if (!isAlways(context)) return false
        if (isEnabled(context)) return false
        val ok = setEnabled(context, true)
        SelfDiag.log("adb", if (ok) "«həmişə aktiv»: ADB yenidən yandırıldı" else "«həmişə aktiv»: ADB yandırıla bilmədi")
        return ok
    }

    /** Wi-Fi şəbəkə ünvanları — «adb connect IP:5555» üçün */
    fun ipv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { n -> n.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    fun openDeveloperSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
