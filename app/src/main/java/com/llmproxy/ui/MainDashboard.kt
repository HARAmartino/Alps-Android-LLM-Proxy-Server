package com.llmproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llmproxy.model.MainUiState
import com.llmproxy.model.ServerStatus

@Composable
fun MainDashboard(
    state: MainUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
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
                state.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(text = error, color = MaterialTheme.colorScheme.error)
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
        }
    }
}
