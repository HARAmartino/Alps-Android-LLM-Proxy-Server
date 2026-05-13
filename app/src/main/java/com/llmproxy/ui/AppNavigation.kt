package com.llmproxy.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

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

    Scaffold(
        modifier = modifier,
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
                    onExportCertificate = viewModel::onExportCertificateRequested,
                )
            }
        }
    }
}
