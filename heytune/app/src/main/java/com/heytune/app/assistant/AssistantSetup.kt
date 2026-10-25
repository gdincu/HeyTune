package com.heytune.app.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.os.Build

/** Helpers around the system ASSISTANT role (API 29+). */
object AssistantSetup {
    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    }

    fun isDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.getRoleHolders(RoleManager.ROLE_ASSISTANT).contains(context.packageName)
    }

    /** Shows the system dialog asking to make HeyTune the assistant. */
    fun requestRole(activity: Activity, onDone: (granted: Boolean) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onDone(false)
            return
        }
        val rm = activity.getSystemService(RoleManager::class.java) ?: run {
            onDone(false)
            return
        }
        if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            onDone(false)
            return
        }
        rm.requestRole(
            RoleManager.ROLE_ASSISTANT,
            activity,
            0,
            activity.mainExecutor,
        ) { onDone(it == activity.packageName) }
    }
}
