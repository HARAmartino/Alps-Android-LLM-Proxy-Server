package com.llmproxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llmproxy.LlmProxyApplication
import com.llmproxy.data.SettingsRepository
import com.llmproxy.logging.SystemLogger
import com.llmproxy.model.MainUiEffect
import com.llmproxy.model.MainUiState
import com.llmproxy.model.RecentError
import com.llmproxy.service.ServerLifecycleManager.RenewalEvent
import com.llmproxy.service.NetworkMonitor
import com.llmproxy.service.ProxyForegroundService
import com.llmproxy.service.ServerLifecycleManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val systemLogger: SystemLogger?,
) : AndroidViewModel(application) {
    private val app = application as LlmProxyApplication
    private val effectsFlow = MutableSharedFlow<MainUiEffect>()
    val effects = effectsFlow.asSharedFlow()

    /** Periodically refreshed list of the last 10 ERROR-level system log entries. */
    private val recentErrorsFlow = MutableStateFlow<List<RecentError>>(emptyList())

    init {
        // Poll recent errors every 30 s. The loop is tied to viewModelScope:
        // when the ViewModel is cleared the scope is cancelled and the coroutine stops.
        viewModelScope.launch {
            while (kotlinx.coroutines.isActive) {
                try {
                    val errors = systemLogger?.recentErrors()
                        ?.takeLast(10)
                        ?.map { entry ->
                            RecentError(
                                timestamp = entry.timestamp,
                                tag = entry.tag,
                                message = entry.message,
                                stacktrace = entry.stacktrace,
                            )
                        }
                        .orEmpty()
                    recentErrorsFlow.value = errors
                } catch (e: Exception) {
                    android.util.Log.w("MainViewModel", "Failed to refresh recent errors: ${e.message}")
                }
                kotlinx.coroutines.delay(30_000L)
            }
        }

        // Forward certificate renewal events to the UI effect channel for snackbar display.
        viewModelScope.launch {
            serverLifecycleManager.renewalEvents.collect { event ->
                val effect = when (event) {
                    is RenewalEvent.Started ->
                        MainUiEffect.ShowRenewalMessage("Renewing certificate\u2026")
                    is RenewalEvent.Succeeded ->
                        MainUiEffect.ShowRenewalResult(
                            message = "Certificate renewed. No restart required for new connections.",
                            canRetry = false,
                        )
                    is RenewalEvent.Failed ->
                        MainUiEffect.ShowRenewalResult(
                            message = "Renewal failed. Using existing cert. Tap to retry.",
                            canRetry = event.canRetry,
                        )
                }
                effectsFlow.emit(effect)
            }
        }
    }

    val uiState: StateFlow<MainUiState> = combine(
        settingsRepository.serverConfig,
        settingsRepository.tunnelingInfoDialogShown,
        settingsRepository.batteryOptimizationGuideShown,
        settingsRepository.batteryOptimizationGuideDontShowAgain,
        serverLifecycleManager.runtimeState,
        networkMonitor.networkState,
        recentErrorsFlow,
    ) { config, tunnelingInfoDialogShown, batteryGuideShown, batteryGuideDontShowAgain, runtimeState, networkState, recentErrors ->
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
            tunnelSessionExpiresAt = runtimeState.tunnelSessionExpiresAt,
            latencyP95Ms = runtimeState.latencyP95Ms,
                latencyP99Ms = runtimeState.latencyP99Ms,
                showManualTunnelReconnect = runtimeState.showManualTunnelReconnect,
                hasSeenTunnelingInfoDialog = tunnelingInfoDialogShown,
                hasSeenBatteryOptimizationGuideDialog = batteryGuideShown,
                batteryOptimizationGuideDontShowAgain = batteryGuideDontShowAgain,
                certificateExpiresAt = runtimeState.certificateExpiresAt,
                acmeInProgress = runtimeState.acmeInProgress,
                certWarning = runtimeState.certWarning,
                recentErrors = recentErrors,
                gracefulCloseCount = runtimeState.gracefulCloseCount,
                forcedCloseCount = runtimeState.forcedCloseCount,
                isRestartDraining = runtimeState.isRestartDraining,
                isWakeLockActive = runtimeState.isWakeLockActive,
                isWifiLockActive = runtimeState.isWifiLockActive,
                totalLockActiveMs = runtimeState.totalLockActiveMs,
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

    fun onLetsEncryptDomainChanged(value: String) {
        viewModelScope.launch {
            settingsRepository.updateLetsEncryptDomain(value)
        }
    }

    fun onCloudflareApiTokenChanged(value: String) {
        viewModelScope.launch {
            settingsRepository.updateCloudflareApiToken(value)
        }
    }

    fun onLetsEncryptAutoRenewChanged(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateLetsEncryptAutoRenew(value)
        }
    }

    fun onTunnelingInfoDialogShown() {
        viewModelScope.launch {
            settingsRepository.markTunnelingInfoDialogShown()
        }
    }

    fun onBatteryOptimizationGuideHandled(dontShowAgain: Boolean) {
        viewModelScope.launch {
            settingsRepository.markBatteryOptimizationGuideShown()
            settingsRepository.updateBatteryOptimizationGuideDontShowAgain(dontShowAgain)
        }
    }

    fun onEnableWakeLockChanged(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateEnableWakeLock(value)
        }
    }

    fun onEnableWifiLockChanged(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateEnableWifiLock(value)
        }
    }

    fun onStartRequested() {
        ProxyForegroundService.start(getApplication())
    }

    fun onStopRequested() {
        ProxyForegroundService.stop(getApplication())
    }

    fun onManualTunnelReconnectRequested() {
        viewModelScope.launch {
            serverLifecycleManager.manualReconnectTunnel()
        }
    }

    fun onExportCertificateRequested() {
        viewModelScope.launch {
            app.sslCertGenerator.ensureCertificateFiles()
            effectsFlow.emit(MainUiEffect.ExportCertificate("Share certificate"))
        }
    }

    fun onExportAccessLogsRequested() {
        viewModelScope.launch {
            effectsFlow.emit(MainUiEffect.ExportAccessLogs("Share access logs"))
        }
    }

    fun onExportSystemLogsRequested() {
        viewModelScope.launch {
            effectsFlow.emit(MainUiEffect.ExportSystemLogs("Share system logs"))
        }
    }

    fun onWebhookForwardUrlChanged(value: String) {
        viewModelScope.launch {
            settingsRepository.updateWebhookForwardUrl(value)
        }
    }

    fun onRequestCertificateRequested() {
        viewModelScope.launch {
            serverLifecycleManager.requestLetsEncryptCertificate()
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
                systemLogger = application.systemLogger,
            ) as T
        }
    }
}
