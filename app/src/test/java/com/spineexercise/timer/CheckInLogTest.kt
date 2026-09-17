package com.spineexercise.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Unit tests for the pure-Kotlin CheckInLog (no Android deps).
 * Calendar helpers: LocalDate.of(y, m, d) and YearMonth.of(y, m).
 */
class CheckInLogTest {

    private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

    // ---------- add / has ----------

    @Test
    fun `empty log has no check-ins`() {
        val log = CheckInLog.empty()
        assertFalse(log.has(d(2026, 9, 11)))
        assertEquals(0, log.total)
    }

    @Test
    fun `add marks the day and dedups within the same day`() {
        val log = CheckInLog.empty()
        assertTrue(log.add(d(2026, 9, 11), Mode.GENTLE))   // new
        assertFalse(log.add(d(2026, 9, 11), Mode.GENTLE))  // same day again -> not new
        assertTrue(log.has(d(2026, 9, 11)))
        assertEquals(1, log.total)                          // still only one day
    }

    @Test
    fun `distinct days are counted separately`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 10), Mode.GENTLE)
        log.add(d(2026, 9, 11), Mode.GENTLE)
        log.add(d(2026, 9, 12), Mode.GENTLE)
        assertEquals(3, log.total)
    }

    // ---------- per-mode counts ----------

    @Test
    fun `same day records both modes as one day with merged mask`() {
        val log = CheckInLog.empty()
        assertTrue(log.add(d(2026, 9, 11), Mode.GENTLE))
        assertFalse(log.add(d(2026, 9, 11), Mode.ISOMETRIC)) // day already exists
        assertTrue(log.hasMode(d(2026, 9, 11), Mode.GENTLE))
        assertTrue(log.hasMode(d(2026, 9, 11), Mode.ISOMETRIC))
        assertEquals(1, log.total)                            // one day, both modes
        assertEquals("v2:fz7.3", log.serialize())             // 1|2 = 3
    }

    @Test
    fun `hasMode is false for unchecked days`() {
        val log = CheckInLog.containing(d(2026, 9, 11), Mode.ISOMETRIC)
        assertTrue(log.hasMode(d(2026, 9, 11), Mode.ISOMETRIC))
        assertFalse(log.hasMode(d(2026, 9, 11), Mode.GENTLE))
        assertFalse(log.hasMode(d(2026, 9, 12), Mode.ISOMETRIC))
    }

    @Test
    fun `countModeInMonth counts per-mode days within a month`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 1), Mode.GENTLE)
        log.add(d(2026, 9, 2), Mode.GENTLE)
        log.add(d(2026, 9, 2), Mode.ISOMETRIC) // same day, other mode
        log.add(d(2026, 9, 20), Mode.ISOMETRIC)
        log.add(d(2026, 10, 1), Mode.ISOMETRIC) // outside the month

        val ym = YearMonth.of(2026, 9)
        assertEquals(2, log.countModeInMonth(ym, Mode.GENTLE))
        assertEquals(2, log.countModeInMonth(ym, Mode.ISOMETRIC))
        assertEquals(0, log.countModeInMonth(YearMonth.of(2026, 8), Mode.GENTLE))
        assertTrue(log.hasModeInMonth(ym, 2, Mode.ISOMETRIC))
        assertFalse(log.hasModeInMonth(ym, 1, Mode.ISOMETRIC))
    }

    @Test
    fun `countModeTotal spans months and years`() {
        val log = CheckInLog.empty()
        log.add(d(2025, 12, 31), Mode.ISOMETRIC)
        log.add(d(2026, 1, 1), Mode.GENTLE)
        log.add(d(2026, 6, 15), Mode.GENTLE)
        assertEquals(2, log.countModeTotal(Mode.GENTLE))
        assertEquals(1, log.countModeTotal(Mode.ISOMETRIC))
    }

    // ---------- month helpers ----------

    @Test
    fun `countInMonth and hasInMonth reflect only that month`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 8, 31), Mode.GENTLE)
        log.add(d(2026, 9, 1), Mode.GENTLE)
        log.add(d(2026, 9, 15), Mode.ISOMETRIC)
        log.add(d(2026, 10, 1), Mode.GENTLE)

        val ym = YearMonth.of(2026, 9)
        assertEquals(2, log.countInMonth(ym))
        assertTrue(log.hasInMonth(ym, 1))
        assertTrue(log.hasInMonth(ym, 15))
        assertFalse(log.hasInMonth(ym, 2))
    }

    // ---------- serialize / parse ----------

    @Test
    fun `empty log round-trips to an empty v2 string`() {
        assertEquals("v2:", CheckInLog.empty().serialize())
        assertEquals(0, CheckInLog.parse("v2:").total)
    }

    @Test
    fun `serialize is compact and read back identically`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 11), Mode.GENTLE) // epochDay 20707 -> "fz7"
        log.add(d(2026, 9, 12), Mode.GENTLE) // epochDay 20708 -> "fz8"
        val s = log.serialize()
        assertTrue("expected compact base36, got: $s", s.startsWith("v2:fz7.1,fz8.1"))
        assertTrue("serialized size should stay small, was ${s.length}", s.length < 20)

        val back = CheckInLog.parse(s)
        assertEquals(2, back.total)
        assertTrue(back.has(d(2026, 9, 11)))
        assertTrue(back.has(d(2026, 9, 12)))
        assertTrue(back.hasMode(d(2026, 9, 11), Mode.GENTLE))
    }

    @Test
    fun `serialize emits sorted ascending tokens regardless of insert order`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 12), Mode.GENTLE)
        log.add(d(2026, 9, 10), Mode.GENTLE) // epochDay 20706 -> "fz6"
        log.add(d(2026, 9, 11), Mode.GENTLE) // epochDay 20707 -> "fz7"
        // epochDay ascending => base36 ascending
        assertEquals("v2:fz6.1,fz7.1,fz8.1", log.serialize())
    }

    @Test
    fun `serialize omits the mask suffix for zero-mask entries`() {
        // A v2 string may carry mask-less days (legacy); round-trip keeps them
        val back = CheckInLog.parse("v2:fz7,fz8.2")
        assertEquals("v2:fz7,fz8.2", back.serialize())
        assertFalse(back.hasMode(d(2026, 9, 11), Mode.GENTLE))
        assertTrue(back.hasMode(d(2026, 9, 12), Mode.ISOMETRIC))
    }

    // ---------- tolerant parsing ----------

    @Test
    fun `parse handles empty and missing prefix gracefully`() {
        assertEquals(0, CheckInLog.parse("").total)
        assertEquals(0, CheckInLog.parse("v2:").total)
        // missing version prefix still parsed
        val back = CheckInLog.parse("fz7,fz8")
        assertEquals(2, back.total)
    }

    @Test
    fun `legacy v1 string parses with days but no mode info`() {
        val back = CheckInLog.parse("v1:fz7,fz8")
        assertEquals(2, back.total)
        assertTrue(back.has(d(2026, 9, 11)))
        assertTrue(back.has(d(2026, 9, 12)))
        // v1 predates modes: counts stay in day stats, per-mode counts stay 0
        assertFalse(back.hasMode(d(2026, 9, 11), Mode.GENTLE))
        assertFalse(back.hasMode(d(2026, 9, 11), Mode.ISOMETRIC))
        assertEquals(0, back.countModeTotal(Mode.GENTLE))
    }

    @Test
    fun `parse skips malformed tokens without throwing`() {
        val back = CheckInLog.parse("v2:fz7,zzzz,garbage,,fz8.2,-1,999999999999999999,fz9.xx")
        assertEquals(3, back.total) // fz7 (mask 0), fz8 (mask 2), fz9 (bad mask -> 0)
        assertTrue(back.has(d(2026, 9, 11)))
        assertTrue(back.has(d(2026, 9, 12)))
        assertTrue(back.has(d(2026, 9, 13)))
        assertTrue(back.hasMode(d(2026, 9, 12), Mode.ISOMETRIC))
        assertFalse(back.hasMode(d(2026, 9, 13), Mode.GENTLE))
    }

    // ---------- streak ----------

    @Test
    fun `currentStreak is zero for an empty log`() {
        assertEquals(0, CheckInLog.empty().currentStreak(d(2026, 9, 11)))
    }

    @Test
    fun `currentStreak counts a run ending today`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 9), Mode.GENTLE)
        log.add(d(2026, 9, 10), Mode.ISOMETRIC)
        log.add(d(2026, 9, 11), Mode.GENTLE)
        assertEquals(3, log.currentStreak(d(2026, 9, 11)))
    }

    @Test
    fun `currentStreak anchors at yesterday when today is not checked in yet`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 9), Mode.GENTLE)
        log.add(d(2026, 9, 10), Mode.GENTLE)
        // 9/11 unchecked: not having worked out yet today does not break the run
        assertEquals(2, log.currentStreak(d(2026, 9, 11)))
    }

    @Test
    fun `currentStreak breaks across a gap`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 5), Mode.GENTLE)
        log.add(d(2026, 9, 6), Mode.GENTLE)
        log.add(d(2026, 9, 7), Mode.GENTLE)
        assertEquals(0, log.currentStreak(d(2026, 9, 11)))
    }

    @Test
    fun `longestStreak handles empty, singles and mixed runs`() {
        assertEquals(0, CheckInLog.empty().longestStreak())

        val scattered = CheckInLog.empty()
        scattered.add(d(2026, 1, 1), Mode.GENTLE)
        scattered.add(d(2026, 1, 3), Mode.GENTLE)
        scattered.add(d(2026, 1, 5), Mode.GENTLE)
        assertEquals(1, scattered.longestStreak())

        val mixed = CheckInLog.empty()
        mixed.add(d(2026, 1, 1), Mode.GENTLE); mixed.add(d(2026, 1, 2), Mode.GENTLE)   // run of 2
        mixed.add(d(2026, 3, 10), Mode.GENTLE); mixed.add(d(2026, 3, 11), Mode.GENTLE)
        mixed.add(d(2026, 3, 12), Mode.GENTLE); mixed.add(d(2026, 3, 13), Mode.GENTLE) // run of 4
        assertEquals(4, mixed.longestStreak())
    }

    // ---------- general date queries ----------

    @Test
    fun `countInYear respects year boundaries`() {
        val log = CheckInLog.empty()
        log.add(d(2025, 12, 31), Mode.GENTLE)
        log.add(d(2026, 1, 1), Mode.GENTLE)
        log.add(d(2026, 6, 15), Mode.GENTLE)
        assertTrue(log.has(d(2025, 12, 31)))
        assertFalse(log.has(d(2026, 1, 2)))
        assertEquals(1, log.countInYear(2025))
        assertEquals(2, log.countInYear(2026))
        assertEquals(0, log.countInYear(2027))
    }

    // ---------- milestones ----------

    @Test
    fun `milestones track cumulative thresholds`() {
        val none = CheckInLog.empty()
        assertEquals(4, none.milestones().size)
        assertTrue(none.milestones().none { it.reached })

        val seven = CheckInLog.empty()
        repeat(7) { seven.add(d(2026, 1, 1).plusDays(it.toLong()), Mode.GENTLE) }
        val ms = seven.milestones()
        assertTrue(ms.first { it.days == 7 }.reached)
        assertFalse(ms.first { it.days == 30 }.reached)

        val all = CheckInLog.empty()
        repeat(366) { all.add(d(2025, 1, 1).plusDays(it.toLong()), Mode.GENTLE) }
        assertTrue(all.milestones().all { it.reached })
    }

    // ---------- epoch internals (via public behavior) ----------

    @Test
    fun `epoch day math matches known date`() {
        // 2026-09-11 = epoch day 20707 (calendar days since 1970-01-01)
        val log = CheckInLog.containing(d(2026, 9, 11))
        assertEquals("v2:fz7.1", log.serialize())
    }
}
