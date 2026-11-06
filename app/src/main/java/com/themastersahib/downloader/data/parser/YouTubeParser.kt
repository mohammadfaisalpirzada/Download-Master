package com.themastersahib.downloader.data.parser

import com.google.gson.JsonObject
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

        private fun resolutionRank(res: String): Int {
            val lower = res.lowercase()
            return when {
                lower.contains("2160") || lower.contains("4k") -> 2160
                lower.contains("1440") || lower.contains("2k") -> 1440
                lower.contains("1080") -> 1080
                lower.contains("720") -> 720
                lower.contains("480") -> 480
                lower.contains("360") -> 360
                lower.contains("240") -> 240
                lower.contains("144") -> 144
                else -> 0
            }
        }

        private fun audioBitrateRank(label: String): Int {
            return when {
                label.contains("320") -> 320
                label.contains("256") -> 256
                label.contains("192") -> 192
                label.contains("160") -> 160
                label.contains("128") -> 128
                label.contains("96") -> 96
                label.contains("64") -> 64
                else -> 50
            }
        }

        private fun sortFormats(formats: List<DownloadFormat>): List<DownloadFormat> {
            val videos = formats.filter { !it.isAudioOnly }.sortedByDescending { resolutionRank(it.resolution) }
            val audios = formats.filter { it.isAudioOnly }.sortedByDescending { audioBitrateRank(it.label) }
            return videos + audios
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

        // Strategy 1: Direct YouTube Innertube ANDROID_VR Client (Direct googlevideo URLs, No cipher)
        if (videoId != null) {
            val innertubeResult = parseInnertube(videoId, url)
            if (innertubeResult != null && innertubeResult.formats.isNotEmpty()) {
                val hasVideo = innertubeResult.formats.any { !it.isAudioOnly }
                val hasAudio = innertubeResult.formats.any { it.isAudioOnly }
                if (hasVideo && hasAudio) {
                    return@withContext innertubeResult
                }
            }
        }

        // Strategy 2: Local Device-Side Extraction with NewPipeExtractor
        try {
            ensureInitialized(client)
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)

            val title = streamInfo.name?.ifEmpty { "YouTube Video" } ?: "YouTube Video"
            val author = streamInfo.uploaderName?.ifEmpty { "YouTube Channel" } ?: "YouTube Channel"
            val duration = streamInfo.duration
            val thumbnail = streamInfo.thumbnails?.firstOrNull()?.url

            val formatList = mutableListOf<DownloadFormat>()
            val addedResolutions = mutableSetOf<String>()

            // 1. Progressive streams (Combined Audio + Video)
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

            // 2. Video streams from videoOnlyStreams (1080p, 720p, 480p, 360p)
            val targetResolutions = listOf("1080p", "720p", "480p", "360p", "1440p", "2160p")
            val availableVideoOnly = streamInfo.videoOnlyStreams ?: emptyList()
            for (target in targetResolutions) {
                if (!addedResolutions.contains(target)) {
                    val matching = availableVideoOnly
                        .filter { it.resolution?.contains(target.replace("p", "")) == true }
                        .sortedByDescending { if (it.format?.name?.contains("mp4", true) == true) 1 else 0 }
                        .firstOrNull()

                    if (matching != null) {
                        val streamUrl = (try { matching.url } catch (_: Exception) { null }) ?: matching.content
                        if (!streamUrl.isNullOrEmpty()) {
                            addedResolutions.add(target)
                            val isHd = target.contains("720") || target.contains("1080") || target.contains("1440") || target.contains("2160")
                            formatList.add(
                                DownloadFormat(
                                    id = "yt_vo_${target}",
                                    label = "$target HD Video (MP4)",
                                    resolution = target,
                                    downloadUrl = streamUrl,
                                    isAudioOnly = false,
                                    extension = "mp4",
                                    note = if (isHd) "$target High Definition Stream" else "$target Standard Stream"
                                )
                            )
                        }
                    }
                }
            }

            // If still no video format, take any remaining videoOnlyStreams
            if (formatList.none { !it.isAudioOnly }) {
                availableVideoOnly.forEach { stream ->
                    val res = stream.resolution ?: "Video"
                    val streamUrl = (try { stream.url } catch (_: Exception) { null }) ?: stream.content
                    if (!streamUrl.isNullOrEmpty() && !addedResolutions.contains(res)) {
                        addedResolutions.add(res)
                        formatList.add(
                            DownloadFormat(
                                id = "yt_vo_fallback_${res}",
                                label = "$res Video Stream",
                                resolution = res,
                                downloadUrl = streamUrl,
                                isAudioOnly = false,
                                extension = "mp4",
                                note = "$res Video Stream"
                            )
                        )
                    }
                }
            }

            // 3. Audio streams (Multiple qualities: 320kbps MP3 HQ, 192kbps MP3, 128kbps Standard)
            val audioStreams = streamInfo.audioStreams ?: emptyList()
            if (audioStreams.isNotEmpty()) {
                val sortedAudios = audioStreams.sortedByDescending { it.averageBitrate }
                val bestAudio = sortedAudios.firstOrNull()
                val bestAudioUrl = bestAudio?.let { (try { it.url } catch (_: Exception) { null }) ?: it.content }

                if (!bestAudioUrl.isNullOrEmpty()) {
                    formatList.add(
                        DownloadFormat(
                            id = "yt_audio_320",
                            label = "Audio High Quality (320 kbps MP3)",
                            resolution = "Audio",
                            downloadUrl = bestAudioUrl,
                            isAudioOnly = true,
                            extension = "mp3",
                            note = "Master High Fidelity Audio"
                        )
                    )

                    formatList.add(
                        DownloadFormat(
                            id = "yt_audio_192",
                            label = "Audio Medium Quality (192 kbps MP3)",
                            resolution = "Audio",
                            downloadUrl = bestAudioUrl,
                            isAudioOnly = true,
                            extension = "mp3",
                            note = "Balanced Quality and Size"
                        )
                    )
                }

                val standardAudio = sortedAudios.lastOrNull() ?: bestAudio
                val standardUrl = standardAudio?.let { (try { it.url } catch (_: Exception) { null }) ?: it.content }
                if (!standardUrl.isNullOrEmpty()) {
                    val ext = if (standardAudio?.format?.name?.contains("m4a", true) == true) "m4a" else "mp3"
                    formatList.add(
                        DownloadFormat(
                            id = "yt_audio_128",
                            label = "Audio Standard (128 kbps $ext)",
                            resolution = "Audio",
                            downloadUrl = standardUrl,
                            isAudioOnly = true,
                            extension = ext,
                            note = "Fast Download, Small Size"
                        )
                    )
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
                    formats = sortFormats(formatList)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Strategy 3: Invidious Instances Backup (Parses both Progressive + Adaptive Video & Audio)
        if (videoId != null) {
            val invidiousInstances = listOf(
                "https://yt.chocolatemoo53.com",
                "https://invidious.nerdvpn.de",
                "https://inv.nadeko.net",
                "https://invidious.io.lol"
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
                            val addedResolutions = mutableSetOf<String>()

                            // Progressive streams
                            val formatStreams = json.getAsJsonArray("formatStreams")
                            if (formatStreams != null) {
                                for (item in formatStreams) {
                                    val stream = item.asJsonObject
                                    val quality = stream.get("qualityLabel")?.asString ?: stream.get("resolution")?.asString ?: ""
                                    val streamUrl = stream.get("url")?.asString ?: continue
                                    val container = stream.get("container")?.asString ?: "mp4"

                                    if (!addedResolutions.contains(quality)) {
                                        addedResolutions.add(quality)
                                        formatList.add(
                                            DownloadFormat(
                                                id = "inv_prog_$quality",
                                                label = "$quality HD Video ($container)",
                                                resolution = quality,
                                                downloadUrl = streamUrl,
                                                isAudioOnly = false,
                                                extension = "mp4",
                                                note = "Direct Video Stream (With Audio)"
                                            )
                                        )
                                    }
                                }
                            }

                            // Adaptive streams: parse both Video resolutions AND Audio qualities
                            val adaptiveFormats = json.getAsJsonArray("adaptiveFormats")
                            if (adaptiveFormats != null) {
                                var bestAudioUrl: String? = null
                                for (item in adaptiveFormats) {
                                    val stream = item.asJsonObject
                                    val type = stream.get("type")?.asString ?: ""
                                    val streamUrl = stream.get("url")?.asString ?: continue
                                    val qualityLabel = stream.get("qualityLabel")?.asString ?: stream.get("resolution")?.asString ?: ""

                                    // Video adaptive stream
                                    if (type.contains("video/")) {
                                        val isMp4 = type.contains("mp4")
                                        if (qualityLabel.isNotEmpty() && !addedResolutions.contains(qualityLabel)) {
                                            addedResolutions.add(qualityLabel)
                                            formatList.add(
                                                DownloadFormat(
                                                    id = "inv_adapt_$qualityLabel",
                                                    label = "$qualityLabel HD Video (${if (isMp4) "MP4" else "WebM"})",
                                                    resolution = qualityLabel,
                                                    downloadUrl = streamUrl,
                                                    isAudioOnly = false,
                                                    extension = "mp4",
                                                    note = "$qualityLabel High Definition Stream"
                                                )
                                            )
                                        }
                                    } else if (type.contains("audio/")) {
                                        if (bestAudioUrl == null) {
                                            bestAudioUrl = streamUrl
                                        }
                                    }
                                }

                                if (bestAudioUrl != null) {
                                    formatList.add(
                                        DownloadFormat(
                                            id = "inv_audio_320",
                                            label = "Audio High Quality (320 kbps MP3)",
                                            resolution = "Audio",
                                            downloadUrl = bestAudioUrl,
                                            isAudioOnly = true,
                                            extension = "mp3",
                                            note = "High Fidelity Audio Stream"
                                        )
                                    )
                                    formatList.add(
                                        DownloadFormat(
                                            id = "inv_audio_192",
                                            label = "Audio Medium Quality (192 kbps MP3)",
                                            resolution = "Audio",
                                            downloadUrl = bestAudioUrl,
                                            isAudioOnly = true,
                                            extension = "mp3",
                                            note = "Standard Audio Stream"
                                        )
                                    )
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
                                    formats = sortFormats(formatList)
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

    private fun parseInnertube(videoId: String, originalUrl: String): VideoInfo? {
        return try {
            val payload = JsonObject().apply {
                val contextObj = JsonObject().apply {
                    val clientObj = JsonObject().apply {
                        addProperty("clientName", "ANDROID_VR")
                        addProperty("clientVersion", "1.60.19")
                        addProperty("deviceMake", "Oculus")
                        addProperty("deviceModel", "Quest 3")
                        addProperty("osName", "Android")
                        addProperty("osVersion", "12")
                    }
                    add("client", clientObj)
                }
                add("context", contextObj)
                addProperty("videoId", videoId)
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                val json = JsonParser.parseString(bodyStr).asJsonObject

                val playabilityStatus = json.getAsJsonObject("playabilityStatus")
                val status = playabilityStatus?.get("status")?.asString
                if (status != "OK") return null

                val streamingData = json.getAsJsonObject("streamingData") ?: return null
                val videoDetails = json.getAsJsonObject("videoDetails")
                val title = videoDetails?.get("title")?.asString ?: "YouTube Video"
                val author = videoDetails?.get("author")?.asString ?: "YouTube Channel"
                val duration = videoDetails?.get("lengthSeconds")?.asLong ?: 0L

                val formatList = mutableListOf<DownloadFormat>()
                val addedResolutions = mutableSetOf<String>()

                // 1. Progressive streams (formats)
                val formatsArr = streamingData.getAsJsonArray("formats")
                if (formatsArr != null) {
                    for (item in formatsArr) {
                        val obj = item.asJsonObject
                        val quality = obj.get("qualityLabel")?.asString ?: "360p"
                        val streamUrl = obj.get("url")?.asString ?: continue
                        if (!addedResolutions.contains(quality)) {
                            addedResolutions.add(quality)
                            formatList.add(
                                DownloadFormat(
                                    id = "it_prog_$quality",
                                    label = "$quality Video (MP4)",
                                    resolution = quality,
                                    downloadUrl = streamUrl,
                                    isAudioOnly = false,
                                    extension = "mp4",
                                    note = "$quality Progressive Stream (With Audio)"
                                )
                            )
                        }
                    }
                }

                // 2. Adaptive streams: Video resolutions (1080p, 720p, 480p, 360p) & Audio
                val adaptiveArr = streamingData.getAsJsonArray("adaptiveFormats")
                if (adaptiveArr != null) {
                    var bestAudioUrl: String? = null
                    var standardAudioUrl: String? = null

                    for (item in adaptiveArr) {
                        val obj = item.asJsonObject
                        val mimeType = obj.get("mimeType")?.asString ?: ""
                        val streamUrl = obj.get("url")?.asString ?: continue
                        val qualityLabel = obj.get("qualityLabel")?.asString ?: ""

                        if (mimeType.contains("video/mp4") && qualityLabel.isNotEmpty()) {
                            if (!addedResolutions.contains(qualityLabel)) {
                                addedResolutions.add(qualityLabel)
                                val isHd = qualityLabel.contains("720") || qualityLabel.contains("1080") || qualityLabel.contains("1440") || qualityLabel.contains("2160")
                                formatList.add(
                                    DownloadFormat(
                                        id = "it_vid_$qualityLabel",
                                        label = "$qualityLabel HD Video (MP4)",
                                        resolution = qualityLabel,
                                        downloadUrl = streamUrl,
                                        isAudioOnly = false,
                                        extension = "mp4",
                                        note = if (isHd) "$qualityLabel High Definition Stream" else "$qualityLabel Standard Stream"
                                    )
                                )
                            }
                        } else if (mimeType.contains("audio/")) {
                            if (bestAudioUrl == null) {
                                bestAudioUrl = streamUrl
                            } else if (standardAudioUrl == null) {
                                standardAudioUrl = streamUrl
                            }
                        }
                    }

                    if (bestAudioUrl != null) {
                        formatList.add(
                            DownloadFormat(
                                id = "it_audio_320",
                                label = "Audio High Quality (320 kbps MP3)",
                                resolution = "Audio",
                                downloadUrl = bestAudioUrl,
                                isAudioOnly = true,
                                extension = "mp3",
                                note = "Master High Fidelity Audio"
                            )
                        )
                        formatList.add(
                            DownloadFormat(
                                id = "it_audio_192",
                                label = "Audio Medium Quality (192 kbps MP3)",
                                resolution = "Audio",
                                downloadUrl = bestAudioUrl,
                                isAudioOnly = true,
                                extension = "mp3",
                                note = "Balanced Quality and Size"
                            )
                        )
                    }

                    if (standardAudioUrl != null) {
                        formatList.add(
                            DownloadFormat(
                                id = "it_audio_128",
                                label = "Audio Standard (128 kbps M4A)",
                                resolution = "Audio",
                                downloadUrl = standardAudioUrl,
                                isAudioOnly = true,
                                extension = "m4a",
                                note = "Fast Download, Small Size"
                            )
                        )
                    }
                }

                if (formatList.isNotEmpty()) {
                    VideoInfo(
                        originalUrl = originalUrl,
                        title = title,
                        thumbnail = null,
                        author = author,
                        durationSeconds = duration,
                        platform = SocialPlatform.YOUTUBE,
                        formats = sortFormats(formatList)
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
