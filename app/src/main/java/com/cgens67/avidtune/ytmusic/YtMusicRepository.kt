package com.cgens67.avidtune.ytmusic

import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.abs

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
