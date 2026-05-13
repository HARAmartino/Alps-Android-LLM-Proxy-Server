package com.llmproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llmproxy.model.MainUiState
import com.llmproxy.model.RecentError
import com.llmproxy.model.ServerConfig
import com.llmproxy.model.ServerStatus
import com.llmproxy.model.TunnelStatus
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val tunnelExpiryTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun MainDashboard(
    state: MainUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onManualReconnectClick: () -> Unit,
    onRetryAcmeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTunneling = state.config.networkMode == ServerConfig.NETWORK_MODE_TUNNELING
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Android LLM Proxy Server", style = MaterialTheme.typography.headlineSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "Server status", style = MaterialTheme.typography.titleMedium)
                    Text(text = state.serverStatus.name)
                    Text(text = "Local endpoint: ${state.localEndpoint}")
                    Text(text = "Network: ${state.networkType.name}")
                    Text(text = "Public IP: ${state.publicIp ?: "Unavailable"}")
                    Text(text = "Active connections: ${state.activeConnections}")
                    Text(text = "Latency p95: ${state.latencyP95Ms?.let { "${it}ms" } ?: "N/A"}")
                    Text(text = "Latency p99: ${state.latencyP99Ms?.let { "${it}ms" } ?: "N/A"}")

                    // Restart metrics — show after at least one graceful restart has occurred.
                    if (state.gracefulCloseCount > 0 || state.forcedCloseCount > 0) {
                        Text(
                            text = "Last restart — graceful: ${state.gracefulCloseCount}  " +
                                "forced: ${state.forcedCloseCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.forcedCloseCount > 0)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.isRestartDraining) {
                        Text(
                            text = "Draining connections before restart\u2026",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Tunneling section: show public URL and status when in tunneling mode.
                    if (isTunneling) {
                        TunnelStatusSection(
                            tunnelStatus = state.tunnelStatus,
                            tunnelPublicUrl = state.tunnelPublicUrl,
                            tunnelSessionExpiresAt = state.tunnelSessionExpiresAt,
                            tunnelLastError = state.lastError,
                            showManualReconnect = state.showManualTunnelReconnect,
                            onManualReconnectClick = onManualReconnectClick,
                            onCopyUrl = { url ->
                                clipboardManager.setText(AnnotatedString(url))
                                scope.launch {
                                    snackbarHostState.showSnackbar("Public URL copied")
                                }
                            },
                        )
                    }

                    state.lastError
                        ?.takeIf { it.isNotBlank() && (!isTunneling || state.tunnelStatus != TunnelStatus.Error) }
                        ?.let { error ->
                            Text(text = error, color = MaterialTheme.colorScheme.error)
                        }
                    state.certWarning?.takeIf { it.isNotBlank() }?.let { warning ->
                        Text(text = warning, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStartClick,
                    enabled = state.serverStatus != ServerStatus.Running && state.config.isReady,
                ) {
                    Text(text = "Start")
                }
                OutlinedButton(
                    onClick = onStopClick,
                    enabled = state.serverStatus == ServerStatus.Running || state.serverStatus == ServerStatus.Starting,
                ) {
                    Text(text = "Stop")
                }
                OutlinedButton(onClick = onSettingsClick) {
                    Text(text = "Settings")
                }
                if (!state.certWarning.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = onRetryAcmeClick,
                        enabled = !state.acmeInProgress,
                    ) {
                        Text(text = if (state.acmeInProgress) "Retrying..." else "Retry Let's Encrypt")
                    }
                }
            }

            // Recent Errors section — shows last 10 ERROR-level system log entries.
            if (state.recentErrors.isNotEmpty()) {
                RecentErrorsSection(errors = state.recentErrors)
            }
        }
    }
}

@Composable
private fun TunnelStatusSection(
    tunnelStatus: TunnelStatus,
    tunnelPublicUrl: String?,
    tunnelSessionExpiresAt: Instant?,
    tunnelLastError: String?,
    showManualReconnect: Boolean,
    onManualReconnectClick: () -> Unit,
    onCopyUrl: (String) -> Unit,
) {
    var isErrorExpanded by rememberSaveable { mutableStateOf(false) }
    val statusLabel = when (tunnelStatus) {
        TunnelStatus.Idle -> "Idle"
        TunnelStatus.Connecting -> "Connecting…"
        TunnelStatus.Active -> "Active"
        TunnelStatus.Error -> "Error"
    }
    val statusColor = when (tunnelStatus) {
        TunnelStatus.Active -> MaterialTheme.colorScheme.primary
        TunnelStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val transition = rememberInfiniteTransition(label = "tunnel-connecting-indicator")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tunnel-connecting-pulse",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (tunnelStatus) {
            TunnelStatus.Connecting -> Icon(
                imageVector = Icons.Default.Circle,
                contentDescription = "Tunnel connecting",
                tint = statusColor.copy(alpha = pulseAlpha),
            )
            TunnelStatus.Active -> Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Tunnel active",
                tint = statusColor,
            )
            TunnelStatus.Error -> Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = "Tunnel error",
                tint = statusColor,
            )
            TunnelStatus.Idle -> Icon(
                imageVector = Icons.Default.Circle,
                contentDescription = "Tunnel idle",
                tint = statusColor,
            )
        }
        Text(
            text = "Tunnel: $statusLabel",
            color = statusColor,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    tunnelPublicUrl?.takeIf { it.isNotBlank() }?.let { url ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Public URL: $url",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = { onCopyUrl(url) }) {
                Text("Copy URL")
            }
        }
    }
    if (tunnelStatus == TunnelStatus.Active && tunnelSessionExpiresAt != null) {
        Text(
            text = "Session expires at: ${tunnelSessionExpiresAt.formatTunnelExpiry()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (tunnelStatus == TunnelStatus.Error && !tunnelLastError.isNullOrBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Last error",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { isErrorExpanded = !isErrorExpanded }) {
                Text(if (isErrorExpanded) "Hide details" else "Show details")
            }
        }
        if (isErrorExpanded) {
            Text(
                text = tunnelLastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (showManualReconnect) {
            OutlinedButton(onClick = onManualReconnectClick) {
                Text("Reconnect manually")
            }
        }
    }
}

private fun Instant?.formatTunnelExpiry(): String = this?.let { tunnelExpiryTimeFormatter.format(it) } ?: "Unknown"

/**
 * Expandable card showing the last up-to-10 ERROR-level system log entries.
 * Each entry can be tapped to reveal its full stacktrace in an [AlertDialog].
 */
@Composable
private fun RecentErrorsSection(errors: List<RecentError>) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedError by remember { mutableStateOf<RecentError?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Recent Errors (${errors.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { isExpanded = !isExpanded }) {
                    Text(if (isExpanded) "Collapse" else "Expand")
                }
            }
            if (isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    errors.forEach { error ->
                        Column {
                            Text(
                                text = "[${error.timestamp}] [${error.tag}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = error.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!error.stacktrace.isNullOrBlank()) {
                                    TextButton(onClick = { selectedError = error }) {
                                        Text("Details")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog to show full stacktrace when an error entry is tapped.
    selectedError?.let { error ->
        AlertDialog(
            onDismissRequest = { selectedError = null },
            title = { Text("[${error.tag}] ${error.message}") },
            text = {
                Text(
                    text = error.stacktrace.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedError = null }) {
                    Text("Close")
                }
            },
        )
    }
}
