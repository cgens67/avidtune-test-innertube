package com.cgens67.innertube

import com.cgens67.innertube.models.Context
import com.cgens67.innertube.models.MediaInfo
import com.cgens67.innertube.models.ReturnYouTubeDislikeResponse
import com.cgens67.innertube.models.YouTubeClient
import com.cgens67.innertube.models.YouTubeLocale
import com.cgens67.innertube.models.body.*
import com.cgens67.innertube.models.response.NextResponse
import com.cgens67.innertube.utils.parseCookieString
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class InnerTube {
    private var httpClient = createClient()

    private companion object {
        const val PLAYBACK_TELEMETRY_VER = "2"
        const val DEFAULT_WEB_REMIX_VERSION = "1.20260707.12.00"
        const val WEB_USER_AGENT = YouTubeClient.USER_AGENT_WEB
        val VISITOR_DATA_REGEX = Regex("""Cg[A-Za-z0-9_%-]{40,}""")
    }

    var locale = YouTubeLocale(
        gl = Locale.getDefault().country.ifEmpty { "US" },
        hl = Locale.getDefault().toLanguageTag().ifEmpty { "en-US" }
    )

    var visitorData: String? = null
    var dataSyncId: String? = null
    var cookie: String? = null
        set(value) {
            if (field != value) {
                scope = null
                visitorData = null
                channelOverride = null
            }
            field = value
            cookieMap = if (value == null) emptyMap() else parseCookieString(value)
        }
    private var cookieMap = emptyMap<String, String>()

    var proxy: Proxy? = null
        set(value) {
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    var proxyAuth: String? = null
    var useLoginForBrowse: Boolean = false

    // ==========================================
    // BitChord Session Scope & Channel Switcher
    // ==========================================

    private class SessionScope(
        val dataSyncId: String?,
        val pageId: String?,
        val authUser: String,
        val clientVersion: String?
    )

    class ChannelSelection(
        val pageId: String?,
        val dataSyncId: String?,
        val authUser: String? = null
    )

    @Volatile
    private var scope: SessionScope? = null
    private val scopeLock = Mutex()

    @Volatile
    private var channelOverride: ChannelSelection? = null

    val liveWebRemixVersion: String
        get() = scope?.clientVersion ?: DEFAULT_WEB_REMIX_VERSION

    fun selectChannel(pageId: String?, dataSyncId: String?, authUser: String? = null) {
        channelOverride = if (pageId == null && dataSyncId == null) {
            null
        } else {
            ChannelSelection(pageId, dataSyncId, authUser)
        }
    }

    suspend fun ensureSessionScope() {
        val session = cookie ?: return
        if (scope != null) return
        scopeLock.withLock {
            if (scope != null || cookie != session) return
            runCatching {
                val html = httpClient.get(YouTubeClient.ORIGIN_YOUTUBE_MUSIC + "/") {
                    header("User-Agent", WEB_USER_AGENT)
                    header("Accept-Language", locale.hl)
                    header("Cookie", session)
                    sapisidFrom(session)?.let { header("Authorization", sapisidHash(it)) }
                }.bodyAsText()

                val signedIn = Regex(""""LOGGED_IN"\s*:\s*(true|false)""").find(html)?.groupValues?.get(1) == "true"
                val clientVersion = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                if (!signedIn) return@runCatching clientVersion?.let { SessionScope(null, null, "0", it) }

                val pageId = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                val rawDataSyncId = pageId ?: Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                val resolvedDataSyncId = normalizeDataSyncId(rawDataSyncId)
                val authUser = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let {
                    visitorData = it
                }
                SessionScope(resolvedDataSyncId, pageId, authUser ?: "0", clientVersion)
            }.getOrNull()?.let {
                scope = it
                if (dataSyncId == null && it.dataSyncId != null) {
                    dataSyncId = it.dataSyncId
                }
            }
        }
    }

    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        runCatching {
            val body = httpClient.get("https://www.youtube.com/sw.js_data") {
                header("User-Agent", WEB_USER_AGENT)
            }.bodyAsText()
            val payload = Json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
            findVisitorData(payload)
        }.getOrNull()?.let { visitorData = it }
        return visitorData
    }

    private fun findVisitorData(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { VISITOR_DATA_REGEX.matches(it) }
        else -> null
    }

    private fun normalizeDataSyncId(raw: String?): String? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        if (!value.contains("||")) return value
        return value.substringAfter("||").takeIf { it.isNotBlank() }
            ?: value.substringBefore("||").takeIf { it.isNotBlank() }
    }

    private fun sapisidFrom(cookieHeader: String): String? {
        val jar = cookieHeader.split(';').mapNotNull { entry ->
            val n = entry.substringBefore('=').trim()
            val v = entry.substringAfter('=', "").trim()
            if (n.isEmpty() || v.isEmpty()) null else n to v
        }.toMap()
        return listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID").firstNotNullOfOrNull { jar[it] }
    }

    private fun sapisidHash(sapisid: String, origin: String = YouTubeClient.ORIGIN_YOUTUBE_MUSIC): String {
        val timestamp = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() = HttpClient(OkHttp) {
        expectSuccess = true

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            })
        }

        install(ContentEncoding) {
            gzip(0.9F)
            deflate(0.8F)
        }

        engine {
            config {
                connectionPool(
                    okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES)
                )
                connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                retryOnConnectionFailure(true)
                cache(
                    okhttp3.Cache(
                        directory = File(System.getProperty("java.io.tmpdir"), "http_cache"),
                        maxSize = 50L * 1024L * 1024L
                    )
                )
                this@InnerTube.proxy?.let { proxyConfig ->
                    proxy(proxyConfig)
                }
                this@InnerTube.proxyAuth?.let { auth ->
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder()
                            .header("Proxy-Authorization", auth)
                            .build()
                    }
                }
            }
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 60000
        }

        defaultRequest {
            url(YouTubeClient.API_URL_YOUTUBE_MUSIC)
            header("Accept", "application/json")
            header("Accept-Language", "en-US,en;q=0.9")
            header("Cache-Control", "no-cache")
        }
    }

    private suspend fun HttpRequestBuilder.ytClient(client: YouTubeClient, setLogin: Boolean = false) {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val clientVersion = if (client.clientName == "WEB_REMIX") (s?.clientVersion ?: client.clientVersion) else client.clientVersion

        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", client.clientId)
            append("X-YouTube-Client-Version", clientVersion)
            append("X-Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
            append("Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
            append("Referer", YouTubeClient.REFERER_YOUTUBE_MUSIC)
            visitorData?.let { append("X-Goog-Visitor-Id", it) }
            if (setLogin && client.loginSupported) {
                cookie?.let { c ->
                    append("Cookie", c)
                    val authUser = channelOverride?.authUser ?: s?.authUser ?: "0"
                    append("X-Goog-AuthUser", authUser)
                    val pageId = channelOverride?.pageId ?: s?.pageId
                    pageId?.let { append("X-Goog-PageId", it) }
                    sapisidFrom(c)?.let { append("Authorization", sapisidHash(it)) }
                }
            }
        }
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (e is HttpRequestTimeoutException) throw e
                attempt++
                if (attempt >= maxAttempts) throw e
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = withRetry {
        httpClient.post("search") {
            ytClient(client, setLogin = false)
            setBody(
                SearchBody(
                    context = client.toContext(locale, visitorData, null),
                    query = query,
                    params = params
                )
            )
            parameter("continuation", continuation)
            parameter("ctoken", continuation)
        }
    }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String? = null,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("player") {
            ytClient(client, setLogin = true)
            setBody(
                PlayerBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId).let {
                        if (client.isEmbedded) {
                            it.copy(
                                thirdParty = Context.ThirdParty(
                                    embedUrl = "https://www.youtube.com/watch?v=${videoId}"
                                )
                            )
                        } else it
                    },
                    videoId = videoId,
                    playlistId = playlistId,
                    playbackContext = if (client.useSignatureTimestamp && signatureTimestamp != null) {
                        PlayerBody.PlaybackContext(
                            PlayerBody.PlaybackContext.ContentPlaybackContext(signatureTimestamp)
                        )
                    } else null,
                    serviceIntegrityDimensions = if (client.useWebPoTokens && poToken != null) {
                        PlayerBody.ServiceIntegrityDimensions(poToken)
                    } else null,
                )
            )
        }
    }

    suspend fun registerPlayback(
        url: String,
        cpn: String,
        playlistId: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        httpClient.get(url) {
            ytClient(client, true)
            parameter("c", client.clientName)
            parameter("cver", liveWebRemixVersion)
            parameter("cpn", cpn)
            parameter("ver", PLAYBACK_TELEMETRY_VER)
            parameter("cplayer", "UNIPLAYER")
            parameter("cbr", "Chrome")
            parameter("cbrver", "141.0.0.0")
            parameter("cos", "Windows")
            parameter("cosver", "10.0")

            if (playlistId != null) {
                parameter("list", playlistId)
                parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
            }
        }
    }

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = if (setLogin || useLoginForBrowse) (channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId) else null

        httpClient.post("browse") {
            ytClient(client, setLogin = setLogin || useLoginForBrowse)
            setBody(
                BrowseBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    browseId = browseId,
                    params = params,
                    continuation = continuation
                )
            )
        }
    }

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("next") {
            ytClient(client, setLogin = true)
            setBody(
                NextBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    videoId = videoId,
                    playlistId = playlistId,
                    playlistSetVideoId = playlistSetVideoId,
                    index = index,
                    params = params,
                    continuation = continuation
                )
            )
        }
    }

    suspend fun feedback(
        client: YouTubeClient,
        tokens: List<String>
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("feedback") {
            ytClient(client, setLogin = true)
            setBody(
                FeedbackBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    feedbackTokens = tokens
                )
            )
        }
    }

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = withRetry {
        httpClient.post("music/get_search_suggestions") {
            ytClient(client)
            setBody(
                GetSearchSuggestionsBody(
                    context = client.toContext(locale, visitorData, null),
                    input = input
                )
            )
        }
    }

    suspend fun getQueue(
        client: YouTubeClient,
        videoIds: List<String>?,
        playlistId: String?,
    ) = withRetry {
        httpClient.post("music/get_queue") {
            ytClient(client)
            setBody(
                GetQueueBody(
                    context = client.toContext(locale, visitorData, null),
                    videoIds = videoIds,
                    playlistId = playlistId
                )
            )
        }
    }

    suspend fun getTranscript(
        client: YouTubeClient,
        videoId: String,
    ) = withRetry {
        httpClient.post("https://music.youtube.com/youtubei/v1/get_transcript") {
            parameter("key", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3")
            headers {
                append("Content-Type", "application/json")
            }
            setBody(
                GetTranscriptBody(
                    context = client.toContext(locale, null, null),
                    params = Base64.Default.encode(
                        "\n${11.toChar()}$videoId".encodeToByteArray()
                    )
                )
            )
        }
    }

    suspend fun getSwJsData() = withRetry { httpClient.get("https://music.youtube.com/sw.js_data") }

    suspend fun accountMenu(client: YouTubeClient) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("account/account_menu") {
            ytClient(client, setLogin = true)
            setBody(AccountMenuBody(client.toContext(locale, visitorData, activeDataSyncId)))
        }
    }

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("like/like") {
            ytClient(client, setLogin = true)
            setBody(
                LikeBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    target = LikeBody.Target.video(videoId)
                )
            )
        }
    }

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("like/removelike") {
            ytClient(client, setLogin = true)
            setBody(
                LikeBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    target = LikeBody.Target.video(videoId)
                )
            )
        }
    }

    suspend fun subscribeChannel(
        client: YouTubeClient,
        channelId: String,
        params: String? = null,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("subscription/subscribe") {
            ytClient(client, setLogin = true)
            setBody(
                SubscribeBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    channelIds = listOf(channelId),
                    params = params
                )
            )
        }
    }

    suspend fun unsubscribeChannel(
        client: YouTubeClient,
        channelId: String,
        params: String? = null,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("subscription/unsubscribe") {
            ytClient(client, setLogin = true)
            setBody(
                SubscribeBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    channelIds = listOf(channelId),
                    params = params
                )
            )
        }
    }

    suspend fun likePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("like/like") {
            ytClient(client, setLogin = true)
            setBody(
                LikeBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    target = LikeBody.Target.playlist(playlistId)
                )
            )
        }
    }

    suspend fun unlikePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("like/removelike") {
            ytClient(client, setLogin = true)
            setBody(
                LikeBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    target = LikeBody.Target.playlist(playlistId)
                )
            )
        }
    }

    suspend fun addToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    playlistId = playlistId.removePrefix("VL"),
                    actions = listOf(
                        Action.AddVideoAction(addedVideoId = videoId)
                    )
                )
            )
        }
    }

    suspend fun addPlaylistToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        addPlaylistId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    playlistId = playlistId.removePrefix("VL"),
                    actions = listOf(
                        Action.AddPlaylistAction(addedFullListId = addPlaylistId)
                    )
                )
            )
        }
    }

    suspend fun removeFromPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    playlistId = playlistId.removePrefix("VL"),
                    actions = listOf(
                        Action.RemoveVideoAction(
                            removedVideoId = videoId,
                            setVideoId = setVideoId,
                        )
                    )
                )
            )
        }
    }

    suspend fun moveSongPlaylist(
        client: YouTubeClient,
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    playlistId = playlistId,
                    actions = listOf(
                        Action.MoveVideoAction(
                            movedSetVideoIdSuccessor = successorSetVideoId,
                            setVideoId = setVideoId,
                        )
                    )
                )
            )
        }
    }

    suspend fun createPlaylist(
        client: YouTubeClient,
        title: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("playlist/create") {
            ytClient(client, true)
            setBody(
                CreatePlaylistBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    title = title
                )
            )
        }
    }

    suspend fun renamePlaylist(
        client: YouTubeClient,
        playlistId: String,
        name: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    playlistId = playlistId,
                    actions = listOf(
                        Action.RenamePlaylistAction(
                            playlistName = name
                        )
                    )
                )
            )
        }
    }

    suspend fun getUploadCustomThumbnailLink(
        client: YouTubeClient,
        contentLength: Int
    ) = withRetry {
        httpClient.post("https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail") {
            ytClient(client, setLogin = true)
            headers {
                append("X-Goog-Upload-Command", "start")
                append("X-Goog-Upload-Protocol", "resumable")
                append("X-Goog-Upload-Header-Content-Length", contentLength.toString())
            }
        }
    }

    suspend fun uploadCustomThumbnail(
        client: YouTubeClient,
        uploadId: String,
        image: ByteArray,
    ) = withRetry {
        httpClient.post("https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail") {
            ytClient(client, setLogin = true)
            parameter("upload_id", uploadId)
            parameter("upload_protocol", "resumable")
            headers {
                append("X-Goog-Upload-Command", "upload, finalize")
                append("X-Goog-Upload-Offset", "0")
            }
            setBody(image)
        }
    }

    suspend fun setThumbnailPlaylist(
        client: YouTubeClient,
        playlistId: String,
        blobId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    playlistId = playlistId,
                    actions = listOf(
                        Action.SetCustomThumbnailAction(
                            addedCustomThumbnail = Action.SetCustomThumbnailAction.AddedCustomThumbnail(
                                playlistScottyEncryptedBlobId = blobId
                            )
                        )
                    )
                )
            )
        }
    }

    suspend fun removeThumbnailPlaylist(
        client: YouTubeClient,
        playlistId: String
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("browse/edit_playlist") {
            ytClient(client, setLogin = true)
            setBody(
                EditPlaylistBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    playlistId = playlistId,
                    actions = listOf(
                        Action.RemoveCustomThumbnailAction()
                    )
                )
            )
        }
    }

    suspend fun deletePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = withRetry {
        if (cookie != null) ensureSessionScope()
        val s = scope
        val activeDataSyncId = channelOverride?.dataSyncId ?: s?.dataSyncId ?: dataSyncId

        httpClient.post("playlist/delete") {
            ytClient(client, setLogin = true)
            setBody(
                PlaylistDeleteBody(
                    context = client.toContext(locale, visitorData, activeDataSyncId),
                    playlistId = playlistId
                )
            )
        }
    }

    private suspend fun returnYouTubeDislike(videoId: String) = withRetry {
        httpClient.get("https://returnyoutubedislikeapi.com/Votes?videoId=$videoId") {
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun initSongUpload(
        filename: String,
        contentLength: Long
    ) = withRetry {
        val authUser = "0"
        httpClient.post("https://upload.youtube.com/upload/usermusic/http?authuser=$authUser") {
            headers {
                append("X-Goog-Upload-Command", "start")
                append("X-Goog-Upload-Protocol", "resumable")
                append("X-Goog-Upload-Header-Content-Length", contentLength.toString())
                append("X-Goog-AuthUser", authUser)
                append("Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
                cookie?.let { c ->
                    append("Cookie", c)
                    sapisidFrom(c)?.let { append("Authorization", sapisidHash(it)) }
                }
            }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("filename=$filename")
        }
    }

    suspend fun uploadSongData(
        uploadUrl: String,
        data: ByteArray,
        onProgress: ((Float) -> Unit)? = null
    ) = withRetry {
        httpClient.post(uploadUrl) {
            headers {
                append("X-Goog-Upload-Command", "upload, finalize")
                append("X-Goog-Upload-Offset", "0")
                append("X-Goog-AuthUser", "0")
                append("Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
                cookie?.let { c ->
                    append("Cookie", c)
                    sapisidFrom(c)?.let { append("Authorization", sapisidHash(it)) }
                }
            }
            contentType(ContentType.Application.OctetStream)
            setBody(data)
            onUpload { bytesSentTotal, contentLength ->
                contentLength?.let {
                    onProgress?.invoke(bytesSentTotal.toFloat() / it.toFloat())
                }
            }
        }
    }

    suspend fun deletePrivatelyOwnedEntity(entityId: String) = withRetry {
        val context = YouTubeClient.WEB_REMIX.toContext(locale, visitorData, null)
        val contextStr = Json.encodeToString(Context.serializer(), context)
        val requestBody = """{"context":$contextStr,"entityId":"$entityId"}"""
        httpClient.post("https://music.youtube.com/youtubei/v1/music/delete_privately_owned_entity") {
            contentType(ContentType.Application.Json)
            headers {
                append("Referer", YouTubeClient.REFERER_YOUTUBE_MUSIC)
                append("Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
                cookie?.let { c ->
                    append("Cookie", c)
                    sapisidFrom(c)?.let { append("Authorization", sapisidHash(it)) }
                }
            }
            parameter("key", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3")
            parameter("prettyPrint", false)
            setBody(requestBody)
        }
    }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            val response = next(client = YouTubeClient.WEB, videoId, null, null, null, null, null).body<NextResponse>()
            val baseForInfo =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find { it?.videoSecondaryInfoRenderer != null }
                    ?.videoSecondaryInfoRenderer
            val baseForTitle =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find { it?.videoPrimaryInfoRenderer != null }
                    ?.videoPrimaryInfoRenderer
            val returnYouTubeDislikeResponse =
                returnYouTubeDislike(videoId).body<ReturnYouTubeDislikeResponse>()
            return@runCatching MediaInfo(
                videoId = videoId,
                title = baseForTitle?.title?.runs?.firstOrNull()?.text,
                author = baseForInfo?.owner?.videoOwnerRenderer?.title?.runs?.firstOrNull()?.text,
                authorId = baseForInfo?.owner?.videoOwnerRenderer?.navigationEndpoint?.browseEndpoint?.browseId,
                authorThumbnail = baseForInfo?.owner?.videoOwnerRenderer?.thumbnail?.thumbnails?.find { it.height == 48 }?.url?.replace("s48", "s960"),
                description = baseForInfo?.attributedDescription?.content,
                subscribers = baseForInfo?.owner?.videoOwnerRenderer?.subscriberCountText?.simpleText?.split(" ")?.firstOrNull(),
                uploadDate = baseForTitle?.dateText?.simpleText,
                viewCount = returnYouTubeDislikeResponse.viewCount,
                like = returnYouTubeDislikeResponse.likes,
                dislike = returnYouTubeDislikeResponse.dislikes,
            )
        }
}
