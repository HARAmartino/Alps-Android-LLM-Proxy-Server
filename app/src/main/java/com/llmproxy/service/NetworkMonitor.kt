package com.llmproxy.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.llmproxy.model.NetworkState
import com.llmproxy.model.NetworkType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class NetworkMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val ddnsUpdateTrigger: DdnsUpdateTrigger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val updateMutex = Mutex()
    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var isRegistered = false
    private var lastObservedNetworkHandle: Long? = null
    private var lastPublicIpCheckAtMs: Long = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshState()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshState()

        override fun onLost(network: Network) = refreshState()

        override fun onUnavailable() = refreshState()
    }

    fun start() {
        if (isRegistered) return
        // Lifecycle-aware registration: service start owns callback registration.
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        isRegistered = true
        refreshState()
    }

    fun stop() {
        if (!isRegistered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        isRegistered = false
    }

    private fun refreshState() {
        scope.launch {
            updateMutex.withLock {
                updateStateLocked()
            }
        }
    }

    private suspend fun updateStateLocked() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val previousState = _networkState.value

        val nextType = when {
            capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                NetworkType.OFFLINE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            else -> NetworkType.MOBILE
        }

        if (nextType == NetworkType.OFFLINE) {
            _networkState.value = NetworkState(type = NetworkType.OFFLINE, ip = null)
            lastObservedNetworkHandle = null
            if (previousState.type != NetworkType.OFFLINE) {
                ddnsUpdateTrigger.onNetworkLost()
            }
            return
        }

        if (nextType == NetworkType.WIFI) {
            val now = System.currentTimeMillis()
            val currentHandle = activeNetwork?.networkHandle
            val shouldRefreshPublicIp =
                currentHandle != lastObservedNetworkHandle ||
                    now - lastPublicIpCheckAtMs >= PUBLIC_IP_REFRESH_INTERVAL_MS ||
                    previousState.ip.isNullOrBlank()
            val publicIp = if (shouldRefreshPublicIp) {
                lastPublicIpCheckAtMs = now
                fetchPublicIp()
            } else {
                previousState.ip
            }
            lastObservedNetworkHandle = currentHandle
            _networkState.value = NetworkState(type = NetworkType.WIFI, ip = publicIp)
            if (!publicIp.isNullOrBlank() && publicIp != previousState.ip) {
                // DDNS should only be triggered when the observed public IP actually changes.
                ddnsUpdateTrigger.onIpChanged(publicIp)
            }
            return
        }

        lastObservedNetworkHandle = activeNetwork?.networkHandle
        _networkState.value = NetworkState(type = NetworkType.MOBILE, ip = null)
    }

    private suspend fun fetchPublicIp(): String? = withContext(ioDispatcher) {
        var connection: HttpURLConnection? = null
        runCatching {
            connection = URL(PUBLIC_IP_ENDPOINT).openConnection() as HttpURLConnection
            connection?.connectTimeout = 3_000
            connection?.readTimeout = 3_000
            connection?.requestMethod = "GET"
            connection?.inputStream?.bufferedReader()?.use { reader ->
                val response = reader.readText().trim()
                response.takeIf { it.isNotBlank() }
            }
        }.getOrNull().also {
            connection?.disconnect()
        }
    }

    private companion object {
        private const val PUBLIC_IP_ENDPOINT = "https://checkip.amazonaws.com"
        private const val PUBLIC_IP_REFRESH_INTERVAL_MS = 15_000L
    }
}
