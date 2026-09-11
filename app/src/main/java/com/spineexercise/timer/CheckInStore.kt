package com.spineexercise.timer

import android.content.Context

// ===================== Check-in persistence =====================
// Thin Android bridge: the pure-Kotlin CheckInLog owns all logic; this object
// only moves its single serialized string in and out of SharedPreferences so
// the history survives app relaunch (rememberSaveable alone dies with the
// task). One small ASCII string per read/write — see CheckIn.kt.

object CheckInStore {
    /** Shared prefs file — reminder settings live here too (see ReminderScheduler). */
    const val PREFS_NAME = "spine_checkins"
    private const val KEY_RAW = "raw_v1"

    /** Load the whole check-in history ("" when nothing stored yet). */
    fun load(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RAW, "") ?: ""

    /** Write the whole check-in history (async; single small string). */
    fun save(context: Context, raw: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RAW, raw).apply()
    }
}
