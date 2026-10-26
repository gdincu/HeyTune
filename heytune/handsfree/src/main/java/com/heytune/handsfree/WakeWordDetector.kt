package com.heytune.handsfree

import kotlin.math.sqrt

/**
 * Pause-to-talk gate: RMS energy VAD only, no wake phrase.
 *
 * Audio path: AudioRecord @16kHz mono -> 80ms frames (1280 samples) ->
 * VAD -> ring buffer -> Whistle (English-only) transcribe.
 *
 * Trigger model: the user taps pause (music stops, so the mic hears no
 * background noise), which opens one listen window. Nothing is always
 * listening and no trigger word is awaited.
 */
object WakeWordDetector {

    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 1280 // 80ms, matches Whistle's frame rate

    /** RMS threshold above which a frame counts as speech. Tune per device. */
    var vadThresholdRms: Float = 0.02f

    /** Silence frames that close the window. */
    var silenceFramesToClose: Int = 15 // ~1.2s

    fun rms(frame: ShortArray, offset: Int = 0, length: Int = frame.size): Float {
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = frame[i] / 32768.0
            sum += s * s
        }
        return sqrt(sum / length).toFloat()
    }

    fun isSpeech(frame: ShortArray): Boolean = rms(frame) > vadThresholdRms

    /** Peak-normalise int16 mono to [-1,1] floats for needle_transcribe. */
    fun toFloatMono(pcm: ShortArray): FloatArray =
        FloatArray(pcm.size) { abs(pcm[it] / 32768f).coerceAtMost(1f) * if (pcm[it] < 0) -1f else 1f }

    private fun abs(x: Float): Float = if (x < 0) -x else x
}
