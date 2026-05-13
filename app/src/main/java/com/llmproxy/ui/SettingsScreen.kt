package com.llmproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.llmproxy.model.MainUiState
import com.llmproxy.model.ServerConfig

@Composable
fun SettingsScreen(
    state: MainUiState,
    onUpstreamUrlChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onBindAddressChanged: (String) -> Unit,
    onNetworkModeChanged: (String) -> Unit,
    onTunnelAuthTokenChanged: (String) -> Unit,
    onExportCertificate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTunneling = state.config.networkMode == ServerConfig.NETWORK_MODE_TUNNELING

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)
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

        // Network mode selector: "local" uses 0.0.0.0, "tunneling" binds to 127.0.0.1 + ngrok.
        NetworkModeSelector(
            selectedMode = state.config.networkMode,
            onModeSelected = onNetworkModeChanged,
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

        CertificateManagerCard(
            certificateReady = state.certificateReady,
            onExportCertificate = onExportCertificate,
        )
    }
}

@Composable
private fun NetworkModeSelector(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
) {
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
                onClick = { onModeSelected(ServerConfig.NETWORK_MODE_TUNNELING) },
                label = { Text("Tunneling (ngrok)") },
            )
        }
    }
}
