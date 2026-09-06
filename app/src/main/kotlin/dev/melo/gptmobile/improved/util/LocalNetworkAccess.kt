package dev.melo.gptmobile.improved.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI

object LocalNetworkAccess {
    fun isConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isLoopbackOrLocalAddress(host: String): Boolean {
        return try {
            val address = InetAddress.getByName(host)
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    fun isLocalOrLanEndpoint(endpoint: String): Boolean {
        return try {
            val uri = URI(endpoint)
            val host = uri.host ?: return false
            if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") {
                return true
            }
            isLoopbackOrLocalAddress(host)
        } catch (_: Exception) {
            false
        }
    }
}
