package com.llmproxy.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.llmproxy.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "llm_proxy_settings")

class SettingsRepository(
    private val context: Context,
    applicationScope: CoroutineScope,
    private val securePreferences: SecurePreferences,
) {
    private val upstreamUrlKey = stringPreferencesKey("upstream_url")
    private val listenPortKey = intPreferencesKey("listen_port")
    private val bindAddressKey = stringPreferencesKey("bind_address")
    private val networkModeKey = stringPreferencesKey("network_mode")
    private val tunnelPublicUrlKey = stringPreferencesKey("tunnel_public_url")
    private val tunnelingInfoDialogShownKey = booleanPreferencesKey("tunneling_info_dialog_shown")
    private val batteryOptimizationGuideShownKey = booleanPreferencesKey("battery_optimization_guide_shown")
    private val batteryOptimizationGuideDontShowAgainKey = booleanPreferencesKey("battery_optimization_guide_dont_show_again")
    private val letsEncryptDomainKey = stringPreferencesKey("lets_encrypt_domain")
    private val letsEncryptAutoRenewKey = booleanPreferencesKey("lets_encrypt_auto_renew")
    private val enableWakeLockKey = booleanPreferencesKey("enable_wake_lock")
    private val enableWifiLockKey = booleanPreferencesKey("enable_wifi_lock")
    private val requireBearerAuthKey = booleanPreferencesKey("require_bearer_auth")
    private val maxRequestsPerMinuteKey = intPreferencesKey("max_requests_per_minute")

    val serverConfig: StateFlow<ServerConfig> = combine(
        combine(
            context.dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .map(::toBaseConfig),
            securePreferences.apiKeyFlow(),
            securePreferences.tunnelAuthTokenFlow(),
            securePreferences.cloudflareApiTokenFlow(),
            securePreferences.bearerTokenFlow(),
        ) { baseConfig, apiKey, tunnelAuthToken, cloudflareApiToken, bearerToken ->
            baseConfig.copy(
                apiKey = apiKey,
                tunnelAuthToken = tunnelAuthToken,
                cloudflareApiToken = cloudflareApiToken,
                bearerToken = bearerToken,
            )
        },
        securePreferences.webhookForwardUrlFlow(),
    ) { config, webhookForwardUrl ->
        config.copy(webhookForwardUrl = webhookForwardUrl)
    }.stateIn(
        scope = applicationScope,
        started = SharingStarted.Eagerly,
        initialValue = ServerConfig(),
    )

    val tunnelingInfoDialogShown: StateFlow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[tunnelingInfoDialogShownKey] ?: false }
        .stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    val batteryOptimizationGuideShown: StateFlow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[batteryOptimizationGuideShownKey] ?: false }
        .stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    val batteryOptimizationGuideDontShowAgain: StateFlow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[batteryOptimizationGuideDontShowAgainKey] ?: false }
        .stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    suspend fun updateUpstreamUrl(value: String) {
        context.dataStore.edit { preferences ->
            preferences[upstreamUrlKey] = value.trim()
        }
    }

    suspend fun updateListenPort(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[listenPortKey] = value
        }
    }

    suspend fun updateBindAddress(value: String) {
        context.dataStore.edit { preferences ->
            preferences[bindAddressKey] = value.trim().ifBlank { ServerConfig.DEFAULT_BIND_ADDRESS }
        }
    }

    suspend fun updateApiKey(value: String) {
        securePreferences.setApiKey(value.trim())
    }

    suspend fun updateNetworkMode(value: String) {
        context.dataStore.edit { preferences ->
            preferences[networkModeKey] = value
        }
    }

    suspend fun updateTunnelAuthToken(value: String) {
        securePreferences.setTunnelAuthToken(value.trim())
    }

    suspend fun updateLetsEncryptDomain(value: String) {
        context.dataStore.edit { preferences ->
            preferences[letsEncryptDomainKey] = value.trim()
        }
    }

    suspend fun updateLetsEncryptAutoRenew(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[letsEncryptAutoRenewKey] = value
        }
    }

    suspend fun updateEnableWakeLock(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[enableWakeLockKey] = value
        }
    }

    suspend fun updateEnableWifiLock(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[enableWifiLockKey] = value
        }
    }

    suspend fun updateCloudflareApiToken(value: String) {
        securePreferences.setCloudflareApiToken(value.trim())
    }

    suspend fun updateWebhookForwardUrl(value: String) {
        securePreferences.setWebhookForwardUrl(value.trim())
    }

    suspend fun updateBearerToken(value: String) {
        securePreferences.setBearerToken(value.trim())
    }

    suspend fun updateRequireBearerAuth(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[requireBearerAuthKey] = value
        }
    }

    suspend fun updateMaxRequestsPerMinute(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[maxRequestsPerMinuteKey] = value.coerceAtLeast(1)
        }
    }

    /** Persists the last known tunnel public URL so it survives app restarts. */
    suspend fun updateTunnelPublicUrl(value: String?) {
        context.dataStore.edit { preferences ->
            if (value != null) {
                preferences[tunnelPublicUrlKey] = value
            } else {
                preferences.remove(tunnelPublicUrlKey)
            }
        }
    }

    suspend fun markTunnelingInfoDialogShown() {
        context.dataStore.edit { preferences ->
            preferences[tunnelingInfoDialogShownKey] = true
        }
    }

    suspend fun markBatteryOptimizationGuideShown() {
        context.dataStore.edit { preferences ->
            preferences[batteryOptimizationGuideShownKey] = true
        }
    }

    suspend fun updateBatteryOptimizationGuideDontShowAgain(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[batteryOptimizationGuideDontShowAgainKey] = value
        }
    }

    private fun toBaseConfig(preferences: Preferences): ServerConfig {
        val networkMode = preferences[networkModeKey] ?: ServerConfig.NETWORK_MODE_LOCAL
        val explicitRequireBearerAuth = preferences[requireBearerAuthKey]
        return ServerConfig(
            upstreamUrl = preferences[upstreamUrlKey].orEmpty(),
            listenPort = preferences[listenPortKey] ?: ServerConfig.DEFAULT_PORT,
            bindAddress = preferences[bindAddressKey] ?: ServerConfig.DEFAULT_BIND_ADDRESS,
            networkMode = networkMode,
            letsEncryptDomain = preferences[letsEncryptDomainKey].orEmpty(),
            letsEncryptAutoRenew = preferences[letsEncryptAutoRenewKey] ?: false,
            enableWakeLock = preferences[enableWakeLockKey] ?: false,
            enableWifiLock = preferences[enableWifiLockKey] ?: false,
            requireBearerAuth = explicitRequireBearerAuth
                ?: (networkMode == ServerConfig.NETWORK_MODE_TUNNELING),
            maxRequestsPerMinute = preferences[maxRequestsPerMinuteKey]
                ?: ServerConfig.DEFAULT_MAX_REQUESTS_PER_MINUTE,
        )
    }
}
