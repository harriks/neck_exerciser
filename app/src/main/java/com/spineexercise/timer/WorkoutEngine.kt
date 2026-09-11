package com.spineexercise.timer

import kotlin.math.ceil

// ===================== UI State =====================
// Pure Kotlin (no Android imports) so it can be unit-tested directly.

data class TimerState(
    val mode: Mode = Mode.GENTLE,
    val phase: Phase = Phase.IDLE,
    val si: Int = 0,
    val di: Int = 0,
    val gi: Int = 0,
    val repCount: Int = 0,
    val countdown: Int = 0,
    val elapsed: Int = 0,       // total elapsed seconds (anchor-based, drift-free)
    val running: Boolean = false,
    val paused: Boolean = false,
    // Pre-computed display fields (avoid recalculation on every read)
    val stageName: String = "",
    val handName: String = "",
    val contractSec: Int = 0,
    val relaxSec: Int = 0,
    val dirCount: Int = 0,
    val groupCount: Int = 0,
    val totalGroupsAll: Int = 0,
    val stageCount: Int = 0,
    val completedGroups: Int = 0, // pre-computed: groups fully completed before current
) {
    val phaseText: String get() = when (phase) {
        Phase.IDLE -> "准备就绪"
        Phase.PREPARE -> "⏳ 准备"
        Phase.CONTRACT -> if (mode == Mode.ISOMETRIC) "💪 $stageName" else "💪 温和发力"
        Phase.RELAX -> "🍃 放松"
        Phase.DONE -> "🎉 完成"
    }

    val phaseLabel: String get() = when (phase) {
        Phase.IDLE -> "等待中"
        Phase.PREPARE -> "倒计时"
        Phase.CONTRACT -> "${handName}发力中"
        Phase.RELAX -> "放松中"
        Phase.DONE -> "已完成"
    }

    val phaseHint: String get() = when (phase) {
        Phase.IDLE -> "点击「开始」按钮，跟随节奏锻炼颈椎"
        Phase.PREPARE -> "请就位，倒计时结束后开始发力"
        Phase.CONTRACT -> if (mode == Mode.ISOMETRIC) "保持稳定，均匀呼吸，切勿憋气"
            else "20%~30% 轻微力量，${handName}支撑"
        Phase.RELAX -> "彻底松开，让肌肉休息"
        Phase.DONE -> "做得好！请缓慢起身，避免突然动作"
    }

    val elapsedText: String get() {
        val m = elapsed / 60
        val s = (elapsed % 60).toString().padStart(2, '0')
        return "$m:$s"
    }
}

// ===================== State factory =====================

internal fun buildState(mode: Mode, si: Int = 0, di: Int = 0, gi: Int = 0,
                        repCount: Int = 0, countdown: Int = 0,
                        phase: Phase = Phase.IDLE, running: Boolean = false, paused: Boolean = false,
                        elapsed: Int = 0): TimerState {
    val st = Config.stageOf(mode, si)
    return TimerState(
        mode = mode, phase = phase, si = si, di = di, gi = gi,
        repCount = repCount, countdown = countdown, running = running, paused = paused,
        elapsed = elapsed,
        stageName = st.name, handName = st.dirs[di],
        contractSec = st.contractSec, relaxSec = st.relaxSec,
        dirCount = st.dirs.size, groupCount = st.groups,
        totalGroupsAll = Config.totalGroups(mode),
        stageCount = Config.stagesOf(mode).size,
        completedGroups = Config.completedGroups(mode, si, gi),
    )
}

// ===================== Events =====================

sealed class EngineEvent {
    data class Speak(val text: String) : EngineEvent()
    enum class SfxType { START, SWITCH, TICK, DONE }
    data class Sfx(val type: SfxType) : EngineEvent()
}

// ===================== Workout Engine =====================

/**
 * Pure-Kotlin workout state machine.
 *
 * Timing is anchor-based: each phase records an absolute end timestamp
 * (phaseEndMs) instead of decrementing a counter every second, so countdown
 * and total elapsed never drift regardless of tick jitter. The clock is
 * injected so tests can use a fake monotonic clock.
 *
 * Audio/voice prompts are emitted as [EngineEvent]s and drained by the host
 * (ViewModel), keeping this class free of Android dependencies.
 */
class WorkoutEngine(private val nowMs: () -> Long = System::currentTimeMillis) {

    private var _state: TimerState = buildState(Mode.GENTLE)
    val state: TimerState get() = _state

    /**
     * Absolute end timestamp of the current phase, valid only while actively
     * running (not paused/done). Hosts can use it to sleep until just past the
     * boundary instead of busy-polling every frame.
     */
    val phaseEndBoundaryMs: Long
        get() = if (_state.running && !_state.paused) phaseEndMs else Long.MAX_VALUE

    private val pendingEvents = mutableListOf<EngineEvent>()

    // Timing anchors
    private var phaseEndMs = 0L       // absolute end of current phase
    private var accumulatedMs = 0L    // active time accumulated before current segment
    private var segmentStartMs = 0L   // start of current active segment (pause-aware)

    fun setMode(mode: Mode) {
        _state = buildState(mode)
        phaseEndMs = 0L
        accumulatedMs = 0L
        segmentStartMs = 0L
    }

    fun start() {
        val s = _state
        if (s.running) return
        val now = nowMs()
        accumulatedMs = 0L
        segmentStartMs = now
        _state = buildState(s.mode, phase = Phase.PREPARE, countdown = Config.prepareSec, running = true)
        phaseEndMs = now + Config.prepareSec * 1000L
        pendingEvents += EngineEvent.Speak(
            if (s.mode == Mode.ISOMETRIC) "${stageTalk(Config.stageOf(s.mode, 0))}开始"
            else "开始，准备"
        )
        pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.START)
    }

    /** Sync pause state without announcements (UI speaks the action itself). */
    fun setPaused(paused: Boolean) {
        val s = _state
        if (!s.running || s.paused == paused) return
        if (paused) {
            accumulatedMs += nowMs() - segmentStartMs
            _state = s.copy(paused = true)
        } else {
            segmentStartMs = nowMs()
            _state = s.copy(paused = false)
        }
    }

    fun togglePause() {
        val s = _state
        if (!s.running) return
        setPaused(!s.paused)
        pendingEvents += EngineEvent.Speak(if (_state.paused) "已暂停" else "继续锻炼")
    }

    fun reset(announce: Boolean = false) {
        _state = buildState(_state.mode)
        phaseEndMs = 0L
        accumulatedMs = 0L
        segmentStartMs = 0L
        if (announce) pendingEvents += EngineEvent.Speak("重置")
    }

    /** Advance the machine to the current instant. Safe to call at any rate. */
    fun tick() {
        val s = _state
        if (!s.running || s.paused) return
        val now = nowMs()

        // Phase transitions (loop in case we are badly overdue)
        var guard = 0
        while (phaseEndMs - now <= 0 && guard++ < 1000) {
            advancePhase(now)
            if (_state.phase == Phase.DONE || !_state.running) break
        }
        if (_state.phase == Phase.DONE || !_state.running) return

        // Drift-free countdown & elapsed
        val st = _state
        val remainingMs = (phaseEndMs - now).coerceAtLeast(0)
        val remaining = ceil(remainingMs / 1000.0).toInt()
        val elapsedSec = ((accumulatedMs + (now - segmentStartMs)) / 1000).toInt()

        if (remaining != st.countdown || elapsedSec != st.elapsed) {
            // Last-3-seconds beep (matches legacy behavior: any phase, on decrement)
            if (remaining < st.countdown && remaining in 1..3) {
                pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.TICK)
            }
            _state = st.copy(countdown = remaining, elapsed = elapsedSec)
        }
    }

    fun drainEvents(): List<EngineEvent> {
        if (pendingEvents.isEmpty()) return emptyList()
        val out = pendingEvents.toList()
        pendingEvents.clear()
        return out
    }

    // ===================== Internals =====================

    private fun currentElapsedSec(now: Long, paused: Boolean): Int {
        val active = if (paused) accumulatedMs else accumulatedMs + (now - segmentStartMs)
        return (active / 1000).toInt()
    }

        private fun advancePhase(now: Long) {
        val s = _state
        when (s.phase) {
            Phase.PREPARE -> beginContract(now, s)
            Phase.CONTRACT -> {
                if (s.relaxSec <= 0) advanceAndContinue(now)
                else beginPhase(now, Phase.RELAX, s.relaxSec)
            }
            Phase.RELAX -> advanceAndContinue(now)
            else -> { phaseEndMs = Long.MAX_VALUE }
        }
    }

    private fun beginPhase(now: Long, p: Phase, sec: Int) {
        _state = _state.copy(phase = p, countdown = sec)
        phaseEndMs = now + sec * 1000L
        if (p == Phase.RELAX) pendingEvents += EngineEvent.Speak("放松")
    }

    private fun beginContract(now: Long, s: TimerState) {
        _state = s.copy(phase = Phase.CONTRACT, countdown = s.contractSec)
        phaseEndMs = now + s.contractSec * 1000L
        pendingEvents += EngineEvent.Speak("请换${s.handName}发力")
        pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.SWITCH)
    }

    private fun advanceAndContinue(now: Long) {
        val s = _state
        val newRep = s.repCount + 1
        val elapsedSec = currentElapsedSec(now, paused = false)

        if (s.mode == Mode.ISOMETRIC) {
            val (newGi, newDi, newSi, hasMore) = advanceGroup(s)
            if (!hasMore) { finish(elapsedSec, newRep); return }
            val st = Config.stageOf(s.mode, newSi)
            _state = buildState(s.mode, newSi, newDi, newGi, newRep,
                countdown = st.contractSec, phase = Phase.CONTRACT, running = true,
                elapsed = elapsedSec)
            phaseEndMs = now + st.contractSec * 1000L
            // Stage transition announcement (merged into one utterance: TTS flushes queue).
            // Uniform concise cue: only the next stage's "开始" (no end/hand hints).
            if (newSi != s.si) {
                pendingEvents += EngineEvent.Speak("${stageTalk(st)}开始")
            } else if (st.dirs.size > 1) {
                // 多方向阶段内换手提示
                pendingEvents += EngineEvent.Speak("请换${st.dirs[newDi]}发力")
            } else {
                // 单方向阶段（弹力带）：放松结束后提示继续
                pendingEvents += EngineEvent.Speak("继续训练")
            }
        } else {
            if (newRep >= s.groupCount) { finish(elapsedSec, newRep); return }
            val newDi = (s.di + 1) % s.dirCount
            _state = buildState(s.mode, s.si, newDi, newRep, newRep,
                countdown = s.contractSec, phase = Phase.CONTRACT, running = true,
                elapsed = elapsedSec)
            phaseEndMs = now + s.contractSec * 1000L
            pendingEvents += EngineEvent.Speak("请换${Config.stageOf(s.mode, s.si).dirs[newDi]}发力")
        }
        pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.SWITCH)
    }

    private data class AdvanceResult(val gi: Int, val di: Int, val si: Int, val hasMore: Boolean)

    // Announcement name: ensure the "训练" suffix appears exactly once.
    private fun stageTalk(st: ExerciseStage): String =
        if (st.name.endsWith("训练")) st.name else "${st.name}训练"

    private fun advanceGroup(s: TimerState): AdvanceResult {
        var gi = s.gi + 1
        var di = s.di
        val si = s.si
        val stageTotal = Config.stagesOf(s.mode).size

        if (s.dirCount > 1) {
            di = (di + 1) % s.dirCount
            if (di == 0 && gi / s.dirCount >= s.groupCount) {
                return AdvanceResult(0, 0, si + 1, si + 1 < stageTotal)
            }
        } else {
            if (gi >= s.groupCount) {
                return AdvanceResult(0, 0, si + 1, si + 1 < stageTotal)
            }
        }
        return AdvanceResult(gi, di, si, true)
    }

    private fun finish(elapsedSec: Int, newRep: Int) {
        _state = _state.copy(
            phase = Phase.DONE, running = false, paused = false,
            repCount = newRep,
            countdown = 0, elapsed = elapsedSec,
        )
        phaseEndMs = Long.MAX_VALUE
        pendingEvents += EngineEvent.Speak("恭喜，全部完成，做得好")
        pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.DONE)
    }
}



