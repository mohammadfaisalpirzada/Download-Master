package com.themastersahib.downloader.data.parser

import com.themastersahib.downloader.data.model.BatchSongItem
import com.themastersahib.downloader.data.model.SocialPlatform
import com.themastersahib.downloader.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LinkParserManager {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val tikTokParser = TikTokParser(httpClient)
    private val youTubeParser = YouTubeParser(httpClient)
    private val socialMediaParser = SocialMediaParser(httpClient)
    private val sunoParser = SunoParser(httpClient)

    fun detectPlatform(url: String): SocialPlatform {
        return SocialPlatform.fromUrl(url)
    }

    suspend fun resolve(rawUrl: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val allUrls = extractAllUrls(rawUrl)
        if (allUrls.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid link format. Please provide a full web link."))
        }

        // Multi-URL batch paste detection (e.g., from Suno console extractor script)
        if (allUrls.size > 1) {
            val batchSongs = mutableListOf<BatchSongItem>()
            var idx = 1
            val uuidPattern = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")

            for (link in allUrls) {
                val clean = link.trim()
                val matcher = uuidPattern.matcher(clean)
                val id = if (matcher.find()) matcher.group(1) else "song_$idx"
                val audioUrl = if (clean.endsWith(".mp3") || clean.endsWith(".m4a")) clean else "https://cdn1.suno.ai/$id.mp3"
                val videoUrl = "https://cdn1.suno.ai/$id.mp4"
                val coverUrl = "https://cdn2.suno.ai/image_large_$id.png"

                batchSongs.add(
                    BatchSongItem(
                        id = id,
                        title = "Suno Track #$idx",
                        audioUrl = audioUrl,
                        videoUrl = videoUrl,
                        coverUrl = coverUrl,
                        isSelected = true
                    )
                )
                idx++
            }

            return@withContext Result.success(
                VideoInfo(
                    originalUrl = allUrls.first(),
                    title = "Suno Batch (${batchSongs.size} Songs)",
                    thumbnail = batchSongs.firstOrNull()?.coverUrl,
                    author = "Suno AI",
                    durationSeconds = 0L,
                    platform = SocialPlatform.SUNO,
                    formats = emptyList(),
                    batchSongs = batchSongs
                )
            )
        }

        val cleanUrl = allUrls[0]
        val platform = detectPlatform(cleanUrl)

        val info = when (platform) {
            SocialPlatform.SUNO -> sunoParser.parse(cleanUrl)
            SocialPlatform.TIKTOK -> tikTokParser.parse(cleanUrl)
            SocialPlatform.YOUTUBE -> youTubeParser.parse(cleanUrl)
            SocialPlatform.FACEBOOK -> socialMediaParser.parseFacebook(cleanUrl)
            SocialPlatform.INSTAGRAM -> socialMediaParser.parseInstagram(cleanUrl)
            SocialPlatform.TWITTER -> socialMediaParser.parseTwitter(cleanUrl)
            SocialPlatform.OTHER -> {
                // If it looks like suno, try Suno first
                if (cleanUrl.contains("suno")) {
                    sunoParser.parse(cleanUrl) ?: socialMediaParser.parseGeneric(cleanUrl)
                } else {
                    socialMediaParser.parseGeneric(cleanUrl)
                }
            }
        }

        return@withContext if (info != null && (info.formats.isNotEmpty() || info.batchSongs.isNotEmpty())) {
            Result.success(info)
        } else if (platform == SocialPlatform.SUNO || cleanUrl.contains("suno")) {
            Result.failure(Exception("No songs found. On Suno, please tap 'Share' -> 'Copy Link' on your song or playlist, or use the Suno Console Script to batch copy all links."))
        } else {
            Result.failure(Exception("Unable to extract video or songs. Please ensure the link is active and accessible."))
        }
    }

    private fun extractAllUrls(text: String): List<String> {
        val list = mutableListOf<String>()
        val matcher = Pattern.compile("https?://[^\\s]+").matcher(text.trim())
        while (matcher.find()) {
            list.add(matcher.group())
        }
        return list
    }
}
