package com.hanek.telegrambackup.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hanek.telegrambackup.data.MediaRepository
import com.hanek.telegrambackup.data.PreferencesManager
import kotlinx.coroutines.flow.first

/**
 * Periodic worker that checks for new media every ~15 minutes.
 * If there are files that haven't been backed up yet, it starts the BackupService.
 */
class BackupWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(context)
        val mediaRepo = MediaRepository(context)

        val token = prefs.botToken.first()
        val chatId = prefs.chatId.first()
        if (token.isBlank() || chatId.isBlank()) {
            return Result.success() // Not configured — skip silently.
        }

        val allMedia = mediaRepo.getAllMedia(includePhotos = true, includeVideos = true)
        val backedUp = prefs.backedUpUris.first()
        val pendingCount = allMedia.count { it.uri.toString() !in backedUp }

        if (pendingCount > 0) {
            // New media found — start the foreground service to back it up.
            BackupService.start(context)
        }

        return Result.success()
    }
}
