package com.llmproxy.service

import android.content.Context
import com.llmproxy.client.tunneling.TunnelSession
import com.llmproxy.client.tunneling.TunnelingClient
import com.llmproxy.client.tunneling.TunnelingException
import com.llmproxy.data.SettingsRepository
import com.llmproxy.model.NetworkType
import com.llmproxy.model.ServerConfig
import com.llmproxy.model.ServerRuntimeState
import com.llmproxy.model.ServerStatus
import com.llmproxy.model.TunnelStatus
import com.llmproxy.server.ProxyServerFactory
import com.llmproxy.server.SslContextLoader
import com.llmproxy.util.Logger
import com.llmproxy.util.NetworkUtils
import com.llmproxy.util.SslCertGenerator
import io.ktor.client.HttpClient
import io.ktor.server.engine.ApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

class ServerLifecycleManager(
    private val context: Context,
    private val applicationScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val sslCertGenerator: SslCertGenerator,
    private val sslContextLoader: SslContextLoader,
    private val upstreamClient: HttpClient,
    private val tunnelingClient: TunnelingClient? = null,
    private val networkMonitor: NetworkMonitor? = null,
) {
    private val lifecycleMutex = Mutex()
    private val activeConnections = MutableStateFlow(0)
    private val _runtimeState = MutableStateFlow(ServerRuntimeState())
    val runtimeState: StateFlow<ServerRuntimeState> = _runtimeState.asStateFlow()

    private var serverEngine: ApplicationEngine? = null
    // Holds the active tunnel session when in tunneling mode; null otherwise.
    private var activeTunnelSession: TunnelSession? = null
    private var lastKnownNetworkType: NetworkType = NetworkType.OFFLINE
    private var tunnelReconnectJob: Job? = null
    private val latencySamples = ArrayDeque<LatencySample>()
    private val latencyLock = Any()
    private val proxyServerFactory: ProxyServerFactory by lazy {
        ProxyServerFactory(upstreamClient = upstreamClient, sslContextLoader = sslContextLoader)
    }

    init {
        applicationScope.launch {
            activeConnections.collect { count ->
                _runtimeState.update { it.copy(activeConnections = count) }
            }
        }
        networkMonitor?.let { monitor ->
            applicationScope.launch {
                monitor.networkState
                    .map { it.type }
                    .distinctUntilChanged()
                    .collect { type ->
                        onNetworkTypeChanged(type)
                    }
            }
        }
    }

    suspend fun startServer() {
        lifecycleMutex.withLock {
            if (serverEngine != null) return

            val config = settingsRepository.serverConfig.value
            if (!config.isReady) {
                _runtimeState.value = ServerRuntimeState(
                    status = ServerStatus.Error,
                    activeConnections = activeConnections.value,
                    localEndpoint = NetworkUtils.formatLocalEndpoint(context, config.bindAddress, config.listenPort),
                    lastError = "Configure an upstream URL and API key before starting the server.",
                )
                return
            }

            // In tunneling mode, override the bind address to loopback so the server is
            // not exposed directly on the network interface – ngrok will front it instead.
            val isTunneling = config.networkMode == ServerConfig.NETWORK_MODE_TUNNELING
            val effectiveBindAddress = if (isTunneling) ServerConfig.TUNNELING_BIND_ADDRESS else config.bindAddress
            val effectiveConfig = config.copy(bindAddress = effectiveBindAddress)

            _runtimeState.value = _runtimeState.value.copy(
                status = ServerStatus.Starting,
                localEndpoint = NetworkUtils.formatLocalEndpoint(context, effectiveBindAddress, config.listenPort),
                lastError = null,
                tunnelStatus = if (isTunneling) TunnelStatus.Connecting else TunnelStatus.Idle,
                tunnelPublicUrl = null,
                tunnelSessionExpiresAt = null,
                showManualTunnelReconnect = false,
            )

            try {
                sslCertGenerator.ensureCertificateFiles()
                serverEngine = proxyServerFactory.create(
                    config = effectiveConfig,
                    activeConnections = activeConnections,
                    onRequestLatencyMeasured = ::recordLatencySample,
                ).also { engine -> engine.start(wait = false) }
                _runtimeState.value = _runtimeState.value.copy(status = ServerStatus.Running)

                // After the server is up, create the ngrok tunnel if in tunneling mode.
                if (isTunneling) {
                    val created = startTunnelLocked(config)
                    if (!created) {
                        scheduleTunnelReconnectLocked(reason = "initial tunnel setup failed")
                    }
                }
            } catch (error: Exception) {
                Logger.e("ServerLifecycleManager", "Failed to start server", error)
                serverEngine = null
                _runtimeState.value = _runtimeState.value.copy(
                    status = ServerStatus.Error,
                    lastError = error.message,
                    tunnelStatus = TunnelStatus.Idle,
                    tunnelSessionExpiresAt = null,
                    showManualTunnelReconnect = false,
                )
            }
        }
    }

    suspend fun stopServer() {
        lifecycleMutex.withLock {
            // Close the tunnel before stopping the engine so ngrok can clean up.
            tunnelReconnectJob?.cancel()
            tunnelReconnectJob = null
            closeTunnelLocked()

            val engine = serverEngine ?: run {
                _runtimeState.value = _runtimeState.value.copy(
                    status = ServerStatus.Stopped,
                    tunnelStatus = TunnelStatus.Idle,
                    tunnelPublicUrl = null,
                    tunnelSessionExpiresAt = null,
                    showManualTunnelReconnect = false,
                )
                return
            }
            _runtimeState.value = _runtimeState.value.copy(status = ServerStatus.Stopping)
            runCatching {
                engine.stop(1_000, 5_000)
            }.onFailure { error ->
                Logger.e("ServerLifecycleManager", "Failed to stop server cleanly", error)
            }
            serverEngine = null
            activeConnections.value = 0
            _runtimeState.value = _runtimeState.value.copy(
                status = ServerStatus.Stopped,
                localEndpoint = NetworkUtils.formatLocalEndpoint(
                    context,
                    settingsRepository.serverConfig.value.bindAddress,
                    settingsRepository.serverConfig.value.listenPort,
                ),
                lastError = null,
                tunnelStatus = TunnelStatus.Idle,
                tunnelPublicUrl = null,
                tunnelSessionExpiresAt = null,
                showManualTunnelReconnect = false,
            )
        }
    }

    suspend fun manualReconnectTunnel() {
        lifecycleMutex.withLock {
            val config = settingsRepository.serverConfig.value
            if (serverEngine == null || config.networkMode != ServerConfig.NETWORK_MODE_TUNNELING) return
            tunnelReconnectJob?.cancel()
            tunnelReconnectJob = null
            closeTunnelLocked()
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Connecting,
                tunnelPublicUrl = null,
                tunnelSessionExpiresAt = null,
                lastError = null,
                showManualTunnelReconnect = false,
            )
            scheduleTunnelReconnectLocked(reason = "manual reconnect requested")
        }
    }

    // Must be called with lifecycleMutex held.
    private suspend fun startTunnelLocked(config: ServerConfig): Boolean {
        val client = tunnelingClient ?: run {
            Logger.e("ServerLifecycleManager", "Tunneling mode requested but no TunnelingClient provided")
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Error,
                lastError = "Tunneling client not configured.",
                tunnelSessionExpiresAt = null,
                showManualTunnelReconnect = false,
            )
            return false
        }

        if (config.tunnelAuthToken.isBlank()) {
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Error,
                lastError = "Invalid ngrok auth token. Check Settings → Tunnel Auth Token.",
                tunnelSessionExpiresAt = null,
                showManualTunnelReconnect = false,
            )
            return false
        }

        try {
            // create → store publicUrl → surface in UI
            val session = client.createTunnel(config.listenPort, config.tunnelAuthToken)
            activeTunnelSession = session
            settingsRepository.updateTunnelPublicUrl(session.publicUrl)
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Active,
                tunnelPublicUrl = session.publicUrl,
                tunnelSessionExpiresAt = session.expiresAt,
                showManualTunnelReconnect = false,
            )
            Logger.d("ServerLifecycleManager", "Tunnel active: ${session.publicUrl}")
            return true
        } catch (e: TunnelingException) {
            Logger.e("ServerLifecycleManager", "Failed to create tunnel", e)
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Error,
                lastError = e.message,
                tunnelSessionExpiresAt = null,
                showManualTunnelReconnect = false,
            )
            return false
        }
    }

    // Must be called with lifecycleMutex held.
    private suspend fun closeTunnelLocked() {
        val session = activeTunnelSession ?: return
        val client = tunnelingClient ?: return
        activeTunnelSession = null
        // close → tunnel released on ngrok side
        with(client) { session.close() }
        settingsRepository.updateTunnelPublicUrl(null)
    }

    private suspend fun onNetworkTypeChanged(newType: NetworkType) {
        lifecycleMutex.withLock {
            val previousType = lastKnownNetworkType
            lastKnownNetworkType = newType

            val config = settingsRepository.serverConfig.value
            val isTunneling = config.networkMode == ServerConfig.NETWORK_MODE_TUNNELING
            if (!isTunneling || serverEngine == null) {
                return
            }

            val switchedBetweenWifiAndMobile =
                (previousType == NetworkType.WIFI && newType == NetworkType.MOBILE) ||
                    (previousType == NetworkType.MOBILE && newType == NetworkType.WIFI)

            if (newType == NetworkType.OFFLINE) {
                closeTunnelLocked()
                _runtimeState.value = _runtimeState.value.copy(
                    tunnelStatus = TunnelStatus.Connecting,
                    tunnelPublicUrl = null,
                    tunnelSessionExpiresAt = null,
                    showManualTunnelReconnect = false,
                )
                tunnelReconnectJob?.cancel()
                tunnelReconnectJob = null
                return
            }

            if (previousType == NetworkType.OFFLINE || switchedBetweenWifiAndMobile) {
                closeTunnelLocked()
                _runtimeState.value = _runtimeState.value.copy(
                    tunnelStatus = TunnelStatus.Connecting,
                    tunnelPublicUrl = null,
                    tunnelSessionExpiresAt = null,
                    showManualTunnelReconnect = false,
                )
                scheduleTunnelReconnectLocked(reason = "network transition $previousType -> $newType")
            }
        }
    }

    // Must be called with lifecycleMutex held.
    private fun scheduleTunnelReconnectLocked(reason: String) {
        tunnelReconnectJob?.cancel()
        tunnelReconnectJob = applicationScope.launch {
            var backoffMs = TUNNEL_RECONNECT_BACKOFF_BASE_MS
            repeat(TUNNEL_RECONNECT_MAX_RETRIES) { attempt ->
                val shouldWaitAndRetry = lifecycleMutex.withLock {
                    val config = settingsRepository.serverConfig.value
                    if (
                        serverEngine == null ||
                        config.networkMode != ServerConfig.NETWORK_MODE_TUNNELING ||
                        lastKnownNetworkType == NetworkType.OFFLINE
                    ) {
                        return@withLock true
                    }

                    val connected = startTunnelLocked(config)
                    if (connected) {
                        return@withLock false
                    }

                    val finalAttempt = attempt == TUNNEL_RECONNECT_MAX_RETRIES - 1
                    if (finalAttempt) {
                        _runtimeState.value = _runtimeState.value.copy(
                            tunnelStatus = TunnelStatus.Error,
                            lastError = "Tunnel reconnect failed after $TUNNEL_RECONNECT_MAX_RETRIES attempts. $reason",
                            showManualTunnelReconnect = true,
                        )
                        return@withLock false
                    }

                    _runtimeState.value = _runtimeState.value.copy(
                        tunnelStatus = TunnelStatus.Connecting,
                        showManualTunnelReconnect = false,
                    )
                    true
                }

                if (!shouldWaitAndRetry) return@launch
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(TUNNEL_RECONNECT_BACKOFF_MAX_MS)
            }
        }
    }

    private fun recordLatencySample(rttMs: Long) {
        val snapshot = synchronized(latencyLock) {
            val now = System.currentTimeMillis()
            latencySamples.addLast(LatencySample(timestampMs = now, latencyMs = rttMs))
            pruneLatencyWindowLocked(now)
            val sorted = latencySamples.map { it.latencyMs }.sorted()
            LatencySnapshot(
                p95Ms = percentileMs(sorted, 0.95),
                p99Ms = percentileMs(sorted, 0.99),
            )
        }
        _runtimeState.update {
            it.copy(
                latencyP95Ms = snapshot.p95Ms,
                latencyP99Ms = snapshot.p99Ms,
            )
        }
    }

    private fun pruneLatencyWindowLocked(nowMs: Long) {
        while (latencySamples.isNotEmpty() && nowMs - latencySamples.first().timestampMs > LATENCY_WINDOW_MS) {
            latencySamples.removeFirst()
        }
    }

    private fun percentileMs(sortedValues: List<Long>, fraction: Double): Long? {
        if (sortedValues.isEmpty()) return null
        val rank = ceil(fraction * sortedValues.size).toInt().coerceIn(1, sortedValues.size)
        return sortedValues[rank - 1]
    }

    private data class LatencySample(
        val timestampMs: Long,
        val latencyMs: Long,
    )

    private data class LatencySnapshot(
        val p95Ms: Long?,
        val p99Ms: Long?,
    )

    private companion object {
        private const val LATENCY_WINDOW_MS = 5 * 60 * 1000L
        private const val TUNNEL_RECONNECT_MAX_RETRIES = 4
        private const val TUNNEL_RECONNECT_BACKOFF_BASE_MS = 2_000L
        private const val TUNNEL_RECONNECT_BACKOFF_MAX_MS = 15_000L
    }
}
