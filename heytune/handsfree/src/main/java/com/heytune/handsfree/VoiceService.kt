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
import androidx.core.app.NotificationCompat

/**
 * Always-on hands-free service for driving.
 *
 * Loop (all on-device, no network):
 *  1. AudioRecord 16kHz mono, 80ms frames; VAD gates Whistle.
 *  2. On speech: buffer until ~1.2s silence, transcribe via NeedleBridge.
 *  3. Require wake phrase ("hey tune", word-boundary); ignore everything else (music!).
 *  4. needle_complete(audio) -> tool calls; EXECUTE>=0.50, CONFIRM shows
 *     notification/TTS, REFUSE -> fallback regex parser.
 *  5. Dispatch via PlayerBridge (MediaController -> MusicService).
 *  6. Duck music (AudioFocus) while listening/confirming.
 *
 * Permissions: RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE.
 * Battery: VAD keeps Whistle off; Whistle keeps Needle off; use
 * --audio-depth / small Needle subnetwork (8L) for the car.
 */
class VoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "handsfree_voice"
        const val NOTIF_ID = 2001
        const val ACTION_START = "com.heytune.handsfree.START"
        const val ACTION_STOP = "com.heytune.handsfree.STOP"

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
            else -> {
                settings = VoiceSettings.load(this)
                if (!settings.enabled || running) return START_STICKY
                startForeground(NOTIF_ID, notification("Listening for 'hey tune'…"))
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
        worker?.interrupt()
        worker = null
        abandonDuck()
    }

    override fun onDestroy() {
        stopVoice()
        super.onDestroy()
    }

    // -- main loop ----------------------------------------------------------

    private fun loop() {
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
        var speechStreak = 0
        var silenceStreak = 0
        var capturing = false

        try {
            while (running && !Thread.interrupted()) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                val speech = WakeWordDetector.isSpeech(frame)

                if (!capturing) {
                    if (speech && ++speechStreak >= WakeWordDetector.speechFramesToOpen) {
                        capturing = true
                        silenceStreak = 0
                        window.clear()
                        duck()
                    } else if (!speech) speechStreak = 0
                    continue
                }
                // capturing: append, watch for end of utterance
                for (i in 0 until n) {
                    if (window.size >= sampleRate * 10) window.removeFirst() // 10s cap
                    window.addLast(frame[i])
                }
                if (speech) silenceStreak = 0 else silenceStreak++
                if (silenceStreak >= WakeWordDetector.silenceFramesToClose || window.size >= sampleRate * 10) {
                    capturing = false
                    speechStreak = 0
                    val pcm = window.toShortArray()
                    window.clear()
                    abandonDuck()
                    if (pcm.size > sampleRate) handleUtterance(pcm) // >1s only
                }
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    private fun handleUtterance(pcm: ShortArray) {
        val samples = WakeWordDetector.toFloatMono(pcm)
        val bridge = playerBridge ?: return

        // 1) Engine path: audio -> tool calls in one call.
        val result = try {
            NeedleBridge.completeAudio(samples, settings.language)
        } catch (_: Exception) { null }

        if (result != null && VoiceTools.hasWake(result.audioText)) {
            val command = VoiceTools.stripWake(result.audioText)
            if (command.isBlank()) return // bare "hey tune": attention, not a command
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
        // 2) No engine / no wake in engine text: transcribe-only then gate.
        // (Keeps music from triggering actions: require wake word.)
        val text = result?.audioText ?: return
        if (!VoiceTools.hasWake(text)) return // ignore cabin/music audio
        if (VoiceTools.stripWake(text).isBlank()) return // bare "hey tune"
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

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HeyTune")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun notify(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID + 1, notification(text))
    }
}
