package com.spineexercise.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ===================== Boot receiver =====================
// Alarms survive neither a reboot nor a package update, and a clock/timezone
// change leaves the armed instant off by the offset (the stored value is a
// local wall-clock time). Re-arm from persisted settings on any of them; all
// four actions are protected system broadcasts, so exported=false is fine.
// Re-arming is idempotent, so overlapping triggers are harmless.

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REARM_ACTIONS) return
        if (ReminderScheduler.isEnabled(context)) ReminderScheduler.schedule(context)
    }

    private companion object {
        // ACTION_TIME_CHANGED is the framework constant behind TIME_SET.
        val REARM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
