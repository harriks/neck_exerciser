package com.spineexercise.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the pure-Kotlin WorkoutEngine state machine (no Android deps).
 * Uses a fake monotonic clock, advanced in 200ms steps like the real ticker.
 */
class WorkoutEngineTest {

    private var now = 0L
    private lateinit var engine: WorkoutEngine

    @Before
    fun setUp() {
        now = 0L
        engine = WorkoutEngine(nowMs = { now })
    }

    private fun advanceSeconds(sec: Int) {
        repeat(sec * 5) {
            now += 200
            engine.tick()
        }
    }

    private fun advanceMs(ms: Long) {
        // tick in 200ms chunks to be realistic
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(200, remaining)
            now += step
            remaining -= step
            engine.tick()
        }
    }

    // ---------- Gentle mode ----------

    @Test
    fun `gentle start enters prepare with 3 second countdown`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        assertEquals(Phase.PREPARE, engine.state.phase)
        assertEquals(3, engine.state.countdown)
        assertTrue(engine.state.running)
    }

    @Test
    fun `gentle alternates hands and finishes after 8 reps`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        // prepare(3) + 8x (contract 8 + relax 5)
        val total = 3 + 8 * (8 + 5)
        advanceSeconds(total)
        assertEquals(Phase.DONE, engine.state.phase)
        assertEquals(8, engine.state.repCount)
        assertFalse(engine.state.running)
    }

    @Test
    fun `gentle hand alternates left right each rep`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        advanceSeconds(3)
        assertEquals(Phase.CONTRACT, engine.state.phase)
        assertEquals("左手", engine.state.handName)
        advanceSeconds(8 + 5) // rep 1 done
        assertEquals(Phase.CONTRACT, engine.state.phase)
        assertEquals("右手", engine.state.handName)
        assertEquals(1, engine.state.repCount)
    }

    @Test
    fun `gentle elapsed time is exact and monotonic across phases`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        advanceSeconds(3)   // prepare done
        assertEquals(3, engine.state.elapsed)
        advanceSeconds(8)   // first contract done
        assertEquals(11, engine.state.elapsed)
        advanceSeconds(5)   // first relax done
        assertEquals(16, engine.state.elapsed)
        assertEquals(1, engine.state.repCount)
    }

    @Test
    fun `last 3 seconds of a phase emit tick sfx`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        engine.drainEvents() // clear start events
        advanceMs(1000)      // countdown 3 -> tick
        val sfx = engine.drainEvents().filterIsInstance<EngineEvent.Sfx>()
        assertTrue(sfx.any { it.type == EngineEvent.SfxType.TICK })
    }

    @Test
    fun `start speaks and beeps`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        val events = engine.drainEvents()
        assertTrue(events.contains(EngineEvent.Speak("开始，准备")))
        assertTrue(events.any { it is EngineEvent.Sfx && it.type == EngineEvent.SfxType.START })
    }

    // ---------- Isometric mode ----------

    @Test
    fun `isometric completes all 15 groups in 3 stages`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        // prepare 3 + stage1: 6*(15+15) + stage2: 6*(15+15) + stage3: 3*(15+30)
        val total = 3 + 6 * 30 + 6 * 30 + 3 * 45
        advanceSeconds(total)
        assertEquals(Phase.DONE, engine.state.phase)
        assertEquals(15, engine.state.totalGroupsAll)
        // completedGroups is the current group index (UI adds +1 for display), 14 = last group done
        assertEquals(14, engine.state.completedGroups)
        // Rep-based: each group counted once per direction visit = 15 reps
        assertEquals(15, engine.state.repCount)
    }

    @Test
    fun `isometric stage names advance correctly`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        advanceSeconds(3 + 6 * 30) // stage 1 done
        assertEquals("侧向抗阻", engine.state.stageName)
        assertEquals(1, engine.state.si)
        advanceSeconds(6 * 30) // stage 2 done
        assertEquals("弹力带训练", engine.state.stageName)
        assertEquals(2, engine.state.si)
    }

    @Test
    fun `isometric relax is 30s for band stage`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        advanceSeconds(3 + 6 * 30 + 6 * 30) // reach band stage, contract running
        assertEquals(Phase.CONTRACT, engine.state.phase)
        assertEquals(15, engine.state.contractSec)
        assertEquals(30, engine.state.relaxSec)
    }

    @Test
    fun `isometric start announces first stage`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        val events = engine.drainEvents()
        assertTrue(events.contains(EngineEvent.Speak("正向抗阻训练开始")))
    }

    @Test
    fun `isometric stage transition announces next stage only`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        advanceSeconds(3 + 6 * 30) // stage 1 done -> stage 2 first contract
        val events = engine.drainEvents()
        assertTrue(events.contains(EngineEvent.Speak("侧向抗阻训练开始")))
    }

    @Test
    fun `band stage only announces on first entry`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        advanceSeconds(3 + 6 * 30 + 6 * 30) // reach band stage, first contract
        val entry = engine.drainEvents().filterIsInstance<EngineEvent.Speak>().map { it.text }
        assertTrue("entry speaks=$entry", entry.contains("弹力带训练开始"))
        advanceSeconds(15 + 30) // band group 1 done -> group 2 contract
        val events = engine.drainEvents().filterIsInstance<EngineEvent.Speak>().map { it.text }
        // No per-group announcement inside the single-direction band stage
        assertTrue("group2 speaks=$events", events.none { it.contains("弹力带") })
        assertTrue("group2 speaks=$events", events.none { it.contains("请换") })
    }

    @Test
    fun `isometric finish announces completion`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        val total = 3 + 6 * 30 + 6 * 30 + 3 * 45
        advanceSeconds(total)
        val events = engine.drainEvents().filterIsInstance<EngineEvent.Speak>().map { it.text }
        assertTrue("finish speaks=$events",
            events.any { it.contains("恭喜，全部完成") })
    }

    // ---------- Pause / reset ----------

    @Test
    fun `pause freezes elapsed and countdown resume continues`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        advanceSeconds(2)
        engine.togglePause()
        assertTrue(engine.state.paused)
        val elapsedAtPause = engine.state.elapsed
        advanceSeconds(10) // time passes while paused
        assertEquals(elapsedAtPause, engine.state.elapsed)
        engine.togglePause()
        assertFalse(engine.state.paused)
        advanceSeconds(1)
        assertEquals(elapsedAtPause + 1, engine.state.elapsed)
    }

    @Test
    fun `pause speaks`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        engine.drainEvents()
        engine.togglePause()
        assertTrue(engine.drainEvents().contains(EngineEvent.Speak("已暂停")))
    }

    @Test
    fun `reset returns to idle state`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        advanceSeconds(20)
        engine.reset()
        assertEquals(Phase.IDLE, engine.state.phase)
        assertEquals(0, engine.state.elapsed)
        assertFalse(engine.state.running)
        assertEquals(Mode.ISOMETRIC, engine.state.mode)
    }

    // ---------- Timing accuracy ----------

    @Test
    fun `countdown is anchor-based so jitter does not accumulate`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        // irregular tick intervals (simulating GC pauses etc.)
        val steps = longArrayOf(350, 100, 600, 50, 900, 300, 200, 200, 200, 100)
        var i = 0
        while (now < 3000L) {
            now += steps[i % steps.size]
            i++
            engine.tick()
        }
        engine.tick()
        assertEquals(Phase.CONTRACT, engine.state.phase)
        // elapsed should be exactly 3s of active time (anchor-based)
        assertEquals(3, engine.state.elapsed)
    }

    // ---------- Pre-computed fields ----------

    @Test
    fun `completedGroups is precomputed and consistent with config`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        advanceSeconds(3 + 6 * 30) // stage 1 done (6 groups)
        assertEquals(Config.completedGroups(Mode.ISOMETRIC, 1, 0), engine.state.completedGroups)
        assertEquals(6, engine.state.completedGroups)
    }
}

