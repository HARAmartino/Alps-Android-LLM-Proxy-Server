package com.llmproxy.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.llmproxy.LlmProxyApplication
import com.llmproxy.util.Logger

class CertificateRenewalWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LlmProxyApplication ?: return Result.failure()
        return runCatching {
            app.serverLifecycleManager.renewCertificateIfNeeded()
            Result.success()
        }.getOrElse { error ->
            Logger.e("CertificateRenewalWorker", "Daily renewal check failed", error)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_certificate_renewal"
    }
}
