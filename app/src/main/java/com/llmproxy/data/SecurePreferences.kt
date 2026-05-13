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

    fun apiKeyFlow(): StateFlow<String> = apiKeyState.asStateFlow()

    suspend fun setApiKey(apiKey: String) {
        sharedPreferences.edit().putString(KEY_API_KEY, apiKey).apply()
        apiKeyState.value = apiKey
    }

    companion object {
        private const val FILE_NAME = "secure_settings"
        private const val KEY_API_KEY = "api_key"
    }
}
