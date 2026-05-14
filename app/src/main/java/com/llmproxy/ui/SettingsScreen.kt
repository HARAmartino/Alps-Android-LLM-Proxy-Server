package com.llmproxy.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.llmproxy.model.MainUiState
import com.llmproxy.model.ServerConfig
import com.llmproxy.util.Logger
import com.llmproxy.util.formatElapsedDuration
import com.llmproxy.util.OemOptimizationGuide
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val certificateExpiryFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun SettingsScreen(
    state: MainUiState,
    onUpstreamUrlChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onBindAddressChanged: (String) -> Unit,
    onNetworkModeChanged: (String) -> Unit,
    onTunnelAuthTokenChanged: (String) -> Unit,
    onLetsEncryptDomainChanged: (String) -> Unit,
    onCloudflareApiTokenChanged: (String) -> Unit,
    onLetsEncryptAutoRenewChanged: (Boolean) -> Unit,
    onRequestCertificate: () -> Unit,
    onTunnelingInfoDialogShown: () -> Unit,
    onBatteryOptimizationGuideHandled: (Boolean) -> Unit,
    onExportCertificate: () -> Unit,
    onExportAccessLogs: () -> Unit,
    onExportSystemLogs: () -> Unit,
    onWebhookForwardUrlChanged: (String) -> Unit,
    onEnableWakeLockChanged: (Boolean) -> Unit,
    onEnableWifiLockChanged: (Boolean) -> Unit,
    onBearerTokenChanged: (String) -> Unit,
    onRequireBearerAuthChanged: (Boolean) -> Unit,
    onMaxRequestsPerMinuteChanged: (String) -> Unit,
    onCorsAllowedOriginsChanged: (String) -> Unit,
    onEnableIpWhitelistChanged: (Boolean) -> Unit,
    onIpWhitelistChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTunneling = state.config.networkMode == ServerConfig.NETWORK_MODE_TUNNELING
    val context = LocalContext.current
    var showBatteryGuideDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(
        state.hasSeenBatteryOptimizationGuideDialog,
        state.batteryOptimizationGuideDontShowAgain,
    ) {
        if (!state.hasSeenBatteryOptimizationGuideDialog && !state.batteryOptimizationGuideDontShowAgain) {
            showBatteryGuideDialog = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)

        if (showBatteryGuideDialog) {
            BatteryOptimizationGuideDialog(
                onDismiss = { dontShowAgain ->
                    showBatteryGuideDialog = false
                    onBatteryOptimizationGuideHandled(dontShowAgain)
                },
                onOpenSettings = { dontShowAgain ->
                    showBatteryGuideDialog = false
                    onBatteryOptimizationGuideHandled(dontShowAgain)
                    val intent = OemOptimizationGuide.resolveSettingsIntent(context)
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            Logger.e("SettingsScreen", "Failed to open battery optimization settings", it)
                            Toast.makeText(context, "Unable to open battery settings", Toast.LENGTH_SHORT).show()
                        }
                },
            )
        }

        Text(text = "Power Lock Controls", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep CPU awake while running")
                Text(
                    text = "Increases battery drain",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Switch(
                checked = state.config.enableWakeLock,
                onCheckedChange = onEnableWakeLockChanged,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Prevent Wi-Fi sleep while running")
                Text(
                    text = "Use only when charging",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Switch(
                checked = state.config.enableWifiLock,
                onCheckedChange = onEnableWifiLockChanged,
            )
        }
        OutlinedButton(
            onClick = {
                showBatteryGuideDialog = true
            },
        ) {
            Text("Battery Optimization Guide")
        }
        Text(
            text = "Total lock-active time: ${formatElapsedDuration(state.totalLockActiveMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.config.upstreamUrl,
            onValueChange = onUpstreamUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Upstream URL") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.config.apiKey,
            onValueChange = onApiKeyChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = state.config.listenPort.toString(),
            onValueChange = onPortChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Listen port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Text(text = "Security", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Require Bearer Auth")
                Text(
                    text = if (isTunneling) {
                        "Enabled by default in tunneling mode."
                    } else {
                        "Optional in local mode."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.config.requireBearerAuth,
                onCheckedChange = onRequireBearerAuthChanged,
            )
        }
        OutlinedTextField(
            value = state.config.bearerToken,
            onValueChange = onBearerTokenChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bearer token") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrect = false,
            ),
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = state.config.maxRequestsPerMinute.toString(),
            onValueChange = onMaxRequestsPerMinuteChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Max RPM per IP") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = state.config.corsAllowedOrigins.joinToString(", "),
            onValueChange = onCorsAllowedOriginsChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Allowed Origins (comma-separated)") },
            singleLine = true,
            placeholder = { Text("*, https://my-app.com") },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable IP Whitelist")
                Text(
                    text = "When enabled, only listed IPs/CIDRs can access the proxy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.config.enableIpWhitelist,
                onCheckedChange = onEnableIpWhitelistChanged,
            )
        }
        OutlinedTextField(
            value = state.config.ipWhitelist.joinToString("\n"),
            onValueChange = onIpWhitelistChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IP Whitelist (one per line)") },
            placeholder = { Text("192.168.1.0/24\n10.0.0.5") },
            minLines = 3,
            maxLines = 6,
        )

        // Network mode selector: "local" uses 0.0.0.0, "tunneling" binds to 127.0.0.1 + ngrok.
        NetworkModeSelector(
            selectedMode = state.config.networkMode,
            hasSeenTunnelingInfoDialog = state.hasSeenTunnelingInfoDialog,
            onModeSelected = onNetworkModeChanged,
            onTunnelingInfoDialogShown = onTunnelingInfoDialogShown,
        )

        // Bind address is only relevant in local mode.
        if (!isTunneling) {
            OutlinedTextField(
                value = state.config.bindAddress,
                onValueChange = onBindAddressChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bind address") },
                singleLine = true,
            )
        }

        // Tunnel auth token is only relevant in tunneling mode.
        if (isTunneling) {
            OutlinedTextField(
                value = state.config.tunnelAuthToken,
                onValueChange = onTunnelAuthTokenChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tunnel Auth Token (ngrok)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        Text(text = "Let's Encrypt (Cloudflare DNS-01)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.config.letsEncryptDomain,
            onValueChange = onLetsEncryptDomainChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Domain") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.config.cloudflareApiToken,
            onValueChange = onCloudflareApiTokenChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Cloudflare API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Auto-renew when < 30 days remain")
            Switch(
                checked = state.config.letsEncryptAutoRenew,
                onCheckedChange = onLetsEncryptAutoRenewChanged,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRequestCertificate,
                enabled = !state.acmeInProgress,
            ) {
                Text("Request Certificate")
            }
            if (state.acmeInProgress) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        Text(
            text = "Certificate expiry: ${state.certificateExpiresAt?.let(certificateExpiryFormatter::format) ?: "Unknown"}",
            style = MaterialTheme.typography.bodySmall,
        )
        state.certWarning?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        CertificateManagerCard(
            certificateReady = state.certificateReady,
            onExportCertificate = onExportCertificate,
        )

        // ── Log export section ──────────────────────────────────────────────
        Text(text = "Logs", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Exported files have sensitive fields (API keys, tokens) automatically redacted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExportAccessLogs) {
                Text("Export Access Logs")
            }
            OutlinedButton(onClick = onExportSystemLogs) {
                Text("Export System Logs")
            }
        }

        // ── External log forwarding (stub) ──────────────────────────────────
        Text(text = "External Log Forwarding", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.config.webhookForwardUrl,
            onValueChange = onWebhookForwardUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Webhook URL (optional)") },
            singleLine = true,
            placeholder = { Text("https://example.com/logs") },
        )
        Text(
            text = "When set, log entries will be forwarded to this URL in the background. Leave blank to disable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatteryOptimizationGuideDialog(
    onDismiss: (dontShowAgain: Boolean) -> Unit,
    onOpenSettings: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    val manufacturer = OemOptimizationGuide.manufacturerDisplayName()
    val instructions = OemOptimizationGuide.instructions()

    AlertDialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        title = { Text("Battery Optimization Guide ($manufacturer)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                instructions.forEachIndexed { index, step ->
                    Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                    )
                    Text(
                        text = "Don't show again",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenSettings(dontShowAgain) }) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(dontShowAgain) }) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun NetworkModeSelector(
    selectedMode: String,
    hasSeenTunnelingInfoDialog: Boolean,
    onModeSelected: (String) -> Unit,
    onTunnelingInfoDialogShown: () -> Unit,
) {
    var showTunnelingInfoDialog by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Network mode", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedMode == ServerConfig.NETWORK_MODE_LOCAL,
                onClick = { onModeSelected(ServerConfig.NETWORK_MODE_LOCAL) },
                label = { Text("Local (port forwarding)") },
            )
            FilterChip(
                selected = selectedMode == ServerConfig.NETWORK_MODE_TUNNELING,
                onClick = {
                    if (selectedMode != ServerConfig.NETWORK_MODE_TUNNELING) {
                        onModeSelected(ServerConfig.NETWORK_MODE_TUNNELING)
                        if (!hasSeenTunnelingInfoDialog) {
                            showTunnelingInfoDialog = true
                        }
                    }
                },
                label = { Text("Tunneling (ngrok)") },
            )
        }

        Text(
            text = "Local: Accessible only within your Wi-Fi network. No external setup required.",
            style = MaterialTheme.typography.bodySmall,
            color = if (selectedMode == ServerConfig.NETWORK_MODE_LOCAL) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = "Tunneling: Accessible from anywhere via Ngrok. Requires auth token. Free tier limits apply.",
            style = MaterialTheme.typography.bodySmall,
            color = if (selectedMode == ServerConfig.NETWORK_MODE_TUNNELING) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    if (showTunnelingInfoDialog) {
        AlertDialog(
            onDismissRequest = {
                showTunnelingInfoDialog = false
                onTunnelingInfoDialogShown()
            },
            title = { Text("ngrok Free Tier Notice") },
            text = {
                Text(
                    "The ngrok free tier has usage limits. Bandwidth is capped and sessions may expire, " +
                        "which can interrupt your public tunnel. Visit ngrok.com for current tier details."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTunnelingInfoDialog = false
                        onTunnelingInfoDialogShown()
                    }
                ) {
                    Text("Got it")
                }
            },
        )
    }
}
