package com.heytune.app.extensions

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.heytune.app.db.entities.Song
import com.heytune.app.models.MediaMetadata
import com.heytune.app.models.toMediaMetadata


val MediaItem.metadata: MediaMetadata?
    get() = localConfiguration?.tag as? MediaMetadata

// NOTE: artwork intentionally omitted (no setArtworkUri). Artwork bitmaps
// cross the Binder to Android Auto controllers and the notification, and
// large/denied local art (e.g. FLAC on removable storage) blows the
// transaction limit and drops the Auto connection. Titles/artists carry
// the UI; a generic icon is shown instead.
fun Song.toMediaItem() = MediaItem.Builder()
    .setMediaId(song.id)
    .setUri(song.id)
    .setCustomCacheKey(song.id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setAlbumTitle(song.albumName)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

fun MediaMetadata.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(this)
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setAlbumTitle(album?.title)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()