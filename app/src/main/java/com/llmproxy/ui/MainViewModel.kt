package com.llmproxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llmproxy.LlmProxyApplication
import com.llmproxy.data.SettingsRepository
import com.llmproxy.model.MainUiEffect
import com.llmproxy.model.MainUiState
import com.llmproxy.service.NetworkMonitor
import com.llmproxy.service.ProxyForegroundService
import com.llmproxy.service.ServerLifecycleManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val serverLifecycleManager: ServerLifecycleManager,
    private val networkMonitor: NetworkMonitor,
) : AndroidViewModel(application) {
    private val app = application as LlmProxyApplication
    private val effectsFlow = MutableSharedFlow<MainUiEffect>()
    val effects = effectsFlow.asSharedFlow()

    val uiState: StateFlow<MainUiState> = combine(
        settingsRepository.serverConfig,
        serverLifecycleManager.runtimeState,
        networkMonitor.networkState,
    ) { config, runtimeState, networkState ->
        MainUiState(
            config = config,
            serverStatus = runtimeState.status,
            activeConnections = runtimeState.activeConnections,
            localEndpoint = runtimeState.localEndpoint,
            networkType = networkState.type,
            publicIp = networkState.ip,
            certificateReady = app.sslCertGenerator.hasCertificateFiles(),
            lastError = runtimeState.lastError,
            tunnelStatus = runtimeState.tunnelStatus,
            tunnelPublicUrl = runtimeState.tunnelPublicUrl,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    fun onUpstreamUrlChanged(value: String) {
        viewModelScope.launch {
            settingsRepository.updateUpstreamUrl(value)
        }
    }

    fun onApiKeyChanged(value: String) {
        viewModelScope.launch {
            settingsRepository.updateApiKey(value)
        }
    }

    fun onPortChanged(value: String) {
        val port = value.toIntOrNull() ?: return
        viewModelScope.launch {
            settingsRepository.updateListenPort(port)
        }
    }

    fun onBindAddressChanged(value: String) {
        viewModelScope.launch {
            settingsRepository.updateBindAddress(value)
        }
    }

    fun onNetworkModeChanged(value: String) {
        viewModelScope.launch {
            settingsRepository.updateNetworkMode(value)
        }
    }

    fun onTunnelAuthTokenChanged(value: String) {
        viewModelScope.launch {
            settingsRepository.updateTunnelAuthToken(value)
        }
    }

    fun onStartRequested() {
        ProxyForegroundService.start(getApplication())
    }

    fun onStopRequested() {
        ProxyForegroundService.stop(getApplication())
    }

    fun onExportCertificateRequested() {
        viewModelScope.launch {
            app.sslCertGenerator.ensureCertificateFiles()
            effectsFlow.emit(MainUiEffect.ExportCertificate("Share certificate"))
        }
    }

    class Factory(
        private val application: LlmProxyApplication,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                application = application,
                settingsRepository = application.settingsRepository,
                serverLifecycleManager = application.serverLifecycleManager,
                networkMonitor = application.networkMonitor,
            ) as T
        }
    }
}
