package com.spineexercise.timer

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.TreeSet

// ===================== Check-in Calendar (打卡) =====================
// Pure Kotlin (no Android / no third-party deps) so it can be unit-tested,
// mirroring the style of WorkoutEngine / TimingConfig.
//
// Persistence footprint matters: rather than storing a list of verbose
// "yyyy-MM-dd" strings (11 bytes each), each date is converted to its
// epoch day (days since 1970-01-01, ~3-4 base36 chars) and compacted into a
// single string read/written once by the UI's rememberSaveable store.

/**
 * Immutable-by-convention log of check-in days.
 *
 * Internally a [TreeSet] of epoch days gives dedup + ascending order for free.
 * The serialized form is the single source of truth handed to the persistence
 * layer as one small string.
 */
class CheckInLog private constructor(private val days: TreeSet<Int>) {

    /**
     * Record a check-in for [date]. Returns true only when it is a NEW day
     * (multiple workouts on the same day collapse into a single check-in).
     */
    fun add(date: LocalDate): Boolean = days.add(epochDay(date))

    /** Whether [date] has been checked in. */
    fun has(date: LocalDate): Boolean = days.contains(epochDay(date))

    /** Total distinct check-in days so far. */
    val total: Int get() = days.size

    /** Whether day-of-month [day] in [ym] was checked in (for calendar cells). */
    fun hasInMonth(ym: YearMonth, day: Int): Boolean {
        val from = epochDay(ym.atDay(day))
        return days.contains(from)
    }

    /** Count of check-in days inside [ym] (for the month header stat). */
    fun countInMonth(ym: YearMonth): Int {
        val first = epochDay(ym.atDay(1))
        val last = epochDay(ym.atEndOfMonth()) + 1
        val view = days.subSet(first, last)
        return view.size
    }

    /** Count of check-in days inside calendar [year] (for the year header stat). */
    fun countInYear(year: Int): Int {
        val first = epochDay(LocalDate.of(year, 1, 1))
        val last = epochDay(LocalDate.of(year + 1, 1, 1))
        return days.subSet(first, last).size
    }

    /**
     * Consecutive check-in days as of [today], with the usual "grace" rule:
     * when today is not checked in yet the streak is anchored at yesterday, so
     * simply not having worked out yet today does not break an ongoing run.
     */
    fun currentStreak(today: LocalDate): Int {
        var cursor = epochDay(today)
        if (!days.contains(cursor)) {
            cursor -= 1
            if (!days.contains(cursor)) return 0
        }
        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor--
        }
        return streak
    }

    /** Longest run of consecutive check-in days in the entire history. */
    fun longestStreak(): Int {
        var best = 0
        var run = 0
        var prev = Int.MIN_VALUE
        for (day in days) {
            run = if (day == prev + 1) run + 1 else 1
            if (run > best) best = run
            prev = day
        }
        return best
    }

    /** Milestone badges evaluated against the cumulative [total]. */
    fun milestones(): List<Milestone> =
        MILESTONE_STEPS.map { (d, emoji) -> Milestone(d, emoji, total >= d) }

    /**
     * Compact serialization: one small ASCII string.
     *
     * Format: `v1:<base36>,<base36>,...` where each token is the base36-encoded
     * epoch day, in ascending order. E.g. 2026-09-11 -> epochDay 20707 ->
     * base36 "fz7", so the value is "v1:fz7".
     *
     * Drops to ~1/3 the size of a yyyy-MM-dd list and has no ambiguity.
     * Empty log serializes to "v1:" (nothing after the colon).
     */
    fun serialize(): String {
        val sb = StringBuilder("v1:")
        var first = true
        for (d in days) {
            if (!first) sb.append(',')
            sb.append(base36(d))
            first = false
        }
        return sb.toString()
    }

    companion object {
        private val EPOCH = LocalDate.of(1970, 1, 1)
        private val B36 = "0123456789abcdefghijklmnopqrstuvwxyz"

        /** Cumulative-day milestone steps: badge emoji per threshold. */
        private val MILESTONE_STEPS = listOf(7 to "🥉", 30 to "🥈", 100 to "🥇", 365 to "👑")

        /** Create an empty log. */
        fun empty(): CheckInLog = CheckInLog(TreeSet())

        /**
         * Parse a serialized string back into a log. Tolerant: unknown/missing
         * "v1:" prefix, empty segments and malformed tokens are skipped rather
         * than throwing, so corrupt storage can never crash the app.
         */
        fun parse(s: String): CheckInLog {
            val log = empty()
            val body = if (s.startsWith("v1:")) s.substring(3) else s
            if (!body.isEmpty()) {
                for (tok in body.split(',')) {
                    val v = unbase36(tok.trim())
                    if (v != null) log.days.add(v)
                }
            }
            return log
        }

        /** Test helper: a log seeded with a single date. */
        fun containing(date: LocalDate): CheckInLog {
            val log = empty()
            log.add(date)
            return log
        }

        /** Days since 1970-01-01 (UTC-independent, calendar-days based). */
        private fun epochDay(date: LocalDate): Int =
            ChronoUnit.DAYS.between(EPOCH, date).toInt()

        private fun base36(v: Int): String {
            var n = v
            var s = ""
            while (n > 0) {
                s = B36[n % 36] + s
                n /= 36
            }
            return if (s.isEmpty()) "0" else s
        }

        private fun unbase36(tok: String): Int? {
            if (tok.isEmpty() || tok.length > 6) return null
            var acc = 0L
            for (c in tok) {
                val idx = B36.indexOf(c)
                if (idx < 0) return null
                acc = acc * 36 + idx
                if (acc > Int.MAX_VALUE) return null
            }
            // Sanity range: ~1970-01-01..2298-12-31. Rejects base36-valid but
            // absurd tokens (e.g. "zzzz") that would otherwise pollute the store.
            if (acc > 120_000L) return null
            return acc.toInt()
        }
    }
}

/**
 * A cumulative check-in milestone badge: reached when [CheckInLog.total]
 * is at least [days]; [emoji] is the badge glyph shown in the calendar UI.
 */
data class Milestone(val days: Int, val emoji: String, val reached: Boolean)