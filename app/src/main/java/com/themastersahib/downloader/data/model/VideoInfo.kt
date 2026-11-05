package com.themastersahib.downloader.data.model

data class VideoInfo(
    val originalUrl: String,
    val title: String,
    val thumbnail: String?,
    val author: String?,
    val durationSeconds: Long = 0L,
    val platform: SocialPlatform,
    val formats: List<DownloadFormat>
) {
    val videoFormats: List<DownloadFormat>
        get() = formats.filter { !it.isAudioOnly }

    val audioFormats: List<DownloadFormat>
        get() = formats.filter { it.isAudioOnly }
}
