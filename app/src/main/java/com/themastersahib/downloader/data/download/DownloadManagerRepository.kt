package com.themastersahib.downloader.data.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.DownloadTask
import com.themastersahib.downloader.data.model.SocialPlatform
import com.themastersahib.downloader.data.model.TaskStatus
import com.themastersahib.downloader.data.model.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Download engine. One instance lives in [com.themastersahib.downloader.MasterDownloaderApp]
 * and is shared by the UI and [DownloadForegroundService].
 */
class DownloadManagerRepository(context: Context) {

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val PREF_MAX_CONCURRENCY = "max_concurrency"
        const val MAX_NETWORK_RETRIES = 3
        const val PROGRESS_INTERVAL_MS = 500L

        /** googlevideo throttles one long request, so those URLs are fetched in ranged chunks. */
        const val CHUNK_BYTES = 8L * 1024 * 1024
        val UUID_PATTERN: Pattern =
            Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
    }

    /** A failure that retrying the same URL will not fix. */
    private open class FatalDownloadException(message: String) : IOException(message)

    /** The server answered with an HTTP error status. */
    private class HttpStatusException(val code: Int) : FatalDownloadException("Server returned error: $code")

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("dm_download_settings", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> error.printStackTrace() }
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Concurrency control: user selectable (1 to 20), can change while downloads run.
    private val _maxConcurrency = MutableStateFlow(prefs.getInt(PREF_MAX_CONCURRENCY, 3).coerceIn(1, 20))
    val maxConcurrency: StateFlow<Int> = _maxConcurrency.asStateFlow()
    private val activeSlots = AtomicInteger(0)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, Call>()

    init {
        // Temp files of a previous run that was killed mid-download.
        scope.launch {
            try { muxTempDir().listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        }
    }

    fun setMaxConcurrency(limit: Int) {
        val safeLimit = limit.coerceIn(1, 20)
        _maxConcurrency.value = safeLimit
        prefs.edit().putInt(PREF_MAX_CONCURRENCY, safeLimit).apply()
    }

    fun startDownload(videoInfo: VideoInfo, format: DownloadFormat) {
        if (format.downloadUrl.isBlank()) return

        val extension = format.extension.trim().trimStart('.').lowercase()
            .ifEmpty { if (format.isAudioOnly) "m4a" else "mp4" }

        val newTask = DownloadTask(
            id = UUID.randomUUID().toString(),
            title = videoInfo.title.ifBlank { "${videoInfo.platform.displayName} media" },
            thumbnailUrl = videoInfo.thumbnail,
            downloadUrl = format.downloadUrl,
            audioTrackUrl = format.audioTrackUrl?.takeIf { it.isNotBlank() },
            platform = videoInfo.platform,
            formatLabel = format.label,
            isAudioOnly = format.isAudioOnly,
            extension = extension,
            mimeType = StorageHelper.mimeTypeForExtension(extension, format.isAudioOnly),
            extractAudio = format.extractAudio,
            totalBytes = format.estimatedSizeBytes.coerceAtLeast(0L),
            status = TaskStatus.PENDING
        )

        _tasks.update { listOf(newTask) + it }
        enqueue(newTask)
    }

    fun retryTask(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        if (task.status != TaskStatus.FAILED || runningJobs.containsKey(taskId)) return

        // Auto-repair old Suno MP3 URLs that return 403 to the working CloudFront audio stream
        var downloadUrl = task.downloadUrl
        var extension = task.extension
        if (downloadUrl.contains("cdn1.suno.ai") && downloadUrl.endsWith(".mp3")) {
            val uuidMatcher = UUID_PATTERN.matcher(downloadUrl)
            if (uuidMatcher.find()) {
                downloadUrl = "https://d2lwuy8qc234o3.cloudfront.net/1/clip/${uuidMatcher.group(1)}.m4a"
                extension = "m4a"
            }
        }

        val updatedTask = task.copy(
            downloadUrl = downloadUrl,
            extension = extension,
            mimeType = StorageHelper.mimeTypeForExtension(extension, task.isAudioOnly),
            status = TaskStatus.PENDING,
            errorMessage = null,
            localFilePath = null,
            progress = 0,
            downloadedBytes = 0L,
            speedBps = 0L
        )

        updateTask(taskId) { updatedTask }
        enqueue(updatedTask)
    }

    /** Stops the download (if running), removes its partial file and drops the task from the list. */
    fun cancelTask(taskId: String) {
        runningJobs.remove(taskId)?.cancel()
        activeCalls.remove(taskId)?.cancel()
        _tasks.update { list -> list.filterNot { it.id == taskId } }
    }

    private fun enqueue(task: DownloadTask) {
        startForegroundService()

        val job = scope.launch {
            try {
                acquireSlot()
                try {
                    updateTask(task.id) { it.copy(status = TaskStatus.DOWNLOADING) }
                    runDownload(task)
                } finally {
                    activeSlots.decrementAndGet()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.printStackTrace()
                failTask(task.id, e.localizedMessage ?: "Download failed")
            } finally {
                activeCalls.remove(task.id)
                coroutineContext[Job]?.let { runningJobs.remove(task.id, it) }
            }
        }
        runningJobs[task.id] = job
        // The job may already be over (instant failure) before it was registered.
        if (job.isCompleted) runningJobs.remove(task.id, job)
    }

    /** Waits (cancellable) until fewer than the allowed number of downloads are running. */
    private suspend fun acquireSlot() {
        while (true) {
            val current = activeSlots.get()
            if (current < _maxConcurrency.value && activeSlots.compareAndSet(current, current + 1)) return
            delay(200)
        }
    }

    private suspend fun runDownload(task: DownloadTask) {
        val tempDir = muxTempDir()
        val tempVideo = File(tempDir, "vid_${task.id}.tmp")
        val tempAudio = File(tempDir, "aud_${task.id}.tmp")
        val tempOutput = File(tempDir, "out_${task.id}.tmp")
        var destination: StorageHelper.Destination? = null

        try {
            val needsMux = !task.audioTrackUrl.isNullOrEmpty()
            val filename = StorageHelper.sanitizeFilename(task.title, task.extension)
            val finalSize: Long

            if (!needsMux && !task.extractAudio) {
                // Plain file: stream straight into the destination.
                val dest = StorageHelper.createDestination(appContext, filename, task.mimeType, task.isAudioOnly)
                destination = dest
                finalSize = fetch(task.id, task.downloadUrl, dest.stream) { downloaded, total, speed ->
                    reportProgress(task.id, downloaded, total, speed, 0, 100)
                }
                if (finalSize <= 0L) throw IOException("Server sent an empty file")
            } else {
                tempDir.mkdirs()

                // 1. Source / video track (0% - 70%)
                tempVideo.outputStream().use { out ->
                    fetch(task.id, task.downloadUrl, out) { downloaded, total, speed ->
                        reportProgress(task.id, downloaded, total, speed, 0, 70)
                    }
                }
                if (tempVideo.length() == 0L) throw IOException("Failed to download media stream")

                if (needsMux) {
                    // 2. Audio track (70% - 90%)
                    tempAudio.outputStream().use { out ->
                        fetch(task.id, task.audioTrackUrl.orEmpty(), out) { downloaded, total, speed ->
                            reportProgress(task.id, downloaded, total, speed, 70, 90)
                        }
                    }
                    if (tempAudio.length() == 0L) throw IOException("Failed to download audio stream")
                }

                // 3. Merge / extract (90% - 95%)
                currentCoroutineContext().ensureActive()
                updateTask(task.id) { it.copy(progress = 92, speedBps = 0L) }
                val ok = if (needsMux) {
                    MediaMuxerHelper.mux(tempVideo, tempAudio, tempOutput)
                } else {
                    MediaMuxerHelper.extractAudio(tempVideo, tempOutput)
                }
                if (!ok) {
                    throw IOException(
                        if (needsMux) {
                            "Could not merge video and audio for this quality. Please choose another resolution."
                        } else {
                            "This media has no audio track that can be saved."
                        }
                    )
                }

                // 4. Copy the result to public storage (95% - 100%)
                currentCoroutineContext().ensureActive()
                updateTask(task.id) { it.copy(progress = 96) }
                val dest = StorageHelper.createDestination(appContext, filename, task.mimeType, task.isAudioOnly)
                destination = dest
                tempOutput.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        dest.stream.write(buffer, 0, read)
                    }
                    dest.stream.flush()
                }
                finalSize = tempOutput.length()
            }

            val finished = destination ?: throw IOException("Failed to create destination storage file")
            StorageHelper.finalizeDestination(appContext, finished)
            destination = null

            updateTask(task.id) {
                it.copy(
                    status = TaskStatus.COMPLETED,
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    progress = 100,
                    speedBps = 0L,
                    errorMessage = null,
                    localFilePath = finished.uri.toString()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cancelled OkHttp call surfaces as an IOException: report it as a cancellation.
            currentCoroutineContext().ensureActive()
            e.printStackTrace()
            failTask(task.id, friendlyError(e))
        } finally {
            destination?.let { StorageHelper.discardDestination(appContext, it) }
            tempVideo.delete()
            tempAudio.delete()
            tempOutput.delete()
        }
    }

    /**
     * Downloads [url] into [out] and returns the number of bytes written.
     * Survives short network drops by resuming with HTTP Range requests.
     */
    private suspend fun fetch(
        taskId: String,
        url: String,
        out: OutputStream,
        onProgress: (downloaded: Long, total: Long, speedBps: Long) -> Unit
    ): Long {
        val host = SocialPlatform.hostOf(url)
        val chunked = SocialPlatform.hostMatches(host, "googlevideo.com")
        val referer = refererFor(host)

        var offset = 0L
        var total = -1L
        var failures = 0
        val buffer = ByteArray(64 * 1024)

        var lastTick = System.currentTimeMillis()
        var bytesSinceTick = 0L

        while (true) {
            currentCoroutineContext().ensureActive()

            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Referer", referer)
            val sentRange = chunked || offset > 0L
            if (chunked) {
                builder.header("Range", "bytes=$offset-${offset + CHUNK_BYTES - 1}")
            } else if (offset > 0L) {
                builder.header("Range", "bytes=$offset-")
            }

            val call = httpClient.newCall(builder.build())
            activeCalls[taskId] = call
            try {
                call.execute().use { response ->
                    // Asked for bytes past the end: everything is already here.
                    if (response.code == 416 && offset > 0L) return offset
                    if (!response.isSuccessful) throw HttpStatusException(response.code)

                    val partial = response.code == 206
                    if (sentRange && !partial && offset > 0L) {
                        // Server ignored the Range header, so the stream cannot be resumed.
                        throw FatalDownloadException("Connection dropped and this server cannot resume. Please retry.")
                    }

                    val body = response.body ?: throw IOException("Empty response body")
                    if (total <= 0L) {
                        total = if (partial) {
                            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                        } else {
                            body.contentLength()
                        }
                    }

                    var received = 0L
                    val input = body.byteStream()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        offset += read
                        received += read
                        bytesSinceTick += read

                        val now = System.currentTimeMillis()
                        if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                            val speed = bytesSinceTick * 1000 / (now - lastTick)
                            onProgress(offset, total, speed)
                            lastTick = now
                            bytesSinceTick = 0L
                        }
                    }
                    failures = 0

                    val complete = when {
                        !partial -> true                              // whole body in one answer
                        total > 0L -> offset >= total                 // known size reached
                        chunked -> received < CHUNK_BYTES             // short chunk = last chunk
                        else -> true                                  // open ended range read to EOF
                    }
                    if (complete) {
                        out.flush()
                        onProgress(offset, total, 0L)
                        return offset
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (e is FatalDownloadException || ++failures > MAX_NETWORK_RETRIES) throw e
                delay(1500L * failures)
            } finally {
                activeCalls.remove(taskId, call)
            }
        }
    }

    private fun reportProgress(
        taskId: String,
        downloaded: Long,
        total: Long,
        speedBps: Long,
        fromPercent: Int,
        toPercent: Int
    ) {
        val span = toPercent - fromPercent
        val percent = if (total > 0L) {
            fromPercent + ((downloaded.coerceAtMost(total) * span) / total).toInt()
        } else {
            fromPercent
        }
        updateTask(taskId) {
            it.copy(
                downloadedBytes = downloaded,
                totalBytes = if (total > 0L) total else it.totalBytes,
                // 100 is reserved for the COMPLETED state.
                progress = percent.coerceIn(0, 99),
                speedBps = speedBps
            )
        }
    }

    private fun friendlyError(e: Exception): String {
        return when (e) {
            is HttpStatusException -> when (e.code) {
                403, 410 -> "Link expired or blocked by the server (${e.code}). Fetch the link again."
                404 -> "File not found on the server (404)."
                429 -> "Too many requests (429). Please try again in a few minutes."
                else -> "Server returned error: ${e.code}"
            }
            is java.net.UnknownHostException, is java.net.ConnectException -> "No internet connection."
            is java.net.SocketTimeoutException -> "Connection timed out. Please retry."
            else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Network interrupted"
        }
    }

    private fun refererFor(host: String): String {
        return when {
            SocialPlatform.hostMatches(host, "facebook.com", "fbcdn.net") -> "https://www.facebook.com/"
            SocialPlatform.hostMatches(host, "youtube.com", "googlevideo.com") -> "https://www.youtube.com/"
            SocialPlatform.hostMatches(host, "suno.ai", "suno.com") || host == "d2lwuy8qc234o3.cloudfront.net" ->
                "https://suno.com/"
            host.contains("tiktok") -> "https://www.tiktok.com/"
            SocialPlatform.hostMatches(host, "instagram.com", "cdninstagram.com") -> "https://www.instagram.com/"
            SocialPlatform.hostMatches(host, "twitter.com", "x.com", "twimg.com") -> "https://twitter.com/"
            host.isNotEmpty() -> "https://$host/"
            else -> "https://www.google.com/"
        }
    }

    private fun muxTempDir(): File = File(appContext.cacheDir, "dm_mux_temp")

    private fun failTask(taskId: String, message: String) {
        updateTask(taskId) {
            it.copy(status = TaskStatus.FAILED, errorMessage = message, speedBps = 0L)
        }
    }

    private fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) transform(it) else it }
        }
    }

    /**
     * Keeps the process alive while downloads run. The service watches [tasks] itself,
     * updates its notification and stops when nothing is pending any more.
     */
    private fun startForegroundService() {
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, DownloadForegroundService::class.java)
            )
        } catch (e: Exception) {
            // Not allowed right now (app in background): the download still runs in-process.
            e.printStackTrace()
        }
    }
}
