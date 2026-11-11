package com.themastersahib.downloader.data.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream

object StorageHelper {

    private const val SUBFOLDER = "DownloadMaster"

    /** An open destination file. Always finish it with [finalizeDestination] or [discardDestination]. */
    class Destination(
        val stream: OutputStream,
        val uri: Uri,
        val legacyFile: File?
    )

    fun sanitizeFilename(raw: String, extension: String): String {
        val clean = raw
            .replace("[\\\\/:*?\"<>|\\p{Cntrl}]".toRegex(), "_")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .trimStart('.')
            .take(60)
            .trim()
        return if (clean.isEmpty()) {
            "DownloadMaster_${System.currentTimeMillis()}.$extension"
        } else {
            "${clean}_${System.currentTimeMillis() % 100000}.$extension"
        }
    }

    fun mimeTypeForExtension(extension: String, isAudio: Boolean): String {
        return when (extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> if (isAudio) "audio/webm" else "video/webm"
            "ogg", "opus" -> "audio/ogg"
            "mp4" -> "video/mp4"
            else -> if (isAudio) "audio/mpeg" else "video/mp4"
        }
    }

    /**
     * Creates the destination file in Movies/DownloadMaster or Music/DownloadMaster.
     * @throws IOException when the file cannot be created (no permission, storage full, ...).
     */
    @Throws(IOException::class)
    fun createDestination(
        context: Context,
        filename: String,
        mimeType: String,
        isAudio: Boolean
    ): Destination {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values)
                    ?: throw IOException("Storage refused to create the file")
                val stream = try {
                    resolver.openOutputStream(uri)
                } catch (e: Exception) {
                    null
                }
                if (stream == null) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    throw IOException("Cannot open the destination file for writing")
                }
                return Destination(stream, uri, null)
            }

            // Android 8 / 9: classic public folders, needs the storage permission.
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                throw IOException("Storage permission is required. Please allow it and retry.")
            }
            val dir = legacyDir(isAudio)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Cannot create folder ${dir.absolutePath}")
            }
            val file = File(dir, filename)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            return Destination(file.outputStream(), uri, file)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.localizedMessage ?: "Failed to create destination storage file", e)
        }
    }

    /** Publishes a fully written file so the gallery and other apps can see it. */
    fun finalizeDestination(context: Context, destination: Destination) {
        try { destination.stream.close() } catch (_: Exception) {}
        if (destination.legacyFile != null) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destination.legacyFile.absolutePath),
                null,
                null
            )
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(destination.uri, values, null, null)
        }
    }

    /** Removes a partial file after a failed or cancelled download. Never throws. */
    fun discardDestination(context: Context, destination: Destination) {
        try { destination.stream.close() } catch (_: Exception) {}
        try {
            if (destination.legacyFile != null) {
                destination.legacyFile.delete()
            } else {
                context.contentResolver.delete(destination.uri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    private fun legacyDir(isAudio: Boolean): File {
        return File(
            Environment.getExternalStoragePublicDirectory(
                if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            ),
            SUBFOLDER
        )
    }

    /** Lists every video and audio file this app saved, newest first. Call off the main thread. */
    fun getSavedFiles(context: Context): List<FileItem> {
        val result = mutableListOf<FileItem>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result += queryCollection(
                context,
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                isAudio = false
            )
            result += queryCollection(
                context,
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                isAudio = true
            )
        } else {
            result += listLegacyDir(context, isAudio = false)
            result += listLegacyDir(context, isAudio = true)
        }
        return result.sortedByDescending { it.dateAdded }
    }

    private fun queryCollection(context: Context, collection: Uri, isAudio: Boolean): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("%/$SUBFOLDER/%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val id = cursor.getLong(idCol)
                    items.add(
                        FileItem(
                            id = (if (isAudio) "a" else "v") + id,
                            name = name,
                            sizeBytes = cursor.getLong(sizeCol),
                            dateAdded = cursor.getLong(dateCol) * 1000,
                            mimeType = cursor.getString(mimeCol)
                                ?: if (isAudio) "audio/mp4" else "video/mp4",
                            contentUri = Uri.withAppendedPath(collection, id.toString()),
                            isAudio = isAudio
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    private fun listLegacyDir(context: Context, isAudio: Boolean): List<FileItem> {
        return try {
            val files = legacyDir(isAudio).listFiles() ?: return emptyList()
            files.filter { it.isFile && it.length() > 0L }.map { file ->
                FileItem(
                    id = (if (isAudio) "a" else "v") + file.name,
                    name = file.name,
                    sizeBytes = file.length(),
                    dateAdded = file.lastModified(),
                    mimeType = mimeTypeForExtension(file.extension, isAudio),
                    contentUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    ),
                    isAudio = isAudio
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
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
