package com.spineexercise.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the hand-rolled JSON timing config parser (pure Kotlin). */
class TimingConfigTest {

    @Test
    fun `default config parses with current values`() {
        val c = TimingConfig.default()
        assertEquals(3, c.prepareSec)
        val gentle = c.stages.getValue(Mode.GENTLE)
        assertEquals(1, gentle.size)
        assertEquals("温和发力", gentle[0].name)
        assertEquals(listOf("左手", "右手"), gentle[0].dirs)
        assertEquals(8, gentle[0].contractSec)
        assertEquals(5, gentle[0].relaxSec)
        assertEquals(4, gentle[0].groups)
        val iso = c.stages.getValue(Mode.ISOMETRIC)
        assertEquals(3, iso.size)
        assertEquals(15, iso[0].contractSec)
        assertEquals(15, iso[0].relaxSec)
        assertEquals(3, iso[0].groups)
        assertEquals("弹力带训练", iso[2].name)
        assertEquals(30, iso[2].relaxSec)
        assertEquals(1, iso[2].dirs.size)
    }

    @Test
    fun `custom config values are honored`() {
        val json = """
        {
          "prepareSec": 5,
          "modes": {
            "gentle": [
              { "name": "测试", "dirs": ["左手", "右手"], "contractSec": 10, "relaxSec": 6, "groups": 4 }
            ],
            "isometric": [
              { "name": "正向抗阻", "dirs": ["右手", "左手"], "contractSec": 20, "relaxSec": 10, "groups": 2 }
            ]
          }
        }
        """.trimIndent()
        Config.configure(json)
        try {
            assertEquals(5, Config.prepareSec)
            assertEquals(4, Config.stagesOf(Mode.GENTLE)[0].groups)
            assertEquals(20, Config.stagesOf(Mode.ISOMETRIC)[0].contractSec)
        } finally {
            Config.configure(DEFAULT_TIMING_JSON) // restore for other tests
        }
    }

    @Test
    fun `engine start uses configured prepare seconds`() {
        Config.configure("""
            {"prepareSec": 2,
             "modes": {
               "gentle": [ { "name": "温和发力", "dirs": ["左手", "右手"], "contractSec": 8, "relaxSec": 5, "groups": 2 } ],
               "isometric": [ { "name": "正向抗阻", "dirs": ["右手", "左手"], "contractSec": 15, "relaxSec": 15, "groups": 1 } ]
             }}
        """.trimIndent())
        try {
            val now = { 0L }
            val engine = WorkoutEngine(nowMs = now)
            engine.setMode(Mode.GENTLE)
            engine.start()
            assertEquals(2, engine.state.countdown)
        } finally {
            Config.configure(DEFAULT_TIMING_JSON)
        }
    }

    @Test
    fun `malformed json throws`() {
        var threw = false
        try { TimingConfig.fromJson("{ not json") } catch (e: Exception) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `toJson round-trips through fromJson unchanged`() {
        // The runtime settings editor saves toJson(); fromJson must restore it.
        val c = TimingConfig.default()
        assertEquals(c, TimingConfig.fromJson(c.toJson()))
    }

    @Test
    fun `toJson round-trips an edited config`() {
        val edited = TimingConfig(
            prepareSec = 7,
            stages = mapOf(
                Mode.GENTLE to listOf(
                    ExerciseStage("温和发力", listOf("左手", "右手"), contractSec = 12, relaxSec = 9, groups = 6)),
                Mode.ISOMETRIC to listOf(
                    ExerciseStage("正向抗阻", listOf("右手", "左手"), contractSec = 20, relaxSec = 10, groups = 2),
                    ExerciseStage("侧向抗阻", listOf("右手", "左手"), contractSec = 20, relaxSec = 10, groups = 2),
                    ExerciseStage("弹力带训练", listOf("弹力带"), contractSec = 25, relaxSec = 45, groups = 5)),
            ),
        )
        assertEquals(edited, TimingConfig.fromJson(edited.toJson()))
    }
}