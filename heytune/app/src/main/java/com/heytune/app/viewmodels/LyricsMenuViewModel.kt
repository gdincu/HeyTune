package com.heytune.app.viewmodels

import androidx.lifecycle.ViewModel
import com.heytune.app.constants.LYRIC_FETCH_TIMEOUT
import com.heytune.app.db.MusicDatabase
import com.heytune.app.lyrics.LyricsHelper
import com.heytune.app.lyrics.LyricsResult
import com.heytune.app.models.MediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.akanework.gramophone.logic.utils.SemanticLyrics
import javax.inject.Inject

@HiltViewModel
class LyricsMenuViewModel @Inject constructor(
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
) : ViewModel() {
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)

    fun refetchLyrics(mediaMetadata: MediaMetadata, onDone: (SemanticLyrics?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            database.deleteLyricById(mediaMetadata.id)
            withTimeoutOrNull(LYRIC_FETCH_TIMEOUT) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                onDone(lyrics)
            }
        }
    }
}
