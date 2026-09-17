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
// epoch day (days since 1970-01-01, ~3-4 base36 chars) and compacted into
// a single string persisted via CheckInStore (SharedPreferences).
//
// Since v2 each day also carries a workout-mode bitmask (one bit per Mode),
// so the calendar stats can break counts down per mode. Legacy v1 entries
// have no mask (mask 0 = "mode unknown"): they still fill the day-based
// stats (streaks / month / total / milestones) but not the per-mode counts.

/**
 * Immutable-by-convention log of check-in days.
 *
 * Internally a [TreeSet] of epoch days gives dedup + ascending order for free;
 * a parallel map holds each day's workout-mode bitmask. The serialized form is
 * the single source of truth handed to the persistence layer as one small string.
 */
class CheckInLog private constructor(private val days: TreeSet<Int>) {

    private val masks = HashMap<Int, Int>() // epochDay -> mode bitmask (0 = unknown)

    /**
     * Record a check-in for [date] with workout [mode]. Returns true only when
     * it is a NEW day (multiple workouts on the same day collapse into a single
     * check-in; the mode bit is merged either way, so finishing the other mode
     * later the same day still shows up in the per-mode stats).
     */
    fun add(date: LocalDate, mode: Mode): Boolean {
        val day = epochDay(date)
        val added = days.add(day)
        masks[day] = (masks[day] ?: 0) or modeBit(mode)
        return added
    }

    /** Whether [date] has been checked in (any mode). */
    fun has(date: LocalDate): Boolean = days.contains(epochDay(date))

    /** Whether [date] has a check-in that included [mode]. */
    fun hasMode(date: LocalDate, mode: Mode): Boolean =
        (masks[epochDay(date)] ?: 0) and modeBit(mode) != 0

    /** Total distinct check-in days so far. */
    val total: Int get() = days.size

    /** Whether day-of-month [day] in [ym] was checked in (for calendar cells). */
    fun hasInMonth(ym: YearMonth, day: Int): Boolean {
        val from = epochDay(ym.atDay(day))
        return days.contains(from)
    }

    /** Whether day-of-month [day] in [ym] included [mode] (calendar mode pins). */
    fun hasModeInMonth(ym: YearMonth, day: Int, mode: Mode): Boolean =
        hasMode(ym.atDay(day), mode)

    /** Count of check-in days inside [ym] (for the month header stat). */
    fun countInMonth(ym: YearMonth): Int {
        val first = epochDay(ym.atDay(1))
        val last = epochDay(ym.atEndOfMonth()) + 1
        val view = days.subSet(first, last)
        return view.size
    }

    /** Count of days inside [ym] whose check-in included [mode]. */
    fun countModeInMonth(ym: YearMonth, mode: Mode): Int {
        val first = epochDay(ym.atDay(1))
        val last = epochDay(ym.atEndOfMonth()) + 1
        val bit = modeBit(mode)
        return days.subSet(first, last).count { (masks[it] ?: 0) and bit != 0 }
    }

    /** Count of days across the whole history whose check-in included [mode]. */
    fun countModeTotal(mode: Mode): Int {
        val bit = modeBit(mode)
        return days.count { (masks[it] ?: 0) and bit != 0 }
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
     * Format v2: `v2:<token>,<token>,...` in ascending day order, each token
     * the base36 epoch day optionally followed by ".<mask>" (decimal mode
     * bitmask, omitted when 0). E.g. 2026-09-11 (epochDay 20707 -> "fz7") with
     * both modes done -> "v2:fz7.3"; a day with no mode info -> "v2:fz7".
     * The v1 format ("v1:fz7,fz8") still parses: those days get mask 0.
     */
    fun serialize(): String {
        val sb = StringBuilder("v2:")
        var first = true
        for (d in days) {
            if (!first) sb.append(',')
            sb.append(base36(d))
            val m = masks[d] ?: 0
            if (m != 0) sb.append('.').append(m)
            first = false
        }
        return sb.toString()
    }

    companion object {
        private val EPOCH = LocalDate.of(1970, 1, 1)
        private val B36 = "0123456789abcdefghijklmnopqrstuvwxyz"

        /** Cumulative-day milestone steps: badge emoji per threshold. */
        private val MILESTONE_STEPS = listOf(7 to "🥉", 30 to "🥈", 100 to "🥇", 365 to "👑")

        /** Mode bitmask bit for [mode] (GENTLE=1, ISOMETRIC=2 — follows enum order). */
        private fun modeBit(mode: Mode): Int = 1 shl mode.ordinal

        /** Create an empty log. */
        fun empty(): CheckInLog = CheckInLog(TreeSet())

        /**
         * Parse a serialized string back into a log. Tolerant: unknown/missing
         * version prefix, empty segments, malformed day/mask tokens are skipped
         * rather than throwing, so corrupt storage can never crash the app.
         * Accepts v2 (day[.mask]) and legacy v1 (day only) formats.
         */
        fun parse(s: String): CheckInLog {
            val log = empty()
            val body = when {
                s.startsWith("v2:") -> s.substring(3)
                s.startsWith("v1:") -> s.substring(3)
                else -> s
            }
            if (!body.isEmpty()) {
                for (tok in body.split(',')) {
                    val parts = tok.trim().split('.')
                    val day = unbase36(parts[0])
                    if (day == null) continue
                    val mask = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 3) ?: 0
                    log.days.add(day)
                    log.masks[day] = (log.masks[day] ?: 0) or mask
                }
            }
            return log
        }

        /** Test helper: a log seeded with a single date. */
        fun containing(date: LocalDate, mode: Mode = Mode.GENTLE): CheckInLog {
            val log = empty()
            log.add(date, mode)
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
