package com.heytune.app.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.heytune.app.R
import com.heytune.app.constants.MediaSessionConstants
import com.heytune.app.constants.SongSortType
import com.heytune.app.db.MusicDatabase
import com.heytune.app.db.entities.PlaylistEntity
import com.heytune.app.db.entities.Song
import com.heytune.app.extensions.metadata
import com.heytune.app.extensions.toMediaItem
import com.heytune.app.extensions.toggleRepeatMode
import com.heytune.app.extensions.toggleShuffleMode
import com.heytune.app.utils.reportException
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.plus
import javax.inject.Inject

class MediaLibrarySessionCallback @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
) : MediaLibrarySession.Callback {
    private val TAG = MediaLibrarySessionCallback::class.simpleName.toString()
    private val scope = CoroutineScope(Dispatchers.Main) + Job()
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands.buildUpon()
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(SessionCommand(MusicService.COMMAND_GET_BINDER, Bundle.EMPTY))
                .build(),
            connectionResult.availablePlayerCommands
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.toggleShuffleMode()
            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MusicService.COMMAND_GET_BINDER -> return Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_SUCCESS).apply {
                    extras.putBinder("music_binder", service.MusicBinder())
                }
            )
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        // TODO: when this is stable, change to debug
        Log.i(TAG, "onPlaybackResumption() called. isForPlayback = $isForPlayback")
        val q = database.getResumptionQueue()
        if (q == null) {
            Log.w(TAG, "No resumption queue data. Loading empty list")
            return@future MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        }
        val queuePosForLog = try { q.getQueuePosShuffled() } catch (_: Exception) { -1 }
        Log.i(TAG, "Resumption queue found. Loading queue: size = ${q.queue.size}, queue name = ${q.title}, " +
                "queuePosShuffled = $queuePosForLog, lastSongPos = ${q.lastSongPos},")

       if (isForPlayback) {
            val items = try {
                q.getCurrentQueueShuffled().map { it.toMediaItem() }
            } catch (_: Exception) {
                return@future MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            }
            if (items.isEmpty()) {
                return@future MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            }
            val pos = try {
                q.getQueuePosShuffled()
            } catch (_: Exception) {
                0
            }
            return@future MediaItemsWithStartPosition(
                items,
                pos.coerceIn(0, items.size - 1),
                q.lastSongPos
            )
        } else {
            // Preview path: single item, so the start index MUST be 0.
            // Returning the queue position here (e.g. 30 for a 1-item list)
            // is out of bounds and Auto drops the resume preview.
            val current = try {
                q.getCurrentSong()
            } catch (_: Exception) {
                null
            } ?: return@future MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            return@future MediaItemsWithStartPosition(
                listOf(current.toMediaItem()),
                0,
                q.lastSongPos
            )
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        LibraryResult.ofItem(
            MediaItem.Builder()
                .setMediaId(MusicService.ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsPlayable(false)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build(),
            params
        )
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val full: List<MediaItem> = when (parentId) {
                MusicService.ROOT -> listOf(
                    browsableMediaItem(
                        MusicService.SONG,
                        context.getString(R.string.songs),
                        null,
                        null,
                        MediaMetadata.MEDIA_TYPE_PLAYLIST
                    ),
                    browsableMediaItem(
                        MusicService.ARTIST,
                        context.getString(R.string.artists),
                        null,
                        null,
                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                    ),
                    browsableMediaItem(
                        MusicService.ALBUM,
                        context.getString(R.string.albums),
                        null,
                        null,
                        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                    ),
                    browsableMediaItem(
                        MusicService.PLAYLIST,
                        context.getString(R.string.playlists),
                        null,
                        null,
                        MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                    )
                )

                MusicService.SONG -> database.songsByCreateDateAsc().first().map { it.toMediaItem(parentId) }
                MusicService.ARTIST -> database.artistsInLibraryAsc().first().map { artist ->
                    browsableMediaItem(
                        "${MusicService.ARTIST}/${artist.id}",
                        artist.artist.name,
                        context.resources.getQuantityString(R.plurals.n_song, artist.songCount, artist.songCount),
                        null,
                        MediaMetadata.MEDIA_TYPE_ARTIST
                    )
                }

                MusicService.ALBUM -> database.albumsInLibraryAsc().first().map { album ->
                    browsableMediaItem(
                        "${MusicService.ALBUM}/${album.id}",
                        album.album.title,
                        album.artists.joinToString { it.name },
                        null,
                        MediaMetadata.MEDIA_TYPE_ALBUM
                    )
                }

                MusicService.PLAYLIST -> {
                    val likedSongCount = database.likedSongsCount().first()
                    val downloadedSongCount = downloadUtil.downloads.value.size
                    listOf(
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                            context.getString(R.string.liked_songs),
                            context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                            null,
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                            context.getString(R.string.downloaded_songs),
                            context.resources.getQuantityString(
                                R.plurals.n_song,
                                downloadedSongCount,
                                downloadedSongCount
                            ),
                            null,
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    ) + database.playlistInLibraryAsc().first().map { playlist ->
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${playlist.id}",
                            playlist.playlist.name,
                            context.resources.getQuantityString(
                                R.plurals.n_song,
                                playlist.songCount,
                                playlist.songCount
                            ),
                            null,
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    }
                }

                else -> when {
                    parentId.startsWith("${MusicService.ARTIST}/") ->
                        database.artistSongsByCreateDateAsc(parentId.removePrefix("${MusicService.ARTIST}/")).first()
                            .map {
                                it.toMediaItem(parentId)
                            }

                    parentId.startsWith("${MusicService.ALBUM}/") ->
                        database.albumSongs(parentId.removePrefix("${MusicService.ALBUM}/")).first().map {
                            it.toMediaItem(parentId)
                        }

                    parentId.startsWith("${MusicService.PLAYLIST}/") ->
                        when (val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")) {
                            PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, true)
                            PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> database.downloadNoLocalSongs()
                            else -> database.playlistSongs(playlistId).map { list ->
                                list.map { it.song }
                            }
                        }.first().map {
                            it.toMediaItem(parentId)
                        }

                    else -> emptyList()
                }
            }
        LibraryResult.ofItemList(paginate(full, page, pageSize), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
        // Top-level containers: describe the node itself so Auto can render it.
        when (mediaId) {
            MusicService.ROOT -> return@future LibraryResult.ofItem(
                MediaItem.Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(context.getString(R.string.app_name))
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build()
                    )
                    .build(), null
            )
            MusicService.SONG -> return@future LibraryResult.ofItem(
                browsableMediaItem(
                    MusicService.SONG,
                    context.getString(R.string.songs),
                    null,
                    null,
                    MediaMetadata.MEDIA_TYPE_PLAYLIST
                ), null
            )
            MusicService.ARTIST -> return@future LibraryResult.ofItem(
                browsableMediaItem(
                    MusicService.ARTIST,
                    context.getString(R.string.artists),
                    null,
                    null,
                    MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                ), null
            )
            MusicService.ALBUM -> return@future LibraryResult.ofItem(
                browsableMediaItem(
                    MusicService.ALBUM,
                    context.getString(R.string.albums),
                    null,
                    null,
                    MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                ), null
            )
            MusicService.PLAYLIST -> return@future LibraryResult.ofItem(
                browsableMediaItem(
                    MusicService.PLAYLIST,
                    context.getString(R.string.playlists),
                    null,
                    null,
                    MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                ), null
            )
        }
        // Nested containers (no "/" song leaf): artist/album/playlist folders.
        if (mediaId.startsWith("${MusicService.ARTIST}/") && !mediaId.removePrefix("${MusicService.ARTIST}/").contains("/")) {
            val artistId = mediaId.removePrefix("${MusicService.ARTIST}/")
            val artist = try { database.artist(artistId).first() } catch (_: Exception) { null }
            if (artist != null) {
                return@future LibraryResult.ofItem(
                    browsableMediaItem(
                        mediaId,
                        artist.artist.name,
                        context.resources.getQuantityString(R.plurals.n_song, artist.songCount, artist.songCount),
                        null,
                        MediaMetadata.MEDIA_TYPE_ARTIST
                    ), null
                )
            }
        }
        if (mediaId.startsWith("${MusicService.ALBUM}/") && !mediaId.removePrefix("${MusicService.ALBUM}/").contains("/")) {
            val albumId = mediaId.removePrefix("${MusicService.ALBUM}/")
            val album = try { database.album(albumId).first() } catch (_: Exception) { null }
            if (album != null) {
                return@future LibraryResult.ofItem(
                    browsableMediaItem(
                        mediaId,
                        album.album.title,
                        album.artists.joinToString { it.name },
                        null,
                        MediaMetadata.MEDIA_TYPE_ALBUM
                    ), null
                )
            }
        }
        if (mediaId.startsWith("${MusicService.PLAYLIST}/") && !mediaId.removePrefix("${MusicService.PLAYLIST}/").contains("/")) {
            val playlistId = mediaId.removePrefix("${MusicService.PLAYLIST}/")
            if (playlistId == PlaylistEntity.LIKED_PLAYLIST_ID) {
                val count = try { database.likedSongsCount().first() } catch (_: Exception) { 0 }
                return@future LibraryResult.ofItem(
                    browsableMediaItem(
                        mediaId,
                        context.getString(R.string.liked_songs),
                        context.resources.getQuantityString(R.plurals.n_song, count, count),
                        null,
                        MediaMetadata.MEDIA_TYPE_PLAYLIST
                    ), null
                )
            }
            if (playlistId == PlaylistEntity.DOWNLOADED_PLAYLIST_ID) {
                val count = downloadUtil.downloads.value.size
                return@future LibraryResult.ofItem(
                    browsableMediaItem(
                        mediaId,
                        context.getString(R.string.downloaded_songs),
                        context.resources.getQuantityString(R.plurals.n_song, count, count),
                        null,
                        MediaMetadata.MEDIA_TYPE_PLAYLIST
                    ), null
                )
            }
            val playlist = try { database.playlist(playlistId).first() } catch (_: Exception) { null }
            if (playlist != null) {
                return@future LibraryResult.ofItem(
                    browsableMediaItem(
                        mediaId,
                        playlist.playlist.name,
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            playlist.songCount,
                            playlist.songCount
                        ),
                        null,
                        MediaMetadata.MEDIA_TYPE_PLAYLIST
                    ), null
                )
            }
        }
        // Song leaves: mediaId is "<prefix>/.../<songId>" (search queries may
        // contain "/", so the song id is always the LAST segment) or a bare
        // song id (resumption/queue items built without a browse path).
        val songId = if ("/" in mediaId) mediaId.substringAfterLast("/") else mediaId
        if (songId.isNotEmpty()) {
            try {
                database.song(songId).first()?.toMediaItem()?.let {
                    return@future LibraryResult.ofItem(it, null)
                }
            } catch (_: Exception) {
            }
        }
        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        // Play from Android Auto. Runs on IO: every branch hits the database
        // (some load whole tables) and must never block the main thread.
        Log.d(TAG, "MediaLibrarySessionCallback.onSetMediaItems")
        val defaultResult = MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
        val rawMediaId = mediaItems.firstOrNull()?.mediaId
            ?: return@future defaultResult
        val path = rawMediaId.split("/")
        Log.d(TAG, "Path: " + path.joinToString(";"))

        // Song ids never contain "/", but search queries can (e.g. "AC/DC").
        // Search segments are Uri-encoded at build time, and old/unencoded
        // clients are handled by treating the song id as the LAST segment.
        val queue: Triple<List<MediaItem>, Int, Long> = when (path.firstOrNull()) {
            MusicService.SONG -> {
                val songId = path.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return@future defaultResult
                val allSongs = database.songsByCreateDateAsc().first()
                val index = allSongs.indexOfFirst { it.id == songId }
                if (index == -1) return@future defaultResult
                Triple(
                    allSongs.map { it.toMediaItem() },
                    index,
                    startPositionMs
                )
            }

            MusicService.ARTIST -> {
                if (path.size < 3) return@future defaultResult
                val artistId = path.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@future defaultResult
                val songId = path.last()
                val songs = database.artistSongsByCreateDateAsc(artistId).first()
                val index = songs.indexOfFirst { it.id == songId }
                if (index == -1) return@future defaultResult
                Triple(
                    songs.map { it.toMediaItem() },
                    index,
                    startPositionMs
                )
            }

            MusicService.ALBUM -> {
                if (path.size < 3) return@future defaultResult
                val albumId = path.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@future defaultResult
                val songId = path.last()
                val albumWithSongs = database.albumWithSongs(albumId).first() ?: return@future defaultResult
                val index = albumWithSongs.songs.indexOfFirst { it.id == songId }
                if (index == -1) return@future defaultResult
                Triple(
                    albumWithSongs.songs.map { it.toMediaItem() },
                    index,
                    startPositionMs
                )
            }

            MusicService.PLAYLIST -> {
                if (path.size < 3) return@future defaultResult
                val playlistId = path.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@future defaultResult
                val songId = path.last()
                val songs = when (playlistId) {
                    PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
                    PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> database.downloadNoLocalSongs()
                    else -> database.playlistSongs(playlistId).map { list ->
                        list.map { it.song }
                    }
                }.first()
                val index = songs.indexOfFirst { it.id == songId }
                if (index == -1) return@future defaultResult
                Triple(
                    songs.map { it.toMediaItem() },
                    index,
                    startPositionMs
                )
            }

            MusicService.SEARCH -> {
                if (path.size < 3) return@future defaultResult
                val songId = path.last()
                // Middle segments re-joined for back-compat with unencoded
                // clients, then decoded (encoded form has no raw "/").
                val searchQuery = Uri.decode(path.subList(1, path.size - 1).joinToString("/"))
                if (searchQuery.isEmpty()) return@future defaultResult
                var results = combine(
                    database.searchSongs(searchQuery),
                    database.searchArtistSongs(searchQuery),
                ) { songs, artistSongs ->
                    (songs + artistSongs).distinctBy { it.id }
                }

                val items = results.first().map { it.toMediaItem() }
                val index = items.indexOfFirst { it.mediaId == songId }
                if (index == -1) return@future defaultResult
                Triple(items, index, C.TIME_UNSET)
            }

            else -> Triple(emptyList<MediaItem>(), startIndex, startPositionMs)
        }

        val queueTitle = context.getString(R.string.android_auto)
        val added = service.queueBoard.value.addQueue(
            queueTitle,
            queue.first.map { it.metadata },
            shuffled = false,
            replace = true,
            delta = false,
            startIndex = queue.second
        )
        // Keep QueueBoard in sync with the player shuffle flag, in BOTH directions.
        // addQueue() is called with shuffled=false and its replace/jump-only paths
        // leave a stale shuffled=true flag behind (they only shuffle when the param
        // is true, never unshuffle). A stale flag makes the next shuffle toggle hit
        // the `q.shuffled == shuffleModeEnabled` early-return in
        // onShuffleModeEnabledChanged and appear to do nothing, while the timeline
        // itself plays sequentially. Also make the Auto queue current: unlike every
        // other addQueue caller, this path never calls setCurrQueue (Media3 sets the
        // timeline from the returned items), so without an explicit makeCurrent the
        // board keeps pointing at the previous queue and later shuffle toggles
        // shuffle the wrong queue.
        val shuffleEnabled = try {
            mediaSession.player.shuffleModeEnabled
        } catch (_: Exception) {
            false
        }
        if (added != null) {
            if (shuffleEnabled) {
                if (added.getSize() > 1) {
                    // (Re)shuffle even if the flag already says shuffled: replaceAll
                    // resets shuffleIndexes to sequential order, so flag==true alone
                    // does not mean the order is actually shuffled. Keep tapped song first.
                    service.queueBoard.value.shuffle(added, preserveCurrent = true, bypassSaveToDb = false)
                    val shuffledItems = added.getCurrentQueueShuffled().map { it.toMediaItem() }
                    val newIndex = added.getQueuePosShuffled().coerceIn(0, shuffledItems.size - 1)
                    MediaItemsWithStartPosition(shuffledItems, newIndex, queue.third)
                } else {
                    // Single song: nothing to reorder, just track it as current.
                    service.queueBoard.value.makeCurrent(added)
                    MediaItemsWithStartPosition(queue.first, queue.second, queue.third)
                }
            } else {
                if (added.shuffled) {
                    service.queueBoard.value.unShuffle(added)
                } else {
                    service.queueBoard.value.makeCurrent(added)
                }
                MediaItemsWithStartPosition(queue.first, queue.second, queue.third)
            }
        } else {
            MediaItemsWithStartPosition(queue.first, queue.second, queue.third)
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        Log.d(TAG, "MediaLibrarySessionCallback.onSearch: $query")
        session.notifySearchResultChanged(browser, query, 1, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Log.d(TAG, "MediaLibrarySessionCallback.onGetSearchResult: $query")
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList(), params)
            }

            try {
                var results = combine(
                    database.searchSongs(query),
                    database.searchArtistSongs(query),
                ) { songs, artistSongs ->
                    (songs + artistSongs).distinctBy { it.id }
                }

                // Slash-safe: the query is Uri-encoded into the mediaId so
                // onSetMediaItems can split on "/" unambiguously.
                // Playable only (browsable=false): a tap must play, not drill
                // into a dead-end child list.
                val items = results.first()
                    .map {
                        it.toMediaItem(
                            path = "${MusicService.SEARCH}/${Uri.encode(query)}",
                            isPlayable = true,
                            isBrowsable = false
                        )
                    }
                LibraryResult.ofItemList(paginate(items, page, pageSize), params)
            } catch (e: Exception) {
                Log.d(TAG, "Could not get search results")
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }
    }

    /**
     * Media3 paginates browse/search results via (page, pageSize). The full
     * list is built first (library queries are Flow-based), then sliced so
     * large libraries don't blow the binder transaction on Auto.
     */
    private fun paginate(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0 || page < 0) return items
        val from = page * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from, minOf(from + pageSize, items.size))
    }

    // NOTE: artwork intentionally never attached (no setArtworkUri/setArtworkData).
    // Artwork bitmaps cross the Binder to Android Auto and large/unreadable local
    // art blows the transaction limit and drops the connection. The iconUri
    // param is dead (always null) and kept only to avoid churning call sites.
    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        @Suppress("UNUSED_PARAMETER") iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC
    ) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()

    // NOTE: no setArtworkUri — see browsableMediaItem note. Titles/artists
    // carry the Auto UI; artwork bitmaps over Binder caused disconnects.
    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false) =
        MediaItem.Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setSubtitle(artists.joinToString { it.name })
                    .setArtist(artists.joinToString { it.name })
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun com.heytune.app.models.MediaMetadata.toMediaItem(isPlayable: Boolean = true, isBrowsable: Boolean = false) = MediaItem.Builder()
        .setMediaId(id)
        .setUri(id)
        .setCustomCacheKey(id)
        .setTag(this)
        .setMediaMetadata(
           MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(artists.joinToString { it.name })
                .setArtist(artists.joinToString { it.name })
                .setAlbumTitle(album?.title)
                .setIsPlayable(isPlayable)
                .setIsBrowsable(isBrowsable)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()
}