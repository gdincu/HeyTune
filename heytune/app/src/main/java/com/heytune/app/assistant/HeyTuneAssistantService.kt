package com.heytune.app.assistant

import android.service.voice.VoiceInteractionService

/**
 * Entry point that makes HeyTune selectable as the system digital
 * assistant (Settings → Apps → Default apps → Digital assistant app).
 * Only the default assistant receives push-to-talk (steering-wheel button),
 * long-press-home/gesture and headset voice-dial invocations.
 *
 * The heavy lifting stays in :handsfree (models, Needle, VoiceService);
 * this service just routes invocations there via a headless session.
 */
class HeyTuneAssistantService : VoiceInteractionService()
