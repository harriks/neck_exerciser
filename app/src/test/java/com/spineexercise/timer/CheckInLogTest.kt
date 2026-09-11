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
        assertTrue(log.add(d(2026, 9, 11)))  // new
        assertFalse(log.add(d(2026, 9, 11))) // same day again -> not new
        assertTrue(log.has(d(2026, 9, 11)))
        assertEquals(1, log.total)           // still only one day
    }

    @Test
    fun `distinct days are counted separately`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 10))
        log.add(d(2026, 9, 11))
        log.add(d(2026, 9, 12))
        assertEquals(3, log.total)
    }

    // ---------- month helpers ----------

    @Test
    fun `countInMonth and hasInMonth reflect only that month`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 8, 31))
        log.add(d(2026, 9, 1))
        log.add(d(2026, 9, 15))
        log.add(d(2026, 10, 1))

        val ym = YearMonth.of(2026, 9)
        assertEquals(2, log.countInMonth(ym))
        assertTrue(log.hasInMonth(ym, 1))
        assertTrue(log.hasInMonth(ym, 15))
        assertFalse(log.hasInMonth(ym, 2))
    }

    // ---------- serialize / parse ----------

    @Test
    fun `empty log round-trips to an empty v1 string`() {
        assertEquals("v1:", CheckInLog.empty().serialize())
        assertEquals(0, CheckInLog.parse("v1:").total)
    }

    @Test
    fun `serialize is compact and read back identically`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 11)) // epochDay 20707 -> "fz7"
        log.add(d(2026, 9, 12)) // epochDay 20708 -> "fz8"
        val s = log.serialize()
        assertTrue("expected compact base36, got: $s", s.startsWith("v1:fz7,fz8"))
        assertTrue("serialized size should stay small, was ${s.length}", s.length < 20)

        val back = CheckInLog.parse(s)
        assertEquals(2, back.total)
        assertTrue(back.has(d(2026, 9, 11)))
        assertTrue(back.has(d(2026, 9, 12)))
    }

    @Test
    fun `serialize emits sorted ascending tokens regardless of insert order`() {
        val log = CheckInLog.empty()
        log.add(d(2026, 9, 12))
        log.add(d(2026, 9, 10)) // epochDay 20706 -> "fz6"
        log.add(d(2026, 9, 11)) // epochDay 20707 -> "fz7"
        // epochDay ascending => base36 ascending
        assertEquals("v1:fz6,fz7,fz8", log.serialize())
    }

    // ---------- tolerant parsing ----------

    @Test
    fun `parse handles empty and missing prefix gracefully`() {
        assertEquals(0, CheckInLog.parse("").total)
        assertEquals(0, CheckInLog.parse("v1:").total)
        // missing v1 prefix still parsed
        val back = CheckInLog.parse("fz7,fz8")
        assertEquals(2, back.total)
    }

    @Test
    fun `parse skips malformed tokens without throwing`() {
        val back = CheckInLog.parse("v1:fz7,zzzz,garbage,,fz8,-1,999999999999999999")
        assertEquals(2, back.total)
        assertTrue(back.has(d(2026, 9, 11)))
        assertTrue(back.has(d(2026, 9, 12)))
    }

    // ---------- epoch internals (via public behavior) ----------

    @Test
    fun `epoch day math matches known date`() {
        // 2026-09-11 = epoch day 20707 (calendar days since 1970-01-01)
        val log = CheckInLog.containing(d(2026, 9, 11))
        assertEquals("v1:fz7", log.serialize())
    }
}