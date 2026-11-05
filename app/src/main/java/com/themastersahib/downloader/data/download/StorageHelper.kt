package com.themastersahib.downloader.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

object StorageHelper {

    private const val SUBFOLDER = "DownloadMaster"

    fun sanitizeFilename(raw: String, extension: String): String {
        val clean = raw.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            .trim()
            .take(60)
        return if (clean.isEmpty()) {
            "DownloadMaster_${System.currentTimeMillis()}.$extension"
        } else {
            "${clean}_${System.currentTimeMillis() % 10000}.$extension"
        }
    }

    fun openOutputStream(
        context: Context,
        filename: String,
        mimeType: String,
        isAudio: Boolean
    ): Pair<OutputStream?, Uri?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isAudio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val relativePath = if (isAudio) {
                "${Environment.DIRECTORY_MUSIC}/$SUBFOLDER"
            } else {
                "${Environment.DIRECTORY_MOVIES}/$SUBFOLDER"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(collection, values)
            val stream = uri?.let { context.contentResolver.openOutputStream(it) }
            Pair(stream, uri)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
                ),
                SUBFOLDER
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            val stream = file.outputStream()
            val uri = Uri.fromFile(file)
            Pair(stream, uri)
        }
    }

    fun markDownloadComplete(context: Context, uri: Uri?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    fun getSavedFiles(context: Context): List<FileItem> {
        val result = mutableListOf<FileItem>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        // Query Videos
        try {
            val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            context.contentResolver.query(
                videoUri,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (name.contains(SUBFOLDER) || cursor.position < 20) {
                        val id = cursor.getLong(idCol)
                        val contentUri = Uri.withAppendedPath(videoUri, id.toString())
                        result.add(
                            FileItem(
                                id = id.toString(),
                                name = name,
                                sizeBytes = cursor.getLong(sizeCol),
                                dateAdded = cursor.getLong(dateCol) * 1000,
                                mimeType = cursor.getString(mimeCol) ?: "video/mp4",
                                contentUri = contentUri,
                                isAudio = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }
}

data class FileItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val mimeType: String,
    val contentUri: Uri,
    val isAudio: Boolean
) {
    val formattedSize: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                String.format("%.2f GB", mb / 1024)
            } else {
                String.format("%.1f MB", mb)
            }
        }
}
