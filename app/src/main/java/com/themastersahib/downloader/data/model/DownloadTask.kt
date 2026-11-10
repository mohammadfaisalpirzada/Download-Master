package com.themastersahib.downloader.data.model

enum class TaskStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadTask(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val downloadUrl: String,
    val platform: SocialPlatform,
    val formatLabel: String,
    val isAudioOnly: Boolean,
    val localFilePath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progress: Int = 0,
    val speedBps: Long = 0L,
    val status: TaskStatus = TaskStatus.PENDING,
    val errorMessage: String? = null,
    val audioTrackUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val formattedSpeed: String
        get() {
            val kb = speedBps / 1024.0
            return if (kb > 1024) {
                String.format("%.1f MB/s", kb / 1024)
            } else {
                String.format("%.0f KB/s", kb)
            }
        }

    val formattedDownloaded: String
        get() {
            val mb = downloadedBytes / (1024.0 * 1024.0)
            return if (totalBytes > 0) {
                val totalMb = totalBytes / (1024.0 * 1024.0)
                String.format("%.1f / %.1f MB", mb, totalMb)
            } else {
                String.format("%.1f MB", mb)
            }
        }
}
