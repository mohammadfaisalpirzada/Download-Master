package com.themastersahib.downloader.data.parser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.SocialPlatform
import com.themastersahib.downloader.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class TikTokParser(private val client: OkHttpClient) {

    suspend fun parse(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            // TikWM provides public zero-login extraction for TikTok videos without watermark
            val apiUrl = "https://www.tikwm.com/api/?url=${java.net.URLEncoder.encode(url, "UTF-8")}&hd=1"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val root = JsonParser.parseString(body).asJsonObject
                if (root.get("code")?.asInt != 0) return@withContext null

                val data = root.getAsJsonObject("data") ?: return@withContext null
                val title = data.get("title")?.asString?.ifEmpty { "TikTok Video" } ?: "TikTok Video"
                val cover = data.get("cover")?.asString
                val author = data.getAsJsonObject("author")?.get("nickname")?.asString ?: "TikTok Creator"
                val duration = data.get("duration")?.asLong ?: 0L

                val formats = mutableListOf<DownloadFormat>()

                // HD No Watermark (if available)
                val hdPlay = data.get("hdplay")?.asString
                if (!hdPlay.isNullOrEmpty()) {
                    val fullHdUrl = if (hdPlay.startsWith("http")) hdPlay else "https://www.tikwm.com$hdPlay"
                    formats.add(
                        DownloadFormat(
                            id = "tiktok_hd",
                            label = "1080p HD (No Watermark)",
                            resolution = "1080p",
                            downloadUrl = fullHdUrl,
                            isAudioOnly = false,
                            note = "Full High Definition Without Watermark"
                        )
                    )
                }

                // Standard No Watermark
                val play = data.get("play")?.asString
                if (!play.isNullOrEmpty()) {
                    val fullPlayUrl = if (play.startsWith("http")) play else "https://www.tikwm.com$play"
                    formats.add(
                        DownloadFormat(
                            id = "tiktok_sd",
                            label = "720p (No Watermark)",
                            resolution = "720p",
                            downloadUrl = fullPlayUrl,
                            isAudioOnly = false,
                            note = "Standard Quality Without Watermark"
                        )
                    )
                }

                // Audio Only
                val music = data.get("music")?.asString
                if (!music.isNullOrEmpty()) {
                    val fullMusicUrl = if (music.startsWith("http")) music else "https://www.tikwm.com$music"
                    formats.add(
                        DownloadFormat(
                            id = "tiktok_audio",
                            label = "Audio Only (MP3)",
                            resolution = "Audio",
                            downloadUrl = fullMusicUrl,
                            isAudioOnly = true,
                            note = "Original Soundtrack / Music"
                        )
                    )
                }

                if (formats.isEmpty()) return@withContext null

                return@withContext VideoInfo(
                    originalUrl = url,
                    title = title,
                    thumbnail = cover,
                    author = author,
                    durationSeconds = duration,
                    platform = SocialPlatform.TIKTOK,
                    formats = formats
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
