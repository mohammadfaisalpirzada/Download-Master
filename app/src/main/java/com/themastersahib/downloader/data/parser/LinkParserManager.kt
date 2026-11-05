package com.themastersahib.downloader.data.parser

import com.themastersahib.downloader.data.model.SocialPlatform
import com.themastersahib.downloader.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LinkParserManager {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val tikTokParser = TikTokParser(httpClient)
    private val youTubeParser = YouTubeParser(httpClient)
    private val socialMediaParser = SocialMediaParser(httpClient)

    fun detectPlatform(url: String): SocialPlatform {
        return SocialPlatform.fromUrl(url)
    }

    suspend fun resolve(rawUrl: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val cleanUrl = extractCleanUrl(rawUrl)
        if (cleanUrl.isNullOrEmpty() || !cleanUrl.startsWith("http")) {
            return@withContext Result.failure(IllegalArgumentException("Invalid link format. Please provide a full web link."))
        }

        val platform = detectPlatform(cleanUrl)

        val info = when (platform) {
            SocialPlatform.TIKTOK -> tikTokParser.parse(cleanUrl)
            SocialPlatform.YOUTUBE -> youTubeParser.parse(cleanUrl)
            SocialPlatform.FACEBOOK -> socialMediaParser.parseFacebook(cleanUrl)
            SocialPlatform.INSTAGRAM -> socialMediaParser.parseInstagram(cleanUrl)
            SocialPlatform.TWITTER -> socialMediaParser.parseTwitter(cleanUrl)
            SocialPlatform.OTHER -> socialMediaParser.parseGeneric(cleanUrl)
        }

        return@withContext if (info != null && info.formats.isNotEmpty()) {
            Result.success(info)
        } else {
            Result.failure(Exception("Unable to extract video formats. Please ensure the link is public and active."))
        }
    }

    private fun extractCleanUrl(text: String): String? {
        val trimmed = text.trim()
        val matcher = java.util.regex.Pattern.compile("https?://[^\\s]+").matcher(trimmed)
        return if (matcher.find()) {
            matcher.group()
        } else null
    }
}
