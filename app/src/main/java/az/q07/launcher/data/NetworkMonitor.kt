package az.q07.launcher.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

data class NetState(
    val type: String = "Yoxlanılır…",
    val connected: Boolean = false,
    val internet: Boolean = false,
    val ip: String? = null,
    val downKbps: Int? = null,
    val upKbps: Int? = null
)

object NetworkMonitor {

    fun stateFlow(context: Context): Flow<NetState> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)

        fun current(): NetState {
            val network = cm.activeNetwork ?: return NetState(type = "Bağlantı yoxdur")
            val caps = cm.getNetworkCapabilities(network)
                ?: return NetState(type = "Bağlantı yoxdur")
            val lp: LinkProperties? = cm.getLinkProperties(network)

            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobil şəbəkə"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                else -> "Digər"
            }
            val ip = lp?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?.address?.hostAddress

            return NetState(
                type = type,
                connected = true,
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                ip = ip,
                downKbps = caps.linkDownstreamBandwidthKbps,
                upKbps = caps.linkUpstreamBandwidthKbps
            )
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(current())
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                trySend(current())
            }
        }

        trySend(current())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
