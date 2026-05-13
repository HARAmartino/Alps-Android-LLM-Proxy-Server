package com.llmproxy.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.llmproxy.model.MainUiEffect

private const val DASHBOARD_ROUTE = "dashboard"
private const val SETTINGS_ROUTE = "settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmProxyApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: DASHBOARD_ROUTE

    val snackbarHostState = remember { SnackbarHostState() }

    // Collect renewal effects and show them as snackbars with an optional retry action.
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MainUiEffect.ShowRenewalMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.message)
                }
                is MainUiEffect.ShowRenewalResult -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = if (effect.canRetry) "Retry" else null,
                    )
                    if (result == SnackbarResult.ActionPerformed && effect.canRetry) {
                        viewModel.onRequestCertificateRequested()
                    }
                }
                else -> {} // ExportCertificate / ExportAccessLogs / ExportSystemLogs / ShowMessage are handled in MainActivity.
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (currentRoute == DASHBOARD_ROUTE) "Dashboard" else "Settings") },
                navigationIcon = {
                    if (currentRoute != DASHBOARD_ROUTE) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (currentRoute == DASHBOARD_ROUTE) {
                        IconButton(onClick = { navController.navigate(SETTINGS_ROUTE) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = DASHBOARD_ROUTE,
            modifier = Modifier.padding(paddingValues),
        ) {
            composable(DASHBOARD_ROUTE) {
                MainDashboard(
                    state = state,
                    onStartClick = viewModel::onStartRequested,
                    onStopClick = viewModel::onStopRequested,
                    onManualReconnectClick = viewModel::onManualTunnelReconnectRequested,
                    onRetryAcmeClick = viewModel::onRequestCertificateRequested,
                    onSettingsClick = { navController.navigate(SETTINGS_ROUTE) },
                )
            }
            composable(SETTINGS_ROUTE) {
                SettingsScreen(
                    state = state,
                    onUpstreamUrlChanged = viewModel::onUpstreamUrlChanged,
                    onApiKeyChanged = viewModel::onApiKeyChanged,
                    onPortChanged = viewModel::onPortChanged,
                    onBindAddressChanged = viewModel::onBindAddressChanged,
                    onNetworkModeChanged = viewModel::onNetworkModeChanged,
                    onTunnelAuthTokenChanged = viewModel::onTunnelAuthTokenChanged,
                    onLetsEncryptDomainChanged = viewModel::onLetsEncryptDomainChanged,
                    onCloudflareApiTokenChanged = viewModel::onCloudflareApiTokenChanged,
                    onLetsEncryptAutoRenewChanged = viewModel::onLetsEncryptAutoRenewChanged,
                    onRequestCertificate = viewModel::onRequestCertificateRequested,
                    onTunnelingInfoDialogShown = viewModel::onTunnelingInfoDialogShown,
                    onBatteryOptimizationGuideHandled = viewModel::onBatteryOptimizationGuideHandled,
                    onExportCertificate = viewModel::onExportCertificateRequested,
                    onExportAccessLogs = viewModel::onExportAccessLogsRequested,
                    onExportSystemLogs = viewModel::onExportSystemLogsRequested,
                    onWebhookForwardUrlChanged = viewModel::onWebhookForwardUrlChanged,
                    onEnableWakeLockChanged = viewModel::onEnableWakeLockChanged,
                    onEnableWifiLockChanged = viewModel::onEnableWifiLockChanged,
                )
            }
        }
    }
}
