package com.elfred434.transciber.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.elfred434.transciber.BuildConfig
import com.elfred434.transciber.domain.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.edit

private val Context.settingsDataStore by preferencesDataStore(name = "transciber_settings")

class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val gatewayUrl = stringPreferencesKey("gateway_url")
        val gatewayToken = stringPreferencesKey("gateway_token")
        val targetLanguage = stringPreferencesKey("target_language")
        val autoSave = booleanPreferencesKey("auto_save_history")
        val incognitoMode = booleanPreferencesKey("incognito_mode")
        val backgroundMode = booleanPreferencesKey("background_mode")
        val theme = stringPreferencesKey("theme")
        val minSpeed = floatPreferencesKey("min_speed")
        val maxSpeed = floatPreferencesKey("max_speed")
        val proximity = booleanPreferencesKey("proximity_sensor")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            gatewayUrl = preferences[Keys.gatewayUrl] ?: BuildConfig.DEFAULT_GATEWAY_URL,
            gatewayToken = preferences[Keys.gatewayToken] ?: "",
            targetLanguage = preferences[Keys.targetLanguage] ?: "English",
            autoSaveHistory = preferences[Keys.autoSave] ?: true,
            incognitoMode = preferences[Keys.incognitoMode] ?: false,
            backgroundMode = preferences[Keys.backgroundMode] ?: false,
            theme = preferences[Keys.theme] ?: "system",
            minPlaybackSpeed = preferences[Keys.minSpeed] ?: 0.75f,
            maxPlaybackSpeed = preferences[Keys.maxSpeed] ?: 1.75f,
            proximitySensor = preferences[Keys.proximity] ?: false
        )
    }

    suspend fun setGatewayUrl(value: String) = context.settingsDataStore.edit { it[Keys.gatewayUrl] = value.trim() }
    suspend fun setGatewayToken(value: String) = context.settingsDataStore.edit { it[Keys.gatewayToken] = value.trim() }
    suspend fun setTargetLanguage(value: String) = context.settingsDataStore.edit { it[Keys.targetLanguage] = value }
    suspend fun setAutoSave(value: Boolean) = context.settingsDataStore.edit { it[Keys.autoSave] = value }
    suspend fun setIncognitoMode(value: Boolean) = context.settingsDataStore.edit { it[Keys.incognitoMode] = value }
    suspend fun setBackgroundMode(value: Boolean) = context.settingsDataStore.edit { it[Keys.backgroundMode] = value }
    suspend fun setTheme(value: String) = context.settingsDataStore.edit { it[Keys.theme] = value }
    suspend fun setProximity(value: Boolean) = context.settingsDataStore.edit { it[Keys.proximity] = value }
}
