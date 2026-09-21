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
    fun `gentle progress bar totals 8 segments and fills with reps`() {
        // Regression: the bar once showed dirs x groups = 16 while the engine
        // ran groups total reps, leaving half the bar permanently dim.
        engine.setMode(Mode.GENTLE)
        engine.start()
        assertEquals(8, engine.state.totalGroupsAll)
        advanceSeconds(3 + (8 + 5)) // first rep done -> in rep 2
        assertEquals(1, engine.state.completedGroups)
        assertEquals(2, engine.state.completedGroups + 1)
    }

    @Test
    fun `gentle hand alternates left right each rep`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        advanceSeconds(3)
        assertEquals(Phase.CONTRACT, engine.state.phase)
        assertEquals("右手", engine.state.handName)
        advanceSeconds(8 + 5) // rep 1 done
        assertEquals(Phase.CONTRACT, engine.state.phase)
        assertEquals("左手", engine.state.handName)
        assertEquals(1, engine.state.repCount)
    }

    @Test
    fun `gentle speak sequence alternates hands each rep and finishes`() {
        // Behavior lock for the engine-path unification: gentle must keep its
        // exact per-rep announcement sequence (放松 + 请换X手发力) and the
        // completion announcement, regardless of which code path advances it.
        engine.setMode(Mode.GENTLE)
        engine.start()
        advanceSeconds(3) // first contract begins (entry announce)
        engine.drainEvents()

        val speaks = mutableListOf<String>()
        repeat(7) { // relax->contract transitions for reps 2..8
            advanceSeconds(8 + 5)
            speaks += engine.drainEvents().filterIsInstance<EngineEvent.Speak>().map { it.text }
        }
        assertEquals(
            listOf(
                "放松", "请换左手发力",
                "放松", "请换右手发力",
                "放松", "请换左手发力",
                "放松", "请换右手发力",
                "放松", "请换左手发力",
                "放松", "请换右手发力",
                "放松", "请换左手发力",
            ),
            speaks,
        )

        advanceSeconds(8 + 5) // rep 8 relax done -> finish
        val final = engine.drainEvents().filterIsInstance<EngineEvent.Speak>().map { it.text }
        assertEquals(Phase.DONE, engine.state.phase)
        assertTrue("finish speaks=$final", final.contains("舒缓训练完成"))
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
    fun `last 3 seconds announce a spoken countdown number each decrement`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        engine.drainEvents() // clear start events
        // Opening 3s prepare announces 2-1 (3 is covered by the start cue)
        advanceMs(1000)
        assertTrue(engine.drainEvents().contains(EngineEvent.Countdown(2)))
        advanceMs(1000)
        assertTrue(engine.drainEvents().contains(EngineEvent.Countdown(1)))
        // Contract (8s) announces the full 3-2-1 on its tail
        advanceMs(1000) // prepare done -> contract begins
        engine.drainEvents()
        advanceSeconds(5) // remaining 7 -> 3
        assertTrue(engine.drainEvents().contains(EngineEvent.Countdown(3)))
        // No numbers outside the last-3 window
        assertTrue(engine.drainEvents().none { it is EngineEvent.Countdown })
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
        // (the stage-switch prepares are carved out of the last relax of each
        // stage, so they add no extra time)
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
        advanceSeconds(3 + 6 * 30) // stage 1 done -> stage 2 first contract
        assertEquals("侧向抗阻", engine.state.stageName)
        assertEquals(1, engine.state.si)
        advanceSeconds(6 * 30) // stage 2 done (prepare carved from its relax) -> stage 3 first contract
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
    fun `stage switch prepare is carved out of the last relax`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        engine.drainEvents()
        advanceSeconds(3 + 5 * 30 + 15) // rep 6 contract done -> last relax of stage 1
        // Carve: this relax runs 15 - 5 = 10s instead of the configured 15
        assertEquals(Phase.RELAX, engine.state.phase)
        assertEquals(10, engine.state.countdown)
        assertEquals(10, engine.state.relaxTotalSec)
        advanceSeconds(10) // shortened relax done -> stage-switch prepare
        assertEquals(Phase.PREPARE, engine.state.phase)
        assertEquals(1, engine.state.si)
        assertEquals(5, engine.state.countdown)
        val prep = engine.drainEvents().filterIsInstance<EngineEvent.Speak>()
        // Heads-up cue only: the stage name plus "请准备" (no hand hint yet)
        assertTrue("prep speaks=$prep", prep.contains(EngineEvent.Speak("侧向抗阻训练，请准备")))
        advanceSeconds(5) // prepare done -> stage 2 first contract
        assertEquals(Phase.CONTRACT, engine.state.phase)
        val events = engine.drainEvents()
        assertTrue(events.contains(EngineEvent.Speak("侧向抗阻训练开始")))
    }

    @Test
    fun `band stage announces continue after each relax`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        advanceSeconds(3 + 6 * 30 + 6 * 30) // reach band stage, first contract
        val entry = engine.drainEvents().filterIsInstance<EngineEvent.Speak>().map { it.text }
        assertTrue("entry speaks=$entry", entry.contains("弹力带训练开始"))
        advanceSeconds(15 + 30) // band group 1 done -> group 2 contract
        val events = engine.drainEvents().filterIsInstance<EngineEvent.Speak>().map { it.text }
        // After the band relax, invite the user to continue the next set
        assertTrue("group2 speaks=$events", events.contains("继续训练") )
        assertTrue("group2 speaks=$events", events.none { it.contains("请换") })
    }

    @Test
    fun `stage prepare seconds come from config`() {
        Config.configure("""
            {"prepareSec": 1, "stagePrepareSec": 2,
             "modes": {
               "gentle": [ { "name": "温和发力", "dirs": ["左手", "右手"], "contractSec": 8, "relaxSec": 5, "groups": 4 } ],
               "isometric": [
                 { "name": "正向抗阻", "dirs": ["右手", "左手"], "contractSec": 2, "relaxSec": 1, "groups": 1 },
                 { "name": "侧向抗阻", "dirs": ["右手", "左手"], "contractSec": 2, "relaxSec": 1, "groups": 1 }
               ]
             }}
        """.trimIndent())
        try {
            engine.setMode(Mode.ISOMETRIC)
            engine.start()
            engine.drainEvents()
            // prepare 1 + rep1 (contract 2 + relax 1) + rep2 contract 2 = 6s,
            // then the last relax converts fully into the stage-switch prepare
            // (relax 1s < stagePrepareSec 2s: capped at the configured relax).
            // Landing exactly on the boundary tick asserts the carved prepare.
            advanceSeconds(6)
            assertEquals(Phase.PREPARE, engine.state.phase)
            assertEquals(1, engine.state.si)
            assertEquals(1, engine.state.countdown)
        } finally {
            Config.configure(DEFAULT_TIMING_JSON) // restore for other tests
        }
    }

    @Test
    fun `isometric finish announces completion`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.start()
        val total = 3 + 6 * 30 + 6 * 30 + 3 * 45
        advanceSeconds(total)
        val events = engine.drainEvents().filterIsInstance<EngineEvent.Speak>().map { it.text }
        assertTrue("finish speaks=$events",
            events.any { it.contains("等长抗阻训练完成") })
    }

    @Test
    fun `finish celebrates all done when the other mode was already completed today`() {
        engine.setMode(Mode.ISOMETRIC)
        engine.otherModeDoneToday = true
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
    fun `pause does not consume the remaining phase time on resume`() {
        engine.setMode(Mode.GENTLE)
        engine.start()
        advanceSeconds(5)       // 2s into the first contract, 6s left
        engine.togglePause()
        advanceSeconds(100)     // paused for 100 real seconds
        engine.togglePause()    // resume
        // The pause must not eat the contract's remaining time...
        assertEquals(Phase.CONTRACT, engine.state.phase)
        assertEquals(6, engine.state.countdown)
        advanceSeconds(6)       // ...the contract only ends after its full span
        assertEquals(Phase.RELAX, engine.state.phase)
        // Active time only: the 100 paused seconds never enter elapsed
        assertEquals(11, engine.state.elapsed)
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

