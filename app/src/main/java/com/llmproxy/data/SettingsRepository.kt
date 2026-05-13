package com.llmproxy.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
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

    val serverConfig: StateFlow<ServerConfig> = combine(
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
    ) { baseConfig, apiKey ->
        baseConfig.copy(apiKey = apiKey)
    }.stateIn(
        scope = applicationScope,
        started = SharingStarted.Eagerly,
        initialValue = ServerConfig(),
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

    private fun toBaseConfig(preferences: Preferences): ServerConfig {
        return ServerConfig(
            upstreamUrl = preferences[upstreamUrlKey].orEmpty(),
            listenPort = preferences[listenPortKey] ?: ServerConfig.DEFAULT_PORT,
            bindAddress = preferences[bindAddressKey] ?: ServerConfig.DEFAULT_BIND_ADDRESS,
        )
    }
}
