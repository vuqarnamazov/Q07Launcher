package az.q07.scanner.sys

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sistemdə tapılan bir yol: xidmət, activity/servis/receiver, Settings açarı və ya sistem xassəsi */
data class DiscoveredVehicleNode(
    val category: String,      // SYSTEM_SERVICE, ACTIVITY, SERVICE, RECEIVER, SETTINGS_KEY, PROPERTY
    val title: String,
    val pathOrKey: String,     // paket/sinif, CEDVEL:açar və ya xassə adı
    val currentValue: String,
    val executableType: String // ACTIVITY, SETTINGS, INFO_ONLY
)

/**
 * Tam sistem skanı (istifadəçinin paketi əsasında, Q07 Skaner-ə uyğunlaşdırılıb):
 * Binder xidmətləri, Changan/Incall/Qiyuan paketlərinin gizli komponentləri, maşınla bağlı Settings açarları, getprop.
 */
object VehicleSystemScanner {

    private val pkgKeywords = listOf("changan", "qiyuan", "incall", "sda", "vehicle", "car", "hvac", "setting", "cluster", "avm", "apa", "quectel", "evs", "adas", "soa")
    private val settingKeywords = listOf("car", "drive", "mode", "steer", "brake", "susp", "energy", "recov", "hvac", "ac_", "seat", "light",
        "changan", "qiyuan", "ambient", "hud", "door", "window", "mirror", "vehicle", "ev_", "charge", "battery", "regen", "incall", "ca_")
    private val propKeywords = listOf("changan", "qiyuan", "vin", "car", "vehicle", "mcu", "can", "sda", "incall", "vendor.ca", "persist.ca", "soa", "tbox", "hw")

    fun runFullScan(context: Context): List<DiscoveredVehicleNode> = buildList {
        addAll(scanSystemServices())
        addAll(scanPackages(context))
        addAll(scanSettingsKeys(context))
        addAll(scanSystemProperties())
    }

    // 1. Binder xidmətləri — əvvəl gizli API, alınmasa "service list" əmri
    @SuppressLint("PrivateApi")
    private fun scanSystemServices(): List<DiscoveredVehicleNode> {
        val names: List<String> = runCatching {
            @Suppress("UNCHECKED_CAST")
            (Class.forName("android.os.ServiceManager").getMethod("listServices").invoke(null) as Array<String>).toList()
        }.getOrNull() ?: runCatching {
            val p = ProcessBuilder("service", "list").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readLines().mapNotNull { l -> Regex("^\\d+\\s+([^:]+):").find(l)?.groupValues?.get(1)?.trim() }
        }.getOrDefault(emptyList())
        if (names.isEmpty()) return listOf(DiscoveredVehicleNode("SYSTEM_SERVICE", "Binder siyahısı alınmadı (sistem icazə vermir)", "-", "", "INFO_ONLY"))
        return names.sorted().map { srv ->
            val carRelated = listOf("car", "can", "vehicle", "changan", "qiyuan", "mcu", "hvac", "body", "drive", "audio", "tbox", "incall", "soa", "evs")
                .any { srv.lowercase().contains(it) }
            DiscoveredVehicleNode("SYSTEM_SERVICE", "Binder: $srv", srv, if (carRelated) "maşınla bağlı" else "", "INFO_ONLY")
        }
    }

    // 2. Zavod paketlərinin BÜTÜN activity / servis / receiver-ləri (gizli olanlar daxil)
    @Suppress("DEPRECATION")
    private fun scanPackages(context: Context): List<DiscoveredVehicleNode> {
        val pm = context.packageManager
        val list = mutableListOf<DiscoveredVehicleNode>()
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
        for (pi in pm.getInstalledPackages(flags)) {
            val pkg = pi.packageName
            if (pkgKeywords.none { pkg.lowercase().contains(it) }) continue
            val short = pkg.substringAfterLast(".")
            pi.activities?.forEach { a ->
                list += DiscoveredVehicleNode("ACTIVITY", "${a.name.substringAfterLast(".")} ($short)", "$pkg/${a.name}",
                    if (a.exported) "Exported — açıla bilər" else "Daxili", if (a.exported) "ACTIVITY" else "INFO_ONLY")
            }
            pi.services?.forEach { s ->
                list += DiscoveredVehicleNode("SERVICE", "${s.name.substringAfterLast(".")} ($short)", "$pkg/${s.name}", if (s.exported) "Exported" else "Daxili", "INFO_ONLY")
            }
            pi.receivers?.forEach { r ->
                list += DiscoveredVehicleNode("RECEIVER", "${r.name.substringAfterLast(".")} ($short)", "$pkg/${r.name}", if (r.exported) "Exported" else "Daxili", "INFO_ONLY")
            }
        }
        return list
    }

    // 3. Settings.Global / System / Secure — maşın parametrləri
    private fun scanSettingsKeys(context: Context): List<DiscoveredVehicleNode> {
        val list = mutableListOf<DiscoveredVehicleNode>()
        val tables = listOf("GLOBAL" to Settings.Global.CONTENT_URI, "SYSTEM" to Settings.System.CONTENT_URI, "SECURE" to Settings.Secure.CONTENT_URI)
        for ((code, uri) in tables) runCatching {
            context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    if (settingKeywords.none { name.lowercase().contains(it) }) continue
                    list += DiscoveredVehicleNode("SETTINGS_KEY", "${code.lowercase()}: $name", "$code:$name", c.getString(1) ?: "", "SETTINGS")
                }
            }
        }
        return list
    }

    // 4. getprop
    private fun scanSystemProperties(): List<DiscoveredVehicleNode> = runCatching {
        val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readLines().mapNotNull { line ->
            if (propKeywords.none { line.lowercase().contains(it) }) return@mapNotNull null
            val parts = line.split("]: [")
            if (parts.size != 2) null else {
                val key = parts[0].removePrefix("[")
                DiscoveredVehicleNode("PROPERTY", key, key, parts[1].removeSuffix("]"), "INFO_ONLY")
            }
        }
    }.getOrDefault(emptyList())

    /** Activity-ni açır */
    fun openActivity(context: Context, node: DiscoveredVehicleNode): String = try {
        val (pkg, cls) = node.pathOrKey.split("/", limit = 2)
        context.startActivity(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Açıldı: ${cls.substringAfterLast(".")}"
    } catch (e: Exception) {
        "Açılmadı: ${e.javaClass.simpleName} — ${e.message?.take(120)}"
    }

    /** Settings açarına istifadəçinin təsdiqlədiyi dəyəri yazır (köhnə dəyər jurnala düşür) */
    fun writeSetting(context: Context, node: DiscoveredVehicleNode, value: String): String = try {
        val (table, key) = node.pathOrKey.split(":", limit = 2)
        val ok = when (table) {
            "GLOBAL" -> Settings.Global.putString(context.contentResolver, key, value)
            "SYSTEM" -> Settings.System.putString(context.contentResolver, key, value)
            else -> Settings.Secure.putString(context.contentResolver, key, value)
        }
        az.q07.scanner.rec.TamQeyd.write("sistemskani", JSONObject().put("hadise", "parametr yazıldı").put("acar", node.pathOrKey)
            .put("kohne", node.currentValue).put("yeni", value).put("ugurlu", ok))
        if (ok) "Yazıldı: $key = $value (əvvəl: ${node.currentValue})" else "Sistem qəbul etmədi: $key"
    } catch (e: Exception) {
        "Yazılmadı (icazə yoxdur?): ${e.javaClass.simpleName} — ${e.message?.take(120)}"
    }

    fun exportAsJson(nodes: List<DiscoveredVehicleNode>): String = JSONArray().apply {
        nodes.forEach { n -> put(JSONObject().put("category", n.category).put("title", n.title).put("path", n.pathOrKey).put("value", n.currentValue).put("type", n.executableType)) }
    }.toString(2)

    /** Nəticəni tam qeyd qovluğuna da yazır — PC-yə Q07_qeyd_cek.bat ilə çəkilir */
    fun saveExport(context: Context, nodes: List<DiscoveredVehicleNode>): File? = runCatching {
        val dir = File(az.q07.scanner.rec.TamQeyd.storageRoot(context), "export").apply { mkdirs() }
        File(dir, "sistem_skani.json").apply { writeText(exportAsJson(nodes)) }
    }.getOrNull()
}
