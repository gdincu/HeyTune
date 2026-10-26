package com.heytune.handsfree

/**
 * Offline fallback parser: fast regex path used when Needle confidence is
 * below floor or the model is unavailable.
 * No wake word: every utterance in the pause-opened window is a command.
 */
object CommandParser {

    private val volumeTo = Regex("""\bvolume(?: to| at)?\s*(?<level>\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val shuffleState = Regex("""\bshuffle\s*(?<state>on|off)\b""", RegexOption.IGNORE_CASE)
    private val repeatState = Regex("""\brepeat\s*(?<mode>off|all|one)\b""", RegexOption.IGNORE_CASE)
    private val playQuery = Regex("""\b(?:play|put on|start)\b\s+(?<query>.+)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ToolCall? {
        val t = text.trim()
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
        repeatState.find(t)?.let {
            return ToolCall("set_repeat_mode", mapOf("mode" to it.groups["mode"]!!.value.lowercase()))
        }
        if (low.contains("repeat this song") || low.contains("repeat one song") ||
            low.contains("loop this") || low.contains("loop one")
        ) {
            return ToolCall("set_repeat_mode", mapOf("mode" to "one"))
        }
        if (low.contains("repeat everything") || low.contains("repeat all songs") ||
            low.contains("loop all") || low.contains("loop everything")
        ) {
            return ToolCall("set_repeat_mode", mapOf("mode" to "all"))
        }
        if (low.contains("stop repeating") || low.contains("don't repeat") ||
            low.contains("do not repeat") || low.contains("repeat off")
        ) {
            return ToolCall("set_repeat_mode", mapOf("mode" to "off"))
        }
        if (low.startsWith("like") || low.contains("like this song") ||
            low.contains("like the song") ||
            (low.contains("add") && (low.contains("favorite") || low.contains("favourite")))
        ) {
            return ToolCall("like_current_song")
        }
        playQuery.find(t)?.let {
            val q = it.groups["query"]!!.value.trim().trimEnd('.', '!', '?')
            if (q.isNotEmpty()) return ToolCall("play_song", mapOf("query" to q))
        }
        return null
    }
}
