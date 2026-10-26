package com.heytune.handsfree

import android.content.Context

/** Persisted driving-mode settings (DataStore/Proto omitted for brevity). */
data class VoiceSettings(
    val enabled: Boolean = false, // user must opt in: mic is sensitive
    val language: String = VoiceTools.VOICE_LANGUAGE, // fixed "en": Whistle English-only
    val duckMusic: Boolean = true,
    val confirmThreshold: Double = VoiceTools.CONFIRM_AT,
) {
    companion object {
        private const val PREF = "handsfree_voice"

        fun load(ctx: Context): VoiceSettings {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return VoiceSettings(
                enabled = p.getBoolean("enabled", false),
                // Force "en" even if an old install stored null/auto-detect.
                language = p.getString("language", VoiceTools.VOICE_LANGUAGE)
                    ?: VoiceTools.VOICE_LANGUAGE,
                duckMusic = p.getBoolean("duck", true),
            )
        }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putString("language", VoiceTools.VOICE_LANGUAGE)
            .putBoolean("duck", duckMusic)
            .apply()
    }
}
