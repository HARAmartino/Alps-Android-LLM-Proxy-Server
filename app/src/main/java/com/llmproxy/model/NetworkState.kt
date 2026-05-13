package com.llmproxy.model

enum class NetworkType {
    WIFI,
    MOBILE,
    OFFLINE,
}

data class NetworkState(
    val type: NetworkType = NetworkType.OFFLINE,
    val ip: String? = null,
)
