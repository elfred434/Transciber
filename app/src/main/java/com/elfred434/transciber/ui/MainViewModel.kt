package com.elfred434.transciber.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elfred434.transciber.data.GatewayClient
import com.elfred434.transciber.data.HistoryDao
import com.elfred434.transciber.data.LocalTranscriber
import com.elfred434.transciber.data.HistoryEntity
import com.elfred434.transciber.data.SettingsStore
import com.elfred434.transciber.domain.AppSettings
import com.elfred434.transciber.domain.HistoryItem
import com.elfred434.transciber.domain.MainUiState
import com.elfred434.transciber.domain.ProcessingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val gatewayClient: GatewayClient,
    private val localTranscriber: LocalTranscriber,
    private val historyDao: HistoryDao,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private var currentHistoryId: Long? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playerPreparing = false

    init {
        viewModelScope.launch {
            combine(settingsStore.settings, historyDao.observeAll()) { settings, history ->
                settings to history
            }.collect { (settings, history) ->
                _state.value = _state.value.copy(
                    settings = settings,
                    history = history.map { it.toDomain() }
                )
            }
        }
    }

    fun acceptIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: intent.clipData?.getItemAt(0)?.uri
            Intent.ACTION_SEND_MULTIPLE -> intent.clipData?.getItemAt(0)?.uri
            else -> null
        } ?: return
        acceptAudio(uri)
    }

    fun acceptAudio(uri: Uri) {
        releasePlayer()
        val name = context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "Message vocal WhatsApp"
        currentHistoryId = null
        _state.value = _state.value.copy(
            audioUri = uri,
            audioName = name,
            transcript = "",
            translation = "",
            summary = "",
            status = ProcessingStatus.READY,
            errorMessage = null,
            isPlaying = false,
            playbackSpeed = 1f
        )
    }

    fun togglePlayback() {
        val uri = _state.value.audioUri ?: return showError("Sélectionnez d'abord un message vocal.")
        if (playerPreparing) return
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) {
                    player.pause()
                    _state.value = _state.value.copy(isPlaying = false)
                } else {
                    player.start()
                    _state.value = _state.value.copy(isPlaying = true)
                }
            }.onFailure {
                releasePlayer()
                showError("La lecture de ce fichier audio a échoué.")
            }
            return
        }

        runCatching {
            playerPreparing = true
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, uri)
                setOnPreparedListener { player ->
                    playerPreparing = false
                    applyPlaybackSpeed(player)
                    player.start()
                    _state.value = _state.value.copy(isPlaying = true, errorMessage = null)
                }
                setOnCompletionListener {
                    _state.value = _state.value.copy(isPlaying = false)
                    releasePlayer()
                }
                setOnErrorListener { _, _, _ ->
                    playerPreparing = false
                    releasePlayer()
                    showError("La lecture de ce fichier audio a échoué.")
                    true
                }
                mediaPlayer = this
                prepareAsync()
            }
        }.onFailure {
            playerPreparing = false
            releasePlayer()
            showError("La lecture de ce fichier audio a échoué.")
        }
    }

    fun pausePlayback() {
        mediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.pause() }
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2f)
        _state.value = _state.value.copy(playbackSpeed = clamped)
        mediaPlayer?.let(::applyPlaybackSpeed)
    }

    fun onProximityChanged(isNear: Boolean) {
        if (isNear && _state.value.settings.proximitySensor) pausePlayback()
    }

    private fun applyPlaybackSpeed(player: MediaPlayer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                player.playbackParams = player.playbackParams.setSpeed(_state.value.playbackSpeed)
            }
        }
    }

    private fun releasePlayer() {
        playerPreparing = false
        mediaPlayer?.let { player -> runCatching { player.release() } }
        mediaPlayer = null
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun transcribe() {
        val uri = _state.value.audioUri ?: return showError("Sélectionnez d'abord un message vocal.")
        viewModelScope.launch {
            setStatus(ProcessingStatus.TRANSCRIBING)
            runCatching { localTranscriber.transcribe(uri) }
                .onSuccess { text ->
                    _state.value = _state.value.copy(
                        transcript = text,
                        translation = "",
                        summary = "",
                        status = ProcessingStatus.SUCCESS,
                        errorMessage = null
                    )
                    maybeSave()
                }
                .onFailure { showError(it.message ?: "La transcription a échoué.") }
        }
    }

    fun translate() {
        val text = _state.value.transcript.takeIf { it.isNotBlank() }
            ?: return showError("Transcrivez le vocal avant de le traduire.")
        viewModelScope.launch {
            setStatus(ProcessingStatus.TRANSLATING)
            runCatching {
                gatewayClient.translate(text, "auto", _state.value.settings.targetLanguage)
            }.onSuccess { translated ->
                _state.value = _state.value.copy(
                    translation = translated,
                    status = ProcessingStatus.SUCCESS,
                    errorMessage = null
                )
                maybeSave()
            }.onFailure { showError(it.message ?: "La traduction a échoué.") }
        }
    }

    fun summarize() {
        val text = _state.value.transcript.takeIf { it.isNotBlank() }
            ?: return showError("Transcrivez le vocal avant de le résumer.")
        viewModelScope.launch {
            setStatus(ProcessingStatus.SUMMARIZING)
            runCatching {
                summarizeLocally(text)
            }.onSuccess { summary ->
                _state.value = _state.value.copy(
                    summary = summary,
                    status = ProcessingStatus.SUCCESS,
                    errorMessage = null
                )
                maybeSave()
            }.onFailure { showError(it.message ?: "Le résumé a échoué.") }
        }
    }

    fun saveCurrent() {
        viewModelScope.launch { saveSnapshot() }
    }

    fun clearCurrent() {
        releasePlayer()
        currentHistoryId = null
        _state.value = _state.value.copy(
            audioUri = null,
            audioName = null,
            transcript = "",
            translation = "",
            summary = "",
            status = ProcessingStatus.IDLE,
            errorMessage = null
        )
    }

    fun setGatewayUrl(value: String) = viewModelScope.launch { settingsStore.setGatewayUrl(value) }
    fun setGatewayToken(value: String) = viewModelScope.launch { settingsStore.setGatewayToken(value) }
    fun setTargetLanguage(value: String) = viewModelScope.launch { settingsStore.setTargetLanguage(value) }
    fun setAutoSave(value: Boolean) = viewModelScope.launch { settingsStore.setAutoSave(value) }
    fun setIncognitoMode(value: Boolean) = viewModelScope.launch { settingsStore.setIncognitoMode(value) }
    fun setBackgroundMode(value: Boolean) = viewModelScope.launch { settingsStore.setBackgroundMode(value) }
    fun setProximity(value: Boolean) = viewModelScope.launch { settingsStore.setProximity(value) }
    fun clearHistory() = viewModelScope.launch { historyDao.clear() }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Partager le résultat").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun summarizeLocally(text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        val sentences = normalized
            .split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        return when {
            sentences.size <= 3 -> normalized
            else -> sentences.take(3).joinToString(" ") + " …"
        }
    }

    private suspend fun maybeSave() {
        if (_state.value.settings.autoSaveHistory && !_state.value.settings.incognitoMode) saveSnapshot()
    }

    private suspend fun saveSnapshot() {
        val current = _state.value
        if (current.transcript.isBlank() || current.settings.incognitoMode) return
        val entity = HistoryEntity(
            id = currentHistoryId ?: 0,
            audioName = current.audioName ?: "Message vocal WhatsApp",
            transcript = current.transcript,
            translation = current.translation,
            summary = current.summary,
            sourceLanguage = "auto",
            targetLanguage = current.settings.targetLanguage
        )
        if (currentHistoryId == null) {
            currentHistoryId = historyDao.insert(entity)
        } else {
            historyDao.update(entity)
        }
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    private fun setStatus(status: ProcessingStatus) {
        _state.value = _state.value.copy(status = status, errorMessage = null)
    }

    fun showError(message: String) {
        _state.value = _state.value.copy(status = ProcessingStatus.ERROR, errorMessage = message)
    }

    private fun HistoryEntity.toDomain() = HistoryItem(
        id = id,
        audioName = audioName,
        transcript = transcript,
        translation = translation,
        summary = summary,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        createdAt = createdAt
    )
}
