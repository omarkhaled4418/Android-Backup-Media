package com.hanek.telegrambackup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TelegramBackupApp : Application() {
    companion object {
        const val CHANNEL_ID = "backup_channel"
        const val CHANNEL_NAME = "Backup Progress"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
}
