package com.spineexercise.timer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

// ===================== Reminder scheduling (Android glue) =====================
// Decisions live in ReminderPolicy (pure Kotlin); this object only turns them
// into AlarmManager calls and owns the reminder settings in SharedPreferences
// (same "spine_checkins" file as CheckInStore, so BootReceiver can re-arm
// after reboot).
//
// Daily repetition is a self-perpetuating chain of setAlarmClock one-shots
// (see schedule below): precise timing, Doze-safe, and the stored wall-clock
// time self-corrects across DST since every fire re-arms from the current local
// time. Exact alarms require an exact-alarm permission from API 31 up (see
// AndroidManifest); if the grant is missing, schedule() degrades to an inexact
// Doze-tolerant alarm instead of throwing.

object ReminderScheduler {
    private const val KEY_ENABLED = "reminder_enabled"
    private const val KEY_HOUR = "reminder_hour"
    private const val KEY_MINUTE = "reminder_minute"

    const val CHANNEL_ID = "reminder"
    // Distinct request codes: Intent.filterEquals ignores flags, so sharing one
    // code between the alarm's show-intent and the notification's content-intent
    // would make them the same PendingIntent (FLAG_UPDATE_CURRENT aliasing).
    private const val REQUEST_CODE = 1001   // the alarm broadcast
    private const val RC_SHOW = 1002        // status-bar alarm icon
    private const val RC_NOTIF_OPEN = 1003  // notification tap target

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

    /**
     * Arm the NEXT daily reminder as an alarm-clock one-shot: fires exactly at
     * the stored time, wakes the device out of Doze, and shows the system alarm
     * icon while armed.
     *
     * This replaced setInexactRepeating, whose documented batching could delay
     * the first trigger by up to a full interval (= a full day for us) — the
     * reminder simply did not show up on time. Daily repetition is now a
     * self-perpetuating chain: ReminderReceiver re-arms the next day when it
     * fires, and BootReceiver re-arms after reboot / package update.
     *
     * Exact alarms are permission-gated from API 31 (see the manifest). When the
     * grant is missing we degrade to a Doze-tolerant inexact alarm, because
     * setAlarmClock would otherwise throw SecurityException — and this method is
     * called from MainActivity.onCreate, so that crash would repeat on every
     * launch with no way for the user to switch the reminder off.
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val (hour, minute) = time(context)
        val triggerAtMs = ReminderPolicy
            .nextTriggerAt(hour, minute, LocalDateTime.now())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Guarded (not "||") so the API-31-only probe is never invoked on the
        // minSdk-26 devices this app still supports.
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true
        if (ReminderPolicy.canScheduleExactAlarm(Build.VERSION.SDK_INT, allowed)) {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMs, showIntent(context)),
                pendingIntent(context),
            )
        } else {
            // Fires in Doze (batched, so possibly a few minutes late) rather than
            // taking the app down with an unhandled SecurityException.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Tapping the status-bar alarm icon opens the app. */
    private fun showIntent(context: Context): PendingIntent = openApp(context, RC_SHOW)

    /** Notification tap target; own request code so it cannot alias [showIntent]. */
    fun notifOpenIntent(context: Context): PendingIntent = openApp(context, RC_NOTIF_OPEN)

    private fun openApp(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Notification channel (minSdk 26, so always available). Re-creating
     *  with the same ID also updates the name after a language switch. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, L10n.s.notifChannelName, NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = L10n.s.notifChannelDesc }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
