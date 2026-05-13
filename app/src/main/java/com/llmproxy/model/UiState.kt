package com.llmproxy.model

import java.time.Instant

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
)

sealed interface MainUiEffect {
    data class ExportCertificate(val chooserTitle: String) : MainUiEffect
    data class ShowMessage(val message: String) : MainUiEffect
}
