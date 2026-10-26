package com.heytune.handsfree

import android.content.Context

/** Persisted voice settings. */
data class VoiceSettings(
    val enabled: Boolean = false, // user must opt in: mic is sensitive
    val duckMusic: Boolean = true,
) {
    companion object {
        private const val PREF = "handsfree_voice"

        fun load(ctx: Context): VoiceSettings {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return VoiceSettings(
                enabled = p.getBoolean("enabled", false),
                duckMusic = p.getBoolean("duck", true),
            )
        }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putBoolean("duck", duckMusic)
            .apply()
    }
}
