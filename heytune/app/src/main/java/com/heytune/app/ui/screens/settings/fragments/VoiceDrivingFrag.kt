/*
 * Settings UI for pause-to-talk voice mode (English-only, in-app only).
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package com.heytune.app.ui.screens.settings.fragments

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heytune.app.MainActivity
import com.heytune.app.R
import com.heytune.app.constants.VoiceDrivingModeKey
import com.heytune.app.handsfree.VoiceIntegration
import com.heytune.app.ui.component.SwitchPreference
import com.heytune.app.utils.rememberPreference

@Composable
fun VoiceDrivingFrag() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val (drivingMode, onDrivingModeChange) = rememberPreference(
        key = VoiceDrivingModeKey,
        defaultValue = false,
    )
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    SwitchPreference(
        title = { Text(stringResource(R.string.voice_driving_title)) },
        description = stringResource(R.string.voice_driving_desc),
        icon = { Icon(Icons.Rounded.Mic, null) },
        checked = drivingMode,
        onCheckedChange = { enabled ->
            val act = activity ?: return@SwitchPreference
            busy = true
            progress = 0f
            VoiceIntegration.setDrivingMode(
                act,
                enabled,
                onProgress = { progress = it },
                onDone = { ok ->
                    busy = false
                    // DataStore is the source of truth; reflect it back.
                    onDrivingModeChange(ok && enabled)
                    if (!ok && enabled) {
                        Toast.makeText(
                            act,
                            act.getString(R.string.voice_models_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
        },
    )
    if (busy) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.voice_downloading_models))
        }
    }
}
