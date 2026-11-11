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
        /** Host of [url] in lower case without a leading "www.", or "" when it cannot be parsed. */
        fun hostOf(url: String): String {
            // Parsed by hand: java.net.URI rejects many real-world links (spaces, "|", "{}" ...).
            return url.trim()
                .substringAfter("://", "")
                .substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
                .lowercase()
                .removePrefix("www.")
        }

        /** True when [host] is [domain] itself or one of its sub-domains (so "netflix.com" never matches "x.com"). */
        fun hostMatches(host: String, vararg domains: String): Boolean {
            return domains.any { host == it || host.endsWith(".$it") }
        }

        fun fromUrl(url: String): SocialPlatform {
            val host = hostOf(url)
            return when {
                hostMatches(host, "suno.com", "suno.ai") -> SUNO
                hostMatches(host, "youtube.com", "youtu.be", "youtube-nocookie.com") -> YOUTUBE
                hostMatches(host, "tiktok.com") -> TIKTOK
                hostMatches(host, "facebook.com", "fb.watch", "fb.com") -> FACEBOOK
                hostMatches(host, "instagram.com", "instagr.am") -> INSTAGRAM
                hostMatches(host, "twitter.com", "x.com") -> TWITTER
                else -> OTHER
            }
        }
    }
}
