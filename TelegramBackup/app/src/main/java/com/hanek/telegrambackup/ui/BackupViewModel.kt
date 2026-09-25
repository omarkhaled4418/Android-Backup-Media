package com.hanek.telegrambackup.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanek.telegrambackup.data.*
import com.hanek.telegrambackup.service.BackupService
import com.hanek.telegrambackup.service.BackupState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BackupUiState(
    val botToken: String = PreferencesManager.DEFAULT_BOT_TOKEN,
    val chatId: String = PreferencesManager.DEFAULT_CHAT_ID,
    val isConfigured: Boolean = true,
    val isConnected: Boolean = false,
    val connectionTestMessage: String = "",
    val isTesting: Boolean = false,
    val mediaItems: List<MediaItem> = emptyList(),
    val backedUpUris: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgress: Int = 0,
    val uploadTotal: Int = 0,
    val currentUploadName: String = "",
    val uploadErrors: List<String> = emptyList(),
    val totalUploaded: Int = 0,
    val backupPhotos: Boolean = true,
    val backupVideos: Boolean = true,
    val photoCount: Int = 0,
    val videoCount: Int = 0,
    val pendingCount: Int = 0
)

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsManager = PreferencesManager(application)
    private val mediaRepo = MediaRepository(application)
    private val telegramApi = TelegramApi(application)

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        // Mirror preferences into UI state.
        viewModelScope.launch {
            combine(
                prefsManager.botToken,
                prefsManager.chatId,
                prefsManager.backedUpUris,
                prefsManager.totalUploaded
            ) { values ->
                val token = values[0] as String
                val chat = values[1] as String
                @Suppress("UNCHECKED_CAST")
                val backed = values[2] as Set<String>
                val total = values[3] as Int
                _uiState.update {
                    it.copy(
                        botToken = token,
                        chatId = chat,
                        isConfigured = token.isNotBlank() && chat.isNotBlank(),
                        backedUpUris = backed,
                        backupPhotos = true,
                        backupVideos = true,
                        totalUploaded = total,
                        pendingCount = it.mediaItems.count { item -> item.uri.toString() !in backed }
                    )
                }
            }.collect()
        }

        // Mirror the service's shared progress into the UI state.
        viewModelScope.launch {
            BackupState.flow.collect { snap ->
                _uiState.update {
                    it.copy(
                        isUploading = snap.isUploading,
                        uploadProgress = snap.uploadProgress,
                        uploadTotal = snap.uploadTotal,
                        currentUploadName = snap.currentUploadName,
                        uploadErrors = snap.uploadErrors,
                        connectionTestMessage = snap.statusMessage.ifEmpty { it.connectionTestMessage }
                    )
                }
            }
        }
    }

    fun updateBotToken(token: String) {
        viewModelScope.launch {
            prefsManager.saveBotToken(token.trim())
        }
    }

    fun updateChatId(chatId: String) {
        viewModelScope.launch {
            prefsManager.saveChatId(chatId.trim())
        }
    }

    fun testConnection() {
        val state = _uiState.value
        if (state.botToken.isBlank() || state.chatId.isBlank()) {
            _uiState.update { it.copy(connectionTestMessage = "Please enter bot token and chat ID") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, connectionTestMessage = "") }
            when (val result = telegramApi.testConnection(state.botToken, state.chatId)) {
                is TelegramResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            isConnected = true,
                            connectionTestMessage = "✅ Connected successfully!"
                        )
                    }
                }
                is TelegramResult.RateLimited -> {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            isConnected = false,
                            connectionTestMessage = "⚠️ Rate limited: retry after ${result.retryAfterSeconds}s"
                        )
                    }
                }
                is TelegramResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            isConnected = false,
                            connectionTestMessage = "❌ ${result.message}"
                        )
                    }
                }
            }
        }
    }

    fun scanMedia() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            val media = withContext(Dispatchers.IO) {
                mediaRepo.getAllMedia(includePhotos = true, includeVideos = true)
            }
            val photos = media.count { !it.isVideo }
            val videos = media.count { it.isVideo }
            _uiState.update {
                it.copy(
                    isScanning = false,
                    mediaItems = media,
                    photoCount = photos,
                    videoCount = videos,
                    pendingCount = media.count { item -> item.uri.toString() !in it.backedUpUris }
                )
            }
        }
    }

    /** Starts (or resumes) the backup via the foreground service. */
    fun startAutoBackup(force: Boolean = false) {
        if (!force && _uiState.value.isUploading) return
        BackupService.start(getApplication())
    }

    /** Stops the backup service. */
    fun stopBackup() {
        BackupService.stop(getApplication())
    }

    /** Clears the upload history and starts a full re-backup. */
    fun clearHistory() {
        viewModelScope.launch {
            prefsManager.clearBackupHistory()
            scanMedia()
        }
    }
}
