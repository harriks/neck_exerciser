package com.spineexercise.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests for the pure-Kotlin ReminderPolicy (no Android deps).
 * Calendar helpers: LocalDateTime.of(y, m, d, h, min).
 */
class ReminderPolicyTest {

    // ---------- shouldNotify ----------

    @Test
    fun `notifies only when enabled and not checked in today`() {
        assertTrue(ReminderPolicy.shouldNotify(enabled = true, checkedInToday = false))
        assertFalse(ReminderPolicy.shouldNotify(enabled = true, checkedInToday = true))
        assertFalse(ReminderPolicy.shouldNotify(enabled = false, checkedInToday = false))
        assertFalse(ReminderPolicy.shouldNotify(enabled = false, checkedInToday = true))
    }

    // ---------- nextTriggerAt ----------

    @Test
    fun `future time today triggers today`() {
        val now = LocalDateTime.of(2026, 9, 11, 8, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 11, 21, 30),
            ReminderPolicy.nextTriggerAt(21, 30, now),
        )
    }

    @Test
    fun `past time today triggers tomorrow`() {
        val now = LocalDateTime.of(2026, 9, 11, 22, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 12, 9, 0),
            ReminderPolicy.nextTriggerAt(9, 0, now),
        )
    }

    @Test
    fun `exact same moment triggers tomorrow not now`() {
        val now = LocalDateTime.of(2026, 9, 11, 21, 30)
        assertEquals(
            LocalDateTime.of(2026, 9, 12, 21, 30),
            ReminderPolicy.nextTriggerAt(21, 30, now),
        )
    }

    @Test
    fun `midnight reminder rolls across month end`() {
        val now = LocalDateTime.of(2026, 9, 30, 23, 59)
        assertEquals(
            LocalDateTime.of(2026, 10, 1, 0, 0),
            ReminderPolicy.nextTriggerAt(0, 0, now),
        )
    }
}
