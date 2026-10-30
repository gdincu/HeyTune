/*
 * HeyTune glue: wires the :handsfree voice module into the app.
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package com.heytune.app.handsfree

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaBrowser
import com.heytune.app.MainActivity
import com.heytune.app.R
import com.heytune.app.constants.VoiceDrivingModeKey
import com.heytune.app.models.toMediaMetadata
import com.heytune.app.playback.PlayerConnection
import com.heytune.app.playback.queues.ListQueue
import com.heytune.app.utils.dataStore
import com.heytune.handsfree.ModelManager
import com.heytune.handsfree.PlayerBridge
import com.heytune.handsfree.VoiceCues
import com.heytune.handsfree.VoiceService
import com.heytune.handsfree.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single place that connects MainActivity + PlayerConnection + :handsfree.
 *
 * Call [attach] from MainActivity.onCreate and [onControllerReady] from the
 * MediaController callback. All voice commands execute against the local
 * library database (the lite branch has no YouTube backend).
 */
object VoiceIntegration {
    private const val TAG = "VoiceIntegration"

    private var micPermissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingStart = false
    private var pendingListenOnce = false

    fun attach(activity: MainActivity) {
        micPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted && pendingListenOnce) {
                pendingListenOnce = false
                listenOnce(activity)
                return@registerForActivityResult
            }
            if (granted && pendingStart) {
                pendingStart = false
                activity.lifecycleScope.launch { startVoice(activity) {} }
            } else if (!granted) {
                pendingStart = false
                pendingListenOnce = false
                Toast.makeText(
                    activity,
                    activity.getString(R.string.voice_mic_denied),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun onControllerReady(
        activity: MainActivity,
        browser: MediaBrowser,
        playerConnection: PlayerConnection,
    ) {
        val bridge = PlayerBridge(browser).apply {
            onPlaySearchRequest = { query ->
                // Synchronous bridge contract: kick off async search+play,
                // report true when the query is non-blank (playback follows).
                if (query.isBlank()) {
                    false
                } else {
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        searchAndPlay(activity, playerConnection, query)
                    }
                    true
                }
            }
            onRandomPlayRequest = {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    playRandom(activity, playerConnection)
                }
                true
            }
            onLikeRequest = { playerConnection.toggleLike() }
        }
        VoiceService.playerBridge = bridge
        activity.lifecycleScope.launch {
            if (isDrivingMode(activity)) startVoice(activity) {}
        }
    }

    suspend fun isDrivingMode(activity: MainActivity): Boolean =
        isDrivingMode(activity.applicationContext)

    suspend fun isDrivingMode(context: android.content.Context): Boolean =
        withContext(Dispatchers.IO) {
            context.dataStore.data.first()[VoiceDrivingModeKey] == true
        }

    /**
     * Called by the settings toggle. Downloads models on first enable,
     * then starts (or stops) the foreground mic service.
     */
    fun setDrivingMode(
        activity: MainActivity,
        enabled: Boolean,
        onProgress: (fraction: Float) -> Unit = {},
        onDone: (ok: Boolean) -> Unit = {},
    ) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                activity.dataStore.edit {
                    it[VoiceDrivingModeKey] = enabled
                }
            }
            if (!enabled) {
                // OFF = no mic at all: mirror to the :handsfree module pref
                // (VoiceService gates LISTEN_ONCE + keep-alive on it), then
                // stop the service. ACTION_STOP's stopVoice() already drops
                // any in-flight window, so no separate CANCEL is needed
                // (it would pointlessly create the service when idle).
                withContext(Dispatchers.IO) {
                    VoiceSettings.load(activity).copy(enabled = false).save(activity)
                }
                pendingStart = false
                pendingListenOnce = false
                stopVoice(activity)
                onDone(true)
                return@launch
            }
            if (hasMicPermission(activity)) {
                startVoice(activity, onProgress, onDone)
            } else {
                pendingStart = true
                // Optimistically persist; actual start happens on grant.
                micPermissionLauncher?.launch(Manifest.permission.RECORD_AUDIO)
                onDone(false)
            }
        }
    }

    /**
     * Pause-button voice trigger: when driving mode is ON, tapping pause
     * stops the music (so the mic hears no background noise) and opens one
     * English-only listen window. When driving mode is OFF this is a no-op
     * (no mic). Call this AFTER pausing playback. Never touches the
     * driving-mode setting.
     */
    fun onPausePressed(activity: MainActivity) = listenOnce(activity)

    /**
     * Play was tapped while a listen window may be open: close the mic and
     * drop the window altogether (no inference, no command executes).
     * Safe to call when nothing is listening — it is a no-op then.
     */
    fun cancelListen(activity: MainActivity) {
        Log.i(TAG, "cancelListen")
        activity.startService(
            Intent(activity, VoiceService::class.java)
                .setAction(VoiceService.ACTION_CANCEL_WINDOW),
        )
    }

    /**
     * One-shot voice command, armed ONLY by the in-app pause button when
     * driving mode is ON: same model prep as driving mode, then a single
     * wake-free English-only window. When driving mode is OFF this is a
     * no-op (no mic, no permission prompt, no download). Never touches the
     * driving-mode setting.
     */
    fun listenOnce(activity: MainActivity) {
        Log.i(TAG, "listenOnce")
        activity.lifecycleScope.launch {
            if (!isDrivingMode(activity)) {
                Log.i(TAG, "listenOnce: ignored (driving mode off)")
                pendingListenOnce = false
                return@launch
            }
            if (!hasMicPermission(activity)) {
                Log.i(TAG, "listenOnce: requesting mic permission")
                pendingListenOnce = true
                micPermissionLauncher?.launch(Manifest.permission.RECORD_AUDIO)
                return@launch
            }
            if (prepareModels(activity)) {
                Log.i(TAG, "listenOnce: models ready, arming")
                activity.startService(
                    Intent(activity, VoiceService::class.java)
                        .setAction(VoiceService.ACTION_LISTEN_ONCE),
                )
            } else {
                Log.w(TAG, "listenOnce: model prep failed")
            }
        }
    }

    // -- internals ----------------------------------------------------------

    private fun hasMicPermission(activity: MainActivity): Boolean =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Shared model prep: download + load. Returns false on failure (toast
     * shown). No state changes: callers decide what flags to set, so the
     * pause path never touches the driving-mode setting.
     */
    private suspend fun prepareModels(
        activity: MainActivity,
        onProgress: (fraction: Float) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!ModelManager.modelsReady(activity)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.voice_downloading_models),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                ModelManager.downloadMissing(activity) { f, _ ->
                    activity.runOnUiThread { onProgress(f) }
                }
            }
            val toolsJson = activity.assets
                .open("handsfree_tools.json")
                .bufferedReader()
                .use { it.readText() }
            ModelManager.ensureLoaded(activity, toolsJson)
        } catch (e: Exception) {
            Log.w(TAG, "model download/load failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.voice_models_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
            false
        }
    }

    private suspend fun startVoice(
        activity: MainActivity,
        onProgress: (fraction: Float) -> Unit = {},
        onDone: (ok: Boolean) -> Unit = {},
    ) {
        if (!hasMicPermission(activity)) return
        val ok = prepareModels(activity, onProgress)
        if (!ok) {
            withContext(Dispatchers.IO) {
                activity.dataStore.edit { it[VoiceDrivingModeKey] = false }
                VoiceSettings.load(activity).copy(enabled = false).save(activity)
            }
            onDone(false)
            return
        }
        VoiceSettings.load(activity).copy(enabled = true).save(activity)
        val intent = Intent(activity, VoiceService::class.java)
            .setAction(VoiceService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                activity,
                activity.getString(R.string.voice_started),
                Toast.LENGTH_SHORT,
            ).show()
        }
        onDone(true)
    }

    private fun stopVoice(activity: MainActivity) {
        // VoiceService clears its running state itself on ACTION_STOP.
        activity.startService(
            Intent(activity, VoiceService::class.java)
                .setAction(VoiceService.ACTION_STOP),
        )
        Toast.makeText(
            activity,
            activity.getString(R.string.voice_stopped),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * "hey tune play X" -> search local library (songs + artist songs),
     * enqueue as a ListQueue and play the first hit.
     */
    private suspend fun searchAndPlay(
        activity: MainActivity,
        playerConnection: PlayerConnection,
        query: String,
    ) {
        try {
            // Engine/fallback queries may carry STT punctuation ("Play, let
            // it be."). Room LIKE is literal, so normalize before searching
            // (same normalization as CommandParser.parse).
            val q = com.heytune.handsfree.CommandParser.normalize(query)
            if (q.isEmpty()) {
                Log.i(TAG, "voice play: empty query after normalize ('$query')")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.voice_not_heard),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                VoiceCues.playError(activity)
                return
            }
            val db = playerConnection.database
            // Fetch separately so we can detect "play <artist>" intent
            val songHits = db.searchSongs(q).first()
            val artistHits = db.searchArtistSongs(q).first()
            val songs = (songHits + artistHits).distinctBy { it.id }
            if (songs.isEmpty()) {
                Log.i(TAG, "voice play: no local results for '$query' (normalized '$q')")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.voice_song_not_found, query),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                VoiceCues.playError(activity)
                return
            }
            // Match found: OK cue first, then execute (user hears
            // confirmation just before the new track starts).
            // Artist name: play random song from that artist, not always the same first one
            val isArtistIntent = artistHits.isNotEmpty() && songHits.isEmpty()
            val ordered = if (isArtistIntent) songs.shuffled() else songs
            val items = ordered.map { it.toMediaMetadata() }
            withContext(Dispatchers.Main) {
                VoiceCues.playOk(activity)
                playerConnection.playQueue(
                    ListQueue(
                        title = "Voice: $query",
                        items = items,
                        startIndex = 0,
                    ),
                )
            }
            Log.i(TAG, "voice play: '$query' -> ${songs.size} result(s) [songHits=${songHits.size} artistHits=${artistHits.size} randomArtist=$isArtistIntent], playing ${if (isArtistIntent) "random" else "first"}")
        } catch (e: Exception) {
            Log.w(TAG, "voice play failed for '$query'", e)
            VoiceCues.playError(activity)
        }
    }

    /** "play a random song" -> pick one random local song and play it */
    private suspend fun playRandom(
        activity: MainActivity,
        playerConnection: PlayerConnection,
    ) {
        try {
            val all = withContext(Dispatchers.IO) { playerConnection.database.allLocalSongs() }
            if (all.isEmpty()) {
                Log.i(TAG, "voice random: empty library")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.voice_library_empty),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                VoiceCues.playError(activity)
                return
            }
            val pick = all.random()
            val items = listOf(pick.toMediaMetadata())
            withContext(Dispatchers.Main) {
                VoiceCues.playOk(activity)
                playerConnection.playQueue(
                    ListQueue(
                        title = "Random",
                        items = items,
                        startIndex = 0,
                    ),
                )
            }
            Log.i(TAG, "voice random -> ${pick.song.title} (${all.size} total)")
        } catch (e: Exception) {
            Log.w(TAG, "voice random failed", e)
            VoiceCues.playError(activity)
        }
    }
}
