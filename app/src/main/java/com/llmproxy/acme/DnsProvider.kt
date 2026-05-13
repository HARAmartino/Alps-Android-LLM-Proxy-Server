package com.llmproxy.acme

interface DnsProvider {
    suspend fun addTxtRecord(name: String, content: String)
    suspend fun removeTxtRecord(name: String, content: String)
}
