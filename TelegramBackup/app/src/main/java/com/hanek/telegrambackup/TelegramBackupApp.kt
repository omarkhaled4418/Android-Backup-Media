package com.hanek.telegrambackup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hanek.telegrambackup.service.BackupWorker
import java.util.concurrent.TimeUnit

class TelegramBackupApp : Application() {
    companion object {
        const val CHANNEL_ID = "backup_channel"
        const val CHANNEL_NAME = "Backup Progress"
        private const val PERIODIC_WORK_NAME = "auto_backup_check"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        schedulePeriodicBackupCheck()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Required for background backup"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Schedules a periodic check every 15 minutes. If new media is found on the
     * device, the BackupWorker starts the foreground service to upload it.
     * KEEP_EXISTING ensures we don't reset the timer on every app open.
     */
    private fun schedulePeriodicBackupCheck() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
