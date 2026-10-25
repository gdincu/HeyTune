package com.heytune.handsfree

import android.content.Context

/** Persisted driving-mode settings (DataStore/Proto omitted for brevity). */
data class VoiceSettings(
    val enabled: Boolean = false, // user must opt in: mic is sensitive
    val language: String? = null, // null = auto-detect (en/de/fr/es/it/nl/pl)
    val keywords: List<String> = emptyList(), // artist names for Whistle biasing
    val duckMusic: Boolean = true,
    val confirmThreshold: Double = VoiceTools.CONFIRM_AT,
) {
    companion object {
        private const val PREF = "handsfree_voice"

        fun load(ctx: Context): VoiceSettings {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return VoiceSettings(
                enabled = p.getBoolean("enabled", false),
                language = p.getString("language", null),
                keywords = p.getStringSet("keywords", emptySet())!!.toList(),
                duckMusic = p.getBoolean("duck", true),
            )
        }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putString("language", language)
            .putStringSet("keywords", keywords.toSet())
            .putBoolean("duck", duckMusic)
            .apply()
    }
}
