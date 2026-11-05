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
import java.net.URLDecoder
import java.util.regex.Pattern

class SocialMediaParser(private val client: OkHttpClient) {

    private val cobaltInstances = listOf(
        "https://cobalt-api.kwiatekm.tokyo",
        "https://api.cobalt.tools"
    )

    suspend fun parseFacebook(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        // Strategy 1: Direct HTML extraction for Facebook public videos
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

                    // Look for HD and SD URLs in page script
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
                        // Extract Title
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
            // Fallback to cobalt
        }

        return@withContext parseViaCobalt(url, SocialPlatform.FACEBOOK)
    }

    suspend fun parseInstagram(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        return@withContext parseViaCobalt(url, SocialPlatform.INSTAGRAM)
    }

    suspend fun parseTwitter(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        return@withContext parseViaCobalt(url, SocialPlatform.TWITTER)
    }

    suspend fun parseGeneric(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        return@withContext parseViaCobalt(url, SocialPlatform.OTHER)
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
                                label = "HD Video (Best Quality)",
                                resolution = "HD",
                                downloadUrl = downloadUrl,
                                isAudioOnly = false,
                                extension = "mp4",
                                note = "Original Quality Video"
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
                // Try next host
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
