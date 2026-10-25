package com.heytune.handsfree

/**
 * Offline fallback parser: fast regex path used when Needle confidence is
 * below floor or the model is unavailable. Must stay in sync with
 * python/pipeline.py::parse_fallback (same commands, same order).
 */
object CommandParser {

    private val volumeTo = Regex("""\bvolume(?: to| at)?\s*(?<level>\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val shuffleState = Regex("""\bshuffle\s*(?<state>on|off)\b""", RegexOption.IGNORE_CASE)
    private val playQuery = Regex("""\b(?:play|put on|start)\b\s+(?<query>.+)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ToolCall? {
        val t = VoiceTools.stripWake(text).trim()
        if (t.isEmpty()) return null
        val low = t.lowercase()

        if (low.contains("pause") || low.contains("stop the music") || low == "stop") {
            return ToolCall("pause_playback")
        }
        if (Regex("""\b(resume|continue|unpause)\b""").containsMatchIn(t)) {
            return ToolCall("resume_playback")
        }
        if (Regex("""\b(next( song| track)?|skip( this)?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return ToolCall("next_track")
        }
        if (Regex("""\b(previous( song| track)?|go back|last song)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return ToolCall("previous_track")
        }
        volumeTo.find(t)?.let {
            val level = it.groups["level"]!!.value.toInt().coerceIn(0, 100)
            return ToolCall("set_volume", mapOf("level" to level))
        }
        if (low.contains("louder")) return ToolCall("set_volume", mapOf("level" to 70))
        if (low.contains("quieter")) return ToolCall("set_volume", mapOf("level" to 30))
        shuffleState.find(t)?.let {
            return ToolCall("toggle_shuffle", mapOf("on" to (it.groups["state"]!!.value.lowercase() == "on")))
        }
        if (low.contains("stop shuffling") || low.contains("disable shuffle")) {
            return ToolCall("toggle_shuffle", mapOf("on" to false))
        }
        if (low.startsWith("like") || (low.contains("add") && (low.contains("favorite") || low.contains("favourite")))) {
            return ToolCall("like_current_song")
        }
        playQuery.find(t)?.let {
            val q = it.groups["query"]!!.value.trim().trimEnd('.', '!', '?')
            if (q.isNotEmpty()) return ToolCall("play_song", mapOf("query" to q))
        }
        return null
    }
}
