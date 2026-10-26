package com.heytune.handsfree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Voice-pipeline checks. Run in Android Studio (needs SDK). */
class HandsFreeTest {

    @Test fun englishOnly() {
        assertEquals("en", VoiceTools.VOICE_LANGUAGE)
    }

    @Test fun fallbackCommands() {
        assertEquals("play_song", CommandParser.parse("play Bohemian Rhapsody")?.name)
        assertEquals("pause_playback", CommandParser.parse("pause")?.name)
        assertEquals("resume_playback", CommandParser.parse("resume")?.name)
        assertEquals("next_track", CommandParser.parse("next song")?.name)
        assertEquals("previous_track", CommandParser.parse("go back")?.name)
        assertEquals(mapOf("level" to 50), CommandParser.parse("volume to 50")?.arguments)
        assertEquals(mapOf("on" to true), CommandParser.parse("shuffle on")?.arguments)
        assertEquals(mapOf("on" to false), CommandParser.parse("stop shuffling")?.arguments)
        assertNull(CommandParser.parse("hello world"))
        assertNull(CommandParser.parse(""))
    }

    @Test fun routing() {
        val exec = VoiceResult(listOf(ToolCall("pause_playback")), confidence = 0.94)
        assertEquals(VoiceDecision.EXECUTE, VoiceTools.route(exec).first)
        val confirm = VoiceResult(listOf(ToolCall("pause_playback")), confidence = 0.43)
        assertEquals(VoiceDecision.CONFIRM, VoiceTools.route(confirm).first)
        assertEquals(VoiceDecision.REFUSE, VoiceTools.route(VoiceResult()).first)
    }

    @Test fun dispatch() {
        val player = FakePlayer()
        listOf(ToolCall("play_song", mapOf("query" to "X"))).dispatchOn(player)
        assertEquals(listOf("play:X"), player.calls)
    }

    class FakePlayer : SimplePlayer {
        val calls = mutableListOf<String>()
        override fun playSong(query: String): Any? = "play:$query".also { calls += it }
        override fun pause(): Any? = null
        override fun resume(): Any? = null
        override fun next(): Any? = null
        override fun previous(): Any? = null
        override fun setVolume(level: Int): Any? = null
        override fun setShuffle(on: Boolean): Any? = null
        override fun likeCurrent(): Any? = null
    }
}
