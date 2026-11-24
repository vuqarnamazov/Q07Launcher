package az.q07.launcher.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class VehicleServiceItem(val id: String, val title: String, val subtitle: String, val phoneNumber: String)
data class CustomerProfile(val customerName: String, val customerPhone: String, val carPlate: String)

object ServiceBookingManager {
    private const val PREFS_NAME = "q07_service_booking_prefs"
    private const val KEY_SERVICES_JSON = "services_list_json"
    private const val KEY_CUST_NAME = "cust_name"
    private const val KEY_CUST_PHONE = "cust_phone"
    private const val KEY_CAR_PLATE = "car_plate"
    private const val GITHUB_SERVICES_URL = "https://raw.githubusercontent.com/vuqarnamazov/Q07Launcher/main/services.json"

    private val DEFAULT_SERVICES = listOf(
        VehicleServiceItem("oil", "Yağdəyişmə və Filtrlər", "Mühərrik və transmissiya yağının dəyişdirilməsi", "+994500000000"),
        VehicleServiceItem("tech", "Texniki Baxış", "İllik dövlət texniki baxışı və hazırlıq", "+994500000000"),
        VehicleServiceItem("diag", "Kompüter Diaqnostikası", "Hibrid sistem, batareya və elektronika yoxlanışı", "+994500000000"),
        VehicleServiceItem("brake", "Əyləc və Asqı Sistemi", "Nakladka, disk və xodovoy təmiri", "+994500000000"),
        VehicleServiceItem("ins", "Sığorta və Sənədləşmə", "İcbari və KASKO sığorta yenilənməsi", "+994500000000"),
        VehicleServiceItem("tire", "Təkər və Balans", "Təkər təmiri, balans və razval xidməti", "+994500000000")
    )

    fun getServices(context: Context): List<VehicleServiceItem> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SERVICES_JSON, null) ?: return DEFAULT_SERVICES
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                VehicleServiceItem(o.optString("id", "srv_$i"), o.optString("title"), o.optString("subtitle"), o.optString("phoneNumber"))
            }
        } catch (_: Exception) { DEFAULT_SERVICES }
    }

    fun saveServices(context: Context, list: List<VehicleServiceItem>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply { put("id", s.id); put("title", s.title); put("subtitle", s.subtitle); put("phoneNumber", s.phoneNumber) })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_SERVICES_JSON, arr.toString()).apply()
    }

    suspend fun syncServicesFromGitHub(context: Context): List<VehicleServiceItem> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$GITHUB_SERVICES_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                val remoteList = List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    VehicleServiceItem(o.optString("id", "srv_$i"), o.optString("title"), o.optString("subtitle"), o.optString("phoneNumber"))
                }
                if (remoteList.isNotEmpty()) { saveServices(context, remoteList); return@withContext remoteList }
            }
        } catch (_: Exception) { }
        return@withContext getServices(context)
    }

    fun saveCustomerProfile(context: Context, profile: CustomerProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUST_NAME, profile.customerName.trim())
            .putString(KEY_CUST_PHONE, profile.customerPhone.trim())
            .putString(KEY_CAR_PLATE, profile.carPlate.trim().uppercase())
            .apply()
    }

    fun getSavedCustomerProfile(context: Context): CustomerProfile {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CustomerProfile(p.getString(KEY_CUST_NAME, "") ?: "", p.getString(KEY_CUST_PHONE, "") ?: "", p.getString(KEY_CAR_PLATE, "") ?: "")
    }

    fun buildRequestMessage(service: VehicleServiceItem, profile: CustomerProfile): String =
        "Salam! Changan Q07 Servis Müraciəti:\n• Xidmət: ${service.title}\n• Müştəri: ${profile.customerName}\n• Mobil nömrə: ${profile.customerPhone}\n• Avtomobil nömrəsi: ${profile.carPlate}"

    fun sendViaSms(context: Context, service: VehicleServiceItem, profile: CustomerProfile) {
        saveCustomerProfile(context, profile)
        val message = buildRequestMessage(service, profile)
        val cleanPhone = service.phoneNumber.replace(" ", "")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                @Suppress("DEPRECATION")
                val sms = SmsManager.getDefault()
                sms.sendMultipartTextMessage(cleanPhone, null, sms.divideMessage(message), null, null)
                Toast.makeText(context, "SMS müraciəti göndərildi: $cleanPhone", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) { }
        }

        try {
            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "SMS tətbiqi tapılmadı: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun sendViaWhatsApp(context: Context, service: VehicleServiceItem, profile: CustomerProfile) {
        saveCustomerProfile(context, profile)
        val message = buildRequestMessage(service, profile)
        val digits = service.phoneNumber.filter { it.isDigit() }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${URLEncoder.encode(message, "UTF-8")}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(context, "WhatsApp tapılmadı, SMS-lə yoxlayın.", Toast.LENGTH_SHORT).show()
        }
    }
}
