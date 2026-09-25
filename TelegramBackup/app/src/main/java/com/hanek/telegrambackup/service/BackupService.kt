package com.hanek.telegrambackup.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hanek.telegrambackup.TelegramBackupApp
import com.hanek.telegrambackup.data.MediaRepository
import com.hanek.telegrambackup.data.PreferencesManager
import com.hanek.telegrambackup.data.TelegramApi
import com.hanek.telegrambackup.data.TelegramResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BackupService : Service() {

    companion object {
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.hanek.telegrambackup.action.START"
        const val ACTION_STOP = "com.hanek.telegrambackup.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, BackupService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackupService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        // Android requires a notification for foreground services — keep it minimal and silent.
        startForeground(NOTIF_ID, buildSilentNotification())

        if (job?.isActive == true) {
            return START_STICKY
        }

        job = scope.launch {
            try {
                runBackup()
            } finally {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun runBackup() {
        val prefs = PreferencesManager(applicationContext)
        val media = MediaRepository(applicationContext)
        val api = TelegramApi(applicationContext)

        val token = prefs.botToken.first()
        val chatId = prefs.chatId.first()
        if (token.isBlank() || chatId.isBlank()) {
            BackupState.flow.update { it.copy(statusMessage = "Not configured", isUploading = false) }
            return
        }

        val allMedia = media.getAllMedia(includePhotos = true, includeVideos = true)
        val backedUp = prefs.backedUpUris.first()
        val pending = allMedia.filter { it.uri.toString() !in backedUp }

        if (pending.isEmpty()) {
            BackupState.flow.update {
                it.copy(
                    isUploading = false,
                    uploadProgress = 0,
                    uploadTotal = 0,
                    currentUploadName = "",
                    statusMessage = "All files already backed up!"
                )
            }
            return
        }

        BackupState.flow.update {
            it.copy(
                isUploading = true,
                uploadProgress = 0,
                uploadTotal = pending.size,
                currentUploadName = "",
                uploadErrors = emptyList(),
                statusMessage = ""
            )
        }

        val errors = mutableListOf<String>()
        var uploaded = 0
        val batches = pending.chunked(10)

        try {
            for ((batchIndex, batch) in batches.withIndex()) {
                currentCoroutineContext().ensureActive()

                val label = if (batch.size > 1) {
                    "Album ${batchIndex + 1}/${batches.size} (${batch.size} files)"
                } else {
                    batch.first().displayName
                }
                BackupState.flow.update { it.copy(currentUploadName = label, uploadProgress = uploaded) }

                var retryCount = 0
                var batchSuccess = false

                while (!batchSuccess && retryCount < 3) {
                    currentCoroutineContext().ensureActive()

                    val result = if (batch.size > 1) {
                        api.sendMediaGroup(token, chatId, batch)
                    } else {
                        api.sendMediaItem(token, chatId, batch[0])
                    }

                    when (result) {
                        is TelegramResult.Success -> {
                            for (item in batch) {
                                prefs.markAsBackedUp(item.uri.toString())
                            }
                            uploaded += batch.size
                            BackupState.flow.update { it.copy(uploadProgress = uploaded) }
                            batchSuccess = true
                        }
                        is TelegramResult.RateLimited -> {
                            delay((result.retryAfterSeconds + 1) * 1000L)
                            retryCount++
                        }
                        is TelegramResult.Error -> {
                            if (batch.size > 1) {
                                for (item in batch) {
                                    currentCoroutineContext().ensureActive()
                                    when (val single = api.sendMediaItem(token, chatId, item)) {
                                        is TelegramResult.Success -> {
                                            prefs.markAsBackedUp(item.uri.toString())
                                            uploaded++
                                            BackupState.flow.update { it.copy(uploadProgress = uploaded) }
                                        }
                                        is TelegramResult.RateLimited -> {
                                            delay((single.retryAfterSeconds + 1) * 1000L)
                                            val retry = api.sendMediaItem(token, chatId, item)
                                            if (retry is TelegramResult.Success) {
                                                prefs.markAsBackedUp(item.uri.toString())
                                                uploaded++
                                                BackupState.flow.update { it.copy(uploadProgress = uploaded) }
                                            } else {
                                                errors.add("${item.displayName}: ${(retry as? TelegramResult.Error)?.message ?: "Failed"}")
                                            }
                                        }
                                        is TelegramResult.Error -> {
                                            errors.add("${item.displayName}: ${single.message}")
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

                delay(50)
            }

            val doneMsg = if (errors.isEmpty())
                "✅ Backup completed! $uploaded files uploaded."
            else
                "⚠️ Backup finished with ${errors.size} error(s). $uploaded uploaded."

            BackupState.flow.update {
                it.copy(
                    isUploading = false,
                    uploadProgress = uploaded,
                    uploadErrors = errors,
                    currentUploadName = "",
                    statusMessage = doneMsg
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            BackupState.flow.update {
                it.copy(
                    isUploading = false,
                    uploadProgress = uploaded,
                    currentUploadName = "",
                    statusMessage = "⏸ Backup paused. $uploaded files uploaded."
                )
            }
            throw e
        } catch (e: Exception) {
            BackupState.flow.update {
                it.copy(
                    isUploading = false,
                    uploadProgress = uploaded,
                    uploadErrors = errors,
                    currentUploadName = "",
                    statusMessage = "❌ Backup failed: ${e.message}"
                )
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** Minimal silent notification — Android requires one for foreground services. */
    private fun buildSilentNotification(): Notification {
        return NotificationCompat.Builder(this, TelegramBackupApp.CHANNEL_ID)
            .setContentTitle("Backup running")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }
}
