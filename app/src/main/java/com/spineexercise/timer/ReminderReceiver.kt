package com.spineexercise.timer

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

// ===================== Reminder receiver =====================
// Fired by the daily alarm-clock. All decisions are pure Kotlin
// (ReminderPolicy); this class only reads state, gates, and posts the
// notification via platform APIs (no androidx.core dependency).
//
// The alarm is a one-shot: re-arming tomorrow happens HERE first (before any
// gate can return), so the daily chain never breaks — skipping today's
// notification (already checked in) still leaves tomorrow armed.

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Cold-process safety: resolve the language (override or system locale)
        // before any user-facing string is built below.
        L10nStore.init(context)

        val enabled = ReminderScheduler.isEnabled(context)
        if (!enabled) return // stale alarm firing after the user disabled

        // Perpetuate the chain first: tomorrow at the same time.
        ReminderScheduler.schedule(context)

        val checkedInToday =
            CheckInLog.parse(CheckInStore.load(context)).has(LocalDate.now())
        if (!ReminderPolicy.shouldNotify(enabled, checkedInToday)) return

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return // permission revoked since arming

        ReminderScheduler.ensureChannel(context)
        val notification = Notification.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(L10n.s.notifTitle)
            .setContentText(L10n.s.notifBody)
            .setContentIntent(ReminderScheduler.notifOpenIntent(context))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 2001
    }
}
