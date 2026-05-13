package com.llmproxy.service

import android.content.Context
import com.llmproxy.client.tunneling.TunnelSession
import com.llmproxy.client.tunneling.TunnelingClient
import com.llmproxy.client.tunneling.TunnelingException
import com.llmproxy.data.SettingsRepository
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerLifecycleManager(
    private val context: Context,
    private val applicationScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val sslCertGenerator: SslCertGenerator,
    private val sslContextLoader: SslContextLoader,
    private val upstreamClient: HttpClient,
    private val tunnelingClient: TunnelingClient? = null,
) {
    private val lifecycleMutex = Mutex()
    private val activeConnections = MutableStateFlow(0)
    private val _runtimeState = MutableStateFlow(ServerRuntimeState())
    val runtimeState: StateFlow<ServerRuntimeState> = _runtimeState.asStateFlow()

    private var serverEngine: ApplicationEngine? = null
    // Holds the active tunnel session when in tunneling mode; null otherwise.
    private var activeTunnelSession: TunnelSession? = null
    private val proxyServerFactory: ProxyServerFactory by lazy {
        ProxyServerFactory(upstreamClient = upstreamClient, sslContextLoader = sslContextLoader)
    }

    init {
        applicationScope.launch {
            activeConnections.collect { count ->
                _runtimeState.update { it.copy(activeConnections = count) }
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
            )

            try {
                sslCertGenerator.ensureCertificateFiles()
                serverEngine = proxyServerFactory.create(effectiveConfig, activeConnections).also { engine ->
                    engine.start(wait = false)
                }
                _runtimeState.value = _runtimeState.value.copy(status = ServerStatus.Running)

                // After the server is up, create the ngrok tunnel if in tunneling mode.
                if (isTunneling) {
                    startTunnelLocked(config)
                }
            } catch (error: Exception) {
                Logger.e("ServerLifecycleManager", "Failed to start server", error)
                serverEngine = null
                _runtimeState.value = _runtimeState.value.copy(
                    status = ServerStatus.Error,
                    lastError = error.message,
                    tunnelStatus = TunnelStatus.Idle,
                )
            }
        }
    }

    suspend fun stopServer() {
        lifecycleMutex.withLock {
            // Close the tunnel before stopping the engine so ngrok can clean up.
            closeTunnelLocked()

            val engine = serverEngine ?: run {
                _runtimeState.value = _runtimeState.value.copy(
                    status = ServerStatus.Stopped,
                    tunnelStatus = TunnelStatus.Idle,
                    tunnelPublicUrl = null,
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
            )
        }
    }

    // Must be called with lifecycleMutex held.
    private suspend fun startTunnelLocked(config: ServerConfig) {
        val client = tunnelingClient ?: run {
            Logger.e("ServerLifecycleManager", "Tunneling mode requested but no TunnelingClient provided")
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Error,
                lastError = "Tunneling client not configured.",
            )
            return
        }

        if (config.tunnelAuthToken.isBlank()) {
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Error,
                lastError = "Invalid ngrok auth token. Check Settings → Tunnel Auth Token.",
            )
            return
        }

        try {
            // create → store publicUrl → surface in UI
            val session = client.createTunnel(config.listenPort, config.tunnelAuthToken)
            activeTunnelSession = session
            settingsRepository.updateTunnelPublicUrl(session.publicUrl)
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Active,
                tunnelPublicUrl = session.publicUrl,
            )
            Logger.d("ServerLifecycleManager", "Tunnel active: ${session.publicUrl}")
        } catch (e: TunnelingException) {
            Logger.e("ServerLifecycleManager", "Failed to create tunnel", e)
            _runtimeState.value = _runtimeState.value.copy(
                tunnelStatus = TunnelStatus.Error,
                lastError = e.message,
            )
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
}
