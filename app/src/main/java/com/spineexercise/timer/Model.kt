package com.spineexercise.timer

// ===================== Data Models =====================

data class ExerciseStage(
    val name: String,
    val dirs: List<String>,
    val contractSec: Int,
    val relaxSec: Int,
    val groups: Int,
)

enum class Mode { GENTLE, ISOMETRIC }

enum class Phase { IDLE, PREPARE, CONTRACT, RELAX, DONE }

// ===================== Config =====================

object Config {
    // Timing (durations/groups/prepare) is sourced from JSON via TimingConfig,
    // so all countdown values can be tuned in one JSON block.
    var timing: TimingConfig = TimingConfig.default()

    /** Reload all durations from a JSON config string. */
    fun configure(json: String) { timing = TimingConfig.fromJson(json) }

    val prepareSec: Int get() = timing.prepareSec

    val stagePrepareSec: Int get() = timing.stagePrepareSec

    val stages: Map<Mode, List<ExerciseStage>> get() = timing.stages

    // Non-throwing, non-"!!" accessors
    fun stagesOf(mode: Mode): List<ExerciseStage> = stages.getValue(mode)

    fun stageOf(mode: Mode, si: Int): ExerciseStage = stagesOf(mode)[si]

    fun totalGroups(mode: Mode): Int =
        stagesOf(mode).sumOf { it.dirs.size * it.groups }

    fun completedGroups(mode: Mode, si: Int, gi: Int): Int {
        var c = 0
        val st = stagesOf(mode)
        for (i in 0 until si) c += st[i].dirs.size * st[i].groups
        return c + gi
    }
}