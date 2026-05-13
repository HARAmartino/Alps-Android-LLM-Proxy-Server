package com.llmproxy.service

import android.content.Context
import com.llmproxy.data.SettingsRepository
import com.llmproxy.model.ServerRuntimeState
import com.llmproxy.model.ServerStatus
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
) {
    private val lifecycleMutex = Mutex()
    private val activeConnections = MutableStateFlow(0)
    private val _runtimeState = MutableStateFlow(ServerRuntimeState())
    val runtimeState: StateFlow<ServerRuntimeState> = _runtimeState.asStateFlow()

    private var serverEngine: ApplicationEngine? = null
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

            _runtimeState.value = _runtimeState.value.copy(
                status = ServerStatus.Starting,
                localEndpoint = NetworkUtils.formatLocalEndpoint(context, config.bindAddress, config.listenPort),
                lastError = null,
            )

            try {
                sslCertGenerator.ensureCertificateFiles()
                serverEngine = proxyServerFactory.create(config, activeConnections).also { engine ->
                    engine.start(wait = false)
                }
                _runtimeState.value = _runtimeState.value.copy(status = ServerStatus.Running)
            } catch (error: Exception) {
                Logger.e("ServerLifecycleManager", "Failed to start server", error)
                serverEngine = null
                _runtimeState.value = _runtimeState.value.copy(
                    status = ServerStatus.Error,
                    lastError = error.message,
                )
            }
        }
    }

    suspend fun stopServer() {
        lifecycleMutex.withLock {
            val engine = serverEngine ?: run {
                _runtimeState.value = _runtimeState.value.copy(status = ServerStatus.Stopped)
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
            )
        }
    }
}
