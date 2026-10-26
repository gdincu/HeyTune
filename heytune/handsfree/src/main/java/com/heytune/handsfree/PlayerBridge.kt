package com.heytune.handsfree

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.session.MediaController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * Bridge between voice tool calls and HeyTune playback.
 *
 * HeyTune wiring: MainActivity binds MusicService via PlayerConnection;
 * this bridge takes the same MediaController and issues Player commands,
 * so voice and UI share one queue/session.
 *
 * play_song: search library/YTM for [query], enqueue + play. The TODO marks
 * the exact HeyTune call site (MusicService.playQueue / search).
 */
class PlayerBridge(private val controller: MediaController) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Media3 [MediaController] must be touched on the application thread
     * (else `IllegalStateException: called from a wrong thread`). VoiceService
     * calls this from its background mic thread, so marshal there and block
     * briefly for the result; already-main callers run inline.
     */
    fun dispatch(calls: List<ToolCall>): List<Any?> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return calls.map(::dispatchOne)
        }
        var result: List<Any?> = emptyList()
        var error: Throwable? = null
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                result = calls.map(::dispatchOne)
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        done.await()
        error?.let { throw it }
        return result
    }

    fun dispatchOne(call: ToolCall): Any? = when (call.name) {
        "pause_playback" -> controller.pause().also { lastAction = "pause" }
        "resume_playback" -> controller.play().also { lastAction = "resume" }
        "next_track" -> controller.seekToNext().also { lastAction = "next" }
        "previous_track" -> controller.seekToPrevious().also { lastAction = "previous" }
        "set_volume" -> {
            val level = (call.arguments["level"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 50
            // Media3 volume is 0..1 on the device stream used by MusicService.
            controller.volume = level / 100f
            lastAction = "volume=$level"
        }
        "toggle_shuffle" -> {
            val on = call.arguments["on"] as? Boolean ?: true
            controller.shuffleModeEnabled = on
            lastAction = "shuffle=$on"
        }
        "set_repeat_mode" -> {
            val mode = (call.arguments["mode"] as? String)?.lowercase() ?: "off"
            controller.repeatMode = when (mode) {
                "one" -> REPEAT_MODE_ONE
                "all" -> REPEAT_MODE_ALL
                else -> REPEAT_MODE_OFF
            }
            lastAction = "repeat=$mode"
        }
        "like_current_song" -> {
            // HeyTune: DatabaseDao toggle like on controller.currentMediaItem.
            // Kept as a callback so the host app owns DB access.
            onLikeRequest?.invoke(controller.currentMediaItem?.mediaId)
            lastAction = "like"
        }
        "play_random_song" -> {
            val handled = onRandomPlayRequest?.invoke() ?: false
            lastAction = if (handled) "random" else "random(unhandled)"
            handled
        }
        "play_song" -> {
            val query = call.arguments["query"] as? String ?: ""
            val handled = onPlaySearchRequest?.invoke(query) ?: false
            if (!handled) {
                // Fallback: best-effort library search via MediaController.
                // Host app should override onPlaySearchRequest for YTM search.
                lastAction = "play(unhandled)=$query"
                false
            } else {
                lastAction = "play=$query"
                true
            }
        }
        else -> error("unknown tool: ${call.name}")
    }

    /** Host hook: (query) -> true if enqueued+playing. Defaults to false. */
    var onPlaySearchRequest: ((String) -> Boolean)? = null

    /** Host hook: () -> true if random play enqueued. */
    var onRandomPlayRequest: (() -> Boolean)? = null

    /** Host hook: (mediaId?) -> Unit for like/favourite. */
    var onLikeRequest: ((String?) -> Unit)? = null

    var lastAction: String? = null
        private set

    companion object {
        /** Diffusion-free helper: run a block when the controller is ready. */
        fun withController(
            controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>,
            block: (MediaController) -> Unit,
        ) {
            val direct = Executor { it.run() }
            controllerFuture.addListener(
                { block(controllerFuture.get()) },
                direct,
            )
        }
    }
}

/** Minimal Player facade for tests / non-Media3 hosts. */
interface SimplePlayer {
    fun playSong(query: String): Any?
    fun pause(): Any?
    fun resume(): Any?
    fun next(): Any?
    fun previous(): Any?
    fun setVolume(level: Int): Any?
    fun setShuffle(on: Boolean): Any?
    fun setRepeatMode(mode: String): Any?
    fun likeCurrent(): Any?
}

fun List<ToolCall>.dispatchOn(player: SimplePlayer): List<Any?> = map { call ->
    when (call.name) {
        "play_song" -> player.playSong(call.arguments["query"] as? String ?: "")
        "play_random_song" -> player.playSong("__random__")
        "pause_playback" -> player.pause()
        "resume_playback" -> player.resume()
        "next_track" -> player.next()
        "previous_track" -> player.previous()
        "set_volume" -> player.setVolume((call.arguments["level"] as? Number)?.toInt() ?: 50)
        "toggle_shuffle" -> player.setShuffle(call.arguments["on"] as? Boolean ?: true)
        "set_repeat_mode" -> player.setRepeatMode(call.arguments["mode"] as? String ?: "off")
        "like_current_song" -> player.likeCurrent()
        else -> mapOf("error" to "unknown tool: ${call.name}")
    }
}
