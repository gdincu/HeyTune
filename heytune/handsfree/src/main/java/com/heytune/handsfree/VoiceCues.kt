package com.heytune.handsfree

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.util.Log

/**
 * Short audible cues for the pause-to-talk flow, 100% offline.
 *
 * - [playOk]: bright ascending chime, played when a command matched (or a
 *   song/artist search found hits) just before the action executes.
 * - [playError]: soft low double-tone, played when nothing matched, nothing
 *   was heard, or a song/artist search found no hits — the user should retry.
 *
 * Sounds are bundled in `:handsfree` res/raw (`voice_ok.wav`,
 * `voice_error.wav`, ~0.26s / ~0.45s). Playback is via [MediaPlayer] with a
 * [ToneGenerator] fallback if the resource is missing. Safe to call from any
 * thread (VoiceService calls from its background mic thread).
 */
object VoiceCues {
    private const val TAG = "VoiceCues"

    /**
     * Tool calls whose success is only known later, on another thread:
     * `play_song` / `play_random_song` fan out to an async library search in
     * the host app. The service must NOT beep OK at dispatch time for these —
     * the search result beeps OK (hits) or error (no hits) when it lands.
     */
    fun isDeferred(toolName: String): Boolean =
        toolName == "play_song" || toolName == "play_random_song"

    /** True when every call in the batch defers its cue to the search result. */
    fun allDeferred(calls: List<ToolCall>): Boolean =
        calls.isNotEmpty() && calls.all { isDeferred(it.name) }

    fun playOk(ctx: Context) = play(ctx, ok = true)

    fun playError(ctx: Context) = play(ctx, ok = false)

    private fun play(ctx: Context, ok: Boolean) {
        try {
            val res = if (ok) R.raw.voice_ok else R.raw.voice_error
            val mp = MediaPlayer.create(ctx.applicationContext, res)
            if (mp == null) {
                toneFallback(ok)
                return
            }
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ ->
                try { p.release() } catch (_: Exception) {}
                true
            }
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "cue play failed, falling back to tone", e)
            toneFallback(ok)
        }
    }

    private fun toneFallback(ok: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            try {
                if (ok) {
                    tg.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                } else {
                    tg.startTone(ToneGenerator.TONE_PROP_NACK, 250)
                }
                // Let the tone finish before releasing the generator.
                Thread.sleep(if (ok) 160L else 300L)
            } finally {
                try { tg.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // Last resort: stay silent, never crash voice handling for a cue.
        }
    }
}
