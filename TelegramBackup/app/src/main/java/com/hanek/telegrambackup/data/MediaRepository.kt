package com.hanek.telegrambackup.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateAdded: Long,
    val isVideo: Boolean
)

class MediaRepository(private val context: Context) {

    fun getPhotos(): List<MediaItem> {
        return queryMedia(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false
        )
    }

    fun getVideos(): List<MediaItem> {
        return queryMedia(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true
        )
    }

    fun getAllMedia(includePhotos: Boolean = true, includeVideos: Boolean = true): List<MediaItem> {
        val media = mutableListOf<MediaItem>()
        if (includePhotos) media.addAll(getPhotos())
        if (includeVideos) media.addAll(getVideos())
        return media.sortedByDescending { it.dateAdded }
    }

    private fun queryMedia(collection: Uri, isVideo: Boolean): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )

        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection, projection, null, null, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "unknown"
                val mime = cursor.getString(mimeColumn) ?: ""
                val size = cursor.getLong(sizeColumn)
                val date = cursor.getLong(dateColumn)
                val contentUri = ContentUris.withAppendedId(collection, id)

                items.add(
                    MediaItem(
                        id = id,
                        uri = contentUri,
                        displayName = name,
                        mimeType = mime,
                        size = size,
                        dateAdded = date,
                        isVideo = isVideo
                    )
                )
            }
        }
        return items
    }
}
