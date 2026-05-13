package com.llmproxy.model

import java.time.Instant

/** Lightweight representation of a recent ERROR-level system log entry for the dashboard. */
data class RecentError(
    val timestamp: String,
    val tag: String,
    val message: String,
    val stacktrace: String? = null,
)

enum class ServerStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

data class ServerRuntimeState(
    val status: ServerStatus = ServerStatus.Stopped,
    val activeConnections: Int = 0,
    val localEndpoint: String = "Not started",
    val lastError: String? = null,
    val tunnelStatus: TunnelStatus = TunnelStatus.Idle,
    val tunnelPublicUrl: String? = null,
    val tunnelSessionExpiresAt: Instant? = null,
    val latencyP95Ms: Long? = null,
    val latencyP99Ms: Long? = null,
    val showManualTunnelReconnect: Boolean = false,
    val certificateExpiresAt: Instant? = null,
    val acmeInProgress: Boolean = false,
    val certWarning: String? = null,
    /** Connections that drained within the timeout window during the last graceful restart. */
    val gracefulCloseCount: Int = 0,
    /** Connections that were force-closed after the drain timeout during the last graceful restart. */
    val forcedCloseCount: Int = 0,
    /** True while the graceful restart drain window is active. */
    val isRestartDraining: Boolean = false,
)

data class MainUiState(
    val config: ServerConfig = ServerConfig(),
    val serverStatus: ServerStatus = ServerStatus.Stopped,
    val activeConnections: Int = 0,
    val localEndpoint: String = "Not started",
    val networkType: NetworkType = NetworkType.OFFLINE,
    val publicIp: String? = null,
    val certificateReady: Boolean = false,
    val lastError: String? = null,
    val tunnelStatus: TunnelStatus = TunnelStatus.Idle,
    val tunnelPublicUrl: String? = null,
    val tunnelSessionExpiresAt: Instant? = null,
    val latencyP95Ms: Long? = null,
    val latencyP99Ms: Long? = null,
    val showManualTunnelReconnect: Boolean = false,
    val hasSeenTunnelingInfoDialog: Boolean = false,
    val certificateExpiresAt: Instant? = null,
    val acmeInProgress: Boolean = false,
    val certWarning: String? = null,
    /** Last up-to-10 ERROR-level system log entries for the dashboard. */
    val recentErrors: List<RecentError> = emptyList(),
    /** Connections that drained within the timeout window during the last graceful restart. */
    val gracefulCloseCount: Int = 0,
    /** Connections that were force-closed after the drain timeout during the last graceful restart. */
    val forcedCloseCount: Int = 0,
    /** True while the graceful restart drain window is active. */
    val isRestartDraining: Boolean = false,
)

sealed interface MainUiEffect {
    data class ExportCertificate(val chooserTitle: String) : MainUiEffect
    data class ExportAccessLogs(val chooserTitle: String) : MainUiEffect
    data class ExportSystemLogs(val chooserTitle: String) : MainUiEffect
    data class ShowMessage(val message: String) : MainUiEffect
    /** Non-blocking renewal progress message (e.g. "Renewing certificate…"). */
    data class ShowRenewalMessage(val message: String) : MainUiEffect
    /** Renewal outcome: success or failure. On failure, [canRetry] triggers a retry snackbar action. */
    data class ShowRenewalResult(val message: String, val canRetry: Boolean) : MainUiEffect
}
