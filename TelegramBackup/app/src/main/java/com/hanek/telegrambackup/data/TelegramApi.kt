package com.hanek.telegrambackup.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class TelegramResult {
    data class Success(val messageId: Int) : TelegramResult()
    data class RateLimited(val retryAfterSeconds: Int) : TelegramResult()
    data class Error(val message: String) : TelegramResult()
}

class TelegramApi(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(botToken: String) = "https://api.telegram.org/bot$botToken"

    suspend fun testConnection(botToken: String, chatId: String): TelegramResult = withContext(Dispatchers.IO) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("text", "✅ Telegram Backup app connected successfully!")
                .build()

            val request = Request.Builder()
                .url("${baseUrl(botToken)}/sendMessage")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(body)
                if (json.getBoolean("ok")) {
                    TelegramResult.Success(json.getJSONObject("result").getInt("message_id"))
                } else {
                    TelegramResult.Error(json.optString("description", "Unknown error"))
                }
            } else {
                val json = try { JSONObject(body) } catch (e: Exception) { null }
                if (response.code == 429) {
                    val retryAfter = json?.optJSONObject("parameters")?.optInt("retry_after", 3) ?: 3
                    TelegramResult.RateLimited(retryAfter)
                } else {
                    val desc = json?.optString("description") ?: "HTTP ${response.code}"
                    TelegramResult.Error(desc)
                }
            }
        } catch (e: IOException) {
            TelegramResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            TelegramResult.Error("Error: ${e.message}")
        }
    }

    /**
     * Compresses an image to ~1280px max dimension and 60% JPEG for a smaller,
     * faster upload (lower quality on purpose). Preserves EXIF orientation.
     */
    private fun compressPhoto(uri: Uri): ByteArray? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val maxDimension = 1280
            var inSampleSize = 1
            var w = boundsOptions.outWidth
            var h = boundsOptions.outHeight
            while (w / 2 >= maxDimension || h / 2 >= maxDimension) {
                w /= 2
                h /= 2
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            var bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            // Read EXIF orientation to keep portrait/landscape correct
            val orientation = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }

            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            bitmap.recycle()
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Uploads an album of up to 10 photos/videos in a SINGLE request via sendMediaGroup.
     * This achieves up to 10x faster backup compared to uploading files individually.
     */
    suspend fun sendMediaGroup(
        botToken: String,
        chatId: String,
        items: List<MediaItem>
    ): TelegramResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext TelegramResult.Error("No items to send")
        if (items.size == 1) return@withContext sendMediaItem(botToken, chatId, items[0])

        try {
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)

            val mediaJsonArray = JSONArray()

            for ((index, item) in items.withIndex()) {
                val attachKey = "file_$index"
                val mediaObject = JSONObject()

                if (item.isVideo) {
                    mediaObject.put("type", "video")
                    mediaObject.put("media", "attach://$attachKey")
                    if (index == 0) {
                        mediaObject.put("caption", "📁 Backup (${items.size} files)")
                    }

                    val mediaType = (item.mimeType.ifEmpty { "video/mp4" }).toMediaType()
                    val videoBody = object : RequestBody() {
                        override fun contentType(): MediaType = mediaType
                        override fun contentLength(): Long = item.size
                        override fun writeTo(sink: okio.BufferedSink) {
                            val stream = context.contentResolver.openInputStream(item.uri)
                                ?: throw IOException("Cannot open file: ${item.displayName}")
                            stream.use { s -> sink.writeAll(s.source()) }
                        }
                    }
                    multipartBuilder.addFormDataPart(attachKey, item.displayName, videoBody)
                } else {
                    mediaObject.put("type", "photo")
                    mediaObject.put("media", "attach://$attachKey")
                    if (index == 0) {
                        mediaObject.put("caption", "📁 Backup (${items.size} photos)")
                    }

                    val compressed = compressPhoto(item.uri)
                    if (compressed != null) {
                        val body = compressed.toRequestBody("image/jpeg".toMediaType())
                        multipartBuilder.addFormDataPart(attachKey, item.displayName, body)
                    } else {
                        val mediaType = (item.mimeType.ifEmpty { "image/jpeg" }).toMediaType()
                        val photoBody = object : RequestBody() {
                            override fun contentType(): MediaType = mediaType
                            override fun contentLength(): Long = item.size
                            override fun writeTo(sink: okio.BufferedSink) {
                                val stream = context.contentResolver.openInputStream(item.uri)
                                    ?: throw IOException("Cannot open file: ${item.displayName}")
                                stream.use { s -> sink.writeAll(s.source()) }
                            }
                        }
                        multipartBuilder.addFormDataPart(attachKey, item.displayName, photoBody)
                    }
                }

                mediaJsonArray.put(mediaObject)
            }

            multipartBuilder.addFormDataPart("media", mediaJsonArray.toString())

            val request = Request.Builder()
                .url("${baseUrl(botToken)}/sendMediaGroup")
                .post(multipartBuilder.build())
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(body)
                if (json.getBoolean("ok")) {
                    val resultArray = json.optJSONArray("result")
                    val firstMsgId = resultArray?.optJSONObject(0)?.optInt("message_id") ?: 0
                    TelegramResult.Success(firstMsgId)
                } else {
                    TelegramResult.Error(json.optString("description", "Unknown error"))
                }
            } else {
                val json = try { JSONObject(body) } catch (e: Exception) { null }
                if (response.code == 429) {
                    val retryAfter = json?.optJSONObject("parameters")?.optInt("retry_after", 3) ?: 3
                    TelegramResult.RateLimited(retryAfter)
                } else {
                    val desc = json?.optString("description") ?: "HTTP ${response.code}"
                    TelegramResult.Error(desc)
                }
            }
        } catch (e: IOException) {
            TelegramResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            TelegramResult.Error("Error: ${e.message}")
        }
    }

    suspend fun sendPhoto(
        botToken: String,
        chatId: String,
        mediaItem: MediaItem
    ): TelegramResult = withContext(Dispatchers.IO) {
        val compressed = compressPhoto(mediaItem.uri)
        if (compressed != null) {
            try {
                val mediaType = "image/jpeg".toMediaType()
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart(
                        "photo",
                        mediaItem.displayName,
                        compressed.toRequestBody(mediaType)
                    )
                    .addFormDataPart("caption", "📷 ${mediaItem.displayName}")
                    .build()

                val request = Request.Builder()
                    .url("${baseUrl(botToken)}/sendPhoto")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.getBoolean("ok")) {
                        TelegramResult.Success(json.getJSONObject("result").getInt("message_id"))
                    } else {
                        TelegramResult.Error(json.optString("description", "Unknown error"))
                    }
                } else {
                    val json = try { JSONObject(body) } catch (e: Exception) { null }
                    if (response.code == 429) {
                        val retryAfter = json?.optJSONObject("parameters")?.optInt("retry_after", 3) ?: 3
                        TelegramResult.RateLimited(retryAfter)
                    } else {
                        val desc = json?.optString("description") ?: "HTTP ${response.code}"
                        TelegramResult.Error(desc)
                    }
                }
            } catch (e: IOException) {
                TelegramResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                TelegramResult.Error("Error: ${e.message}")
            }
        } else {
            sendFile(botToken, chatId, mediaItem, "sendPhoto", "photo")
        }
    }

    suspend fun sendVideo(
        botToken: String,
        chatId: String,
        mediaItem: MediaItem
    ): TelegramResult = withContext(Dispatchers.IO) {
        // Videos are uploaded as-is (no transcoding) to keep the backup fast.
        sendFile(botToken, chatId, mediaItem, "sendVideo", "video")
    }

    suspend fun sendDocument(
        botToken: String,
        chatId: String,
        mediaItem: MediaItem
    ): TelegramResult = withContext(Dispatchers.IO) {
        sendFile(botToken, chatId, mediaItem, "sendDocument", "document")
    }

    private fun sendFile(
        botToken: String,
        chatId: String,
        mediaItem: MediaItem,
        endpoint: String,
        fieldName: String
    ): TelegramResult {
        return try {
            val testStream = context.contentResolver.openInputStream(mediaItem.uri)
                ?: return TelegramResult.Error("Cannot open file: ${mediaItem.displayName}")
            testStream.close()

            val mediaType = (mediaItem.mimeType.ifEmpty { "application/octet-stream" }).toMediaType()

            val fileBody = object : RequestBody() {
                override fun contentType(): MediaType = mediaType
                override fun contentLength(): Long = mediaItem.size
                override fun writeTo(sink: okio.BufferedSink) {
                    val inputStream = context.contentResolver.openInputStream(mediaItem.uri)
                        ?: throw IOException("Cannot open file: ${mediaItem.displayName}")
                    inputStream.use { stream ->
                        sink.writeAll(stream.source())
                    }
                }
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart(
                    fieldName,
                    mediaItem.displayName,
                    fileBody
                )
                .addFormDataPart("caption", "📁 ${mediaItem.displayName}")
                .build()

            val request = Request.Builder()
                .url("${baseUrl(botToken)}/$endpoint")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(body)
                if (json.getBoolean("ok")) {
                    TelegramResult.Success(json.getJSONObject("result").getInt("message_id"))
                } else {
                    TelegramResult.Error(json.optString("description", "Unknown error"))
                }
            } else {
                val json = try { JSONObject(body) } catch (e: Exception) { null }
                if (response.code == 429) {
                    val retryAfter = json?.optJSONObject("parameters")?.optInt("retry_after", 3) ?: 3
                    TelegramResult.RateLimited(retryAfter)
                } else {
                    val desc = json?.optString("description") ?: "HTTP ${response.code}"
                    if (response.code == 413 || desc.contains("too big", ignoreCase = true)) {
                        TelegramResult.Error("File too large for Telegram (max 50MB): ${mediaItem.displayName}")
                    } else {
                        TelegramResult.Error(desc)
                    }
                }
            }
        } catch (e: IOException) {
            TelegramResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            TelegramResult.Error("Error: ${e.message}")
        }
    }

    suspend fun sendMediaItem(
        botToken: String,
        chatId: String,
        mediaItem: MediaItem
    ): TelegramResult {
        return if (mediaItem.isVideo) {
            if (mediaItem.size > 50 * 1024 * 1024) {
                TelegramResult.Error("File too large (max 50MB): ${mediaItem.displayName}")
            } else {
                sendVideo(botToken, chatId, mediaItem)
            }
        } else {
            if (mediaItem.size > 10 * 1024 * 1024) {
                sendDocument(botToken, chatId, mediaItem)
            } else {
                sendPhoto(botToken, chatId, mediaItem)
            }
        }
    }
}
