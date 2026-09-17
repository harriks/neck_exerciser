package com.spineexercise.timer

import android.content.Context

// ===================== Language persistence =====================
// SharedPreferences-backed storage for the in-app language override
// (⋮ → Language). Pure glue: resolution logic lives here because reading the
// system locale needs a Context; the Strings bundles stay in pure-Kotlin L10n.
//
// Stored value: "auto" (follow system) | "zh" | "en".
// init() is cheap and idempotent — call it at Activity startup AND in
// ReminderReceiver, where the process may be cold and the notification text
// must honor the saved language.

object L10nStore {
    private const val PREFS_NAME = "spine_l10n"
    private const val KEY_LANG = "lang"
    const val AUTO = "auto"
    const val ZH = "zh"
    const val EN = "en"

    /** Apply the stored override (or the system locale for "auto") to [L10n]. */
    fun init(context: Context) {
        L10n.set(when (load(context)) {
            ZH -> Lang.ZH
            EN -> Lang.EN
            else -> systemLang(context)
        })
    }

    /** Currently stored choice: [AUTO], [ZH] or [EN]. */
    fun load(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANG, AUTO) ?: AUTO

    fun save(context: Context, choice: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, choice).apply()
    }

    private fun systemLang(context: Context): Lang =
        if (context.resources.configuration.locales[0].language.startsWith("zh")) Lang.ZH
        else Lang.EN
}
