package com.heytune.app.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.heytune.handsfree.VoiceService

/** Session host, bound explicitly via assistant_interaction.xml. */
class HeyTuneAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        HeyTuneAssistantSession(this)
}

/**
 * Headless session: no assistant UI of its own. A press routes straight
 * into one HeyTune listen window (same path as the notification action,
 * which also works when the screen is off or Android Auto is connected).
 */
class HeyTuneAssistantSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // AssistantListenActivity is translucent + finishes immediately, so
        // the user only ever sees the "Listening… speak now" notification.
        // (Launched via the session so background-activity-start rules allow it.)
        startAssistantActivity(Intent(context, AssistantListenActivity::class.java))
        hide()
    }
}
