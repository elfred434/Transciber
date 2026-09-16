package com.elfred434.transciber.domain

import android.net.Uri

enum class ProcessingStatus {
    IDLE,
    READY,
    TRANSCRIBING,
    TRANSLATING,
    SUMMARIZING,
    SUCCESS,
    ERROR
}

data class AppSettings(
    val gatewayUrl: String = "",
    val gatewayToken: String = "",
    val targetLanguage: String = "English",
    val autoSaveHistory: Boolean = true,
    val incognitoMode: Boolean = false,
    val backgroundMode: Boolean = false,
    val theme: String = "system",
    val minPlaybackSpeed: Float = 0.75f,
    val maxPlaybackSpeed: Float = 1.75f,
    val proximitySensor: Boolean = false
)

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val history: List<HistoryItem> = emptyList(),
    val audioUri: Uri? = null,
    val audioName: String? = null,
    val transcript: String = "",
    val translation: String = "",
    val summary: String = "",
    val status: ProcessingStatus = ProcessingStatus.IDLE,
    val errorMessage: String? = null,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1f
)

data class HistoryItem(
    val id: Long,
    val audioName: String,
    val transcript: String,
    val translation: String,
    val summary: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val createdAt: Long
)
