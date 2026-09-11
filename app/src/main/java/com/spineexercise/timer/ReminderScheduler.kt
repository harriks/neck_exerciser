package com.spineexercise.timer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

// ===================== Reminder scheduling (Android glue) =====================
// Decisions live in ReminderPolicy (pure Kotlin); this object only turns them
// into AlarmManager calls and owns the reminder settings in SharedPreferences
// (same "spine_checkins" file as CheckInStore, so BootReceiver can re-arm
// after reboot).
//
// Uses setInexactRepeating: a daily nudge tolerates minute-level drift, and
// inexact alarms need no SCHEDULE_EXACT_ALARM special permission. DST shifts
// may move the clock time by an hour until the next reboot/re-arm — accepted.

object ReminderScheduler {
    private const val KEY_ENABLED = "reminder_enabled"
    private const val KEY_HOUR = "reminder_hour"
    private const val KEY_MINUTE = "reminder_minute"

    const val CHANNEL_ID = "reminder"
    private const val REQUEST_CODE = 1001
    private const val INTERVAL_MS = 86_400_000L // 1 day in ms

    // ---- settings ----

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(CheckInStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** Stored reminder time as (hour, minute); defaults to 20:00. */
    fun time(context: Context): Pair<Int, Int> {
        val p = context.getSharedPreferences(CheckInStore.PREFS_NAME, Context.MODE_PRIVATE)
        return p.getInt(KEY_HOUR, 20) to p.getInt(KEY_MINUTE, 0)
    }

    /**
     * Enable + schedule (or disable + cancel) in one call. [hour]/[minute]
     * update the stored time; pass them when the user picks a new time.
     */
    fun setEnabled(context: Context, enabled: Boolean, hour: Int? = null, minute: Int? = null) {
        context.getSharedPreferences(CheckInStore.PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_ENABLED, enabled)
            if (hour != null) putInt(KEY_HOUR, hour)
            if (minute != null) putInt(KEY_MINUTE, minute)
            apply()
        }
        if (enabled) {
            ensureChannel(context) // defensive: channel exists before first fire
            schedule(context)
        } else cancel(context)
    }

    // ---- alarm wiring ----

    /** (Re)arm the repeating alarm from the stored settings. */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val (hour, minute) = time(context)
        val triggerAtMs = ReminderPolicy
            .nextTriggerAt(hour, minute, LocalDateTime.now())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAtMs, INTERVAL_MS, pendingIntent(context))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Notification channel (minSdk 26, so always available). */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "锻炼提醒", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "每日颈椎锻炼提醒" }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
