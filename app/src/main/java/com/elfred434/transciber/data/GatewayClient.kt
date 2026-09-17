package com.elfred434.transciber.data

import com.elfred434.transciber.domain.AppSettings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Singleton
class GatewayClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore
) {
    private suspend fun settings(): AppSettings = settingsStore.settings.first()

    suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String =
        withContext(Dispatchers.IO) {
            val settings = settings()
            val body = JSONObject()
                .put("text", text)
                .put("sourceLanguage", sourceLanguage)
                .put("targetLanguage", targetLanguage)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val response = execute("v1/translate", settings, body)
            response.optString("translation").takeIf { it.isNotBlank() }
                ?: throw IOException(response.optString("error", "La traduction est vide."))
        }

    suspend fun summarize(text: String, language: String): String = withContext(Dispatchers.IO) {
        val settings = settings()
        val body = JSONObject()
            .put("text", text)
            .put("language", language)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val response = execute("v1/summarize", settings, body)
        response.optString("summary").takeIf { it.isNotBlank() }
            ?: throw IOException(response.optString("error", "Le résumé est vide."))
    }

    private fun execute(path: String, settings: AppSettings, body: RequestBody): JSONObject {
        val rawBase = settings.gatewayUrl.trim()
        if (rawBase.isBlank()) {
            throw IOException("Configurez l'URL du gateway Gemini dans Réglages.")
        }
        val base = if (rawBase.endsWith('/')) rawBase else "$rawBase/"
        val requestBuilder = Request.Builder().url(base + path).post(body)
        if (settings.gatewayToken.isNotBlank()) {
            requestBuilder.header("X-Client-Token", settings.gatewayToken)
        }
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", raw) }
            if (!response.isSuccessful) {
                throw IOException(json.optString("error", "Erreur du gateway (${response.code})."))
            }
            return json
        }
    }
}
