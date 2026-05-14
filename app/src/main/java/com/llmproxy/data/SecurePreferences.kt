package com.llmproxy.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SecurePreferences(context: Context) {
    private val sharedPreferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val apiKeyState = MutableStateFlow(sharedPreferences.getString(KEY_API_KEY, "").orEmpty())
    private val tunnelAuthTokenState = MutableStateFlow(
        sharedPreferences.getString(KEY_TUNNEL_AUTH_TOKEN, "").orEmpty()
    )
    private val cloudflareApiTokenState = MutableStateFlow(
        sharedPreferences.getString(KEY_CLOUDFLARE_API_TOKEN, "").orEmpty()
    )
    private val webhookForwardUrlState = MutableStateFlow(
        sharedPreferences.getString(KEY_WEBHOOK_FORWARD_URL, "").orEmpty()
    )
    private val bearerTokenState = MutableStateFlow(
        sharedPreferences.getString(KEY_BEARER_TOKEN, "").orEmpty()
    )

    fun apiKeyFlow(): StateFlow<String> = apiKeyState.asStateFlow()

    fun tunnelAuthTokenFlow(): StateFlow<String> = tunnelAuthTokenState.asStateFlow()

    fun cloudflareApiTokenFlow(): StateFlow<String> = cloudflareApiTokenState.asStateFlow()

    fun webhookForwardUrlFlow(): StateFlow<String> = webhookForwardUrlState.asStateFlow()

    fun bearerTokenFlow(): StateFlow<String> = bearerTokenState.asStateFlow()

    suspend fun setApiKey(apiKey: String) {
        sharedPreferences.edit().putString(KEY_API_KEY, apiKey).apply()
        apiKeyState.value = apiKey
    }

    suspend fun setTunnelAuthToken(token: String) {
        sharedPreferences.edit().putString(KEY_TUNNEL_AUTH_TOKEN, token).apply()
        tunnelAuthTokenState.value = token
    }

    suspend fun setCloudflareApiToken(token: String) {
        sharedPreferences.edit().putString(KEY_CLOUDFLARE_API_TOKEN, token).apply()
        cloudflareApiTokenState.value = token
    }

    suspend fun setWebhookForwardUrl(url: String) {
        sharedPreferences.edit().putString(KEY_WEBHOOK_FORWARD_URL, url).apply()
        webhookForwardUrlState.value = url
    }

    suspend fun setBearerToken(token: String) {
        sharedPreferences.edit().putString(KEY_BEARER_TOKEN, token).apply()
        bearerTokenState.value = token
    }

    companion object {
        private const val FILE_NAME = "secure_settings"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TUNNEL_AUTH_TOKEN = "tunnel_auth_token"
        private const val KEY_CLOUDFLARE_API_TOKEN = "cloudflare_api_token"
        private const val KEY_WEBHOOK_FORWARD_URL = "webhook_forward_url"
        private const val KEY_BEARER_TOKEN = "bearer_token"
    }
}
