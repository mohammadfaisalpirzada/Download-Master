package com.themastersahib.downloader.data.parser

import com.google.gson.JsonParser
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.SocialPlatform
import com.themastersahib.downloader.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.regex.Pattern

class YouTubeParser(private val client: OkHttpClient) {

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.privacydev.net",
        "https://pipedapi.tokhmi.xyz"
    )

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/)([a-zA-Z0-9_-]{11})"
        )
        for (pattern in patterns) {
            val matcher = Pattern.compile(pattern).matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    suspend fun parse(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)

        // Strategy 1: Piped API (Provides detailed streams and exact file sizes)
        if (videoId != null) {
            for (instance in pipedInstances) {
                try {
                    val request = Request.Builder()
                        .url("$instance/streams/$videoId")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@use
                            val json = JsonParser.parseString(body).asJsonObject

                            val title = json.get("title")?.asString ?: "YouTube Video"
                            val thumbnail = json.get("thumbnailUrl")?.asString
                            val uploader = json.get("uploader")?.asString ?: "Channel"
                            val duration = json.get("duration")?.asLong ?: 0L

                            val formatList = mutableListOf<DownloadFormat>()

                            // Video Streams
                            val videoStreams = json.getAsJsonArray("videoStreams")
                            if (videoStreams != null) {
                                val addedQualities = mutableSetOf<String>()
                                for (item in videoStreams) {
                                    val stream = item.asJsonObject
                                    val quality = stream.get("quality")?.asString ?: ""
                                    val streamUrl = stream.get("url")?.asString ?: continue
                                    val size = stream.get("contentLength")?.asLong ?: 0L
                                    val format = stream.get("format")?.asString ?: "mp4"

                                    // Deduplicate and filter primary resolutions
                                    if (!addedQualities.contains(quality) && (quality.contains("1080") || quality.contains("720") || quality.contains("480") || quality.contains("360"))) {
                                        addedQualities.add(quality)
                                        formatList.add(
                                            DownloadFormat(
                                                id = "yt_v_$quality",
                                                label = "$quality Video ($format)",
                                                resolution = quality,
                                                downloadUrl = streamUrl,
                                                isAudioOnly = false,
                                                extension = "mp4",
                                                estimatedSizeBytes = size,
                                                note = if (quality.contains("1080")) "High Definition 1080p" else "Standard HD $quality"
                                            )
                                        )
                                    }
                                }
                            }

                            // Audio Streams
                            val audioStreams = json.getAsJsonArray("audioStreams")
                            if (audioStreams != null && audioStreams.size() > 0) {
                                val bestAudio = audioStreams[0].asJsonObject
                                val audioUrl = bestAudio.get("url")?.asString
                                val audioSize = bestAudio.get("contentLength")?.asLong ?: 0L
                                val bitrate = bestAudio.get("bitrate")?.asInt ?: 128
                                if (!audioUrl.isNullOrEmpty()) {
                                    formatList.add(
                                        DownloadFormat(
                                            id = "yt_audio_hq",
                                            label = "Audio (MP3 / M4A - ${bitrate / 1000} kbps)",
                                            resolution = "Audio",
                                            downloadUrl = audioUrl,
                                            isAudioOnly = true,
                                            extension = "mp3",
                                            estimatedSizeBytes = audioSize,
                                            note = "High Quality Audio Only"
                                        )
                                    )
                                }
                            }

                            if (formatList.isNotEmpty()) {
                                return@withContext VideoInfo(
                                    originalUrl = url,
                                    title = title,
                                    thumbnail = thumbnail,
                                    author = uploader,
                                    durationSeconds = duration,
                                    platform = SocialPlatform.YOUTUBE,
                                    formats = formatList
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Try next instance
                }
            }
        }

        // Strategy 2: Cobalt Privacy Downloader Gateway
        return@withContext parseViaCobalt(url, SocialPlatform.YOUTUBE)
    }

    private suspend fun parseViaCobalt(url: String, platform: SocialPlatform): VideoInfo? = withContext(Dispatchers.IO) {
        val cobaltInstances = listOf(
            "https://cobalt-api.kwiatekm.tokyo",
            "https://api.cobalt.tools"
        )
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        for (host in cobaltInstances) {
            try {
                // Request 1080p
                val payload = "{\"url\": \"$url\", \"videoQuality\": \"1080\", \"downloadMode\": \"auto\"}"
                val request = Request.Builder()
                    .url(host)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JsonParser.parseString(body).asJsonObject
                        val downloadUrl = json.get("url")?.asString ?: return@use
                        val filename = json.get("filename")?.asString ?: "Video"

                        val formats = listOf(
                            DownloadFormat(
                                id = "cobalt_video_hd",
                                label = "1080p / 720p HD Video",
                                resolution = "1080p",
                                downloadUrl = downloadUrl,
                                isAudioOnly = false,
                                extension = "mp4",
                                note = "High Definition Video"
                            ),
                            DownloadFormat(
                                id = "cobalt_audio",
                                label = "Audio Only (MP3)",
                                resolution = "Audio",
                                downloadUrl = downloadUrl,
                                isAudioOnly = true,
                                extension = "mp3",
                                note = "Extracted Audio Stream"
                            )
                        )

                        return@withContext VideoInfo(
                            originalUrl = url,
                            title = filename.substringBeforeLast("."),
                            thumbnail = null,
                            author = "Master Downloader",
                            durationSeconds = 0L,
                            platform = platform,
                            formats = formats
                        )
                    }
                }
            } catch (e: Exception) {
                // Next host
            }
        }
        null
    }
}
