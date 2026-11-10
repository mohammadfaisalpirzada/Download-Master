package com.themastersahib.downloader.data.download

import android.content.Context
import android.content.Intent
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.DownloadTask
import com.themastersahib.downloader.data.model.TaskStatus
import com.themastersahib.downloader.data.model.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.UUID
import java.util.regex.Pattern
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManagerRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("dm_download_settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Concurrency control: User selectable (1 to 20)
    private val _maxConcurrency = MutableStateFlow(prefs.getInt("max_concurrency", 3))
    val maxConcurrency: StateFlow<Int> = _maxConcurrency.asStateFlow()

    private var semaphore = Semaphore(_maxConcurrency.value)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val runningJobs = ConcurrentHashMap<String, Job>()

    fun setMaxConcurrency(limit: Int) {
        val safeLimit = limit.coerceIn(1, 20)
        _maxConcurrency.value = safeLimit
        prefs.edit().putInt("max_concurrency", safeLimit).apply()
        semaphore = Semaphore(safeLimit)
    }

    fun startDownload(videoInfo: VideoInfo, format: DownloadFormat) {
        val taskId = UUID.randomUUID().toString()
        val extension = if (format.extension.isNotEmpty()) format.extension else (if (format.isAudioOnly) "mp3" else "mp4")
        val mimeType = when (extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> if (format.isAudioOnly) "audio/mpeg" else "video/mp4"
        }

        val newTask = DownloadTask(
            id = taskId,
            title = videoInfo.title,
            thumbnailUrl = videoInfo.thumbnail,
            downloadUrl = format.downloadUrl,
            audioTrackUrl = format.audioTrackUrl,
            platform = videoInfo.platform,
            formatLabel = format.label,
            isAudioOnly = format.isAudioOnly,
            totalBytes = format.estimatedSizeBytes,
            status = TaskStatus.PENDING
        )

        _tasks.update { listOf(newTask) + it }

        val job = scope.launch {
            semaphore.acquire()
            try {
                updateTask(taskId) { it.copy(status = TaskStatus.DOWNLOADING) }
                startForegroundServiceNotification(newTask.title, 0)
                runDownload(taskId, newTask, extension, mimeType)
            } finally {
                semaphore.release()
                checkStopForeground()
            }
        }
        runningJobs[taskId] = job
    }

    fun retryTask(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return

        // Auto-repair old Suno MP3 URLs that return 403 to the working CloudFront audio stream
        var downloadUrl = task.downloadUrl
        var isAudio = task.isAudioOnly
        var extension = if (isAudio) "m4a" else "mp4"

        if (downloadUrl.contains("cdn1.suno.ai") && downloadUrl.endsWith(".mp3")) {
            val uuidMatcher = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})").matcher(downloadUrl)
            if (uuidMatcher.find()) {
                val uuid = uuidMatcher.group(1)
                downloadUrl = "https://d2lwuy8qc234o3.cloudfront.net/1/clip/$uuid.m4a"
                extension = "m4a"
            }
        }

        val mimeType = when (extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> if (isAudio) "audio/mpeg" else "video/mp4"
        }

        val updatedTask = task.copy(
            downloadUrl = downloadUrl,
            audioTrackUrl = task.audioTrackUrl,
            status = TaskStatus.PENDING,
            errorMessage = null,
            progress = 0,
            downloadedBytes = 0L,
            speedBps = 0L
        )

        updateTask(taskId) { updatedTask }

        val job = scope.launch {
            semaphore.acquire()
            try {
                updateTask(taskId) { it.copy(status = TaskStatus.DOWNLOADING) }
                startForegroundServiceNotification(updatedTask.title, 0)
                runDownload(taskId, updatedTask, extension, mimeType)
            } finally {
                semaphore.release()
                checkStopForeground()
            }
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
            return
        }

        // If format requires muxing (adaptive video + audio track)
        if (!task.audioTrackUrl.isNullOrEmpty()) {
            runMuxedDownload(taskId, task, outputStream, contentUri)
            return
        }

        try {
            val referer = getRefererForUrl(task.downloadUrl)

            val request = Request.Builder()
                .url(task.downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Referer", referer)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    updateTask(taskId) {
                        it.copy(
                            status = TaskStatus.FAILED,
                            errorMessage = "Server returned error: ${response.code}"
                        )
                    }
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
        }
    }

    private fun runMuxedDownload(
        taskId: String,
        task: DownloadTask,
        outputStream: java.io.OutputStream,
        contentUri: android.net.Uri?
    ) {
        val tempDir = java.io.File(context.cacheDir, "dm_mux_temp").apply { mkdirs() }
        val tempVideoFile = java.io.File(tempDir, "vid_${taskId}.mp4")
        val tempAudioFile = java.io.File(tempDir, "aud_${taskId}.m4a")
        val tempMuxedFile = java.io.File(tempDir, "mux_${taskId}.mp4")

        try {
            val videoReferer = getRefererForUrl(task.downloadUrl)
            val audioReferer = getRefererForUrl(task.audioTrackUrl ?: "")

            // 1. Download video track (0% - 70%)
            val videoOk = downloadToFile(
                url = task.downloadUrl,
                destFile = tempVideoFile,
                referer = videoReferer,
                onProgress = { downloaded, total ->
                    val progress = if (total > 0) ((downloaded * 70) / total).toInt() else 0
                    updateTask(taskId) {
                        it.copy(
                            progress = progress,
                            downloadedBytes = downloaded,
                            totalBytes = total
                        )
                    }
                    startForegroundServiceNotification(task.title, progress)
                }
            )

            if (!videoOk || !tempVideoFile.exists() || tempVideoFile.length() == 0L) {
                updateTask(taskId) {
                    it.copy(
                        status = TaskStatus.FAILED,
                        errorMessage = "Failed to download video stream"
                    )
                }
                return
            }

            // 2. Download audio track (70% - 90%)
            val audioOk = downloadToFile(
                url = task.audioTrackUrl ?: "",
                destFile = tempAudioFile,
                referer = audioReferer,
                onProgress = { downloaded, total ->
                    val progress = 70 + (if (total > 0) ((downloaded * 20) / total).toInt() else 0)
                    updateTask(taskId) {
                        it.copy(progress = progress)
                    }
                    startForegroundServiceNotification(task.title, progress)
                }
            )

            // 3. Mux video and audio streams together (90% - 95%)
            updateTask(taskId) { it.copy(progress = 92) }
            val muxOk = if (audioOk && tempAudioFile.exists() && tempAudioFile.length() > 0L) {
                MediaMuxerHelper.mux(tempVideoFile, tempAudioFile, tempMuxedFile)
            } else {
                false
            }

            val finalSourceFile = if (muxOk && tempMuxedFile.exists() && tempMuxedFile.length() > 0L) {
                tempMuxedFile
            } else {
                tempVideoFile
            }

            // 4. Stream final file into destination Storage Uri (95% - 100%)
            updateTask(taskId) { it.copy(progress = 96) }
            outputStream.use { out ->
                finalSourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                out.flush()
            }

            StorageHelper.markDownloadComplete(context, contentUri)

            val totalFinalSize = finalSourceFile.length()
            updateTask(taskId) {
                it.copy(
                    status = TaskStatus.COMPLETED,
                    downloadedBytes = totalFinalSize,
                    totalBytes = totalFinalSize,
                    progress = 100,
                    speedBps = 0L,
                    localFilePath = contentUri?.toString()
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            updateTask(taskId) {
                it.copy(
                    status = TaskStatus.FAILED,
                    errorMessage = e.localizedMessage ?: "Failed during video and audio muxing"
                )
            }
        } finally {
            try { tempVideoFile.delete() } catch (_: Exception) {}
            try { tempAudioFile.delete() } catch (_: Exception) {}
            try { tempMuxedFile.delete() } catch (_: Exception) {}
            runningJobs.remove(taskId)
        }
    }

    private fun downloadToFile(
        url: String,
        destFile: java.io.File,
        referer: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Boolean {
        try {
            if (destFile.exists()) destFile.delete()
            destFile.parentFile?.mkdirs()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Referer", referer)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val totalLength = body.contentLength()

                var downloaded = 0L
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                val inputStream = body.byteStream()

                var lastUpdateTime = System.currentTimeMillis()

                java.io.FileOutputStream(destFile).use { out ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= 300) {
                            onProgress?.invoke(downloaded, totalLength)
                            lastUpdateTime = now
                        }
                    }
                    out.flush()
                }
                onProgress?.invoke(downloaded, totalLength)
                return destFile.exists() && destFile.length() > 0L
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun getRefererForUrl(url: String): String {
        return when {
            url.contains("facebook.com") || url.contains("fbcdn.net") -> "https://www.facebook.com/"
            url.contains("youtube.com") || url.contains("googlevideo.com") -> "https://www.youtube.com/"
            url.contains("suno") || url.contains("cloudfront.net") -> "https://suno.com/"
            url.contains("tiktok") -> "https://www.tiktok.com/"
            url.contains("instagram") -> "https://www.instagram.com/"
            url.contains("twitter.com") || url.contains("x.com") || url.contains("twimg.com") -> "https://twitter.com/"
            else -> "https://www.google.com/"
        }
    }

    fun cancelTask(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        _tasks.update { list ->
            list.filterNot { it.id == taskId }
        }
        checkStopForeground()
    }

    private fun checkStopForeground() {
        val hasActive = _tasks.value.any { it.status == TaskStatus.DOWNLOADING }
        if (!hasActive) {
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
            // Handled
        }
    }

    private fun stopForegroundServiceNotification() {
        try {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            // Handled
        }
    }
}
