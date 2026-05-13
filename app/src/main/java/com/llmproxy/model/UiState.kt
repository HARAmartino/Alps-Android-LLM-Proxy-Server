package com.llmproxy.model

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
)

sealed interface MainUiEffect {
    data class ExportCertificate(val chooserTitle: String) : MainUiEffect
    data class ShowMessage(val message: String) : MainUiEffect
}
