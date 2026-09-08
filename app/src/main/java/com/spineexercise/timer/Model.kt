package com.spineexercise.timer

import androidx.compose.ui.graphics.Color

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
    val stages = mapOf(
        Mode.GENTLE to listOf(
            ExerciseStage("温和发力", listOf("左手", "右手"), 8, 5, 8),
        ),
        Mode.ISOMETRIC to listOf(
            ExerciseStage("正向抗阻", listOf("右手", "左手"), 15, 15, 3),
            ExerciseStage("侧向抗阻", listOf("右手", "左手"), 15, 15, 3),
            ExerciseStage("弹力带训练", listOf("弹力带"), 15, 30, 3),
        ),
    )

    val tips = mapOf(
        Mode.GENTLE to listOf(
            "力度：只需 20%～30% 的轻微力量，绝不能使出全力",
            "支撑手：每个发力回合交替左手/右手，会有语音与文字提示",
            "呼吸：发力与放松时保持均匀顺畅，不要憋气",
            "节奏：准备 3 秒 → 发力 8 秒 → 放松 5 秒，左右交替 8 次",
            "体感：以轻微酸胀感为宜，如有疼痛请立即停止",
        ),
        Mode.ISOMETRIC to listOf(
            "阶段1：正向抗阻，左右手交替各3组（共6组），每组15秒、休息15秒",
            "阶段2：侧向抗阻，左右手交替各3组（共6组），每组15秒、休息15秒",
            "阶段3：弹力带训练，3组，每组15秒、休息30秒",
            "力度：持续静态发力，保持稳定不晃动，20%～30% 轻微力量",
            "呼吸：全程保持均匀呼吸，切勿憋气",
            "体感：以持续轻微酸胀为宜，如有疼痛请立即停止",
        ),
    )

    fun totalGroups(mode: Mode): Int =
        stages[mode]!!.sumOf { it.dirs.size * it.groups }

    fun completedGroups(mode: Mode, si: Int, gi: Int): Int {
        var c = 0
        val st = stages[mode]!!
        for (i in 0 until si) c += st[i].dirs.size * st[i].groups
        return c + gi
    }
}

// ===================== Colors =====================

object PhaseColors {
    val contract = Color(0xFFFF7043)
    val relax = Color(0xFF4FC3F7)
    val prepare = Color(0xFFFFCA28)
    val idle = Color(0xFF607D8B)
    val done = Color(0xFF9CCC65)

    fun forPhase(phase: Phase) = when (phase) {
        Phase.CONTRACT -> contract
        Phase.RELAX -> relax
        Phase.PREPARE -> prepare
        Phase.IDLE -> idle
        Phase.DONE -> done
    }
}
