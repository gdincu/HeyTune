package com.heytune.handsfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pause-to-talk voice service (no always-on listening, no wake word,
 * no assistant integration).
 *
 * Loop (all on-device, no network):
 *  1. Parked with the mic RELEASED until the in-app pause button arms a window.
 *     Tapping pause stops the music first, so the mic hears no background noise.
 *  2. On trigger: open mic, record 16kHz mono until ~1.2s silence
 *     (after speech starts), a 10s cap, or the window timeout.
 *  3. Whistle (English-only, language="en") transcribes; every non-empty
 *     transcript in the window is a command — no wake phrase required.
 *  4. needle_complete(audio) -> tool calls; EXECUTE>=0.50, CONFIRM shows
 *     notification/TTS, REFUSE -> fallback regex parser.
 *  5. Dispatch via PlayerBridge (MediaController -> MusicService).
 *  6. Duck music (AudioFocus) only while intentionally listening.
 *
 * Permissions: RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE.
 */
class VoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "handsfree_voice"
        const val NOTIF_ID = 2001
        const val ACTION_START = "com.heytune.handsfree.START"
        const val ACTION_STOP = "com.heytune.handsfree.STOP"

        /**
         * One-shot listen, armed ONLY by the in-app pause button. Opens a
         * wake-free window; the service stops itself afterwards unless
         * driving mode is on.
         */
        const val ACTION_LISTEN_ONCE = "com.heytune.handsfree.LISTEN_ONCE"

        /** How long a LISTEN_ONCE window stays open for the next utterance. */
        const val LISTEN_ONCE_WINDOW_MS = 20_000L

        /**
         * Cancels the current listen window: the mic closes, any captured
         * audio is dropped without inference, and no command executes.
         * The service itself keeps running (unless driving mode is off).
         * Sent when the user taps play while listening.
         */
        const val ACTION_CANCEL_WINDOW = "com.heytune.handsfree.CANCEL_WINDOW"

        /** Host app injects its MediaController-backed bridge before start. */
        var playerBridge: PlayerBridge? = null

        /**
         * True while a pause-opened window has the mic open. The app UI
         * collects this to show a green mic badge, mirroring the system
         * privacy indicator.
         */
        private val _isListening = MutableStateFlow(false)
        val isListening: StateFlow<Boolean> = _isListening

        private const val TAG = "HeyTuneVoice"
    }

    private var running = false
    private var worker: Thread? = null
    private var audioFocus: AudioFocusRequest? = null

    var settings = VoiceSettings()
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVoice()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN_ONCE -> {
                settings = VoiceSettings.load(this)
                // Promote to foreground immediately when started via
                // startForegroundService (e.g. MusicService remote pause).
                // Otherwise an early return (model not ready / no native)
                // would trigger ForegroundServiceDidNotStartInTimeException.
                val needsFg = !running
                if (needsFg) {
                    startForeground(NOTIF_ID, notification("Listening… speak now 🎤"))
                }
                if (!ModelManager.modelsReady(this)) {
                    Log.w(TAG, "LISTEN_ONCE: models not ready")
                    notify("Voice models not ready — enable driving mode once to download")
                    if (needsFg) stopSelf()
                    return START_NOT_STICKY
                }
                Log.i(TAG, "LISTEN_ONCE: arming window (running=$running)")
                // Self-heal before arming: a dead worker leaves a stale
                // claim that would ignore this (and every later) arm.
                if (running) ensureWorker()
                armWindow()
                if (!running) {
                    if (!NeedleBridge.nativeAvailable) {
                        Log.w(TAG, "LISTEN_ONCE: native engine unavailable")
                        notify("Voice engine unavailable on this device/ABI")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    // Already foregrounded above; just start the worker
                    startVoice()
                } else {
                    Log.i(TAG, "LISTEN_ONCE: re-armed while running")
                }
                return START_STICKY
            }
            ACTION_CANCEL_WINDOW -> {
                Log.i(TAG, "CANCEL_WINDOW")
                cancelWindow()
                if (!running) stopSelf()
                return START_STICKY
            }
            else -> {
                settings = VoiceSettings.load(this)
                if (!settings.enabled || running) return START_STICKY
                startForeground(NOTIF_ID, notification("Voice ready — tap pause, then speak"))
                startVoice()
                return START_STICKY
            }
        }
    }

    private fun startVoice() {
        if (!NeedleBridge.nativeAvailable) {
            notify("Voice engine unavailable on this device/ABI")
            stopSelf()
            return
        }
        running = true
        worker = newWorker()
    }

    private fun stopVoice() {
        running = false
        _isListening.value = false
        listenArmed = false
        synchronized(lock) {
            windowClaimed = false
            lock.notifyAll()
        }
        worker?.interrupt()
        worker = null
        abandonDuck()
    }

    override fun onDestroy() {
        stopVoice()
        super.onDestroy()
    }

    // -- main loop ----------------------------------------------------------
    //
    // Pause-triggered only: the thread parks with the mic RELEASED until the
    // in-app pause button arms a window, captures one utterance, then parks
    // again (or the service stops when driving mode is off). No always-on
    // listening, no wake word, no assistant integration.

    private val lock = java.lang.Object()

    @Volatile
    private var listenArmed = false
    private var armTime = 0L

    /**
     * Set when the user taps play mid-window: the in-flight capture
     * aborts, its audio is dropped without inference, and nothing executes.
     */
    @Volatile
    private var cancelRequested = false

    /**
     * Synchronous "window in flight" claim. Unlike [_isListening] (set only
     * after AudioRecord starts, ~50-100ms later), this is set inside
     * [armWindow]'s synchronized block, closing the race where two
     * LISTEN_ONCE intents ~18ms apart both pass the dedupe checks and the
     * second queues a spare window. Cleared when the capture fully ends
     * (see [loop]), on [cancelWindow] while parked, and on [stopVoice].
     */
    @Volatile
    private var windowClaimed = false

    /**
     * Arms one pause-triggered window; wakes the parked loop thread.
     *
     * Deduplicates: one pause press fans out to TWO LISTEN_ONCE intents
     * (in-app pause button via VoiceIntegration.listenOnce + MusicService
     * onIsPlayingChanged auto-arm). Without this, the second intent queues
     * a spare window that opens the mic AFTER the first window's command
     * already resumed playback / changed the track — the "STT icon flashes
     * during playback / when the next song starts" bug. While the mic is
     * open, a window is queued, or one is being torn down, further arms
     * are ignored.
     */
    private fun armWindow() {
        synchronized(lock) {
            if (_isListening.value || listenArmed || windowClaimed) {
                Log.i(TAG, "armWindow: ignored (listening=${_isListening.value} armed=$listenArmed claimed=$windowClaimed)")
                return
            }
            listenArmed = true
            windowClaimed = true
            cancelRequested = false
            armTime = SystemClock.elapsedRealtime()
            lock.notifyAll()
        }
    }

    /**
     * Cancels the current window (user tapped play): disarm so no new
     * capture starts, and flag the in-flight capture so the mic closes
     * promptly (within one audio frame). The service itself keeps running.
     *
     * While a capture is in flight [windowClaimed] stays set so an arm
     * landing mid-teardown can't start a window on stale audio; the loop
     * clears the claim when the capture fully ends.
     *
     * NOTE: never interrupts the worker here. Interrupting while parked in
     * lock.wait() used to kill the worker thread with running=true, after
     * which every future arm was ignored forever. notifyAll() wakes the
     * parked thread; cancelRequested stops an in-flight capture.
     */
    private fun cancelWindow() {
        cancelRequested = true
        listenArmed = false
        synchronized(lock) {
            if (!_isListening.value) windowClaimed = false
            lock.notifyAll()
        }
    }

    /**
     * Self-heal: if the worker thread died while [running] stayed true
     * (e.g. an older build's cancel interrupt, or a native crash contained
     * below the loop), stale flags would ignore every future arm forever.
     * Detect that, reset the flags, and restart so the next pause works.
     * Called on the LISTEN_ONCE path before arming.
     */
    private fun ensureWorker() {
        val w = worker
        if (w != null && w.isAlive) return
        Log.w(TAG, "ensureWorker: worker dead while running — restarting")
        synchronized(lock) {
            listenArmed = false
            windowClaimed = false
            cancelRequested = false
            lock.notifyAll()
        }
        _isListening.value = false
        worker = newWorker()
    }

    private fun newWorker(): Thread = Thread(::loop, "handsfree-voice").apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
            Log.w(TAG, "voice worker died on $t", e)
        }
        start()
    }

    private fun loop() {
        while (running && !Thread.interrupted()) {
            // Park with the mic released until the pause button arms a window.
            try {
                synchronized(lock) {
                    while (running && !listenArmed) lock.wait()
                }
            } catch (_: InterruptedException) {
                // A cancel/stop nudge, never a reason to die: re-check state
                // and keep waiting unless the service itself is stopping.
                // (Returning here with running=true used to kill the worker
                // for good: every later arm was then ignored with a stale
                // claim set — STT "stops working altogether".)
                if (!running) {
                    synchronized(lock) { windowClaimed = false }
                    return
                }
                continue
            }
            if (!running || Thread.interrupted()) {
                synchronized(lock) { windowClaimed = false }
                return
            }
            synchronized(lock) { listenArmed = false }
            try {
                captureOne()
            } catch (t: Throwable) {
                // One bad window (mic error, duck failure, inference throw,
                // ...) must never kill the worker: drop it and stay alive
                // for the next pause press. The claim is always cleared in
                // finally so future arms are never ignored forever.
                Log.w(TAG, "capture failed, dropping window", t)
            } finally {
                synchronized(lock) { windowClaimed = false }
            }
            // Pause-only use: stop after the window unless driving mode
            // keeps the service alive for the next pause press.
            if (!settings.enabled) {
                stopVoice()
                stopSelf()
                return
            }
        }
    }

    /**
     * One bounded capture: mic opens on pause press, closes on ~1.2s of
     * silence (after speech starts), a 10s audio cap, or the window timeout.
     * Gives up after ~5s of pure silence (paused by accident / too slow).
     */
    private fun captureOne() {
        val sampleRate = WakeWordDetector.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) {
            notify("Microphone permission missing")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) return
        record.startRecording()
        _isListening.value = true

        val frame = ShortArray(WakeWordDetector.FRAME_SAMPLES)
        val window = ArrayDeque<Short>()
        var silenceStreak = 0
        var speechSeen = false

        try {
            duck() // duck as soon as we intentionally start listening
            while (running && !Thread.interrupted() && !cancelRequested) {
                val elapsed = SystemClock.elapsedRealtime() - armTime
                if (elapsed > LISTEN_ONCE_WINDOW_MS) break
                if (!speechSeen && elapsed > 5_000) break // pressed, never spoke
                if (window.size >= sampleRate * 10) break // 10s audio cap
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                val speech = WakeWordDetector.isSpeech(frame)
                for (i in 0 until n) {
                    if (window.size >= sampleRate * 10) window.removeFirst()
                    window.addLast(frame[i])
                }
                if (speech) {
                    speechSeen = true
                    silenceStreak = 0
                } else {
                    silenceStreak++
                }
                // End of utterance: ~1.2s of silence, but only after speech
                // started (gives the driver a beat to speak after pressing).
                if (speechSeen && silenceStreak >= WakeWordDetector.silenceFramesToClose) break
            }
        } finally {
            _isListening.value = false
            try { record.stop() } catch (_: Exception) {}
            record.release()
            abandonDuck()
        }
        // A cancelled window (user tapped play) is dropped altogether:
        // no inference, no dispatch. Never act on stale audio.
        val cancelled = cancelRequested
        cancelRequested = false
        if (cancelled) {
            Log.i(TAG, "capture: window cancelled, dropping ${window.size} samples")
            window.clear()
            return
        }
        val pcm = window.toShortArray()
        Log.i(TAG, "capture: done samples=${pcm.size} speechSeen=$speechSeen running=$running")
        // Short commands ("pause") run well under a second; only drop blips.
        if (pcm.size > sampleRate / 3 && running) {
            handleUtterance(pcm)
        } else {
            Log.i(TAG, "capture: dropped (too short or stopped)")
        }
    }

    private fun handleUtterance(pcm: ShortArray) {
        val samples = WakeWordDetector.toFloatMono(pcm)
        val bridge = playerBridge ?: run {
            Log.w(TAG, "utterance: no playerBridge, dropping ${pcm.size} samples")
            return
        }

        // Pause-triggered window: no wake gate at all — the whole transcript
        // is the command. Whistle is English-only (VoiceTools.VOICE_LANGUAGE).
        // 1) Engine path: audio -> tool calls in one call.
        val result = try {
            NeedleBridge.completeAudio(samples, VoiceTools.VOICE_LANGUAGE)
        } catch (e: Exception) {
            Log.w(TAG, "utterance: completeAudio threw", e)
            null
        }

        if (result == null) {
            // 2) No engine result: try transcribe-only, then regex fallback.
            // (Previously this path returned silently — the most common
            // "STT does nothing" report.)
            Log.w(TAG, "utterance: completeAudio returned null, trying transcribe")
            val t = try {
                NeedleBridge.transcribe(samples, VoiceTools.VOICE_LANGUAGE)
            } catch (e: Exception) {
                Log.w(TAG, "utterance: transcribe threw", e)
                null
            }
            val text = t?.audioText?.trim().orEmpty()
            if (text.isBlank()) {
                Log.w(TAG, "utterance: no transcript, dropping")
                notify("Didn't catch that — try again")
                VoiceCues.playError(this)
                return
            }
            Log.i(TAG, "utterance: transcribe-only '$text'")
            fallback(text, bridge)
            return
        }
        val command = result.audioText.trim()
        if (command.isBlank()) {
            Log.i(TAG, "utterance: blank transcript, ignoring")
            notify("Didn't catch that — try again")
            VoiceCues.playError(this)
            return
        }
        val (decision, calls) = VoiceTools.route(result)
        Log.i(TAG, "utterance: '$command' decision=$decision calls=$calls conf=${result.confidence}")
        when (decision) {
            VoiceDecision.EXECUTE -> {
                try {
                    bridge.dispatch(calls)
                } catch (e: Exception) {
                    Log.w(TAG, "utterance: dispatch failed", e)
                    notify("Command failed — try again")
                    VoiceCues.playError(this)
                    return
                }
                // play_song / play_random_song cue later, from the async
                // search result (match -> OK before playing, miss -> error).
                if (!VoiceCues.allDeferred(calls)) VoiceCues.playOk(this)
                if (calls.none { it.name == "pause_playback" }) {
                    try { bridge.dispatch(listOf(ToolCall("resume_playback", emptyMap()))) } catch (_: Exception) {}
                }
                notify("✔ ${calls.firstOrNull()?.name}")
            }
            VoiceDecision.CONFIRM -> {
                // Agreement upgrade: deterministic fallback agrees -> just do it.
                val fb = CommandParser.parse(command)
                val agreed = fb != null && calls.any { it.name == fb.name && it.arguments == fb.arguments }
                if (agreed) {
                    try {
                        bridge.dispatch(calls)
                    } catch (e: Exception) {
                        Log.w(TAG, "utterance: dispatch failed", e)
                        notify("Command failed — try again")
                        VoiceCues.playError(this)
                        return
                    }
                    if (!VoiceCues.allDeferred(calls)) VoiceCues.playOk(this)
                    if (calls.none { it.name == "pause_playback" }) {
                        try { bridge.dispatch(listOf(ToolCall("resume_playback", emptyMap()))) } catch (_: Exception) {}
                    }
                    notify("✔ ${calls.firstOrNull()?.name} (confirmed)")
                } else {
                    notify("Heard '${result.audioText}' — tap to confirm")
                    VoiceCues.playError(this)
                }
            }
            VoiceDecision.REFUSE -> fallback(result.audioText, bridge)
        }
    }

    private fun fallback(text: String, bridge: PlayerBridge) {
        Log.i(TAG, "fallback: '$text'")
        val call = CommandParser.parse(text) ?: run {
            Log.i(TAG, "fallback: no matching command for '$text'")
            notify("Heard '$text' — no matching command")
            VoiceCues.playError(this)
            return
        }
        try {
            bridge.dispatch(listOf(call))
        } catch (e: Exception) {
            Log.w(TAG, "fallback: dispatch failed", e)
            notify("Command failed — try again")
            VoiceCues.playError(this)
            return
        }
        if (!VoiceCues.allDeferred(listOf(call))) VoiceCues.playOk(this)
        if (call.name != "pause_playback") {
            try { bridge.dispatch(listOf(ToolCall("resume_playback", emptyMap()))) } catch (_: Exception) {}
        }
        notify("✔ ${call.name} (offline)")
    }

    // -- audio focus / notification ------------------------------------------

    private fun duck() {
        if (!settings.duckMusic) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).build()
            audioFocus = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonDuck() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        audioFocus?.let {
            if (Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(it)
            audioFocus = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hands-free voice", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HeyTune")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID + 1, notification(text))
    }
}
