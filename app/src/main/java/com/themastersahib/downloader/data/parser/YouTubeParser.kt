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

        /** Audio entries keep the order the parser produced (best first); videos go high to low. */
        private fun sortFormats(formats: List<DownloadFormat>): List<DownloadFormat> {
            val videos = formats.filter { !it.isAudioOnly }.sortedByDescending { resolutionRank(it.resolution) }
            val audios = formats.filter { it.isAudioOnly }
            return videos + audios
        }

        /** Codecs the on-device MP4 muxer can merge with an AAC audio track. */
        private fun isMuxableVideo(mimeType: String): Boolean {
            val lower = mimeType.lowercase()
            return lower.contains("video/mp4") &&
                (lower.contains("avc1") || lower.contains("hvc1") || lower.contains("hev1"))
        }

        private fun isAacAudio(mimeType: String): Boolean = mimeType.lowercase().contains("audio/mp4")

        private fun audioLabel(container: String, bitrateBps: Long): String {
            val kbps = bitrateBps / 1000
            return if (kbps > 0) "Audio ${container.uppercase()} ($kbps kbps)" else "Audio ${container.uppercase()}"
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

            // 1. Audio streams: resolve best audio first so it can be muxed with video
            val audioStreams = streamInfo.audioStreams ?: emptyList()
            val sortedAudios = audioStreams.sortedByDescending { it.averageBitrate }
            // AAC (m4a) first: it is the only audio the MP4 muxer can merge with the video track.
            val m4aAudios = sortedAudios.filter { it.format?.suffix.equals("m4a", ignoreCase = true) }
            val bestAudio = m4aAudios.firstOrNull() ?: sortedAudios.firstOrNull()
            val bestAudioIsM4a = m4aAudios.isNotEmpty()
            val bestAudioUrl = bestAudio?.let { (try { it.url } catch (_: Exception) { null }) ?: it.content }
            // Without an AAC track adaptive video cannot be merged, so it is not offered.
            val muxAudioUrl = if (bestAudioIsM4a) bestAudioUrl else null

            // 2. Progressive streams (Combined Audio + Video already embedded)
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
                            label = "$res Video ($formatName)",
                            resolution = res,
                            downloadUrl = streamUrl,
                            audioTrackUrl = null,
                            isAudioOnly = false,
                            extension = "mp4",
                            note = if (isHd) "High Definition Video (With Audio)" else "Standard Definition (With Audio)"
                        )
                    )
                }
            }

            // 3. Video streams from videoOnlyStreams (1080p, 720p, 480p, 360p) with audioTrackUrl attached!
            val targetResolutions = listOf("1080p", "720p", "480p", "360p", "1440p", "2160p")
            val availableVideoOnly = if (muxAudioUrl.isNullOrEmpty()) {
                emptyList()
            } else {
                // MP4 container only: WebM (VP9) video cannot be merged on-device.
                (streamInfo.videoOnlyStreams ?: emptyList())
                    .filter { it.format?.suffix.equals("mp4", ignoreCase = true) }
            }
            for (target in targetResolutions) {
                if (!addedResolutions.contains(target)) {
                    val matching = availableVideoOnly
                        .firstOrNull { it.resolution?.startsWith(target.removeSuffix("p")) == true }

                    if (matching != null) {
                        val streamUrl = (try { matching.url } catch (_: Exception) { null }) ?: matching.content
                        if (!streamUrl.isNullOrEmpty()) {
                            addedResolutions.add(target)
                            val isHd = target.contains("720") || target.contains("1080") || target.contains("1440") || target.contains("2160")
                            formatList.add(
                                DownloadFormat(
                                    id = "yt_vo_${target}",
                                    label = "$target Video (MP4)",
                                    resolution = target,
                                    downloadUrl = streamUrl,
                                    audioTrackUrl = muxAudioUrl,
                                    isAudioOnly = false,
                                    extension = "mp4",
                                    note = if (isHd) "$target HD Video (With Audio)" else "$target SD Video (With Audio)"
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
                                audioTrackUrl = muxAudioUrl,
                                isAudioOnly = false,
                                extension = "mp4",
                                note = "$res Video Stream (With Audio)"
                            )
                        )
                    }
                }
            }

            // 4. Audio format options (real container and bitrate, no fake MP3 labels)
            val offeredAudios = listOfNotNull(bestAudio, (if (bestAudioIsM4a) m4aAudios else sortedAudios).lastOrNull())
                .distinct()
            for (audio in offeredAudios) {
                val audioUrl = (try { audio.url } catch (_: Exception) { null }) ?: audio.content
                if (audioUrl.isNullOrEmpty()) continue
                val ext = if (audio.format?.suffix.equals("m4a", ignoreCase = true)) "m4a" else "webm"
                formatList.add(
                    DownloadFormat(
                        id = "yt_audio_${ext}_${audio.averageBitrate}",
                        label = audioLabel(ext, audio.averageBitrate.toLong() * 1000),
                        resolution = "Audio",
                        downloadUrl = audioUrl,
                        audioTrackUrl = null,
                        isAudioOnly = true,
                        extension = ext,
                        note = if (audio === bestAudio) "Best available audio" else "Smaller file size"
                    )
                )
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

                            // Adaptive streams: H.264 video merged with the best AAC audio track
                            val adaptiveFormats = json.getAsJsonArray("adaptiveFormats")
                            if (adaptiveFormats != null) {
                                var bestAudioUrl: String? = null
                                var bestAudioBitrate = 0L
                                for (item in adaptiveFormats) {
                                    val stream = item.asJsonObject
                                    val type = stream.get("type")?.asString ?: ""
                                    val streamUrl = stream.get("url")?.asString ?: continue
                                    val bitrate = stream.get("bitrate")?.asString?.toLongOrNull() ?: 0L
                                    if (isAacAudio(type) && (bestAudioUrl == null || bitrate > bestAudioBitrate)) {
                                        bestAudioUrl = streamUrl
                                        bestAudioBitrate = bitrate
                                    }
                                }

                                if (bestAudioUrl != null) {
                                    for (item in adaptiveFormats) {
                                        val stream = item.asJsonObject
                                        val type = stream.get("type")?.asString ?: ""
                                        val streamUrl = stream.get("url")?.asString ?: continue
                                        val qualityLabel = stream.get("qualityLabel")?.asString ?: stream.get("resolution")?.asString ?: ""

                                        if (isMuxableVideo(type) && qualityLabel.isNotEmpty() && addedResolutions.add(qualityLabel)) {
                                            formatList.add(
                                                DownloadFormat(
                                                    id = "inv_adapt_$qualityLabel",
                                                    label = "$qualityLabel Video (MP4)",
                                                    resolution = qualityLabel,
                                                    downloadUrl = streamUrl,
                                                    audioTrackUrl = bestAudioUrl,
                                                    isAudioOnly = false,
                                                    extension = "mp4",
                                                    note = "$qualityLabel Video (With Audio)"
                                                )
                                            )
                                        }
                                    }

                                    formatList.add(
                                        DownloadFormat(
                                            id = "inv_audio_m4a",
                                            label = audioLabel("m4a", bestAudioBitrate),
                                            resolution = "Audio",
                                            downloadUrl = bestAudioUrl,
                                            audioTrackUrl = null,
                                            isAudioOnly = true,
                                            extension = "m4a",
                                            note = "Best available audio"
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

                // 2. Adaptive streams: H.264 video merged with the best AAC audio track
                val adaptiveArr = streamingData.getAsJsonArray("adaptiveFormats")
                if (adaptiveArr != null) {
                    // (url, bitrate) of every AAC track in the original language, best first
                    val aacTracks = mutableListOf<Pair<String, Long>>()
                    for (item in adaptiveArr) {
                        val obj = item.asJsonObject
                        val mimeType = obj.get("mimeType")?.asString ?: ""
                        val streamUrl = obj.get("url")?.asString ?: continue
                        if (!isAacAudio(mimeType)) continue
                        // Dubbed tracks carry audioTrack.audioIsDefault = false: skip them.
                        val audioTrack = obj.getAsJsonObject("audioTrack")
                        if (audioTrack != null && audioTrack.get("audioIsDefault")?.asBoolean == false) continue
                        aacTracks.add(streamUrl to (obj.get("bitrate")?.asLong ?: 0L))
                    }
                    aacTracks.sortByDescending { it.second }
                    val bestAudio = aacTracks.firstOrNull()

                    if (bestAudio != null) {
                        for (item in adaptiveArr) {
                            val obj = item.asJsonObject
                            val mimeType = obj.get("mimeType")?.asString ?: ""
                            val streamUrl = obj.get("url")?.asString ?: continue
                            val qualityLabel = obj.get("qualityLabel")?.asString ?: ""

                            // VP9 / AV1 streams are skipped: the on-device muxer cannot merge them.
                            if (isMuxableVideo(mimeType) && qualityLabel.isNotEmpty() && addedResolutions.add(qualityLabel)) {
                                formatList.add(
                                    DownloadFormat(
                                        id = "it_vid_$qualityLabel",
                                        label = "$qualityLabel Video (MP4)",
                                        resolution = qualityLabel,
                                        downloadUrl = streamUrl,
                                        audioTrackUrl = bestAudio.first,
                                        isAudioOnly = false,
                                        extension = "mp4",
                                        estimatedSizeBytes = obj.get("contentLength")?.asString?.toLongOrNull() ?: 0L,
                                        note = "$qualityLabel Video (With Audio)"
                                    )
                                )
                            }
                        }

                        val offered = listOf(bestAudio, aacTracks.last()).distinct()
                        offered.forEachIndexed { index, (audioUrl, bitrate) ->
                            formatList.add(
                                DownloadFormat(
                                    id = "it_audio_m4a_$index",
                                    label = audioLabel("m4a", bitrate),
                                    resolution = "Audio",
                                    downloadUrl = audioUrl,
                                    audioTrackUrl = null,
                                    isAudioOnly = true,
                                    extension = "m4a",
                                    note = if (index == 0) "Best available audio" else "Smaller file size"
                                )
                            )
                        }
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
