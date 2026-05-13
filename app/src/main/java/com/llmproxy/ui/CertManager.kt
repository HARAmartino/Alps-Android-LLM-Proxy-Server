package com.llmproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CertificateManagerCard(
    certificateReady: Boolean,
    onExportCertificate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Certificate", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (certificateReady) {
                    "A self-signed TLS certificate is available for local HTTPS access."
                } else {
                    "The certificate will be generated automatically on first launch."
                },
            )
            Button(onClick = onExportCertificate) {
                Text(text = "Export .crt file")
            }
        }
    }
}
