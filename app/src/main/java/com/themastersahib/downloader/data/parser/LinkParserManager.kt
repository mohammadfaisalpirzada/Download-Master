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

        // Multi-URL batch paste detection (e.g., from Suno console extractor script).
        // Only Suno links form a batch; any other pasted text falls back to its first link.
        val sunoUrls = allUrls.filter { SocialPlatform.fromUrl(it) == SocialPlatform.SUNO }
        if (sunoUrls.size > 1) {
            // Keyed by id: the same song pasted twice must appear once (duplicate list keys crash the UI).
            val batchSongs = LinkedHashMap<String, BatchSongItem>()
            val uuidPattern = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")

            for (clean in sunoUrls) {
                val matcher = uuidPattern.matcher(clean)
                // A link without a song id (profile, playlist...) cannot be turned into a file URL.
                val id = (if (matcher.find()) matcher.group(1) else null) ?: continue
                if (batchSongs.containsKey(id)) continue
                val path = clean.substringBefore('?').lowercase()
                // cdn1 .mp3 links answer 403 nowadays, so only a direct .m4a link is used as-is.
                val audioUrl = if (path.endsWith(".m4a")) {
                    clean
                } else {
                    "https://d2lwuy8qc234o3.cloudfront.net/1/clip/$id.m4a"
                }

                batchSongs[id] = BatchSongItem(
                    id = id,
                    title = "Suno Track #${batchSongs.size + 1}",
                    audioUrl = audioUrl,
                    videoUrl = "https://cdn1.suno.ai/$id.mp4",
                    coverUrl = "https://cdn2.suno.ai/image_large_$id.png",
                    isSelected = true
                )
            }

            if (batchSongs.size > 1) {
                val songs = batchSongs.values.toList()
                return@withContext Result.success(
                    VideoInfo(
                        originalUrl = sunoUrls.first(),
                        title = "Suno Batch (${songs.size} Songs)",
                        thumbnail = songs.first().coverUrl,
                        author = "Suno AI",
                        durationSeconds = 0L,
                        platform = SocialPlatform.SUNO,
                        formats = emptyList(),
                        batchSongs = songs
                    )
                )
            }
        }

        val cleanUrl = allUrls[0]
        val platform = detectPlatform(cleanUrl)

        val info = try {
            when (platform) {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A parser bug or malformed page must never crash the app.
            e.printStackTrace()
            null
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
        val matcher = Pattern.compile("https?://[^\\s\"'<>]+").matcher(text.trim())
        while (matcher.find()) {
            // Links copied from sentences often drag punctuation along.
            val url = matcher.group().trimEnd('.', ',', ';', ')', ']', '!')
            if (url.length > 10 && SocialPlatform.hostOf(url).contains('.')) list.add(url)
        }
        return list.distinct()
    }
}
