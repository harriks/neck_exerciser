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
    // Actual duration the relax phase runs at (== relaxSec, except the stage-
    // switch carved relax which is shorter — see beginRelax). UI reads this for
    // the ring denominator instead of the configured relaxSec.
    val relaxTotalSec: Int = 0,
) {
    // Display fields resolve through L10n at read time, so a language switch
    // (⋮ → Language) applies on the next recomposition without state changes.
    val phaseText: String get() {
        val s = L10n.s
        return when (phase) {
            Phase.IDLE -> s.ready
            Phase.PREPARE -> s.prepare
            Phase.CONTRACT -> s.contractText(L10n.stage(stageName)) // gentle's single stage is 温和发力
            Phase.RELAX -> s.relaxText
            Phase.DONE -> s.done
        }
    }

    val phaseLabel: String get() {
        val s = L10n.s
        return when (phase) {
            Phase.IDLE -> s.waiting
            Phase.PREPARE -> s.countdown
            Phase.CONTRACT -> s.contractLabel(L10n.dir(handName))
            Phase.RELAX -> s.relaxing
            Phase.DONE -> s.complete
        }
    }

    val phaseHint: String get() {
        val s = L10n.s
        return when (phase) {
            Phase.IDLE -> s.idleHint
            Phase.PREPARE -> if (si > 0) s.nextStageHint(L10n.stage(stageName)) else s.prepareHint
            Phase.CONTRACT -> if (mode == Mode.ISOMETRIC) s.isoContractHint
                else s.gentleContractHint(L10n.dir(handName))
            Phase.RELAX -> s.relaxHint
            Phase.DONE -> s.doneHint
        }
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
        relaxTotalSec = st.relaxSec,
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
    private var pausedAtMs = 0L       // wall time when the current pause began

    fun setMode(mode: Mode) {
        _state = buildState(mode)
        phaseEndMs = 0L
        accumulatedMs = 0L
        segmentStartMs = 0L
        pausedAtMs = 0L
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
            if (s.mode == Mode.ISOMETRIC) L10n.s.stageStart(L10n.talk(Config.stageOf(s.mode, 0).name))
            else L10n.s.startGentle
        )
        pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.START)
    }

    /** Sync pause state without announcements (UI speaks the action itself). */
    fun setPaused(paused: Boolean) {
        val s = _state
        if (!s.running || s.paused == paused) return
        if (paused) {
            pausedAtMs = nowMs()
            accumulatedMs += pausedAtMs - segmentStartMs
            _state = s.copy(paused = true)
        } else {
            val now = nowMs()
            // Shift the phase end by the pause length: a pause must never
            // consume the remaining phase time (resuming after a long pause
            // would otherwise skip straight past the phase boundary).
            phaseEndMs += now - pausedAtMs
            segmentStartMs = now
            _state = s.copy(paused = false)
        }
    }

    fun togglePause() {
        val s = _state
        if (!s.running) return
        setPaused(!s.paused)
        pendingEvents += EngineEvent.Speak(if (_state.paused) L10n.s.pausedCue else L10n.s.resumeCue)
    }

    fun reset(announce: Boolean = false) {
        _state = buildState(_state.mode)
        phaseEndMs = 0L
        accumulatedMs = 0L
        segmentStartMs = 0L
        pausedAtMs = 0L
        if (announce) pendingEvents += EngineEvent.Speak(L10n.s.resetCue)
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
                else beginRelax(now, s)
            }
            Phase.RELAX -> advanceAndContinue(now)
            else -> { phaseEndMs = Long.MAX_VALUE }
        }
    }

    /**
     * Enter RELAX. When the next rep would switch stages, the stage-switch
     * prepare window is carved out of this (last) relax — its announcement and
     * yellow countdown then play during what used to be the tail of the rest
     * period, so the pause between stages stays exactly the configured
     * relaxSec and no extra waiting is added.
     */
    private fun beginRelax(now: Long, s: TimerState) {
        val switchAhead = advanceGroup(s).let { it.hasMore && it.si != s.si }
        val total = if (switchAhead && Config.stagePrepareSec > 0)
            (s.relaxSec - Config.stagePrepareSec).coerceAtLeast(0)
        else s.relaxSec
        if (total <= 0) { advanceAndContinue(now); return } // relax fully converted to prepare
        _state = s.copy(phase = Phase.RELAX, countdown = total, relaxTotalSec = total)
        phaseEndMs = now + total * 1000L
        pendingEvents += EngineEvent.Speak(L10n.s.relaxCue)
    }

    private fun beginContract(now: Long, s: TimerState) {
        _state = s.copy(phase = Phase.CONTRACT, countdown = s.contractSec)
        phaseEndMs = now + s.contractSec * 1000L
        // si > 0 means this PREPARE was a stage-switch one (start() is the only
        // other PREPARE entry and always at si = 0): announce the stage start
        // instead of the hand cue (also avoids "请换弹力带发力").
        pendingEvents += if (s.si > 0)
            EngineEvent.Speak(L10n.s.stageStart(L10n.talk(Config.stageOf(s.mode, s.si).name)))
        else
            EngineEvent.Speak(L10n.s.changeHand(L10n.dir(s.handName)))
        pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.SWITCH)
    }

    private fun advanceAndContinue(now: Long) {
        val s = _state
        val newRep = s.repCount + 1
        val elapsedSec = currentElapsedSec(now, paused = false)

        // One path for every mode: gentle is just a single-stage config, so
        // advanceGroup's stage/direction/group math covers it identically
        // (groups is per-direction; a stage ends after dirs × groups reps).
        val (newGi, newDi, newSi, hasMore) = advanceGroup(s)
        if (!hasMore) { finish(elapsedSec, newRep); return }
        val st = Config.stageOf(s.mode, newSi)
        // Stage switch: give the user time to change position before the new
        // stage starts contracting. The prepare window is carved out of the
        // relax that just ended (see beginRelax), so cap it to that relax time
        // and never add extra waiting (stagePrepareSec = 0 keeps the direct path).
        val prep = if (s.relaxSec > 0) minOf(Config.stagePrepareSec, s.relaxSec)
                   else Config.stagePrepareSec
        if (newSi != s.si && prep > 0) {
            _state = buildState(s.mode, newSi, newDi, newGi, newRep,
                countdown = prep, phase = Phase.PREPARE, running = true,
                elapsed = elapsedSec)
            phaseEndMs = now + prep * 1000L
            pendingEvents += EngineEvent.Speak(L10n.s.stageGetReady(L10n.talk(st.name)))
            pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.SWITCH)
            return
        }
        _state = buildState(s.mode, newSi, newDi, newGi, newRep,
            countdown = st.contractSec, phase = Phase.CONTRACT, running = true,
            elapsed = elapsedSec)
        phaseEndMs = now + st.contractSec * 1000L
        // Stage transition announcement (merged into one utterance: TTS flushes queue).
        // Uniform concise cue: only the next stage's "开始" (no end/hand hints).
        if (newSi != s.si) {
            pendingEvents += EngineEvent.Speak(L10n.s.stageStart(L10n.talk(st.name)))
        } else if (st.dirs.size > 1) {
            // 多方向阶段内换手提示（舒缓单阶段也走这里：逐轮换手）
            pendingEvents += EngineEvent.Speak(L10n.s.changeHand(L10n.dir(st.dirs[newDi])))
        } else {
            // 单方向阶段（弹力带）：放松结束后提示继续
            pendingEvents += EngineEvent.Speak(L10n.s.continueCue)
        }
        pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.SWITCH)
    }

    private data class AdvanceResult(val gi: Int, val di: Int, val si: Int, val hasMore: Boolean)

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
        pendingEvents += EngineEvent.Speak(L10n.s.finishCue)
        pendingEvents += EngineEvent.Sfx(EngineEvent.SfxType.DONE)
    }
}



