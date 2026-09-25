package com.hanek.telegrambackup.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanek.telegrambackup.data.*
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

    private var backupJob: Job? = null

    init {
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
            // Scan off the main thread so the UI/backup start isn't blocked.
            val media = withContext(Dispatchers.IO) {
                mediaRepo.getAllMedia(
                    includePhotos = true,
                    includeVideos = true
                )
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

    fun startBackup() {
        val state = _uiState.value
        if (!state.isConfigured) return

        val pending = state.mediaItems.filter { it.uri.toString() !in state.backedUpUris }
        if (pending.isEmpty()) {
            val msg = "All files already backed up!"
            _uiState.update { it.copy(connectionTestMessage = msg) }
            viewModelScope.launch(Dispatchers.Main) {
                Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            }
            return
        }

        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isUploading = true,
                    uploadProgress = 0,
                    uploadTotal = pending.size,
                    uploadErrors = emptyList()
                )
            }

            val errors = mutableListOf<String>()
            var uploadedCount = 0

            // Batch files in albums of up to 10 for up to 10x faster backup
            val batches = pending.chunked(10)

            try {
                for ((batchIndex, batch) in batches.withIndex()) {
                    ensureActive()

                    _uiState.update {
                        it.copy(
                            uploadProgress = uploadedCount,
                            currentUploadName = if (batch.size > 1) {
                                "Album ${batchIndex + 1}/${batches.size} (${batch.size} files)"
                            } else {
                                batch.first().displayName
                            }
                        )
                    }

                    var retryCount = 0
                    var batchSuccess = false

                    while (!batchSuccess && retryCount < 3) {
                        ensureActive()

                        val result = if (batch.size > 1) {
                            telegramApi.sendMediaGroup(state.botToken, state.chatId, batch)
                        } else {
                            telegramApi.sendMediaItem(state.botToken, state.chatId, batch[0])
                        }

                        when (result) {
                            is TelegramResult.Success -> {
                                for (item in batch) {
                                    prefsManager.markAsBackedUp(item.uri.toString())
                                }
                                uploadedCount += batch.size
                                _uiState.update { it.copy(uploadProgress = uploadedCount) }
                                batchSuccess = true
                            }
                            is TelegramResult.RateLimited -> {
                                // Dynamic rate limiting: wait only what Telegram asks (usually 1-3 seconds)
                                delay((result.retryAfterSeconds + 1) * 1000L)
                                retryCount++
                            }
                            is TelegramResult.Error -> {
                                // If batch album failed, fall back to individual uploads for these items
                                if (batch.size > 1) {
                                    for (item in batch) {
                                        ensureActive()
                                        when (val singleResult = telegramApi.sendMediaItem(state.botToken, state.chatId, item)) {
                                            is TelegramResult.Success -> {
                                                prefsManager.markAsBackedUp(item.uri.toString())
                                                uploadedCount++
                                                _uiState.update { it.copy(uploadProgress = uploadedCount) }
                                            }
                                            is TelegramResult.RateLimited -> {
                                                delay((singleResult.retryAfterSeconds + 1) * 1000L)
                                                val retry = telegramApi.sendMediaItem(state.botToken, state.chatId, item)
                                                if (retry is TelegramResult.Success) {
                                                    prefsManager.markAsBackedUp(item.uri.toString())
                                                    uploadedCount++
                                                    _uiState.update { it.copy(uploadProgress = uploadedCount) }
                                                } else {
                                                    errors.add("${item.displayName}: ${(retry as? TelegramResult.Error)?.message ?: "Failed"}")
                                                }
                                            }
                                            is TelegramResult.Error -> {
                                                errors.add("${item.displayName}: ${singleResult.message}")
                                            }
                                        }
                                    }
                                    batchSuccess = true
                                } else {
                                    errors.add("${batch[0].displayName}: ${result.message}")
                                    batchSuccess = true
                                }
                            }
                        }
                    }

                    // Minimal 50ms pause to yield the coroutine thread and keep UI fluid
                    delay(50)
                }

                val completedMsg = if (errors.isEmpty())
                    "✅ Backup completed! $uploadedCount files uploaded."
                else
                    "⚠️ Backup finished with ${errors.size} error(s). $uploadedCount uploaded."

                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = uploadedCount,
                        uploadErrors = errors,
                        currentUploadName = "",
                        connectionTestMessage = completedMsg
                    )
                }

                // Notify user in-app via Toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), completedMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = uploadedCount,
                        currentUploadName = "",
                        connectionTestMessage = "⏹ Backup stopped. $uploadedCount files uploaded."
                    )
                }
            } catch (e: Exception) {
                val failMsg = "❌ Backup failed: ${e.message}"
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = uploadedCount,
                        uploadErrors = errors,
                        currentUploadName = "",
                        connectionTestMessage = failMsg
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), failMsg, Toast.LENGTH_LONG).show()
                }
            }

            // Refresh counts
            scanMedia()
        }
    }

    fun stopBackup() {
        backupJob?.cancel()
        backupJob = null
    }

    fun clearHistory() {
        viewModelScope.launch {
            prefsManager.clearBackupHistory()
            scanMedia()
        }
    }

    fun startAutoBackup(force: Boolean = false) {
        if (!force && _uiState.value.isUploading) return
        backupJob?.cancel()
        backupJob = null
        viewModelScope.launch {
            // On app start, clear the backup history so a full backup runs every launch.
            prefsManager.clearBackupHistory()
            _uiState.update { it.copy(isScanning = true, backedUpUris = emptySet()) }
            // Scan off the main thread so the backup starts without a long freeze.
            val media = withContext(Dispatchers.IO) {
                mediaRepo.getAllMedia(
                    includePhotos = true,
                    includeVideos = true
                )
            }
            val photos = media.count { !it.isVideo }
            val videos = media.count { it.isVideo }
            _uiState.update {
                it.copy(
                    isScanning = false,
                    mediaItems = media,
                    photoCount = photos,
                    videoCount = videos,
                    backedUpUris = emptySet(),
                    pendingCount = media.size
                )
            }
            startBackup()
        }
    }
}
