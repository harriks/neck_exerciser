package com.spineexercise.timer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for the bilingual string bundle and config-name localization. */
class L10nTest {

    @Before
    fun resetToChinese() { L10n.set(Lang.ZH) }

    @After
    fun restoreChinese() { L10n.set(Lang.ZH) }

    @Test
    fun `default language is chinese`() {
        assertEquals(Lang.ZH, L10n.lang)
        assertEquals("开始", L10n.s.start)
        assertEquals("放松", L10n.s.relaxCue)
    }

    @Test
    fun `switching to english swaps bundles`() {
        L10n.set(Lang.EN)
        assertEquals("Start", L10n.s.start)
        assertEquals("Relax", L10n.s.relaxCue)
        assertEquals("Congratulations, all done, great job", L10n.s.finishCue)
    }

    @Test
    fun `stage names translate in english and pass through in chinese`() {
        assertEquals("正向抗阻", L10n.stage("正向抗阻"))
        L10n.set(Lang.EN)
        assertEquals("Gentle Effort", L10n.stage("温和发力"))
        assertEquals("Front Resistance", L10n.stage("正向抗阻"))
        assertEquals("Side Resistance", L10n.stage("侧向抗阻"))
        assertEquals("Resistance Band", L10n.stage("弹力带训练"))
        // Unknown names (future config edits) pass through unchanged
        assertEquals("自定义阶段", L10n.stage("自定义阶段"))
    }

    @Test
    fun `direction names translate`() {
        L10n.set(Lang.EN)
        assertEquals("right hand", L10n.dir("右手"))
        assertEquals("left hand", L10n.dir("左手"))
        assertEquals("resistance band", L10n.dir("弹力带"))
        assertEquals("未知方向", L10n.dir("未知方向"))
    }

    @Test
    fun `talk form guarantees a single suffix in chinese`() {
        assertEquals("侧向抗阻训练", L10n.talk("侧向抗阻"))
        assertEquals("弹力带训练", L10n.talk("弹力带训练")) // no double suffix
        L10n.set(Lang.EN)
        assertEquals("Side Resistance", L10n.talk("侧向抗阻"))
    }

    @Test
    fun `tts locale follows language`() {
        assertEquals(java.util.Locale.SIMPLIFIED_CHINESE, L10n.ttsLocale)
        L10n.set(Lang.EN)
        assertEquals(java.util.Locale.US, L10n.ttsLocale)
    }

    @Test
    fun `engine announcements and labels follow the active language`() {
        var t = 0L
        val engine = WorkoutEngine(nowMs = { t })
        L10n.set(Lang.EN)
        try {
            engine.setMode(Mode.GENTLE)
            engine.start()
            engine.drainEvents() // start announcement emitted in English
            t += 3_000; engine.tick() // prepare done -> first contract
            val speaks = engine.drainEvents()
                .filterIsInstance<EngineEvent.Speak>().map { it.text }
            assertTrue("speaks=$speaks", speaks.contains("Switch to your right hand"))
            assertEquals("Pushing with right hand", engine.state.phaseLabel)
            assertEquals("Ready", WorkoutEngine(nowMs = { t }).state.phaseText)
        } finally {
            L10n.set(Lang.ZH)
        }
    }
}
