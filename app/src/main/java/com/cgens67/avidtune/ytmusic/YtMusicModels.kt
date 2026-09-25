package com.cgens67.avidtune.ytmusic

import kotlinx.serialization.Serializable
import java.util.Locale

// ==========================================
// Track & Playback Models
// ==========================================

enum class QueueTier { USER_QUEUE, CONTEXT, AUTOPLAY }
enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }
enum class BrowseType { ALBUM, ARTIST, PLAYLIST, OTHER }
enum class WebSessionMode { SIGN_IN, SWITCH_CHANNEL }

enum class PlaybackSourceType {
    HOME, SEARCH, HISTORY, REPLAY, EXPLORE, BROWSE, SHARED_LINK, QUEUE
}

@Serializable
data class Song(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
    val isVideoOrigin: Boolean = isVideo,
    val setVideoId: String? = null,
    val queueTier: QueueTier = QueueTier.CONTEXT,
    val queueEntryId: String? = null,
    val radioName: String? = null,
    val localUri: String? = null,
    val downloadFormat: String? = null,
    val localPath: String? = null,
    val localDateAddedSeconds: Long? = null,
    val localDateModifiedSeconds: Long? = null,
    val sourceQuality: String? = null,
    val isExplicit: Boolean? = null,
    val playbackSource: String? = null,
    val playbackSourceType: PlaybackSourceType? = null,
    val playbackSourceId: String? = null,
) {
    val fromAutoplay: Boolean get() = queueTier == QueueTier.AUTOPLAY
}

fun Song.durationMillis(): Long = durationText.durationMillis()

fun String?.durationMillis(): Long {
    val parts = this?.trim()?.takeIf { it.isNotEmpty() }?.split(":") ?: return 0L
    val numbers = parts.map { it.trim().toLongOrNull() ?: return 0L }
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        3 -> numbers[0] * 3_600 + numbers[1] * 60 + numbers[2]
        else -> return 0L
    }
    return (seconds * 1_000).coerceAtLeast(0L)
}

private val SIZE_HINT = Regex("""w\d+-h\d+""")
fun String?.artworkAt(px: Int): String? = this?.replace(SIZE_HINT, "w$px-h$px")
fun Song.artworkAt(px: Int): String? = thumbnailUrl.artworkAt(px)

const val ROW_ART_PX = 160
const val CARD_ART_PX = 480
const val HEADER_ART_PX = 720
const val NOTIFICATION_ART_PX = 544
const val PLAYER_ART_PX = 1200

// ==========================================
// Feeds, Shelves & Browse Models
// ==========================================

data class ShelfItem(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
)

data class HomeShelf(
    val title: String,
    val items: List<ShelfItem>,
    val subtitle: String = "",
    val moreBrowseId: String? = null,
    val moreParams: String? = null,
)

data class HomeFeed(
    val shelves: List<HomeShelf>,
    val continuation: String?,
)

data class BrowseItem(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
)

sealed interface SearchResult {
    data class TopTrack(val song: Song) : SearchResult
    data class Track(val song: Song) : SearchResult
    data class Browse(val item: BrowseItem) : SearchResult
}

data class SearchPage(
    val rows: List<SearchResult>,
    val continuation: String?
)

data class PlaylistShelfPage(
    val songs: List<Song>,
    val suggested: List<Song>,
    val continuation: String?
)

enum class SearchFilter(val label: String, val params: String?) {
    ALL("All", null),
    SONGS("Songs", "EgWKAQIIAWoKEAkQChAFEAMQBA=="),
    VIDEOS("Videos", "EgWKAQIQAWoKEAkQChAFEAMQBA=="),
    ALBUMS("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA=="),
    ARTISTS("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA=="),
    PLAYLISTS("Playlists", "EgWKAQIoAWoKEAkQChAFEAMQBA=="),
}

data class MoodGenre(
    val title: String,
    val browseId: String,
    val params: String?,
    val thumbnailUrl: String? = null,
)

data class MoodGenreSection(val title: String, val items: List<MoodGenre>)

data class LibraryPage(
    val likedSongs: List<Song>,
    val librarySongs: List<Song>,
    val shelves: List<HomeShelf>,
    val likedContinuation: String? = null,
) {
    val isEmpty: Boolean get() = likedSongs.isEmpty() && librarySongs.isEmpty() && shelves.isEmpty()
}

data class DetailPage(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: UiState<List<Song>>,
    val type: BrowseType = BrowseType.OTHER,
    val sections: List<HomeShelf> = emptyList(),
    val suggestedSongs: List<Song> = emptyList(),
    val library: LibraryState? = null,
    val description: String? = null,
    val subscriberCountText: String? = null,
    val monthlyListenerCount: String? = null,
    val subscription: SubscriptionState? = null,
)

data class LibraryState(val playlistId: String, val saved: Boolean)
data class SubscriptionState(val channelId: String, val subscribed: Boolean)

data class ArtistPage(
    val songs: List<Song>,
    val moreSongsBrowseId: String?,
    val sections: List<HomeShelf>,
    val thumbnailUrl: String? = null,
    val name: String? = null,
    val description: String? = null,
    val subscriberCountText: String? = null,
    val monthlyListenerCount: String? = null,
    val subscription: SubscriptionState? = null,
)

enum class PlaylistPrivacy(val label: String, val apiValue: String) {
    PRIVATE("Private", "PRIVATE"),
    UNLISTED("Unlisted", "UNLISTED"),
    PUBLIC("Public", "PUBLIC"),
}

data class UserPlaylist(
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
) {
    val browseId: String get() = "VL$playlistId"
}

data class SongMenu(
    val likeStatus: LikeStatus?,
    val inLibrary: Boolean,
    val addToLibraryToken: String?,
    val removeFromLibraryToken: String?,
)

data class Account(val name: String, val email: String, val thumbnailUrl: String?)

data class AccountChannel(
    val name: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val pageId: String?,
    val dataSyncId: String?,
    val activeOnWeb: Boolean,
) {
    val key: String get() = pageId ?: dataSyncId ?: name
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

// ==========================================
// Account & Auth Session Models
// ==========================================

data class GoogleAccountSession(
    val accountId: String,
    val cookie: String,
    val name: String = "",
    val email: String = "",
    val profiles: List<YouTubeProfile> = emptyList(),
    val activeProfileId: String? = null,
)

data class YouTubeProfile(
    val profileId: String,
    val name: String,
    val handle: String = "",
    val avatar: String? = null,
    val pageId: String? = null,
    val dataSyncId: String? = null,
    val authUser: String? = null,
    val isBrandAccount: Boolean = false,
)

data class CapturedSession(
    val cookie: String,
    val pageId: String?,
    val dataSyncId: String?,
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?,
    val loggedIn: Boolean,
)

fun normalizeDataSyncId(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    if (!value.contains("||")) return value
    return value.substringAfter("||").takeIf { it.isNotBlank() }
        ?: value.substringBefore("||").takeIf { it.isNotBlank() }
}
