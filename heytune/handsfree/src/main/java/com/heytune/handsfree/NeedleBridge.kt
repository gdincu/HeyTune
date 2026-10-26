package com.heytune.handsfree

import org.json.JSONObject

/**
 * JNI boundary for the Cactus C++ engine (libneedle.a + needle.h).
 *
 * The native lib loads whichever .cact it is given:
 *  - whistle.cact  -> NEEDLE_SPEECH
 *  - needle3.cact  -> NEEDLE_TEXT
 * Loading BOTH lets needle_complete() do audio->tool-calls inside the
 * engine, so the transcript never crosses JNI as anything but JSON.
 *
 * Models are downloaded on first run to app filesDir (see ModelManager)
 * and kept alive by the native layer for the process lifetime.
 */
object NeedleBridge {

    init {
        try {
            System.loadLibrary("needle_bridge")
        } catch (_: UnsatisfiedLinkError) {
            // Unit-test / stub-ABI environment without NDK libs.
        }
    }

    var nativeAvailable: Boolean = false
        private set

    /**
     * Serializes ALL native engine access. The engine keeps global state
     * (needle_set_audio mutates it per call), so overlapping calls from
     * different threads — e.g. a model reload on Dispatchers.IO while an
     * inference runs on the voice worker thread — corrupt it and crash
     * native code (SIGSEGV in project_batch_neon). Every entry point
     * below goes through this lock.
     */
    private val engineLock = Any()

    /** Hash of the tools JSON the engine was last initialised with. */
    private var loadedToolsHash: Int? = null

    /** Load whistle.cact + needle3.cact and register toolsJson. */
    external fun nativeLoad(whistlePath: String, needlePath: String, toolsJson: String): Boolean

    /** Engine JSON for audio->tool-calls (function_calls, confidence, audio_*). */
    external fun nativeCompleteAudio(samples: FloatArray, language: String?): ByteArray?

    /** Engine JSON for transcription only ({text, language, ttft_ms, ...}). */
    external fun nativeTranscribe(samples: FloatArray, language: String?): ByteArray?

    fun load(whistlePath: String, needlePath: String, toolsJson: String): Boolean {
        return try {
            synchronized(engineLock) {
                // Skip redundant reloads: re-initialising the engine on every
                // pause tap churns native state for no benefit. A changed
                // tools JSON (e.g. after an app update) still reloads.
                if (nativeAvailable && loadedToolsHash == toolsJson.hashCode()) {
                    return true
                }
                nativeAvailable = nativeLoad(whistlePath, needlePath, toolsJson)
                if (nativeAvailable) loadedToolsHash = toolsJson.hashCode()
                nativeAvailable
            }
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun completeAudio(samples: FloatArray, language: String? = VoiceTools.VOICE_LANGUAGE): VoiceResult? {
        val bytes = try {
            synchronized(engineLock) {
                nativeCompleteAudio(samples, language ?: VoiceTools.VOICE_LANGUAGE)
            }
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        return parseEngineJson(String(bytes, Charsets.UTF_8))
    }

    fun transcribe(samples: FloatArray, language: String? = VoiceTools.VOICE_LANGUAGE): VoiceResult? {
        val bytes = try {
            synchronized(engineLock) {
                nativeTranscribe(samples, language ?: VoiceTools.VOICE_LANGUAGE)
            }
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        return VoiceResult(
            audioText = o.optString("text", ""),
            audioLanguage = o.optString("language").takeIf { it.isNotEmpty() },
        )
    }

    internal fun parseEngineJson(json: String): VoiceResult {
        val o = JSONObject(json)
        fun calls(key: String): List<ToolCall> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return List(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                val args = c.optJSONObject("arguments")
                val map = mutableMapOf<String, Any?>()
                args?.keys()?.forEach { k -> map[k] = args.get(k) }
                ToolCall(c.optString("name"), map)
            }
        }
        return VoiceResult(
            functionCalls = calls("function_calls"),
            suppressedCalls = calls("suppressed_calls"),
            confidence = o.optDouble("confidence", 0.0),
            audioText = o.optString("audio_text", ""),
            audioLanguage = o.optString("audio_language").takeIf { it.isNotEmpty() },
            reasoning = o.optString("reasoning").takeIf { it.isNotEmpty() },
        )
    }
}
