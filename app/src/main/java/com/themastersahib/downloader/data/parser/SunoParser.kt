package com.themastersahib.downloader.data.parser

import com.themastersahib.downloader.data.model.BatchSongItem
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.SocialPlatform
import com.themastersahib.downloader.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

class SunoParser(private val client: OkHttpClient) {

    private val uuidPattern = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")

    suspend fun parse(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()

        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null

                // Check if this is a single song link: e.g. suno.com/song/{uuid}
                if (cleanUrl.contains("/song/")) {
                    val matcher = uuidPattern.matcher(cleanUrl)
                    if (matcher.find()) {
                        val singleUuid = matcher.group(1)
                        return@withContext parseSingleSong(html, singleUuid, cleanUrl)
                    }
                }

                // Parse Multi-Song / Playlist / Create Feed Page (e.g. suno.com/create?wid=default)
                val batchSongs = extractAllSongsFromPage(html)
                if (batchSongs.isNotEmpty()) {
                    if (batchSongs.size == 1) {
                        val song = batchSongs[0]
                        return@withContext createVideoInfoFromSong(song, cleanUrl)
                    }

                    return@withContext VideoInfo(
                        originalUrl = cleanUrl,
                        title = "Suno AI Music (${batchSongs.size} Songs)",
                        thumbnail = batchSongs.firstOrNull()?.coverUrl,
                        author = "Suno AI",
                        durationSeconds = 0L,
                        platform = SocialPlatform.SUNO,
                        formats = emptyList(),
                        batchSongs = batchSongs
                    )
                }

                // If no batch items were found by multi-regex, check if any UUID exists in page
                val anyUuidMatcher = uuidPattern.matcher(html)
                if (anyUuidMatcher.find()) {
                    val fallbackUuid = anyUuidMatcher.group(1)
                    return@withContext parseSingleSong(html, fallbackUuid, cleanUrl)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        null
    }

    private fun getAudioUrl(html: String, uuid: String): String {
        // Check if there is an active stream URL in the page
        val cfPattern = Pattern.compile("https://([a-zA-Z0-9.-]+\\.cloudfront\\.net)/1/clip/$uuid\\.m4a")
        val matcher = cfPattern.matcher(html)
        if (matcher.find()) {
            val host = matcher.group(1)
            return "https://$host/1/clip/$uuid.m4a"
        }
        // Direct official CloudFront audio stream endpoint for Suno AI
        return "https://d2lwuy8qc234o3.cloudfront.net/1/clip/$uuid.m4a"
    }

    private fun extractAllSongsFromPage(html: String): List<BatchSongItem> {
        val songsMap = LinkedHashMap<String, BatchSongItem>()

        // Strategy 1: Find /song/{uuid} links and adjacent titles
        val songLinkPattern = Pattern.compile("/song/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
        val linkMatcher = songLinkPattern.matcher(html)

        val foundUuids = LinkedHashSet<String>()
        while (linkMatcher.find()) {
            linkMatcher.group(1)?.let { foundUuids.add(it) }
        }

        // Strategy 2: Scan JSON blocks containing contentId / id / clipId and title
        val jsonPattern = Pattern.compile("(?:id|contentId|clipId)\\\\?\":\\\\?\"([0-9a-fA-F-]{36})\\\\?\".*?title\\\\?\":\\\\?\"([^\\\\\"]+)\\\\?\"")
        val jsonMatcher = jsonPattern.matcher(html)
        while (jsonMatcher.find()) {
            val id = jsonMatcher.group(1)
            val title = jsonMatcher.group(2)
            if (!id.isNullOrEmpty() && !title.isNullOrEmpty() && !songsMap.containsKey(id)) {
                songsMap[id] = BatchSongItem(
                    id = id,
                    title = cleanTitle(title),
                    audioUrl = getAudioUrl(html, id),
                    videoUrl = "https://cdn1.suno.ai/$id.mp4",
                    coverUrl = "https://cdn2.suno.ai/image_large_$id.png",
                    isSelected = true
                )
            }
        }

        // Add any remaining found UUIDs
        var count = songsMap.size + 1
        for (uuid in foundUuids) {
            if (!songsMap.containsKey(uuid)) {
                val title = findTitleNearUuid(html, uuid) ?: "Suno AI Track #$count"
                songsMap[uuid] = BatchSongItem(
                    id = uuid,
                    title = cleanTitle(title),
                    audioUrl = getAudioUrl(html, uuid),
                    videoUrl = "https://cdn1.suno.ai/$uuid.mp4",
                    coverUrl = "https://cdn2.suno.ai/image_large_$uuid.png",
                    isSelected = true
                )
                count++
            }
        }

        return songsMap.values.toList()
    }

    private fun findTitleNearUuid(html: String, uuid: String): String? {
        try {
            val idx = html.indexOf(uuid)
            if (idx != -1) {
                val start = (idx - 300).coerceAtLeast(0)
                val end = (idx + 300).coerceAtMost(html.length)
                val snippet = html.substring(start, end)

                val tPattern = Pattern.compile("title\\\\?\":\\\\?\"([^\\\\\"]+)\\\\?\"")
                val tMatcher = tPattern.matcher(snippet)
                if (tMatcher.find()) {
                    return tMatcher.group(1)
                }
            }
        } catch (e: Exception) {
            // Ignored
        }
        return null
    }

    private fun parseSingleSong(html: String, uuid: String, originalUrl: String): VideoInfo {
        // Extract Title from og:title or page <title>
        val ogTitlePattern = Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val ogMatcher = ogTitlePattern.matcher(html)
        var title = if (ogMatcher.find()) ogMatcher.group(1) else null

        if (title.isNullOrEmpty()) {
            val pageTitlePattern = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
            val pMatcher = pageTitlePattern.matcher(html)
            title = if (pMatcher.find()) pMatcher.group(1)?.replace(" | Suno", "")?.trim() else "Suno AI Song"
        }

        val coverUrl = "https://cdn2.suno.ai/image_large_$uuid.png"
        val audioUrl = getAudioUrl(html, uuid)
        val videoUrl = "https://cdn1.suno.ai/$uuid.mp4"

        val formats = listOf(
            DownloadFormat(
                id = "suno_audio_m4a",
                label = "Studio Master Audio (M4A / MP3)",
                resolution = "320kbps",
                downloadUrl = audioUrl,
                isAudioOnly = true,
                extension = "m4a",
                note = "Original Master Sound Track"
            ),
            DownloadFormat(
                id = "suno_video_mp4",
                label = "Music Video (MP4 HD)",
                resolution = "1080p",
                downloadUrl = videoUrl,
                isAudioOnly = false,
                extension = "mp4",
                note = "Full Music Video with Visuals"
            )
        )

        return VideoInfo(
            originalUrl = originalUrl,
            title = cleanTitle(title ?: "Suno AI Song"),
            thumbnail = coverUrl,
            author = "Suno AI",
            durationSeconds = 0L,
            platform = SocialPlatform.SUNO,
            formats = formats
        )
    }

    private fun createVideoInfoFromSong(song: BatchSongItem, originalUrl: String): VideoInfo {
        val formats = listOf(
            DownloadFormat(
                id = "suno_audio_m4a",
                label = "Studio Master Audio (M4A / MP3)",
                resolution = "320kbps",
                downloadUrl = song.audioUrl,
                isAudioOnly = true,
                extension = "m4a",
                note = "Original Master Sound Track"
            ),
            DownloadFormat(
                id = "suno_video_mp4",
                label = "Music Video (MP4 HD)",
                resolution = "1080p",
                downloadUrl = song.videoUrl,
                isAudioOnly = false,
                extension = "mp4",
                note = "Full Music Video with Visuals"
            )
        )

        return VideoInfo(
            originalUrl = originalUrl,
            title = song.title,
            thumbnail = song.coverUrl,
            author = "Suno AI",
            durationSeconds = 0L,
            platform = SocialPlatform.SUNO,
            formats = formats
        )
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\/", "/")
            .trim()
            .take(80)
            .ifEmpty { "Suno Song" }
    }
}
