package com.llmproxy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.llmproxy.model.MainUiEffect
import com.llmproxy.ui.LlmProxyApp
import com.llmproxy.ui.MainViewModel
import com.llmproxy.ui.theme.LlmProxyTheme
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application as LlmProxyApplication)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        mainViewModel.effects.onEach { effect ->
            when (effect) {
                is MainUiEffect.ExportCertificate -> startActivity(
                    android.content.Intent.createChooser(
                        (application as LlmProxyApplication).sslCertGenerator.createExportIntent(),
                        effect.chooserTitle,
                    )
                )
                is MainUiEffect.ExportAccessLogs -> startActivity(
                    android.content.Intent.createChooser(
                        (application as LlmProxyApplication).accessLogger.createExportIntent(),
                        effect.chooserTitle,
                    )
                )
                is MainUiEffect.ExportSystemLogs -> startActivity(
                    android.content.Intent.createChooser(
                        (application as LlmProxyApplication).systemLogger.createExportIntent(),
                        effect.chooserTitle,
                    )
                )
                is MainUiEffect.ShowMessage -> Unit
                is MainUiEffect.ShowRenewalMessage -> Unit // handled by LlmProxyApp snackbar
                is MainUiEffect.ShowRenewalResult -> Unit  // handled by LlmProxyApp snackbar
            }
        }.launchIn(lifecycleScope)

        setContent {
            LlmProxyTheme {
                LlmProxyApp(viewModel = mainViewModel)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
