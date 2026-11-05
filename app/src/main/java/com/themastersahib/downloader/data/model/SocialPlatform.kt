package com.themastersahib.downloader.data.model

enum class SocialPlatform(val displayName: String) {
    YOUTUBE("YouTube"),
    TIKTOK("TikTok"),
    FACEBOOK("Facebook"),
    INSTAGRAM("Instagram"),
    TWITTER("Twitter / X"),
    SUNO("Suno AI"),
    OTHER("Web Video");

    companion object {
        fun fromUrl(url: String): SocialPlatform {
            val lower = url.lowercase().trim()
            return when {
                lower.contains("suno.com") || lower.contains("suno.ai") -> SUNO
                lower.contains("youtube.com") || lower.contains("youtu.be") -> YOUTUBE
                lower.contains("tiktok.com") -> TIKTOK
                lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> FACEBOOK
                lower.contains("instagram.com") || lower.contains("instagr.am") -> INSTAGRAM
                lower.contains("twitter.com") || lower.contains("x.com") -> TWITTER
                else -> OTHER
            }
        }
    }
}
