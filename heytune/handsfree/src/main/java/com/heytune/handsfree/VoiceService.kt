package com.heytune.handsfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat

/**
 * Button-triggered hands-free service for driving (no always-on listening).
 *
 * Loop (all on-device, no network):
 *  1. Parked with the mic RELEASED until a press arms a window
 *     (steering-wheel assistant button, notification action).
 *  2. On press: open mic, record 16kHz mono until ~1.2s silence
 *     (after speech starts), a 10s cap, or the window timeout.
 *  3. Whistle transcribes; no wake phrase required inside a window.
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
         * One-shot listen (steering-wheel assistant button, notification
         * action). Opens a wake-free window; the service stops itself
         * afterwards unless driving mode is on.
         */
        const val ACTION_LISTEN_ONCE = "com.heytune.handsfree.LISTEN_ONCE"

        /** How long a LISTEN_ONCE window stays open for the next utterance. */
        const val LISTEN_ONCE_WINDOW_MS = 20_000L

        /** Host app injects its MediaController-backed bridge before start. */
        var playerBridge: PlayerBridge? = null
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
                if (!ModelManager.modelsReady(this)) {
                    notify("Voice models not ready — enable driving mode once to download")
                    stopSelf()
                    return START_NOT_STICKY
                }
                armWindow()
                if (!running) {
                    if (!NeedleBridge.nativeAvailable) {
                        notify("Voice engine unavailable on this device/ABI")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startForeground(NOTIF_ID, notification("Listening… speak now 🎤"))
                    startVoice()
                } else {
                    notify("Listening… speak now 🎤")
                }
                return START_STICKY
            }
            else -> {
                settings = VoiceSettings.load(this)
                if (!settings.enabled || running) return START_STICKY
                startForeground(NOTIF_ID, notification("Voice ready — press 🎤 or wheel button"))
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
        worker = Thread(::loop, "handsfree-voice").apply { isDaemon = true; start() }
    }

    private fun stopVoice() {
        running = false
        listenArmed = false
        synchronized(lock) { lock.notifyAll() }
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
    // Button-triggered only: the thread parks with the mic RELEASED until a
    // press arms a window, captures one utterance, then parks again (or the
    // service stops when driving mode is off). No always-on listening.

    private val lock = java.lang.Object()

    @Volatile
    private var listenArmed = false
    private var armTime = 0L

    /** Arms one button-triggered window; wakes the parked loop thread. */
    private fun armWindow() {
        synchronized(lock) {
            listenArmed = true
            armTime = SystemClock.elapsedRealtime()
            lock.notifyAll()
        }
    }

    private fun loop() {
        while (running && !Thread.interrupted()) {
            // Park with the mic released until a button press arms a window.
            try {
                synchronized(lock) {
                    while (running && !listenArmed) lock.wait()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!running || Thread.interrupted()) return
            listenArmed = false
            captureOne()
            // Button-only use: stop after the window unless driving mode
            // keeps the service alive for the next press.
            if (!settings.enabled) {
                stopVoice()
                stopSelf()
                return
            }
        }
    }

    /**
     * One bounded capture: mic opens on button press, closes on ~1.2s of
     * silence (after speech starts), a 10s audio cap, or the window timeout.
     * Gives up after ~5s of pure silence (pressed by accident / too slow).
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

        val frame = ShortArray(WakeWordDetector.FRAME_SAMPLES)
        val window = ArrayDeque<Short>()
        var silenceStreak = 0
        var speechSeen = false

        try {
            duck() // duck as soon as we intentionally start listening
            while (running && !Thread.interrupted()) {
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
            try { record.stop() } catch (_: Exception) {}
            record.release()
            abandonDuck()
        }
        val pcm = window.toShortArray()
        // Short commands ("pause") run well under a second; only drop blips.
        if (pcm.size > sampleRate / 3 && running) handleUtterance(pcm)
    }

    private fun handleUtterance(pcm: ShortArray) {
        val samples = WakeWordDetector.toFloatMono(pcm)
        val bridge = playerBridge ?: return

        // Button-triggered window: no wake gate at all. stripWake is still
        // applied so a habitual "hey tune ..." prefix never leaks into commands.
        // 1) Engine path: audio -> tool calls in one call.
        val result = try {
            NeedleBridge.completeAudio(samples, settings.language)
        } catch (_: Exception) { null }

        if (result != null) {
            val command = VoiceTools.stripWake(result.audioText)
            if (command.isBlank()) return // nothing said
            val (decision, calls) = VoiceTools.route(result)
            when (decision) {
                VoiceDecision.EXECUTE -> {
                    bridge.dispatch(calls)
                    notify("✔ ${calls.firstOrNull()?.name}")
                }
                VoiceDecision.CONFIRM -> {
                    // Agreement upgrade: deterministic fallback agrees -> just do it.
                    val fb = CommandParser.parse(command)
                    val agreed = fb != null && calls.any { it.name == fb.name && it.arguments == fb.arguments }
                    if (agreed) {
                        bridge.dispatch(calls)
                        notify("✔ ${calls.firstOrNull()?.name} (confirmed)")
                    } else {
                        notify("Heard '${result.audioText}' — tap to confirm")
                        onConfirmRequest?.invoke(result)
                    }
                }
                VoiceDecision.REFUSE -> fallback(result.audioText, bridge)
            }
            return
        }
        // 2) No engine result: transcribe-only then fallback parse.
        val text = result?.audioText ?: return
        fallback(text, bridge)
    }

    private fun fallback(text: String, bridge: PlayerBridge) {
        val call = CommandParser.parse(text) ?: run {
            notify("Heard '$text' — no matching command")
            return
        }
        bridge.dispatch(listOf(call))
        notify("✔ ${call.name} (offline)")
    }

    /** Host UI (confirm dialog / TTS) hooks here. */
    var onConfirmRequest: ((VoiceResult) -> Unit)? = null

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
        val listen = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).setAction(ACTION_LISTEN_ONCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HeyTune")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "Listen", listen)
            .build()
    }

    private fun notify(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID + 1, notification(text))
    }
}
