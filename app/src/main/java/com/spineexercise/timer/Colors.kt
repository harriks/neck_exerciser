package com.spineexercise.timer

import androidx.compose.ui.graphics.Color

// ===================== Colors =====================
// Moved out of Model.kt so that data/config stay pure Kotlin (unit-testable
// without Android/Compose on the classpath).

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
