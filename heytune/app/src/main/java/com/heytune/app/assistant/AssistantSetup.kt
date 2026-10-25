package com.heytune.app.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.os.Build

/** Helpers around the system ASSISTANT role (API 29+).
 *
 * Uses only public RoleManager APIs:
 * - isRoleAvailable to check support,
 * - isRoleHeld to check if HeyTune is the default assistant,
 * - createRequestRoleIntent to ask the user (via
 *   Activity-Result launcher in the settings UI).
 *
 * NOTE: RoleManager.getRoleHolders and the old
 * `requestRole(role, activity, executor, callback)` overloads are
 * `@hide` / system-only and are NOT in the public SDK, so they fail
 * compilation with `compileSdk = 37` ("Unresolved reference").
 */
object AssistantSetup {
    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    }

    fun isDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return try {
            rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } catch (_: Exception) {
            false
        }
    }

    /** Public intent that prompts the user to make HeyTune the assistant. */
    fun createRequestIntent(context: Context): android.content.Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }

    /** Backwards-compatible fire-and-forget launcher (no result callback).
     *
     * Prefer [createRequestIntent] + `rememberLauncherForActivityResult` so
     * the caller can refresh [isDefaultAssistant] when the system dialog
     * returns. This overload is kept so older call sites still compile.
     */
    fun requestRole(activity: Activity, onDone: (granted: Boolean) -> Unit = {}) {
        val intent = createRequestIntent(activity) ?: run {
            onDone(false)
            return
        }
        // No result callback is available from a plain Activity reference
        // without an ActivityResultLauncher; launch and report current state.
        // Callers using Compose should use createRequestIntent() directly.
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, REQUEST_CODE)
        // Best-effort: caller refreshes via isDefaultAssistant() on resume/result.
        onDone(isDefaultAssistant(activity))
    }

    const val REQUEST_CODE = 1977

    /** Forward this from `onActivityResult` if you used [requestRole]. */
    fun onActivityResult(requestCode: Int, activity: Activity, onDone: (granted: Boolean) -> Unit) {
        if (requestCode != REQUEST_CODE) return
        onDone(isDefaultAssistant(activity))
    }
}
