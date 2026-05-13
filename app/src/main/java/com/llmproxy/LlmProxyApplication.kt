package com.llmproxy

import android.app.Application
import com.llmproxy.client.UpstreamHttpClientFactory
import com.llmproxy.client.tunneling.NgrokRestClient
import com.llmproxy.data.SecurePreferences
import com.llmproxy.data.SettingsRepository
import com.llmproxy.server.SslContextLoader
import com.llmproxy.service.DdnsUpdateTrigger
import com.llmproxy.service.NetworkMonitor
import com.llmproxy.service.NoOpDdnsUpdateTrigger
import com.llmproxy.service.ServerLifecycleManager
import com.llmproxy.util.SslCertGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LlmProxyApplication : Application() {
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val securePreferences: SecurePreferences by lazy {
        SecurePreferences(this)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this, applicationScope, securePreferences)
    }

    val sslCertGenerator: SslCertGenerator by lazy {
        SslCertGenerator(this)
    }

    val sslContextLoader: SslContextLoader by lazy {
        SslContextLoader(sslCertGenerator)
    }

    val upstreamHttpClientFactory: UpstreamHttpClientFactory by lazy {
        UpstreamHttpClientFactory()
    }

    val ddnsUpdateTrigger: DdnsUpdateTrigger by lazy {
        NoOpDdnsUpdateTrigger
    }

    val networkMonitor: NetworkMonitor by lazy {
        NetworkMonitor(
            context = this,
            scope = applicationScope,
            ddnsUpdateTrigger = ddnsUpdateTrigger,
        )
    }

    val ngrokRestClient: NgrokRestClient by lazy {
        NgrokRestClient()
    }

    val serverLifecycleManager: ServerLifecycleManager by lazy {
        ServerLifecycleManager(
            context = this,
            applicationScope = applicationScope,
            settingsRepository = settingsRepository,
            sslCertGenerator = sslCertGenerator,
            sslContextLoader = sslContextLoader,
            upstreamClient = upstreamHttpClientFactory.create(),
            tunnelingClient = ngrokRestClient,
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            sslCertGenerator.ensureCertificateFiles()
        }
    }
}
