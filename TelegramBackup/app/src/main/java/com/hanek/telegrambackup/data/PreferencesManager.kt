package com.hanek.telegrambackup.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        const val DEFAULT_BOT_TOKEN = "8912378846:AAG_-9usdjm3wU3TJjXTpY8FejuixC_Vgww"
        const val DEFAULT_CHAT_ID = "7825761805"
        val BOT_TOKEN = stringPreferencesKey("bot_token")
        val CHAT_ID = stringPreferencesKey("chat_id")
        val BACKED_UP_URIS = stringSetPreferencesKey("backed_up_uris")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val BACKUP_PHOTOS = booleanPreferencesKey("backup_photos")
        val BACKUP_VIDEOS = booleanPreferencesKey("backup_videos")
        val TOTAL_UPLOADED = intPreferencesKey("total_uploaded")
    }

    val botToken: Flow<String> = context.dataStore.data.map { it[BOT_TOKEN] ?: DEFAULT_BOT_TOKEN }
    val chatId: Flow<String> = context.dataStore.data.map { it[CHAT_ID] ?: DEFAULT_CHAT_ID }
    val backedUpUris: Flow<Set<String>> = context.dataStore.data.map { it[BACKED_UP_URIS] ?: emptySet() }
    val autoBackup: Flow<Boolean> = context.dataStore.data.map { it[AUTO_BACKUP] ?: false }
    val backupPhotos: Flow<Boolean> = context.dataStore.data.map { it[BACKUP_PHOTOS] ?: true }
    val backupVideos: Flow<Boolean> = context.dataStore.data.map { it[BACKUP_VIDEOS] ?: true }
    val totalUploaded: Flow<Int> = context.dataStore.data.map { it[TOTAL_UPLOADED] ?: 0 }

    suspend fun saveBotToken(token: String) {
        context.dataStore.edit { it[BOT_TOKEN] = token }
    }

    suspend fun saveChatId(chatId: String) {
        context.dataStore.edit { it[CHAT_ID] = chatId }
    }

    suspend fun markAsBackedUp(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[BACKED_UP_URIS] ?: emptySet()
            prefs[BACKED_UP_URIS] = current + uri
            prefs[TOTAL_UPLOADED] = (prefs[TOTAL_UPLOADED] ?: 0) + 1
        }
    }

    suspend fun setAutoBackup(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_BACKUP] = enabled }
    }

    suspend fun setBackupPhotos(enabled: Boolean) {
        context.dataStore.edit { it[BACKUP_PHOTOS] = enabled }
    }

    suspend fun setBackupVideos(enabled: Boolean) {
        context.dataStore.edit { it[BACKUP_VIDEOS] = enabled }
    }

    suspend fun clearBackupHistory() {
        context.dataStore.edit {
            it[BACKED_UP_URIS] = emptySet()
            it[TOTAL_UPLOADED] = 0
        }
    }
}
