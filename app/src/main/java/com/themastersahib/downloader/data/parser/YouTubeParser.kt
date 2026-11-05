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
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.regex.Pattern

class YouTubeParser(private val client: OkHttpClient) {

    companion object {
        @Volatile
        private var isExtractorInitialized = false

        fun ensureInitialized(client: OkHttpClient) {
            if (!isExtractorInitialized) {
                synchronized(this) {
                    if (!isExtractorInitialized) {
                        try {
                            NewPipe.init(NewPipeDownloader(client))
                            isExtractorInitialized = true
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/|youtube\\.com/reel/)([a-zA-Z0-9_-]{11})",
            "(?:v=|vi=|/v/|/embed/|/e/)([a-zA-Z0-9_-]{11})"
        )
        for (pattern in patterns) {
            val matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    suspend fun parse(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
        val canonicalUrl = if (videoId != null) "https://www.youtube.com/watch?v=$videoId" else url

        // Strategy 1: Local Device-Side Extraction with NewPipeExtractor
        try {
            ensureInitialized(client)
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)

            val title = streamInfo.name?.ifEmpty { "YouTube Video" } ?: "YouTube Video"
            val author = streamInfo.uploaderName?.ifEmpty { "YouTube Channel" } ?: "YouTube Channel"
            val duration = streamInfo.duration
            val thumbnail = streamInfo.thumbnails?.firstOrNull()?.url

            val formatList = mutableListOf<DownloadFormat>()
            val addedResolutions = mutableSetOf<String>()

            // Progressive streams (Combined Audio + Video, ready to play & download)
            streamInfo.videoStreams?.forEach { stream ->
                val res = stream.resolution ?: ""
                val streamUrl = (try { stream.url } catch (_: Exception) { null }) ?: stream.content
                if (!streamUrl.isNullOrEmpty() && !addedResolutions.contains(res)) {
                    addedResolutions.add(res)
                    val formatName = stream.format?.name?.lowercase() ?: "mp4"
                    val isHd = res.contains("720") || res.contains("1080")
                    formatList.add(
                        DownloadFormat(
                            id = "yt_prog_${res}_${formatName}",
                            label = "$res HD Video ($formatName)",
                            resolution = res,
                            downloadUrl = streamUrl,
                            isAudioOnly = false,
                            extension = "mp4",
                            note = if (isHd) "High Definition Video (With Audio)" else "Standard Definition (With Audio)"
                        )
                    )
                }
            }

            // Audio only stream
            streamInfo.audioStreams?.firstOrNull()?.let { audio ->
                val audioUrl = (try { audio.url } catch (_: Exception) { null }) ?: audio.content
                if (!audioUrl.isNullOrEmpty()) {
                    val bitrate = audio.averageBitrate
                    val ext = if (audio.format?.name?.contains("m4a", true) == true) "m4a" else "mp3"
                    formatList.add(
                        DownloadFormat(
                            id = "yt_audio_hq",
                            label = "Audio Only ($ext - ${if (bitrate > 0) "${bitrate}kbps" else "HQ"})",
                            resolution = "Audio",
                            downloadUrl = audioUrl,
                            isAudioOnly = true,
                            extension = ext,
                            note = "High Quality Audio Stream"
                        )
                    )
                }
            }

            // Video only streams (fallback if progressive is limited)
            if (formatList.none { !it.isAudioOnly }) {
                streamInfo.videoOnlyStreams?.take(3)?.forEach { stream ->
                    val res = stream.resolution ?: ""
                    val streamUrl = (try { stream.url } catch (_: Exception) { null }) ?: stream.content
                    if (!streamUrl.isNullOrEmpty() && !addedResolutions.contains(res)) {
                        addedResolutions.add(res)
                        formatList.add(
                            DownloadFormat(
                                id = "yt_vo_${res}",
                                label = "$res Video Stream",
                                resolution = res,
                                downloadUrl = streamUrl,
                                isAudioOnly = false,
                                extension = "mp4",
                                note = "$res High Definition Stream"
                            )
                        )
                    }
                }
            }

            if (formatList.isNotEmpty()) {
                return@withContext VideoInfo(
                    originalUrl = url,
                    title = title,
                    thumbnail = thumbnail,
                    author = author,
                    durationSeconds = duration,
                    platform = SocialPlatform.YOUTUBE,
                    formats = formatList
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Strategy 2: Invidious Instances Backup
        if (videoId != null) {
            val invidiousInstances = listOf(
                "https://yt.chocolatemoo53.com",
                "https://invidious.nerdvpn.de",
                "https://inv.nadeko.net"
            )
            for (host in invidiousInstances) {
                try {
                    val request = Request.Builder()
                        .url("$host/api/v1/videos/$videoId")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@use
                            val json = JsonParser.parseString(body).asJsonObject
                            val title = json.get("title")?.asString ?: "YouTube Video"
                            val author = json.get("author")?.asString ?: "YouTube Channel"
                            val duration = json.get("lengthSeconds")?.asLong ?: 0L

                            val formatList = mutableListOf<DownloadFormat>()
                            val formatStreams = json.getAsJsonArray("formatStreams")
                            if (formatStreams != null) {
                                for (item in formatStreams) {
                                    val stream = item.asJsonObject
                                    val quality = stream.get("qualityLabel")?.asString ?: stream.get("resolution")?.asString ?: ""
                                    val streamUrl = stream.get("url")?.asString ?: continue
                                    val container = stream.get("container")?.asString ?: "mp4"

                                    formatList.add(
                                        DownloadFormat(
                                            id = "inv_prog_$quality",
                                            label = "$quality Video ($container)",
                                            resolution = quality,
                                            downloadUrl = streamUrl,
                                            isAudioOnly = false,
                                            extension = "mp4",
                                            note = "Direct Invidious Video Stream"
                                        )
                                    )
                                }
                            }

                            val adaptiveFormats = json.getAsJsonArray("adaptiveFormats")
                            if (adaptiveFormats != null) {
                                for (item in adaptiveFormats) {
                                    val stream = item.asJsonObject
                                    val type = stream.get("type")?.asString ?: ""
                                    val streamUrl = stream.get("url")?.asString ?: continue
                                    if (type.contains("audio/")) {
                                        formatList.add(
                                            DownloadFormat(
                                                id = "inv_audio",
                                                label = "Audio Only (MP3 / M4A)",
                                                resolution = "Audio",
                                                downloadUrl = streamUrl,
                                                isAudioOnly = true,
                                                extension = "mp3",
                                                note = "Audio Stream"
                                            )
                                        )
                                        break
                                    }
                                }
                            }

                            if (formatList.isNotEmpty()) {
                                return@withContext VideoInfo(
                                    originalUrl = url,
                                    title = title,
                                    thumbnail = null,
                                    author = author,
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

        null
    }
}
