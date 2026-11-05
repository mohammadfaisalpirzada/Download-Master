package com.themastersahib.downloader.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.DownloadTask
import com.themastersahib.downloader.data.model.SocialPlatform
import com.themastersahib.downloader.data.model.TaskStatus
import com.themastersahib.downloader.data.model.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManagerRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val runningJobs = ConcurrentHashMap<String, Job>()

    fun startDownload(videoInfo: VideoInfo, format: DownloadFormat) {
        val taskId = UUID.randomUUID().toString()
        val extension = if (format.isAudioOnly) "mp3" else "mp4"
        val mimeType = if (format.isAudioOnly) "audio/mpeg" else "video/mp4"

        val newTask = DownloadTask(
            id = taskId,
            title = videoInfo.title,
            thumbnailUrl = videoInfo.thumbnail,
            downloadUrl = format.downloadUrl,
            platform = videoInfo.platform,
            formatLabel = format.label,
            isAudioOnly = format.isAudioOnly,
            totalBytes = format.estimatedSizeBytes,
            status = TaskStatus.DOWNLOADING
        )

        _tasks.update { listOf(newTask) + it }
        startForegroundServiceNotification(newTask.title, 0)

        val job = scope.launch {
            runDownload(taskId, newTask, extension, mimeType)
        }
        runningJobs[taskId] = job
    }

    private fun runDownload(
        taskId: String,
        task: DownloadTask,
        extension: String,
        mimeType: String
    ) {
        val sanitizedTitle = StorageHelper.sanitizeFilename(task.title, extension)
        val (outputStream, contentUri) = StorageHelper.openOutputStream(
            context,
            sanitizedTitle,
            mimeType,
            task.isAudioOnly
        )

        if (outputStream == null) {
            updateTask(taskId) {
                it.copy(
                    status = TaskStatus.FAILED,
                    errorMessage = "Failed to create destination storage file"
                )
            }
            stopForegroundServiceNotification()
            return
        }

        try {
            val request = Request.Builder()
                .url(task.downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    updateTask(taskId) {
                        it.copy(
                            status = TaskStatus.FAILED,
                            errorMessage = "Server returned error: ${response.code}"
                        )
                    }
                    stopForegroundServiceNotification()
                    return
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val totalLength = if (body.contentLength() > 0) body.contentLength() else task.totalBytes

                var downloaded = 0L
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                val inputStream: InputStream = body.byteStream()

                var lastUpdateTime = System.currentTimeMillis()
                var bytesSinceLastUpdate = 0L

                outputStream.use { out ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        bytesSinceLastUpdate += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= 500) {
                            val durationSec = (now - lastUpdateTime) / 1000.0
                            val speed = if (durationSec > 0) (bytesSinceLastUpdate / durationSec).toLong() else 0L
                            val progress = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt() else 0

                            updateTask(taskId) {
                                it.copy(
                                    downloadedBytes = downloaded,
                                    totalBytes = totalLength,
                                    progress = progress,
                                    speedBps = speed
                                )
                            }
                            startForegroundServiceNotification(task.title, progress)

                            lastUpdateTime = now
                            bytesSinceLastUpdate = 0L
                        }
                    }
                    out.flush()
                }

                StorageHelper.markDownloadComplete(context, contentUri)

                updateTask(taskId) {
                    it.copy(
                        status = TaskStatus.COMPLETED,
                        downloadedBytes = downloaded,
                        totalBytes = downloaded,
                        progress = 100,
                        speedBps = 0L,
                        localFilePath = contentUri?.toString()
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            updateTask(taskId) {
                it.copy(
                    status = TaskStatus.FAILED,
                    errorMessage = e.localizedMessage ?: "Network interrupted"
                )
            }
        } finally {
            runningJobs.remove(taskId)
            if (runningJobs.isEmpty()) {
                stopForegroundServiceNotification()
            }
        }
    }

    fun cancelTask(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        _tasks.update { list ->
            list.filterNot { it.id == taskId }
        }
        if (runningJobs.isEmpty()) {
            stopForegroundServiceNotification()
        }
    }

    private fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) transform(it) else it }
        }
    }

    private fun startForegroundServiceNotification(title: String, progress: Int) {
        try {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START
                putExtra(DownloadForegroundService.EXTRA_TITLE, title)
                putExtra(DownloadForegroundService.EXTRA_PROGRESS, progress)
            }
            context.startService(intent)
        } catch (e: Exception) {
            // Background start restriction handling
        }
    }

    private fun stopForegroundServiceNotification() {
        try {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            // Ignored
        }
    }
}
