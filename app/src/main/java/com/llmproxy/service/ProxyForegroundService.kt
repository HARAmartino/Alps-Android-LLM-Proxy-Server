package com.llmproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.llmproxy.LlmProxyApplication
import com.llmproxy.R
import com.llmproxy.model.ServerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProxyForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serverLifecycleManager by lazy {
        (application as LlmProxyApplication).serverLifecycleManager
    }

    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing local proxy server"))
        notificationJob = serviceScope.launch {
            serverLifecycleManager.runtimeState.collectLatest { runtimeState ->
                val description = when (runtimeState.status) {
                    ServerStatus.Running -> "Serving ${runtimeState.localEndpoint}"
                    ServerStatus.Starting -> "Starting ${runtimeState.localEndpoint}"
                    ServerStatus.Stopping -> "Stopping server"
                    ServerStatus.Error -> runtimeState.lastError ?: "Server error"
                    ServerStatus.Stopped -> "Server stopped"
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, buildNotification(description))
                if (runtimeState.status == ServerStatus.Stopped) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> serviceScope.launch { serverLifecycleManager.startServer() }
            ACTION_STOP -> serviceScope.launch { serverLifecycleManager.stopServer() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "proxy_server_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.llmproxy.action.START"
        const val ACTION_STOP = "com.llmproxy.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyForegroundService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
