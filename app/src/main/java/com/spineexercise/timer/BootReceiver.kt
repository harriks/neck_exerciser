package com.spineexercise.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ===================== Boot receiver =====================
// Alarms do not survive a reboot; re-arm from persisted settings when the
// user has reminders enabled (official pattern: exported=false works for
// the protected BOOT_COMPLETED broadcast).

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ReminderScheduler.isEnabled(context)) ReminderScheduler.schedule(context)
    }
}
