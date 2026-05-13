package com.llmproxy.service

interface DdnsUpdateTrigger {
    suspend fun onIpChanged(newIp: String)

    suspend fun onNetworkLost()
}

object NoOpDdnsUpdateTrigger : DdnsUpdateTrigger {
    override suspend fun onIpChanged(newIp: String) = Unit

    override suspend fun onNetworkLost() = Unit
}
