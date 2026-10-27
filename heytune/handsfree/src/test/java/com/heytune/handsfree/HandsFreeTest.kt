package com.heytune.handsfree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(mapOf("mode" to "off"), CommandParser.parse("repeat off")?.arguments)
        assertEquals(mapOf("mode" to "all"), CommandParser.parse("repeat all")?.arguments)
        assertEquals(mapOf("mode" to "one"), CommandParser.parse("repeat one")?.arguments)
        assertEquals(mapOf("mode" to "one"), CommandParser.parse("repeat this song")?.arguments)
        assertEquals("like_current_song", CommandParser.parse("like this song")?.name)
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

    @Test fun cueDeferral() {
        // play_song / play_random_song resolve async in the host app: the
        // service must not beep OK at dispatch time, the search result beeps.
        assertTrue(VoiceCues.isDeferred("play_song"))
        assertTrue(VoiceCues.isDeferred("play_random_song"))
        assertFalse(VoiceCues.isDeferred("pause_playback"))
        assertFalse(VoiceCues.isDeferred("next_track"))
        assertTrue(VoiceCues.allDeferred(listOf(ToolCall("play_song", mapOf("query" to "X")))))
        assertFalse(VoiceCues.allDeferred(listOf(ToolCall("pause_playback"))))
        assertFalse(VoiceCues.allDeferred(emptyList()))
    }

    @Test fun dispatch() {
        val player = FakePlayer()
        listOf(ToolCall("play_song", mapOf("query" to "X"))).dispatchOn(player)
        assertEquals(listOf("play:X"), player.calls)
        listOf(ToolCall("set_repeat_mode", mapOf("mode" to "one"))).dispatchOn(player)
        assertEquals(listOf("play:X", "repeat:one"), player.calls)
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
        override fun setRepeatMode(mode: String): Any? = "repeat:$mode".also { calls += it }
        override fun likeCurrent(): Any? = null
    }
}
