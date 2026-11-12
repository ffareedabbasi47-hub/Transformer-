package com.stsmn.monitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.URL
import java.net.URLConnection

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

        }
    }

    fun stop() {
        if (!registered) return
        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) {  }
        registered = false
        wifi = null
    }

    fun openConnection(url: URL): URLConnection {
        val network = wifi
        return if (network != null) network.openConnection(url) else url.openConnection()
    }
}
