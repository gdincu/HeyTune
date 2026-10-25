package com.heytune.handsfree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors tests/test_pipeline.py. Run in Android Studio (needs SDK). */
class HandsFreeTest {

    @Test fun wakeGate() {
        assertTrue(VoiceTools.hasWake("hey tune play X"))
        assertTrue(VoiceTools.hasWake("Hey Tune, pause"))
        assertTrue(VoiceTools.hasWake("hey tunes next song")) // plural tolerated
        assertTrue(!VoiceTools.hasWake("play X"))
        assertTrue(!VoiceTools.hasWake("hey play X")) // bare "hey" must NOT wake
        assertTrue(!VoiceTools.hasWake("they went home")) // word boundary
        assertEquals("play X", VoiceTools.stripWake("hey tune play X"))
        assertEquals("", VoiceTools.stripWake("hey tune"))
    }

    @Test fun fallbackCommands() {
        assertEquals("play_song", CommandParser.parse("hey tune play Bohemian Rhapsody")?.name)
        assertEquals("pause_playback", CommandParser.parse("hey tune pause")?.name)
        assertEquals("resume_playback", CommandParser.parse("hey tune resume")?.name)
        assertEquals("next_track", CommandParser.parse("hey tune next song")?.name)
        assertEquals("previous_track", CommandParser.parse("hey tune go back")?.name)
        assertEquals(mapOf("level" to 50), CommandParser.parse("hey tune volume to 50")?.arguments)
        assertEquals(mapOf("on" to true), CommandParser.parse("hey tune shuffle on")?.arguments)
        assertEquals(mapOf("on" to false), CommandParser.parse("hey tune stop shuffling")?.arguments)
        assertNull(CommandParser.parse("hey tune hello world"))
        assertNull(CommandParser.parse("hey tune"))
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
