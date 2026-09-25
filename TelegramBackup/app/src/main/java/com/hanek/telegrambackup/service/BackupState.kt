package com.hanek.telegrambackup.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared, in-process backup progress. The BackupService writes to it; the ViewModel
 * mirrors it into the UI. Kept as a top-level singleton so the state survives the
 * ViewModel being destroyed while the service keeps running in the background.
 */
object BackupState {
    data class Snapshot(
        val isUploading: Boolean = false,
        val uploadProgress: Int = 0,
        val uploadTotal: Int = 0,
        val currentUploadName: String = "",
        val uploadErrors: List<String> = emptyList(),
        val statusMessage: String = ""
    )

    val flow = MutableStateFlow(Snapshot())
}
