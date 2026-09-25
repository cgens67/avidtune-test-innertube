package com.cgens67.avidtune.ytmusic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

data class PlayerClient(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val origin: String? = null,
) {
    val referer: String? get() = origin?.let { "$it/" }

    fun mediaHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        origin?.let { put("Origin", it) }
        referer?.let { put("Referer", it) }
    }

    companion object {
        const val MUSIC_ORIGIN = "https://music.youtube.com"
        const val YOUTUBE_ORIGIN = "https://www.youtube.com"

        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

        val IOS = PlayerClient(
            clientName = "IOS",
            clientVersion = "21.26.4",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        )

        val IOS_RECENT = IOS.copy(
            clientVersion = "21.29.1",
            userAgent = "com.google.ios.youtube/21.29.1 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)",
        )

        val ANDROID = PlayerClient(
            clientName = "ANDROID",
            clientVersion = "21.26.364",
            userAgent = "com.google.android.youtube/21.26.364 " +
                "(Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip",
        )

        val ANDROID_MUSIC = PlayerClient(
            clientName = "ANDROID_MUSIC",
            clientVersion = "8.39.42",
            userAgent = "com.google.android.apps.youtube.music/8.39.42 " +
                "(Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002) gzip",
        )

        val ANDROID_VR = PlayerClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        )

        val ANDROID_VR_LEGACY = ANDROID_VR.copy(
            clientVersion = "1.43.32",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
        )

        val WEB_REMIX = PlayerClient(
            clientName = "WEB_REMIX",
            clientVersion = "1.20260707.12.00",
            userAgent = WEB_USER_AGENT,
            origin = MUSIC_ORIGIN,
        )

        val WEB = PlayerClient(
            clientName = "WEB",
            clientVersion = "2.20260708.00.00",
            userAgent = WEB_USER_AGENT,
            origin = YOUTUBE_ORIGIN,
        )

        val TVHTML5 = PlayerClient(
            clientName = "TVHTML5",
            clientVersion = "7.20260707.07.00",
            userAgent = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 " +
                "(KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            origin = YOUTUBE_ORIGIN,
        )

        fun forStreamUrl(url: String): PlayerClient {
            val parsed = url.toHttpUrlOrNull() ?: return IOS
            val name = parsed.queryParameter("c")?.uppercase(Locale.ROOT) ?: return IOS
            val version = parsed.queryParameter("cver")
            return when {
                name.startsWith("IOS") ->
                    if (version == IOS_RECENT.clientVersion) IOS_RECENT else IOS
                name == "ANDROID_VR" ->
                    if (version == ANDROID_VR_LEGACY.clientVersion) ANDROID_VR_LEGACY else ANDROID_VR
                name == "ANDROID_MUSIC" -> ANDROID_MUSIC
                name.startsWith("ANDROID") -> ANDROID
                name.startsWith("TVHTML5") -> TVHTML5
                name == "WEB_REMIX" -> WEB_REMIX
                name.startsWith("WEB") || name == "MWEB" -> WEB
                else -> IOS
            }
        }

        fun rangeBytesFor(url: String): Long {
            val parsed = url.toHttpUrlOrNull() ?: return Long.MAX_VALUE
            if (!parsed.host.endsWith("googlevideo.com")) return Long.MAX_VALUE
            val name = parsed.queryParameter("c")?.uppercase(Locale.ROOT)
            return if (name == "ANDROID_VR" || name?.startsWith("TVHTML5_SIMPLY") == true) {
                NARROW_RANGE_BYTES
            } else {
                RANGE_BYTES
            }
        }

        private const val RANGE_BYTES = 1024L * 1024
        private const val NARROW_RANGE_BYTES = 512L * 1024
    }
}
