package com.stsmn.monitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.URL
import java.net.URLConnection

/**
 * The ESP32 access point has no internet, so Android may route traffic over mobile
 * data instead of Wi-Fi. This class tracks the Wi-Fi network and opens connections
 * through it explicitly, so requests to 192.168.4.1 always use the ESP32's Wi-Fi.
 */
class WifiNetworkBinder(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile private var wifi: Network? = null
    @Volatile private var registered = false

    val isBound: Boolean get() = wifi != null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wifi = network }
        override fun onLost(network: Network) { if (wifi == network) wifi = null }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            // Fall back to the default network; polling still works when Wi-Fi is default.
        }
    }

    fun stop() {
        if (!registered) return
        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { /* already gone */ }
        registered = false
        wifi = null
    }

    fun openConnection(url: URL): URLConnection {
        val network = wifi
        return if (network != null) network.openConnection(url) else url.openConnection()
    }
}
