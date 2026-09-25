package com.cgens67.avidtune.ytmusic

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

// ==========================================
// Client Identifier Profiles
// ==========================================

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
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

        val IOS = PlayerClient("IOS", "21.26.4", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
        val ANDROID = PlayerClient("ANDROID", "21.26.364", "com.google.android.youtube/21.26.364 (Linux; U; Android 15; en_US; Pixel 9 Pro) gzip")
        val WEB_REMIX = PlayerClient("WEB_REMIX", "1.20260707.12.00", WEB_USER_AGENT, MUSIC_ORIGIN)

        fun forStreamUrl(url: String): PlayerClient {
            val parsed = url.toHttpUrlOrNull() ?: return IOS
            val name = parsed.queryParameter("c")?.uppercase(Locale.ROOT) ?: return IOS
            return when {
                name.startsWith("IOS") -> IOS
                name.startsWith("ANDROID") -> ANDROID
                name.startsWith("WEB_REMIX") -> WEB_REMIX
                else -> IOS
            }
        }

        fun rangeBytesFor(url: String): Long {
            val parsed = url.toHttpUrlOrNull() ?: return Long.MAX_VALUE
            if (!parsed.host.endsWith("googlevideo.com")) return Long.MAX_VALUE
            return 1024L * 1024
        }
    }
}

// ==========================================
// InnerTube Core Network Service
// ==========================================

object BitChordInnertube {
    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val WEB_REMIX_VERSION = "1.20250101.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"

    var currentLanguage: String = "en"
    private val acceptLanguageHeader: String get() = if (currentLanguage == "en") "en-US,en;q=0.9" else "$currentLanguage,en-US;q=0.8,en;q=0.7"

    var cookie: String? = null
        set(value) {
            if (field != value) {
                scope = null
                visitorData = null
                channelOverride = null
            }
            field = value
        }

    @Volatile var visitorData: String? = null
    @Volatile private var scope: SessionScope? = null
    private val scopeLock = Mutex()
    @Volatile private var channelOverride: ChannelSelection? = null

    class ChannelSelection(val pageId: String?, val dataSyncId: String?, val authUser: String? = null)
    private class SessionScope(val dataSyncId: String?, val pageId: String?, val authUser: String, val clientVersion: String?)

    private val json = Json { ignoreUnknownKeys = true }
    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val client = HttpClient(OkHttp) {
        engine { preconfigured = okHttpClient }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = true
    }

    fun selectChannel(pageId: String?, dataSyncId: String?, authUser: String? = null) {
        channelOverride = if (pageId == null && dataSyncId == null) null else ChannelSelection(pageId, dataSyncId, authUser)
    }

    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        runCatching {
            val body = client.get("https://www.youtube.com/sw.js_data") {
                header("User-Agent", PlayerClient.WEB_USER_AGENT)
            }.bodyAsText()
            val payload = Json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
            findVisitorData(payload)
        }.getOrNull()?.let { visitorData = it }
        return visitorData
    }

    private fun findVisitorData(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { Regex("""Cg[A-Za-z0-9_%-]{40,}""").matches(it) }
        else -> null
    }

    suspend fun ensureSessionScope() {
        val session = cookie ?: return
        if (scope != null) return
        scopeLock.withLock {
            if (scope != null || cookie != session) return
            runCatching {
                val html = client.get("$MUSIC_ORIGIN/") {
                    header("User-Agent", PlayerClient.WEB_USER_AGENT)
                    header("Accept-Language", acceptLanguageHeader)
                    header("Cookie", session)
                    sapisidFrom(session)?.let { header("Authorization", sapisidHash(it)) }
                }.bodyAsText()
                val signedIn = Regex(""""LOGGED_IN"\s*:\s*(true|false)""").find(html)?.groupValues?.get(1) == "true"
                val clientVersion = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                if (!signedIn) return@runCatching clientVersion?.let { SessionScope(null, null, "0", it) }
                val pageId = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                val dataSyncId = pageId ?: normalizeDataSyncId(Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1))
                val authUser = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { visitorData = it }
                SessionScope(dataSyncId, pageId, authUser ?: "0", clientVersion)
            }.getOrNull()?.let { scope = it }
        }
    }

    private fun sapisidFrom(cookieHeader: String): String? {
        val jar = cookieHeader.split(';').mapNotNull { entry ->
            val n = entry.substringBefore('=').trim()
            val v = entry.substringAfter('=', "").trim()
            if (n.isEmpty() || v.isEmpty()) null else n to v
        }.toMap()
        return listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID").firstNotNullOfOrNull { jar[it] }
    }

    private fun sapisidHash(sapisid: String, origin: String = MUSIC_ORIGIN): String {
        val timestamp = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }

    suspend fun postMusic(endpoint: String, query: Map<String, String> = emptyMap(), bodyExtras: JsonObjectBuilder.() -> Unit): JsonObject {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val clientVersion = s?.clientVersion ?: WEB_REMIX_VERSION
        var backoff = 500L
        for (attempt in 1..3) {
            try {
                val resp = client.post("$MUSIC_BASE/$endpoint") {
                    contentType(ContentType.Application.Json)
                    parameter("prettyPrint", "false")
                    parameter("hl", currentLanguage)
                    header("Accept-Language", acceptLanguageHeader)
                    query.forEach { (k, v) -> parameter(k, v) }
                    header("X-Origin", MUSIC_ORIGIN)
                    header("Origin", MUSIC_ORIGIN)
                    header("Referer", "$MUSIC_ORIGIN/")
                    header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                    header("X-YouTube-Client-Version", clientVersion)
                    visitorData?.let { header("X-Goog-Visitor-Id", it) }
                    cookie?.let { c ->
                        header("Cookie", c)
                        header("X-Goog-AuthUser", channelOverride?.authUser ?: s?.authUser ?: "0")
                        (channelOverride?.pageId ?: s?.pageId)?.let { header("X-Goog-PageId", it) }
                        sapisidFrom(c)?.let { header("Authorization", sapisidHash(it)) }
                    }
                    setBody(buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", clientVersion)
                                put("hl", currentLanguage)
                                put("gl", "US")
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                (channelOverride?.dataSyncId ?: s?.dataSyncId)?.let { put("onBehalfOfUser", it) }
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    })
                }.body<JsonObject>()
                if (visitorData == null) {
                    visitorData = (resp["responseContext"] as? JsonObject)?.get("visitorData")?.jsonPrimitive?.contentOrNull
                }
                return resp
            } catch (e: Exception) {
                if (e is HttpRequestTimeoutException || attempt == 3) throw e
                delay(backoff)
                backoff *= 2
            }
        }
        error("Request failed")
    }

    // Endpoints
    suspend fun browse(browseId: String, params: String? = null): JsonObject = postMusic("browse") {
        put("browseId", browseId)
        params?.let { put("params", it) }
    }

    suspend fun browseContinuation(token: String): JsonObject = postMusic("browse", mapOf("ctoken" to token, "continuation" to token, "type" to "next")) {
        put("continuation", token)
    }

    suspend fun search(query: String, params: String? = null): JsonObject = postMusic("search") {
        put("query", query)
        params?.let { put("params", it) }
    }

    suspend fun searchContinuation(token: String): JsonObject = postMusic("search", mapOf("ctoken" to token, "continuation" to token, "type" to "next")) {
        put("continuation", token)
    }

    suspend fun searchSuggestions(input: String): JsonObject = postMusic("music/get_search_suggestions") {
        put("input", input)
    }

    suspend fun next(videoId: String): JsonObject = postMusic("next") {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    }

    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}
    suspend fun accountsList(): JsonObject = postMusic("account/accounts_list") {}

    suspend fun rate(videoId: String, status: LikeStatus) {
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        postMusic(endpoint) { putJsonObject("target") { put("videoId", videoId) } }
    }

    suspend fun ratePlaylist(playlistId: String, saved: Boolean) {
        val endpoint = if (saved) "like/like" else "like/removelike"
        postMusic(endpoint) { putJsonObject("target") { put("playlistId", playlistId) } }
    }

    suspend fun setSubscribed(channelId: String, subscribed: Boolean) {
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        postMusic(endpoint) { putJsonArray("channelIds") { add(channelId) } }
    }

    suspend fun sendFeedback(token: String) = postMusic("feedback") {
        putJsonArray("feedbackTokens") { add(token) }
    }

    suspend fun createPlaylist(title: String, privacy: PlaylistPrivacy, description: String? = null, videoIds: List<String> = emptyList()): String {
        val res = postMusic("playlist/create") {
            put("title", title)
            put("description", description.orEmpty())
            put("privacyStatus", privacy.apiValue)
            if (videoIds.isNotEmpty()) putJsonArray("videoIds") { videoIds.forEach { add(it) } }
        }
        return res["playlistId"]?.jsonPrimitive?.contentOrNull ?: error("No playlist ID returned")
    }

    suspend fun deletePlaylist(playlistId: String) = postMusic("playlist/delete") {
        put("playlistId", playlistId.removePrefix("VL"))
    }

    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Map<String, String> {
        val res = postMusic("browse/edit_playlist") {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                videoIds.forEach { vid -> addJsonObject { put("action", "ACTION_ADD_VIDEO"); put("addedVideoId", vid) } }
            }
        }
        return (res["playlistEditResults"] as? JsonArray).orEmpty().mapNotNull { result ->
            val data = (result as? JsonObject)?.get("playlistEditVideoAddedResultData") as? JsonObject ?: return@mapNotNull null
            val vid = data["videoId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val setVid = data["setVideoId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            vid to setVid
        }.toMap()
    }

    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) {
        postMusic("browse/edit_playlist") {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                entries.forEach { (setVid, vid) ->
                    addJsonObject { put("action", "ACTION_REMOVE_VIDEO"); put("setVideoId", setVid); put("removedVideoId", vid) }
                }
            }
        }
    }

    suspend fun renamePlaylist(playlistId: String, title: String) {
        postMusic("browse/edit_playlist") {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                addJsonObject { put("action", "ACTION_SET_PLAYLIST_NAME"); put("playlistName", title) }
            }
        }
    }
}

// ==========================================
// InnerTube Response Parser
// ==========================================

object InnertubeParser {
    fun parseHome(response: JsonObject): List<HomeShelf> {
        val sections = response.o("contents")?.o("singleColumnBrowseResultsRenderer")?.a("tabs")?.firstOrNull()
            ?.o("tabRenderer")?.o("content")?.o("sectionListRenderer")?.a("contents").orEmpty()
        return sections.mapNotNull { sec ->
            sec.o("musicCarouselShelfRenderer")?.let(::carouselShelf)
                ?: sec.o("musicShelfRenderer")?.let(::plainShelf)
        }
    }

    fun parseHomeContinuation(root: JsonElement): List<HomeShelf> {
        val out = mutableListOf<HomeShelf>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    node["musicCarouselShelfRenderer"]?.let { (it as? JsonObject)?.let(::carouselShelf)?.let(out::add) }
                    node["musicShelfRenderer"]?.let { (it as? JsonObject)?.let(::plainShelf)?.let(out::add) }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun carouselShelf(carousel: JsonObject): HomeShelf? {
        val header = carousel.o("header")?.o("musicCarouselShelfBasicHeaderRenderer")
        val title = header?.o("title").runs()
        val strapline = header?.o("strapline").runs()
        if (title.contains("video", ignoreCase = true)) return null
        val items = carousel.a("contents").orEmpty().mapNotNull { item ->
            parseTwoRowItem(item.o("musicTwoRowItemRenderer"))
                ?: parseResponsiveListItem(item.o("musicResponsiveListItemRenderer"))?.takeUnless { it.isVideo }?.let {
                    ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null)
                }
        }
        return if (items.isEmpty()) null else HomeShelf(title.ifBlank { "For you" }, items, strapline)
    }

    private fun plainShelf(shelf: JsonObject): HomeShelf? {
        val title = shelf.o("title").runs()
        if (title.contains("video", ignoreCase = true)) return null
        val items = shelf.a("contents").orEmpty().mapNotNull { parseResponsiveListItem(it.o("musicResponsiveListItemRenderer")) }
            .filterNot { it.isVideo }
            .map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null) }
        return if (items.isEmpty()) null else HomeShelf(title.ifBlank { "For you" }, items)
    }

    fun parseSearchPage(response: JsonObject, includeVideos: Boolean = false): SearchPage {
        val topResults = collectRenderers(response, "musicCardShelfRenderer").mapNotNull { card ->
            parseCardShelfSong(card)?.let(SearchResult::TopTrack)
                ?: parseCardShelfBrowse(card)?.let(SearchResult::Browse)
        }
        val rows = collectRenderers(response, "musicResponsiveListItemRenderer")
        val seen = HashSet<String>()
        val parsed = buildList {
            topResults.forEach { res ->
                when (res) {
                    is SearchResult.TopTrack -> if (!res.song.isVideo && seen.add("v:${res.song.videoId}")) add(res)
                    is SearchResult.Browse -> if (seen.add("b:${res.item.browseId}")) add(res)
                    is SearchResult.Track -> Unit
                }
            }
            rows.forEach { renderer ->
                val browse = parseBrowseItem(renderer)
                if (browse != null) {
                    if (seen.add("b:${browse.browseId}")) add(SearchResult.Browse(browse))
                } else {
                    parseResponsiveListItem(renderer)?.let { song ->
                        if (song.isVideo == includeVideos && seen.add("v:${song.videoId}")) add(SearchResult.Track(song))
                    }
                }
            }
        }
        return SearchPage(parsed, continuationToken(response))
    }

    fun parseSearchSuggestions(response: JsonObject): List<String> =
        collectRenderers(response, "searchSuggestionRenderer").mapNotNull { renderer ->
            renderer.o("navigationEndpoint")?.o("searchEndpoint")?.s("query")
                ?: renderer.o("suggestion").runs().takeIf { it.isNotBlank() }
        }.distinct()

    fun parseWatchQueue(root: JsonElement): List<Song> {
        val out = LinkedHashMap<String, Song>()
        collectRenderers(root, "playlistPanelVideoRenderer").forEach { renderer ->
            val vid = renderer.s("videoId") ?: return@forEach
            val title = renderer.o("title").runs()
            if (title.isBlank()) return@forEach
            val bylineRuns = renderer.o("longBylineText")?.a("runs").orEmpty()
            val byline = bylineRuns.map { it.s("text").orEmpty() }
            val artist = byline.takeWhile { !it.contains("•") }.joinToString("").trim()
            out[vid] = Song(
                videoId = vid,
                title = title,
                artist = artist,
                thumbnailUrl = renderer.o("thumbnail")?.a("thumbnails").best(),
                durationText = renderer.o("lengthText").runs().takeIf { it.isNotBlank() },
                isVideo = byline.any { it.contains("views", ignoreCase = true) },
            )
        }
        return out.values.toList()
    }

    fun collectSongsDeep(root: JsonElement): List<Song> {
        val out = LinkedHashMap<String, Song>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    node["musicResponsiveListItemRenderer"]?.let { (it as? JsonObject)?.let(::parseResponsiveListItem)?.let { s -> out[s.videoId] = s } }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out.values.toList()
    }

    fun parseSongMenu(root: JsonElement, videoId: String): SongMenu? {
        val row = collectRenderers(root, "playlistPanelVideoRenderer").firstOrNull { it.s("videoId") == videoId } ?: return null
        val likeStatus = when (collectRenderers(row, "likeButtonRenderer").firstOrNull().s("likeStatus")) {
            "LIKE" -> LikeStatus.LIKE
            "DISLIKE" -> LikeStatus.DISLIKE
            "INDIFFERENT" -> LikeStatus.INDIFFERENT
            else -> null
        }
        val toggle = collectRenderers(row, "toggleMenuServiceItemRenderer").firstOrNull { it.feedbackToken("defaultServiceEndpoint") != null }
        val defaultAdds = toggle?.o("defaultIcon")?.s("iconType") == "LIBRARY_ADD"
        return SongMenu(
            likeStatus = likeStatus,
            inLibrary = toggle != null && !defaultAdds,
            addToLibraryToken = if (defaultAdds) toggle?.feedbackToken("defaultServiceEndpoint") else toggle?.feedbackToken("toggledServiceEndpoint"),
            removeFromLibraryToken = if (defaultAdds) toggle?.feedbackToken("toggledServiceEndpoint") else toggle?.feedbackToken("defaultServiceEndpoint"),
        )
    }

    fun parseLibraryState(root: JsonElement): LibraryState? {
        val buttons = collectRenderers(root, "musicResponsiveHeaderRenderer").firstOrNull()?.a("buttons").orEmpty()
        val save = buttons.firstNotNullOfOrNull { it.o("toggleButtonRenderer")?.takeIf { b -> b.o("defaultIcon")?.s("iconType")?.contains("BOOKMARK") == true } } ?: return null
        val play = buttons.firstNotNullOfOrNull { it.o("musicPlayButtonRenderer")?.o("playNavigationEndpoint") }
        val pid = play?.o("watchPlaylistEndpoint")?.s("playlistId") ?: play?.o("watchEndpoint")?.s("playlistId") ?: return null
        return LibraryState(playlistId = pid, saved = save.s("isToggled") == "true")
    }

    fun parsePlaylistOwned(root: JsonElement): Boolean? {
        if (collectRenderers(root, "musicEditablePlaylistDetailHeaderRenderer").isNotEmpty()) return true
        val header = collectRenderers(root, "musicResponsiveHeaderRenderer").firstOrNull() ?: return null
        return header.a("buttons").orEmpty().none { it.o("toggleButtonRenderer")?.o("defaultIcon")?.s("iconType")?.contains("BOOKMARK") == true }
    }

    fun parseBrowseHeader(root: JsonElement): BrowseHeader? {
        val header = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer").firstNotNullOfOrNull {
            collectRenderers(root, it).firstOrNull()
        } ?: return null
        val title = header.o("title").runs()
        if (title.isBlank()) return null
        val subtitle = listOf("straplineTextOne", "subtitle").map { header.o(it).runs() }.filter { it.isNotBlank() }.distinct().joinToString(" • ")
        return BrowseHeader(title, subtitle, collectRenderers(header, "musicThumbnailRenderer").firstOrNull()?.o("thumbnail")?.a("thumbnails").best())
    }

    data class BrowseHeader(val title: String, val subtitle: String, val thumbnailUrl: String?)

    fun parseArtistPage(response: JsonObject): ArtistPage {
        val songs = mutableListOf<Song>()
        var moreSongs: String? = null
        val shelves = mutableListOf<HomeShelf>()
        val header = response["header"]

        collectRenderers(response, "musicShelfRenderer").forEach { shelf ->
            shelf.a("contents").orEmpty().forEach { row ->
                parseResponsiveListItem(row.o("musicResponsiveListItemRenderer"))?.let(songs::add)
            }
            if (moreSongs == null) {
                moreSongs = shelf.o("title")?.a("runs")?.firstOrNull()?.o("navigationEndpoint")?.o("browseEndpoint")?.s("browseId")
            }
        }
        collectRenderers(response, "musicCarouselShelfRenderer").forEach { carousel ->
            val h = carousel.o("header")?.o("musicCarouselShelfBasicHeaderRenderer")
            val title = h?.o("title").runs()
            val items = carousel.a("contents").orEmpty().mapNotNull { parseTwoRowItem(it.o("musicTwoRowItemRenderer")) }.filter { it.browseId != null }
            if (title.isNotBlank() && items.isNotEmpty()) {
                shelves += HomeShelf(title, items)
            }
        }
        val imm = header?.o("musicImmersiveHeaderRenderer")
        return ArtistPage(
            songs = songs,
            moreSongsBrowseId = moreSongs,
            sections = shelves,
            thumbnailUrl = imm?.o("thumbnail")?.o("musicThumbnailRenderer")?.o("thumbnail")?.a("thumbnails").best(),
            name = imm?.o("title").runs().takeIf { it.isNotBlank() },
        )
    }

    fun parseUserPlaylists(items: List<ShelfItem>): List<UserPlaylist> = items.mapNotNull { item ->
        val bid = item.browseId ?: return@mapNotNull null
        if (!bid.startsWith("VL") || listOf("VLLM", "VLSE", "VLRD").any { bid.startsWith(it) }) return@mapNotNull null
        UserPlaylist(bid.removePrefix("VL"), item.title, item.subtitle, item.thumbnailUrl)
    }

    fun parseLibraryItems(root: JsonElement): List<ShelfItem> =
        collectRenderers(root, "musicTwoRowItemRenderer").mapNotNull { parseTwoRowItem(it) }

    fun parsePlaylistShelf(root: JsonElement): PlaylistShelfPage? {
        val playlistScope = collectRenderers(root, "musicPlaylistShelfRenderer").firstOrNull() ?: return null
        val songs = collectRenderers(playlistScope, "musicResponsiveListItemRenderer").mapNotNull { parseResponsiveListItem(it) }.distinctBy { it.videoId }
        val token = continuationToken(playlistScope)
        return PlaylistShelfPage(songs, emptyList(), token)
    }

    fun continuationToken(root: JsonElement): String? {
        collectRenderers(root, "continuationItemRenderer").firstOrNull()
            ?.o("continuationEndpoint")?.o("continuationCommand")?.s("token")?.let { return it }
        return collectRenderers(root, "nextContinuationData").firstOrNull().s("continuation")
    }

    fun artistFromSubtitle(subtitle: String): String =
        subtitle.split(" • ").firstOrNull { it.isNotBlank() && !it.contains(":") } ?: subtitle

    private fun parseResponsiveListItem(renderer: JsonObject?): Song? {
        if (renderer == null) return null
        val vid = renderer.o("playlistItemData")?.s("videoId")
            ?: renderer.o("overlay")?.o("musicItemThumbnailOverlayRenderer")?.o("content")?.o("musicPlayButtonRenderer")
                ?.o("playNavigationEndpoint")?.o("watchEndpoint")?.s("videoId") ?: return null

        val columns = renderer.a("flexColumns").orEmpty()
        val title = columns.getOrNull(0)?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text").runs()
        if (title.isBlank()) return null
        val subtitle = columns.getOrNull(1)?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text").runs()
        val parts = subtitle.split(" • ").filter { it.isNotBlank() }
        val duration = parts.lastOrNull()?.takeIf { it.matches(Regex("""\d+:\d{2}""")) }
        val artist = parts.firstOrNull { it.lowercase() != "song" && it.lowercase() != "video" && !it.matches(Regex("""\d+:\d{2}""")) } ?: "Unknown artist"
        val thumb = renderer.o("thumbnail")?.o("musicThumbnailRenderer")?.o("thumbnail")?.a("thumbnails").best()
        return Song(videoId = vid, title = title, artist = artist, thumbnailUrl = thumb, durationText = duration, isVideo = subtitle.contains("video", true))
    }

    private fun parseCardShelfSong(renderer: JsonObject): Song? {
        val vid = renderer.o("onTap")?.o("watchEndpoint")?.s("videoId") ?: return null
        val title = renderer.o("title").runs()
        val subtitle = renderer.o("subtitle").runs()
        return Song(videoId = vid, title = title, artist = subtitle.split(" • ").firstOrNull() ?: "Unknown artist", thumbnailUrl = renderer.o("thumbnail")?.o("musicThumbnailRenderer")?.o("thumbnail")?.a("thumbnails").best())
    }

    private fun parseCardShelfBrowse(renderer: JsonObject): BrowseItem? {
        val endpoint = renderer.o("onTap")?.o("browseEndpoint") ?: return null
        val bid = endpoint.s("browseId") ?: return null
        return BrowseItem(bid, renderer.o("title").runs(), renderer.o("subtitle").runs(), renderer.o("thumbnail")?.o("musicThumbnailRenderer")?.o("thumbnail")?.a("thumbnails").best(), BrowseType.OTHER)
    }

    private fun parseBrowseItem(renderer: JsonObject): BrowseItem? {
        val endpoint = renderer.o("navigationEndpoint")?.o("browseEndpoint") ?: return null
        val bid = endpoint.s("browseId") ?: return null
        val cols = renderer.a("flexColumns").orEmpty()
        val title = cols.getOrNull(0)?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text").runs()
        return if (title.isBlank()) null else BrowseItem(bid, title, cols.getOrNull(1)?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text").runs(), renderer.o("thumbnail")?.o("musicThumbnailRenderer")?.o("thumbnail")?.a("thumbnails").best(), BrowseType.OTHER)
    }

    private fun parseTwoRowItem(renderer: JsonObject?): ShelfItem? {
        if (renderer == null) return null
        val title = renderer.o("title").runs().ifBlank { return null }
        val endp = renderer.o("navigationEndpoint")
        val bid = endp?.o("browseEndpoint")?.s("browseId")
        val vid = endp?.o("watchEndpoint")?.s("videoId")
        return ShelfItem(title, renderer.o("subtitle").runs(), renderer.o("thumbnailRenderer")?.o("musicThumbnailRenderer")?.o("thumbnail")?.a("thumbnails").best(), vid, bid)
    }

    private fun collectRenderers(root: JsonElement, name: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> { (node[name] as? JsonObject)?.let(out::add); node.values.forEach(::walk) }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun JsonElement?.feedbackToken(endpoint: String): String? = this.o(endpoint)?.o("feedbackEndpoint")?.s("feedbackToken")
    private fun JsonElement?.o(k: String): JsonObject? = (this as? JsonObject)?.get(k) as? JsonObject
    private fun JsonElement?.a(k: String): JsonArray? = (this as? JsonObject)?.get(k) as? JsonArray
    private fun JsonElement?.s(k: String): String? = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.runs(): String = this?.o("title")?.a("runs")?.joinToString("") { it.s("text").orEmpty() }
        ?: (this as? JsonObject)?.a("runs")?.joinToString("") { it.s("text").orEmpty() }.orEmpty()
    private fun JsonArray?.best(): String? = this?.lastOrNull()?.s("url")
}
