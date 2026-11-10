package com.themastersahib.downloader.data.model

data class DownloadFormat(
    val id: String,
    val label: String,
    val resolution: String,
    val downloadUrl: String,
    val isAudioOnly: Boolean = false,
    val extension: String = if (isAudioOnly) "mp3" else "mp4",
    val estimatedSizeBytes: Long = 0L,
    val note: String = "",
    val audioTrackUrl: String? = null
) {
    val formattedSize: String
        get() {
            if (estimatedSizeBytes <= 0L) return ""
            val mb = estimatedSizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1024) {
                String.format("%.2f GB", mb / 1024)
            } else {
                String.format("%.1f MB", mb)
            }
        }
}
