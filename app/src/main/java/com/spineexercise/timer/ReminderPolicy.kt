package com.spineexercise.timer

import java.time.LocalDateTime

// ===================== Reminder policy (pure Kotlin) =====================
// Scheduling decisions kept free of Android so they are unit-testable,
// mirroring CheckInLog / WorkoutEngine. The Android glue (ReminderScheduler,
// ReminderReceiver) only translates these decisions into AlarmManager calls
// and notifications.

object ReminderPolicy {

    /**
     * Whether a reminder should actually fire right now: the user asked for
     * reminders AND has not already checked in today (a reminder should
     * encourage, not nag someone who already worked out).
     */
    fun shouldNotify(enabled: Boolean, checkedInToday: Boolean): Boolean =
        enabled && !checkedInToday

    /**
     * Next wall-clock firing time for a daily reminder at [hour]:[minute]:
     * today at that time while it is still ahead of [now], otherwise tomorrow
     * at that time. Setting a reminder for exactly "now" schedules tomorrow
     * (never fires with zero delay).
     */
    fun nextTriggerAt(hour: Int, minute: Int, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(hour, minute)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /**
     * Whether an exact alarm ([android.app.AlarmManager.setAlarmClock]) may be
     * used on this device. Exact alarms are ungated below API 31; from API 31
     * the app must hold an exact-alarm permission — SCHEDULE_EXACT_ALARM on
     * 31-32, USE_EXACT_ALARM on 33+ (see AndroidManifest). When it is missing,
     * setAlarmClock throws SecurityException, so callers fall back to a
     * Doze-tolerant inexact alarm instead of crashing.
     *
     * [sdkInt] and [exactAlarmsAllowed] are passed in to keep this decision
     * pure and unit-testable (the Android glue only supplies the probe).
     */
    fun canScheduleExactAlarm(sdkInt: Int, exactAlarmsAllowed: Boolean): Boolean =
        sdkInt < 31 || exactAlarmsAllowed
}
