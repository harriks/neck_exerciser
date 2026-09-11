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
}
