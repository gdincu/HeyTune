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

    fun attach(activity: MainActivity) {
        micPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted && pendingStart) {
                pendingStart = false
                activity.lifecycleScope.launch { startVoice(activity) {} }
            } else if (!granted) {
                pendingStart = false
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
            onLikeRequest = { playerConnection.toggleLike() }
        }
        VoiceService.playerBridge = bridge
        activity.lifecycleScope.launch {
            if (isDrivingMode(activity)) startVoice(activity) {}
        }
    }

    suspend fun isDrivingMode(activity: MainActivity): Boolean =
        withContext(Dispatchers.IO) {
            activity.dataStore.data.first()[VoiceDrivingModeKey] == true
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

    // -- internals ----------------------------------------------------------

    private fun hasMicPermission(activity: MainActivity): Boolean =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private suspend fun startVoice(
        activity: MainActivity,
        onProgress: (fraction: Float) -> Unit = {},
        onDone: (ok: Boolean) -> Unit = {},
    ) {
        if (!hasMicPermission(activity)) return
        val ok = withContext(Dispatchers.IO) {
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
        if (!ok) {
            withContext(Dispatchers.IO) {
                activity.dataStore.edit { it[VoiceDrivingModeKey] = false }
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
            val db = playerConnection.database
            val songs = combine(
                db.searchSongs(query),
                db.searchArtistSongs(query),
            ) { songs, artistSongs ->
                (songs + artistSongs).distinctBy { it.id }
            }.first()
            if (songs.isEmpty()) {
                Log.i(TAG, "voice play: no local results for '$query'")
                return
            }
            val items = songs.map { it.toMediaMetadata() }
            withContext(Dispatchers.Main) {
                playerConnection.playQueue(
                    ListQueue(
                        title = "Voice: $query",
                        items = items,
                        startIndex = 0,
                    ),
                )
            }
            Log.i(TAG, "voice play: '$query' -> ${songs.size} result(s), playing first")
        } catch (e: Exception) {
            Log.w(TAG, "voice play failed for '$query'", e)
        }
    }
}
