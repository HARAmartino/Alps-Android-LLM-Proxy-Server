package com.llmproxy

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.llmproxy.acme.AcmeCertManager
import com.llmproxy.acme.CloudflareDnsProvider
import com.llmproxy.client.UpstreamHttpClientFactory
import com.llmproxy.client.tunneling.NgrokRestClient
import com.llmproxy.data.SecurePreferences
import com.llmproxy.data.SettingsRepository
import com.llmproxy.model.ServerConfig
import com.llmproxy.server.SslContextLoader
import com.llmproxy.service.DdnsUpdateTrigger
import com.llmproxy.service.CertificateRenewalWorker
import com.llmproxy.service.NetworkMonitor
import com.llmproxy.service.NoOpDdnsUpdateTrigger
import com.llmproxy.service.ServerLifecycleManager
import com.llmproxy.util.SslCertGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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
            isPortForwardingMode = {
                settingsRepository.serverConfig.value.networkMode == ServerConfig.NETWORK_MODE_LOCAL
            },
        )
    }

    val ngrokRestClient: NgrokRestClient by lazy {
        NgrokRestClient()
    }

    val cloudflareDnsProvider: CloudflareDnsProvider by lazy {
        CloudflareDnsProvider(
            apiTokenProvider = { settingsRepository.serverConfig.value.cloudflareApiToken },
            httpClient = upstreamHttpClientFactory.create(),
        )
    }

    val acmeCertManager: AcmeCertManager by lazy {
        AcmeCertManager(
            context = this,
            sslCertGenerator = sslCertGenerator,
            dnsProvider = cloudflareDnsProvider,
        )
    }

    val serverLifecycleManager: ServerLifecycleManager by lazy {
        ServerLifecycleManager(
            context = this,
            applicationScope = applicationScope,
            settingsRepository = settingsRepository,
            sslCertGenerator = sslCertGenerator,
            sslContextLoader = sslContextLoader,
            acmeCertManager = acmeCertManager,
            upstreamClient = upstreamHttpClientFactory.create(),
            tunnelingClient = ngrokRestClient,
            networkMonitor = networkMonitor,
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            sslCertGenerator.ensureCertificateFiles()
        }
        scheduleCertificateRenewalWorker()
    }

    private fun scheduleCertificateRenewalWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workRequest = PeriodicWorkRequestBuilder<CertificateRenewalWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CertificateRenewalWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest,
        )
    }
}
