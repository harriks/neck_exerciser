package com.spineexercise.timer

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// ===================== UI State =====================

data class TimerState(
    val mode: Mode = Mode.GENTLE,
    val phase: Phase = Phase.IDLE,
    val si: Int = 0,
    val di: Int = 0,
    val gi: Int = 0,
    val repCount: Int = 0,
    val countdown: Int = 0,
    val elapsed: Int = 0,       // total elapsed seconds
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
) {
    // Derived display (cheap string concat from cached fields)
    val dispGroup get() = gi + 1
    val maxGroup get() = dirCount * groupCount
    val completedGroups: Int get() {
        // Pre-computed total minus remaining
        var c = 0
        val st = Config.stages[mode]!!
        for (i in 0 until si) c += st[i].dirs.size * st[i].groups
        return c + gi
    }

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

    val statusText: String get() = when (phase) {
        Phase.IDLE -> "空闲"
        Phase.DONE -> if (mode == Mode.ISOMETRIC) "完成！共 $totalGroupsAll 组 · 用时 $elapsedText"
            else "完成！共 $repCount 次 · 用时 $elapsedText"
        else -> if (mode == Mode.ISOMETRIC)
            "阶段 ${si + 1}/$stageCount · $stageName · $handName 第$dispGroup/${maxGroup}组 [${countdown}s]"
        else
            "温和发力 · $handName 第${repCount + 1}/${groupCount}次 [${countdown}s]"
    }

    val elapsedText: String get() {
        val m = elapsed / 60
        val s = elapsed % 60
        return "${m}:${String.format("%02d", s)}"
    }
}

// ===================== Factory =====================

private fun buildState(mode: Mode, si: Int = 0, di: Int = 0, gi: Int = 0,
                       repCount: Int = 0, countdown: Int = 0,
                       phase: Phase = Phase.IDLE, running: Boolean = false, paused: Boolean = false): TimerState {
    val st = Config.stages[mode]!![si]
    return TimerState(
        mode = mode, phase = phase, si = si, di = di, gi = gi,
        repCount = repCount, countdown = countdown, running = running, paused = paused,
        stageName = st.name, handName = st.dirs[di],
        contractSec = st.contractSec, relaxSec = st.relaxSec,
        dirCount = st.dirs.size, groupCount = st.groups,
        totalGroupsAll = Config.totalGroups(mode), stageCount = Config.stages[mode]!!.size,
    )
}

// ===================== ViewModel =====================

class TimerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(buildState(Mode.GENTLE))
    val state: StateFlow<TimerState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var tts: TextToSpeech? = null
    private var toneGen: ToneGenerator? = null
    private var ttsReady = false

    init {
        toneGen = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (e: Exception) { null }
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                tts?.setSpeechRate(1.1f)
                ttsReady = true
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        tts?.stop(); tts?.shutdown()
        toneGen?.release()
    }

    // ===================== Actions =====================

    fun startWorkout() {
        val s = _state.value
        if (s.running) return
        _state.value = buildState(s.mode, phase = Phase.PREPARE, countdown = 3, running = true)
        sfxStart(); speak("开始，准备")
        startTick()
    }

    fun togglePause() {
        val s = _state.value
        if (!s.running) return
        val nowPaused = !s.paused
        _state.value = s.copy(paused = nowPaused)
        if (nowPaused) speak("已暂停")
    }

    fun resetWorkout() {
        tickJob?.cancel()
        _state.value = buildState(_state.value.mode)
    }

    fun setMode(mode: Mode) {
        tickJob?.cancel()
        _state.value = buildState(mode)
    }

    // ===================== Helpers =====================

    private fun nextHandName(s: TimerState): String {
        val st = Config.stages[s.mode]!![s.si]
        return if (st.dirs.size > 1) {
            st.dirs[(s.di + 1) % st.dirs.size]
        } else {
            st.dirs[0]
        }
    }

    // ===================== Tick =====================

    private fun startTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = _state.value
                if (!s.running || s.paused) continue

                if (s.countdown > 1) {
                    val newCd = s.countdown - 1
                    _state.value = s.copy(countdown = newCd, elapsed = s.elapsed + 1)
                    if (newCd <= 3) sfxTick()
                } else {
                    // Increment elapsed on the tick that hits zero
                    _state.value = s.copy(elapsed = s.elapsed + 1)
                    onPhaseComplete()
                }
            }
        }
    }

    private fun onPhaseComplete() {
        val s = _state.value
        when (s.phase) {
            Phase.PREPARE -> startPhase(Phase.CONTRACT)
            Phase.CONTRACT -> {
                if (s.relaxSec <= 0) advanceAndContinue()
                else startPhase(Phase.RELAX)
            }
            Phase.RELAX -> advanceAndContinue()
            else -> {}
        }
    }

    private fun advanceAndContinue() {
        val s = _state.value
        val newRep = s.repCount + 1

        if (s.mode == Mode.ISOMETRIC) {
            val (newGi, newDi, newSi, hasMore) = advanceGroup(s)
            if (!hasMore) { finishWorkout(); return }
            _state.value = buildState(s.mode, newSi, newDi, newGi, newRep,
                Config.stages[s.mode]!![newSi].contractSec, Phase.CONTRACT, true)
            speak("换${Config.stages[s.mode]!![newSi].dirs[newDi]}")
            sfxSwitch()
        } else {
            if (newRep >= s.groupCount) { finishWorkout(); return }
            val newDi = (s.di + 1) % s.dirCount
            _state.value = buildState(s.mode, s.si, newDi, newRep, newRep,
                s.contractSec, Phase.CONTRACT, true)
            speak("换${Config.stages[s.mode]!![s.si].dirs[newDi]}")
            sfxSwitch()
        }
    }

    private data class AdvanceResult(val gi: Int, val di: Int, val si: Int, val hasMore: Boolean)

    private fun advanceGroup(s: TimerState): AdvanceResult {
        var gi = s.gi + 1
        var di = s.di
        var si = s.si
        val stages = Config.stages[s.mode]!!

        if (s.dirCount > 1) {
            di = (di + 1) % s.dirCount
            if (di == 0 && gi / s.dirCount >= s.groupCount) {
                return AdvanceResult(0, 0, si + 1, si + 1 < stages.size)
            }
        } else {
            if (gi >= s.groupCount) {
                return AdvanceResult(0, 0, si + 1, si + 1 < stages.size)
            }
        }
        return AdvanceResult(gi, di, si, true)
    }

    private fun startPhase(p: Phase) {
        val s = _state.value
        val cd = when (p) {
            Phase.PREPARE -> 3
            Phase.CONTRACT -> s.contractSec
            Phase.RELAX -> s.relaxSec
            else -> 0
        }
        _state.value = s.copy(phase = p, countdown = cd)
        when (p) {
            Phase.CONTRACT -> {
                val verb = if (s.repCount == 0 && s.gi == 0 && s.si == 0) "用" else "换"
                speak("$verb${s.handName}"); sfxSwitch()
            }
            Phase.RELAX -> speak("放松")
            else -> {}
        }
    }

    private fun finishWorkout() {
        val s = _state.value
        _state.value = s.copy(phase = Phase.DONE, running = false, paused = false, countdown = 0)
        speak("完成，做得好"); sfxDone()
    }

    // ===================== Audio =====================

    private fun sfxStart()  { toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 150) }
    private fun sfxSwitch() { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200) }
    private fun sfxTick()   { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
    private fun sfxDone() {
        toneGen?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
        viewModelScope.launch {
            delay(250); toneGen?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            delay(350); toneGen?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }
}
