package com.spineexercise.timer

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

// ===================== ViewModel =====================
// The state machine lives in WorkoutEngine (pure Kotlin, unit-testable).
// This class only bridges engine events to Android audio (ToneGenerator/TTS)
// and publishes engine state as a StateFlow.

class TimerViewModel(app: Application) : AndroidViewModel(app) {

    // Monotonic clock: immune to wall-clock changes and drift-free
    private val engine = WorkoutEngine(nowMs = { SystemClock.elapsedRealtime() })

    private val _state = MutableStateFlow(engine.state)
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
        engine.start()
        syncAndPlay()
        startTick()
    }

    fun togglePause() {
        engine.togglePause()
        syncAndPlay()
        // The tick loop exits while paused; restart it on resume.
        if (engine.state.running && !engine.state.paused) startTick()
    }

    fun resetWorkout() {
        tickJob?.cancel()
        engine.reset()
        syncAndPlay()
    }

    fun setMode(mode: Mode) {
        tickJob?.cancel()
        engine.setMode(mode)
        syncAndPlay()
    }

    // ===================== Tick =====================

    // Anchor-based computation = drift-free countdown. The loop is adaptive:
    // it sleeps until just past the phase boundary, then ticks in short steps
    // for the countdown seconds - and exits entirely when paused/finished, so
    // no polling happens while idle or paused (saves battery).
    private fun startTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                engine.tick()
                syncAndPlay()
                val st = engine.state
                if (st.paused || !st.running) break
                val untilBoundary = engine.phaseEndBoundaryMs - SystemClock.elapsedRealtime()
                val delayMs = when {
                    untilBoundary <= 0L -> 50L                    // overdue: re-check quickly
                    untilBoundary <= 300L -> untilBoundary + 40L  // land just past the boundary
                    else -> 200L
                }
                delay(delayMs)
            }
        }
    }

    /** Single TTS entry point for UI-originated announcements (button taps). */
    fun speak(text: String) = speakInternal(text)

    private fun syncAndPlay() {
        _state.value = engine.state
        engine.drainEvents().forEach(::handleEvent)
    }

    private fun handleEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Speak -> speak(event.text)
            is EngineEvent.Sfx -> when (event.type) {
                EngineEvent.SfxType.START -> sfxStart()
                EngineEvent.SfxType.SWITCH -> sfxSwitch()
                EngineEvent.SfxType.TICK -> sfxTick()
                EngineEvent.SfxType.DONE -> sfxDone()
            }
        }
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

    private fun speakInternal(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }
}
