package com.spineexercise.timer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

// ===================== Reminder receiver =====================
// Fired by the (inexact) daily alarm. All decisions are pure Kotlin
// (ReminderPolicy); this class only reads state, gates, and posts the
// notification via platform APIs (no androidx.core dependency).

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val checkedInToday =
            CheckInLog.parse(CheckInStore.load(context)).has(LocalDate.now())
        if (!ReminderPolicy.shouldNotify(ReminderScheduler.isEnabled(context), checkedInToday)) return

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return // permission revoked since arming

        ReminderScheduler.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("🦴 颈椎锻炼时间到")
            .setContentText("几分钟的温柔锻炼，让颈椎放松一下")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 2001
    }
}
