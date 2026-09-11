package com.spineexercise.timer

import android.content.Context

// ===================== Timing persistence =====================
// The runtime settings editor (⋮ → 计时设置) edits durations and saves them
// here as one JSON string in the shared schema. Empty storage = user never
// touched settings = use DEFAULT_TIMING_JSON. Applied once in
// MainActivity.onCreate, before any engine/UI reads Config.

object TimingStore {
    private const val PREFS_NAME = "spine_timing"
    private const val KEY_JSON = "json_v1"

    /** Custom timing JSON, or "" when defaults are in effect. */
    fun load(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JSON, "") ?: ""

    /** Persist a custom timing JSON (called after Config.configure succeeded). */
    fun save(context: Context, json: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, json).apply()
    }

    /** Restore defaults: drop the stored override. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_JSON).apply()
    }
}
