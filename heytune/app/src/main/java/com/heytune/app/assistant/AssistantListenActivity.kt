package com.heytune.app.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.heytune.handsfree.ModelManager
import com.heytune.handsfree.NeedleBridge
import com.heytune.handsfree.VoiceService
import com.heytune.app.MainActivity

/**
 * Invisible trampoline between the assistant invocation and HeyTune voice.
 * Runs in the foreground when started, so handing off to the voice
 * foreground service is always allowed here (unlike from the session alone).
 *
 * - Models + mic ready → fires ACTION_LISTEN_ONCE and finishes.
 * - Otherwise → opens the app so the user enables driving mode once
 *   (downloads the ~45 MB models), then finishes.
 */
class AssistantListenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micOk && ModelManager.modelsReady(this) && NeedleBridge.nativeAvailable) {
            startForegroundService(
                Intent(this, VoiceService::class.java)
                    .setAction(VoiceService.ACTION_LISTEN_ONCE),
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Toast.makeText(
                this,
                "Enable Hands-free driving mode once to set up voice",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }
}
