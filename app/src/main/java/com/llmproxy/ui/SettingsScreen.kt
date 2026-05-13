package com.llmproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@Composable
fun SettingsScreen(
    state: MainUiState,
    onUpstreamUrlChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onBindAddressChanged: (String) -> Unit,
    onExportCertificate: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        OutlinedTextField(
            value = state.config.bindAddress,
            onValueChange = onBindAddressChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bind address") },
            singleLine = true,
        )
        CertificateManagerCard(
            certificateReady = state.certificateReady,
            onExportCertificate = onExportCertificate,
        )
    }
}
