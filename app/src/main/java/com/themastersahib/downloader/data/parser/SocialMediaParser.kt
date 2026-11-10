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
        var cleanUrl = url.trim()

        // 1. Resolve redirect if shortlink (e.g. fb.watch, share/r, share/v)
        try {
            val headReq = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            client.newCall(headReq).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (finalUrl.isNotEmpty() && finalUrl != cleanUrl) {
                    cleanUrl = finalUrl
                }
            }
        } catch (_: Exception) {}

        // Strategy 1: Desktop Facebook with complete browser headers
        try {
            val desktopUrl = cleanUrl
                .replace("m.facebook.com", "www.facebook.com")
                .replace("mbasic.facebook.com", "www.facebook.com")

            val request = Request.Builder()
                .url(desktopUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val videoInfo = extractFacebookDesktop(html, url)
                    if (videoInfo != null && videoInfo.formats.isNotEmpty()) {
                        return@withContext videoInfo
                    }
                }
            }
        } catch (_: Exception) {}

        // Strategy 2: Mobile basic (mbasic) scraper
        try {
            val mbasicUrl = cleanUrl
                .replace("www.facebook.com", "mbasic.facebook.com")
                .replace("m.facebook.com", "mbasic.facebook.com")
                .let { if (!it.contains("mbasic.facebook.com")) it.replace("facebook.com", "mbasic.facebook.com") else it }

            val request = Request.Builder()
                .url(mbasicUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val videoInfo = extractFacebookMbasic(html, url)
                    if (videoInfo != null && videoInfo.formats.isNotEmpty()) {
                        return@withContext videoInfo
                    }
                }
            }
        } catch (_: Exception) {}

        // Strategy 3: Universal HTML5 & OpenGraph fallback
        return@withContext parseGeneric(url)
    }

    private fun extractFacebookDesktop(html: String, originalUrl: String): VideoInfo? {
        var hdUrl: String? = null
        var sdUrl: String? = null

        val hdPatterns = listOf(
            Pattern.compile("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("playable_url_quality_hd[\":\\s]+([^\",;\\s]+)"),
            Pattern.compile("\"hd_src\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"hd_src_no_ratelimit\"\\s*:\\s*\"([^\"]+)\"")
        )
        for (p in hdPatterns) {
            val m = p.matcher(html)
            if (m.find()) {
                val candidate = unescapeFbUrl(m.group(1))
                if (candidate.startsWith("http")) {
                    hdUrl = candidate
                    break
                }
            }
        }

        val sdPatterns = listOf(
            Pattern.compile("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("playable_url[\":\\s]+([^\",;\\s]+)"),
            Pattern.compile("\"sd_src\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"sd_src_no_ratelimit\"\\s*:\\s*\"([^\"]+)\"")
        )
        for (p in sdPatterns) {
            val m = p.matcher(html)
            if (m.find()) {
                val candidate = unescapeFbUrl(m.group(1))
                if (candidate.startsWith("http")) {
                    sdUrl = candidate
                    break
                }
            }
        }

        // Fallback meta tag video
        if (hdUrl == null && sdUrl == null) {
            val metaPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:video(?::secure_url|:url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
            val metaMatcher = metaPattern.matcher(html)
            if (metaMatcher.find()) {
                val candidate = unescapeFbUrl(metaMatcher.group(1))
                if (candidate.startsWith("http")) {
                    hdUrl = candidate
                }
            }
        }

        if (hdUrl == null && sdUrl == null) return null

        val formats = mutableListOf<DownloadFormat>()
        if (hdUrl != null) {
            formats.add(
                DownloadFormat(
                    id = "fb_hd",
                    label = "1080p / 720p HD Video (MP4)",
                    resolution = "HD",
                    downloadUrl = hdUrl,
                    isAudioOnly = false,
                    extension = "mp4",
                    note = "High Definition Video (With Audio)"
                )
            )
        }

        if (sdUrl != null) {
            formats.add(
                DownloadFormat(
                    id = "fb_sd",
                    label = "480p / 360p SD Video (MP4)",
                    resolution = "SD",
                    downloadUrl = sdUrl,
                    isAudioOnly = false,
                    extension = "mp4",
                    note = "Standard Definition Video (With Audio)"
                )
            )
        }

        val audioSource = hdUrl ?: sdUrl
        if (audioSource != null) {
            formats.add(
                DownloadFormat(
                    id = "fb_audio",
                    label = "Audio Only (MP3)",
                    resolution = "Audio",
                    downloadUrl = audioSource,
                    isAudioOnly = true,
                    extension = "mp3",
                    note = "Audio Track Extracted"
                )
            )
        }

        val titlePattern = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
        val titleMatcher = titlePattern.matcher(html)
        val title = if (titleMatcher.find()) {
            titleMatcher.group(1).replace(" | Facebook", "").replace("Facebook", "").trim().ifEmpty { "Facebook Video" }
        } else {
            "Facebook Video"
        }

        var thumbnail: String? = null
        val thumbPattern = Pattern.compile("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val thumbMatcher = thumbPattern.matcher(html)
        if (thumbMatcher.find()) {
            thumbnail = unescapeFbUrl(thumbMatcher.group(1))
        }

        return VideoInfo(
            originalUrl = originalUrl,
            title = title,
            thumbnail = thumbnail,
            author = "Facebook",
            durationSeconds = 0L,
            platform = SocialPlatform.FACEBOOK,
            formats = formats
        )
    }

    private fun extractFacebookMbasic(html: String, originalUrl: String): VideoInfo? {
        val redirectPattern = Pattern.compile("href=[\"']/video_redirect/\\?src=([^\"'&]+)", Pattern.CASE_INSENSITIVE)
        val redirectMatcher = redirectPattern.matcher(html)
        var streamUrl: String? = null
        if (redirectMatcher.find()) {
            val encoded = redirectMatcher.group(1)
            try {
                streamUrl = URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {}
        }

        if (streamUrl == null) {
            val videoTagPattern = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
            val videoTagMatcher = videoTagPattern.matcher(html)
            if (videoTagMatcher.find()) {
                streamUrl = unescapeFbUrl(videoTagMatcher.group(1))
            }
        }

        if (streamUrl.isNullOrEmpty() || !streamUrl.startsWith("http")) return null

        val formats = listOf(
            DownloadFormat(
                id = "fb_mbasic_video",
                label = "Video (Original MP4)",
                resolution = "SD",
                downloadUrl = streamUrl,
                isAudioOnly = false,
                extension = "mp4",
                note = "Facebook Video Stream"
            ),
            DownloadFormat(
                id = "fb_mbasic_audio",
                label = "Audio Only (MP3)",
                resolution = "Audio",
                downloadUrl = streamUrl,
                isAudioOnly = true,
                extension = "mp3",
                note = "Audio Track"
            )
        )

        return VideoInfo(
            originalUrl = originalUrl,
            title = "Facebook Video",
            thumbnail = null,
            author = "Facebook",
            durationSeconds = 0L,
            platform = SocialPlatform.FACEBOOK,
            formats = formats
        )
    }

    private fun unescapeFbUrl(raw: String): String {
        var s = raw.replace("\\/", "/")
            .replace("\\u0025", "%")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\"", "")
            .trim()
        if (s.contains("%2F") || s.contains("%3A")) {
            try {
                s = URLDecoder.decode(s, "UTF-8")
            } catch (_: Exception) {}
        }
        return s
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
