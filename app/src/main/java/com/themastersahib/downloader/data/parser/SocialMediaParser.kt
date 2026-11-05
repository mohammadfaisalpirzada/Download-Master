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
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Pattern

class SocialMediaParser(private val client: OkHttpClient) {

    private val cobaltInstances = listOf(
        "https://cobalt-api.kwiatekm.tokyo",
        "https://api.cobalt.tools"
    )

    suspend fun parseFacebook(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            val mobileUrl = url.replace("www.facebook.com", "m.facebook.com")
            val request = Request.Builder()
                .url(mobileUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val formats = mutableListOf<DownloadFormat>()

                    val hdPattern = Pattern.compile("playable_url_quality_hd[\":\\s]+([^\",;\\s]+)")
                    val sdPattern = Pattern.compile("playable_url[\":\\s]+([^\",;\\s]+)")

                    val hdMatcher = hdPattern.matcher(html)
                    val sdMatcher = sdPattern.matcher(html)

                    if (hdMatcher.find()) {
                        val rawHd = hdMatcher.group(1).replace("\\/", "/")
                        val cleanHd = cleanUrl(rawHd)
                        formats.add(
                            DownloadFormat(
                                id = "fb_hd",
                                label = "720p / 1080p HD",
                                resolution = "HD",
                                downloadUrl = cleanHd,
                                isAudioOnly = false,
                                note = "High Definition Video"
                            )
                        )
                    }

                    if (sdMatcher.find()) {
                        val rawSd = sdMatcher.group(1).replace("\\/", "/")
                        val cleanSd = cleanUrl(rawSd)
                        formats.add(
                            DownloadFormat(
                                id = "fb_sd",
                                label = "480p / 360p SD",
                                resolution = "SD",
                                downloadUrl = cleanSd,
                                isAudioOnly = false,
                                note = "Standard Definition Video"
                            )
                        )
                    }

                    if (formats.isNotEmpty()) {
                        val titlePattern = Pattern.compile("<title>([^<]+)</title>")
                        val titleMatcher = titlePattern.matcher(html)
                        val title = if (titleMatcher.find()) {
                            titleMatcher.group(1).replace(" | Facebook", "").trim()
                        } else {
                            "Facebook Video"
                        }

                        return@withContext VideoInfo(
                            originalUrl = url,
                            title = title,
                            thumbnail = null,
                            author = "Facebook",
                            durationSeconds = 0L,
                            platform = SocialPlatform.FACEBOOK,
                            formats = formats
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        return@withContext parseViaCobalt(url, SocialPlatform.FACEBOOK)
    }

    suspend fun parseInstagram(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        return@withContext parseViaCobalt(url, SocialPlatform.INSTAGRAM)
    }

    suspend fun parseTwitter(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        return@withContext parseViaCobalt(url, SocialPlatform.TWITTER)
    }

    /**
     * Universal Web Extractor: Extracts videos from ANY unknown website or direct media URL.
     */
    suspend fun parseGeneric(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()

        // 1. Check if direct media file link (.mp4, .webm, .mkv, .mov, .m4v, .m3u8, .mp3)
        val lower = cleanUrl.lowercase()
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") ||
            lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.contains(".mp4?") ||
            lower.contains(".webm?")
        ) {
            val fileName = cleanUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "Web Video" }
            val formats = listOf(
                DownloadFormat(
                    id = "direct_video_hd",
                    label = "Original Video (Direct Link)",
                    resolution = "HD",
                    downloadUrl = cleanUrl,
                    isAudioOnly = false,
                    extension = "mp4",
                    note = "Direct Web Video Stream"
                ),
                DownloadFormat(
                    id = "direct_audio",
                    label = "Audio Only (MP3)",
                    resolution = "Audio",
                    downloadUrl = cleanUrl,
                    isAudioOnly = true,
                    extension = "mp3",
                    note = "Audio Track"
                )
            )
            return@withContext VideoInfo(
                originalUrl = url,
                title = fileName,
                thumbnail = null,
                author = "Web Video",
                durationSeconds = 0L,
                platform = SocialPlatform.OTHER,
                formats = formats
            )
        }

        // 2. Universal HTML5 & OpenGraph Scraper for any web page
        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""

                    // Video candidates from OpenGraph and HTML5 tags
                    val videoUrlCandidates = mutableListOf<String>()

                    val ogVideoPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"'](?:og:video|og:video:url|og:video:secure_url|twitter:player:stream)[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                    val ogMatcher = ogVideoPattern.matcher(html)
                    while (ogMatcher.find()) {
                        ogMatcher.group(1)?.let { videoUrlCandidates.add(it) }
                    }

                    // HTML5 video tag src
                    val videoTagPattern = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                    val vMatcher = videoTagPattern.matcher(html)
                    while (vMatcher.find()) {
                        vMatcher.group(1)?.let { videoUrlCandidates.add(it) }
                    }

                    // Source tag inside video
                    val sourceTagPattern = Pattern.compile("<source[^>]+src=[\"']([^\"']+)[\"'][^>]+type=[\"']video/[^\"']+[\"']", Pattern.CASE_INSENSITIVE)
                    val sMatcher = sourceTagPattern.matcher(html)
                    while (sMatcher.find()) {
                        sMatcher.group(1)?.let { videoUrlCandidates.add(it) }
                    }

                    // Extract Title and Thumbnail
                    val titlePattern = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
                    val tMatcher = titlePattern.matcher(html)
                    val pageTitle = if (tMatcher.find()) tMatcher.group(1)?.trim() ?: "Web Video" else "Web Video"

                    val ogImagePattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                    val imgMatcher = ogImagePattern.matcher(html)
                    val thumbUrl = if (imgMatcher.find()) imgMatcher.group(1) else null

                    if (videoUrlCandidates.isNotEmpty()) {
                        val formats = mutableListOf<DownloadFormat>()
                        var index = 1
                        for (cand in videoUrlCandidates.distinct()) {
                            val absoluteVideoUrl = resolveAbsoluteUrl(cleanUrl, cand)
                            if (absoluteVideoUrl.startsWith("http")) {
                                formats.add(
                                    DownloadFormat(
                                        id = "universal_v_$index",
                                        label = if (index == 1) "HD Video (Web Source)" else "Video Stream #$index",
                                        resolution = "HD",
                                        downloadUrl = absoluteVideoUrl,
                                        isAudioOnly = false,
                                        extension = "mp4",
                                        note = "Extracted from Web Player"
                                    )
                                )
                                index++
                            }
                        }

                        if (formats.isNotEmpty()) {
                            // Add Audio format
                            formats.add(
                                DownloadFormat(
                                    id = "universal_audio",
                                    label = "Audio Only (MP3)",
                                    resolution = "Audio",
                                    downloadUrl = formats[0].downloadUrl,
                                    isAudioOnly = true,
                                    extension = "mp3",
                                    note = "Extracted Audio Track"
                                )
                            )

                            return@withContext VideoInfo(
                                originalUrl = url,
                                title = pageTitle,
                                thumbnail = thumbUrl,
                                author = URL(cleanUrl).host.replace("www.", ""),
                                durationSeconds = 0L,
                                platform = SocialPlatform.OTHER,
                                formats = formats
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Try fallback
        }

        // 3. Fallback to Cobalt Universal
        return@withContext parseViaCobalt(url, SocialPlatform.OTHER)
    }

    private fun resolveAbsoluteUrl(baseUrl: String, candidate: String): String {
        return try {
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                candidate
            } else if (candidate.startsWith("//")) {
                "https:$candidate"
            } else {
                val base = URL(baseUrl)
                URL(base, candidate).toString()
            }
        } catch (e: Exception) {
            candidate
        }
    }

    private suspend fun parseViaCobalt(url: String, platform: SocialPlatform): VideoInfo? = withContext(Dispatchers.IO) {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        for (host in cobaltInstances) {
            try {
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
                        val filename = json.get("filename")?.asString ?: "${platform.displayName} Video"

                        val formats = mutableListOf(
                            DownloadFormat(
                                id = "${platform.name.lowercase()}_video_hd",
                                label = "HD Video (Original Quality)",
                                resolution = "HD",
                                downloadUrl = downloadUrl,
                                isAudioOnly = false,
                                extension = "mp4",
                                note = "High Definition Video"
                            ),
                            DownloadFormat(
                                id = "${platform.name.lowercase()}_audio",
                                label = "Audio Only (MP3)",
                                resolution = "Audio",
                                downloadUrl = downloadUrl,
                                isAudioOnly = true,
                                extension = "mp3",
                                note = "Extracted Audio Track"
                            )
                        )

                        return@withContext VideoInfo(
                            originalUrl = url,
                            title = filename.substringBeforeLast(".").ifEmpty { "${platform.displayName} Video" },
                            thumbnail = null,
                            author = platform.displayName,
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

    private fun cleanUrl(raw: String): String {
        return try {
            val decoded = URLDecoder.decode(raw, "UTF-8")
            if (decoded.startsWith("\"") && decoded.endsWith("\"")) {
                decoded.substring(1, decoded.length - 1)
            } else {
                decoded
            }
        } catch (e: Exception) {
            raw.replace("\"", "")
        }
    }
}
