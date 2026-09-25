package com.cgens67.avidtune.ytmusic

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// ==========================================
// Stream Resolution Engine (BitChord Optimized)
// ==========================================

object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val NEXT_ENDPOINT = "/youtubei/v1/next"
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""
    private const val PROBE_TIMEOUT_SECONDS = 6L
    private const val AUTH_BOUNDARY_BYTES = 1024L * 1024
    private const val PROBE_READ_BYTES = 16L * 1024
    private val REFUSAL_CODES = setOf(403, 404, 410)

    data class Stream(
        val url: String,
        val kbps: Int,
        val mimeType: String,
        val loudnessDb: Double? = null
    )

    private val extractorClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            .pingInterval(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val proberClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            // Drop NewPipe's unneeded next endpoint call to eliminate multi-second hangs
            if (NEXT_ENDPOINT in request.url()) {
                val bytes = EMPTY_NEXT_RESPONSE.toByteArray()
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, bytes, request.url())
            }

            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }

            if (!hasUserAgent) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
                )
            }

            val response = extractorClient.newCall(builder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }

            val bodyString = response.body?.string()
            val bodyBytes = bodyString?.toByteArray()
            val latestUrl = response.request.url.toString()

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                bodyBytes,
                latestUrl
            )
        }

        override fun executeAsync(request: Request, callback: AsyncCallback?): CancellableCall {
            throw UnsupportedOperationException()
        }
    }

    private val newPipeInit by lazy {
        if (NewPipe.getDownloader() == null) {
            NewPipe.init(OkHttpDownloader())
        }
    }

    private val recent = ConcurrentHashMap<String, Pair<String, Long>>()
    private val inFlight = ConcurrentHashMap<String, Deferred<String>>()
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractionGate = Mutex()

    suspend fun resolve(videoId: String): String = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        recent[videoId]?.let { (url, time) ->
            if (now - time < 20 * 60 * 1000L) return@withContext url
        }

        inFlight[videoId]?.let { return@withContext it.await() }

        val task = resolverScope.async(start = CoroutineStart.LAZY) {
            resolveUncached(videoId)
        }

        val running = inFlight.putIfAbsent(videoId, task)
        if (running != null) {
            task.cancel()
            return@withContext running.await()
        }

        task.invokeOnCompletion { inFlight.remove(videoId, task) }
        task.start()
        task.await()
    }

    private suspend fun resolveUncached(videoId: String): String {
        newPipeInit
        val stream = extractStreamWithRetry(videoId)
        recent[videoId] = stream.url to SystemClock.elapsedRealtime()
        return stream.url
    }

    private suspend fun extractStreamWithRetry(videoId: String): Stream {
        var failure: Exception? = null
        for (attempt in 1..3) {
            if (attempt > 1) delay(1000L * (attempt - 1))
            try {
                val stream = extractStream(videoId)
                val probeResult = probe(stream.url)
                if (probeResult == Probe.OK) {
                    return stream
                }
                Timber.w("Stream URL probe failed ($probeResult) for $videoId on attempt $attempt")
            } catch (e: Exception) {
                Timber.w(e, "Extraction attempt $attempt failed for $videoId")
                failure = e
            }
        }
        throw failure ?: IOException("Failed to extract verified stream for $videoId")
    }

    private suspend fun extractStream(videoId: String): Stream = extractionGate.withLock {
        withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()

            val candidates = extractor.audioStreams
                .filter {
                    !it.content.isNullOrBlank() &&
                        (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod != null)
                }

            val chosen = candidates.maxByOrNull { it.averageBitrate }
                ?: extractor.audioStreams.filter { !it.content.isNullOrBlank() }.maxByOrNull { it.averageBitrate }
                ?: throw IOException("No audio streams available for $videoId")

            Stream(
                url = chosen.content,
                kbps = chosen.averageBitrate,
                mimeType = chosen.format?.mimeType ?: "audio/webm"
            )
        }
    }

    private enum class Probe { OK, REFUSED, UNREACHABLE }

    private fun probe(url: String): Probe {
        val length = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
        val start = if (length != null && length > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) AUTH_BOUNDARY_BYTES else 0L
        val end = minOf(start + PlayerClient.rangeBytesFor(url), length ?: Long.MAX_VALUE) - 1

        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")

        PlayerClient.forStreamUrl(url).mediaHeaders().forEach { (k, v) ->
            builder.header(k, v)
        }

        return try {
            proberClient.newCall(builder.build()).execute().use { response ->
                when {
                    response.code in REFUSAL_CODES -> Probe.REFUSED
                    response.code !in 200..299 && response.code != 416 -> Probe.UNREACHABLE
                    response.header("Content-Type")?.startsWith("audio/") != true -> Probe.REFUSED
                    response.body?.source()?.request(PROBE_READ_BYTES) != true -> Probe.UNREACHABLE
                    else -> Probe.OK
                }
            }
        } catch (e: Exception) {
            Probe.UNREACHABLE
        }
    }

    fun onPlaybackRefused(url: String, responseCode: Int) {
        if (responseCode in REFUSAL_CODES) {
            recent.entries.firstOrNull { it.value.first == url }?.let {
                recent.remove(it.key)
                Timber.w("Invalidated cached stream for ${it.key} due to HTTP $responseCode")
            }
        }
    }
}

// ==========================================
// Catalogue Fuzzy Matcher
// ==========================================

object TrackMatcher {
    data class Target(val title: String, val artist: String, val durationSec: Int? = null)

    fun queries(target: Target): List<String> {
        val clean = target.title.replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ").trim()
        val cleanArtist = target.artist.split(Regex("""[,&/]""")).firstOrNull()?.trim().orEmpty()
        return listOf("$clean $cleanArtist".trim(), clean)
    }

    fun best(candidates: List<Song>, target: Target): Song? {
        val cleanTarget = target.title.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "")
        val targetArtist = target.artist.lowercase(Locale.ROOT)

        return candidates.minByOrNull { song ->
            val cleanTitle = song.title.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "")
            val titlePenalty = if (cleanTitle == cleanTarget) 0 else 50
            val artistPenalty = if (song.artist.lowercase(Locale.ROOT).contains(targetArtist)) 0 else 30
            val durDiff = target.durationSec?.let { exp ->
                val got = song.durationMillis() / 1000
                if (got > 0) abs(exp - got).toInt() else 10
            } ?: 0
            titlePenalty + artistPenalty + durDiff
        }
    }
}

// ==========================================
// Main YouTube Music High-Level Repository
// ==========================================

object YtMusicRepository {
    const val LIKED_MUSIC = "VLLM"
    const val PLAYLISTS_SHELF = "Playlists"
    private const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"

    suspend fun home(): Result<HomeFeed> = call {
        val res = BitChordInnertube.browse("FEmusic_home")
        HomeFeed(InnertubeParser.parseHome(res), InnertubeParser.continuationToken(res))
    }

    suspend fun moreHome(token: String): Result<HomeFeed> = call {
        val res = BitChordInnertube.browseContinuation(token)
        HomeFeed(InnertubeParser.parseHomeContinuation(res), InnertubeParser.continuationToken(res))
    }

    suspend fun searchPage(query: String, filter: SearchFilter): Result<SearchPage> = call {
        val res = BitChordInnertube.search(query, filter.params)
        InnertubeParser.parseSearchPage(res, includeVideos = filter == SearchFilter.VIDEOS)
    }

    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        searchPage(query, filter).map { it.rows }

    suspend fun searchSuggestions(input: String): Result<List<String>> = call {
        InnertubeParser.parseSearchSuggestions(BitChordInnertube.searchSuggestions(input))
    }

    suspend fun browseSongs(browseId: String): Result<PlaylistShelfPage> = call {
        val res = BitChordInnertube.browse(browseId)
        InnertubeParser.parsePlaylistShelf(res) ?: PlaylistShelfPage(InnertubeParser.collectSongsDeep(res), emptyList(), null)
    }

    suspend fun allSongs(browseId: String): Result<List<Song>> = call {
        InnertubeParser.collectSongsDeep(BitChordInnertube.browse(browseId))
    }

    suspend fun radio(videoId: String): Result<List<Song>> = call {
        InnertubeParser.parseWatchQueue(BitChordInnertube.next(videoId))
    }

    suspend fun trackLinks(videoId: String): Result<Song> = call {
        InnertubeParser.parseWatchQueue(BitChordInnertube.next(videoId)).firstOrNull { it.videoId == videoId }
            ?: error("Track not found")
    }

    suspend fun library(): Result<LibraryPage> = call {
        coroutineScope {
            val liked = async { browseSongs(LIKED_MUSIC).getOrNull()?.songs.orEmpty() }
            val playlists = async { userPlaylists().getOrNull().orEmpty() }
            val likedList = liked.await()
            val shelfItems = playlists.await().map {
                ShelfItem(it.title, it.subtitle, it.thumbnailUrl, null, it.browseId)
            }
            LibraryPage(likedList, emptyList(), listOf(HomeShelf(PLAYLISTS_SHELF, shelfItems)))
        }
    }

    suspend fun userPlaylists(): Result<List<UserPlaylist>> = call {
        val res = BitChordInnertube.browse(LIBRARY_PLAYLISTS)
        InnertubeParser.parseUserPlaylists(InnertubeParser.parseLibraryItems(res))
    }

    suspend fun resolveAudio(song: Song): Song {
        if (!song.isVideo) return song
        val target = TrackMatcher.Target(song.title, song.artist, (song.durationMillis() / 1000).toInt())
        for (q in TrackMatcher.queries(target)) {
            val candidates = search(q, SearchFilter.SONGS).getOrNull()
                ?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
            val match = TrackMatcher.best(candidates, target)
            if (match != null) return match
        }
        return song
    }

    suspend fun resolveVideo(song: Song): Song? {
        if (song.isVideo) return null
        val target = TrackMatcher.Target(song.title, song.artist, (song.durationMillis() / 1000).toInt())
        for (q in TrackMatcher.queries(target)) {
            val candidates = search(q, SearchFilter.VIDEOS).getOrNull()
                ?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
            val match = TrackMatcher.best(candidates, target)
            if (match != null) return match
        }
        return null
    }

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> = call { BitChordInnertube.rate(videoId, status) }
    suspend fun createPlaylist(title: String, privacy: PlaylistPrivacy, videoIds: List<String> = emptyList()): Result<String> =
        call { BitChordInnertube.createPlaylist(title, privacy, videoIds = videoIds) }
    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Result<Map<String, String>> =
        call { BitChordInnertube.addToPlaylist(playlistId, videoIds) }
    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>): Result<Unit> =
        call { BitChordInnertube.removeFromPlaylist(playlistId, entries) }
    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> =
        call { BitChordInnertube.renamePlaylist(playlistId, title) }
    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        call { BitChordInnertube.deletePlaylist(playlistId) }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching { block() }
    }
}
