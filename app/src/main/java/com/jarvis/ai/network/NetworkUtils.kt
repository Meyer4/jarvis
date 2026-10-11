package com.jarvis.ai.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Checks whether Android currently has a validated network with internet access. */
class NetworkUtils(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * True when a network claims internet access even if Android has not validated it yet. Used so a
     * self-hosted model on the same Wi-Fi network can still be reached without an internet route.
     */
    fun hasNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
