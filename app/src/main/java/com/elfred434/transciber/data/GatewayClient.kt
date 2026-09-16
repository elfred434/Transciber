package com.elfred434.transciber.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.elfred434.transciber.domain.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class GatewayClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context
) {
    private suspend fun settings(): AppSettings = settingsStore.settings.first()

    suspend fun transcribe(uri: Uri): String = withContext(Dispatchers.IO) {
        val settings = settings()
        val response = multipartRequest("v1/transcribe", settings, uri)
        response.optString("text").takeIf { it.isNotBlank() }
            ?: throw IOException(response.optString("error", "La transcription est vide."))
    }

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

    private fun multipartRequest(path: String, settings: AppSettings, uri: Uri): JSONObject {
        val mime = context.contentResolver.getType(uri) ?: "audio/ogg"
        val fileName = displayName(context.contentResolver, uri) ?: "whatsapp-voice.ogg"
        val fileBody = UriRequestBody(context.contentResolver, uri, mime)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", fileName, fileBody)
            .addFormDataPart("mimeType", mime)
            .build()
        return execute(path, settings, body)
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

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}

private class UriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mime: String
) : RequestBody() {
    override fun contentType() = mime.toMediaTypeOrNull()

    override fun contentLength(): Long = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: -1L

    override fun writeTo(sink: BufferedSink) {
        resolver.openInputStream(uri)?.use { input -> sink.writeAll(input.source()) }
            ?: throw IOException("Impossible de lire le fichier audio.")
    }
}
