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
)

sealed interface MainUiEffect {
    data class ExportCertificate(val chooserTitle: String) : MainUiEffect
    data class ExportAccessLogs(val chooserTitle: String) : MainUiEffect
    data class ExportSystemLogs(val chooserTitle: String) : MainUiEffect
    data class ShowMessage(val message: String) : MainUiEffect
}
