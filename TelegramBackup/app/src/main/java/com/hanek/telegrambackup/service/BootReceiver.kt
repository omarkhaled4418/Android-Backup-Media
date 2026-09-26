package com.hanek.telegrambackup.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the backup service automatically when the device boots up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            BackupService.start(context)
        }
    }
}
