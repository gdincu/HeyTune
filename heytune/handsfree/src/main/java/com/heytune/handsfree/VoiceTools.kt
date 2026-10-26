package com.heytune.handsfree

/** Single music-control tool call, mirrors app/src/main/assets/handsfree_tools.json. */
data class ToolCall(val name: String, val arguments: Map<String, Any?> = emptyMap())

/** Needle complete() response subset we route on. */
data class VoiceResult(
    val functionCalls: List<ToolCall> = emptyList(),
    val suppressedCalls: List<ToolCall> = emptyList(),
    val confidence: Double = 0.0,
    val audioText: String = "",
    val audioLanguage: String? = null,
    val reasoning: String? = null,
)

enum class VoiceDecision { EXECUTE, CONFIRM, REFUSE }

object VoiceTools {
    // Calibrated 2026-10-25 on Needle 3 base: play 0.855, next 0.836,
    // volume 0.644, pause 0.613, shuffle 0.562, resume 0.462.
    // 0.50 executes common driving commands; rest go to confirm UI.
    // Fine-tuning on these 8 tools is the proper fix (+18-36 pts).
    const val EXECUTE_AT = 0.50
    const val CONFIRM_AT = 0.10 // engine floor: below -> suppressed_calls

    /** Whistle is fixed to English: no auto-detect, no other languages. */
    const val VOICE_LANGUAGE = "en"

    fun route(result: VoiceResult): Pair<VoiceDecision, List<ToolCall>> {
        if (result.functionCalls.isNotEmpty() && result.confidence >= EXECUTE_AT) {
            return VoiceDecision.EXECUTE to result.functionCalls
        }
        val held = result.functionCalls.ifEmpty { result.suppressedCalls }
        if (held.isNotEmpty()) return VoiceDecision.CONFIRM to held
        return VoiceDecision.REFUSE to emptyList()
    }
}
