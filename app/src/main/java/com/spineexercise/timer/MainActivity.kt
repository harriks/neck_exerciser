package com.spineexercise.timer

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Resolve the app language (in-app override or system locale) before
        // any composable or announcement reads L10n.
        L10nStore.init(this)
        // Apply the user's saved timing override (⋮ → 计时设置) before anything
        // reads Config (engine state is built lazily after setContent).
        TimingStore.load(this).takeIf { it.isNotBlank() }?.let { json ->
            runCatching { Config.configure(json) } // corrupt storage → keep defaults
        }
        // Re-arm the daily reminder with the current alarm mechanism — cheap and
        // idempotent, and migrates any stale alarm from an older app version.
        if (ReminderScheduler.isEnabled(this)) ReminderScheduler.schedule(this)
        setContent { AppTheme { AppNavigation() } }
    }
}

// ===================== Cached constants =====================

private val BgBrush = Brush.linearGradient(listOf(Color(0xFF1A2A3A), Color(0xFF2D3E50), Color(0xFF1A2A3A)))
private val RingBgColor = Color.White.copy(alpha = 0.08f)
private val HintColor = Color(0xFF90A4AE)
private val SubTextColor = Color(0xFFB0BEC5)
private val MutedColor = Color(0xFF78909C)
private val AccentBlue = Color(0xFF81D4FA)
private val AccentCyan = Color(0xFF4FC3F7)
private val AccentBtn = Color(0xFF29B6F6)
private val AccentOrange = Color(0xFFFF7043)
private val DoneGreen = Color(0xFF66BB6A)
private val CountdownNormal = Color(0xFF66BB6A) // 倒计时数字：剩余 >3 秒，绿色
private val CountdownWarn = Color(0xFFFF5252)    // 倒计时数字：剩余最后 3 秒，红色
private val TipHeaderColor = Color(0xFFFFAB91)
private val SegDimColor = Color.White.copy(alpha = 0.1f)
private val ContentColor = Color(0xFFE8EEF2)
private val OnDoneGreen = Color(0xFF14301E) // dark ink legible on the DoneGreen cell fill

private val CardShape = RoundedCornerShape(16.dp)
private val BtnShape = RoundedCornerShape(50.dp)
private val SegShape = RoundedCornerShape(3.dp)
private val TipShape = RoundedCornerShape(12.dp)

// ===================== Theme =====================

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = AccentCyan, secondary = AccentOrange,
        background = Color(0xFF1A2A3A), surface = Color(0xFF2D3E50),
        onPrimary = Color.White, onSecondary = Color.White,
        onBackground = ContentColor, onSurface = ContentColor,
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// ===================== Shared entrance animation =====================

/**
 * Entrance helper: alpha fade-in, [delayMs] staggers siblings into a cascade.
 * Fade-only on purpose — slide/expand enter animations re-run layout every
 * frame and measured 65% janky on the home screen; pure alpha stays cheap.
 */
@Composable
private fun FadeIn(delayMs: Int = 0, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); visible = true }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(260))) { content() }
}

// ===================== TTS Voice =====================

// (Removed) UI-level TTS: all announcements (engine events + button taps) now go
// through the single TextToSpeech instance owned by TimerViewModel, so voices no
// longer overlap (previously tapping start spoke "开始" while the engine spoke
// "开始，准备") and one fewer TTS engine is created per screen.

// ===================== Navigation =====================

@Composable
fun AppNavigation() {
    // rememberSaveable: survives rotation / process recreation
    var selectedMode by rememberSaveable { mutableStateOf<Mode?>(null) }
    // SharedPreferences is the single source of truth for the check-in history
    // (one compact string, see CheckIn.kt / CheckInStore): re-read at the
    // composition root so relaunch, rotation and process death all restore it.
    val context = LocalContext.current
    var checkinsRaw by remember { mutableStateOf(CheckInStore.load(context)) }
    var showCalendar by remember { mutableStateOf(false) }
    // Bumped after an in-app language switch so the whole tree re-reads L10n
    // (plain object property reads are not Compose state).
    var langKey by remember { mutableIntStateOf(0) }

    key(langKey) {
    if (showCalendar) {
        CalendarScreen(
            raw = checkinsRaw,
            onBack = { showCalendar = false },
        )
    } else if (selectedMode == null) {
        val checkinLog = remember(checkinsRaw) { CheckInLog.parse(checkinsRaw) }
        ModeSelectScreen(
            onSelect = { selectedMode = it },
            onOpenCalendar = { showCalendar = true },
            streakDays = checkinLog.currentStreak(LocalDate.now()),
            totalDays = checkinLog.total,
        )
    } else {
        TimerScreen(
            mode = selectedMode!!,
            onBack = { selectedMode = null },
            checkinsRaw = checkinsRaw,
            onCheckIn = { raw ->
                checkinsRaw = raw
                CheckInStore.save(context, raw) // write-through: survives relaunch
            },
            onLanguageChanged = { langKey++ },
        )
    }
    }
}

// ===================== Mode Select Screen =====================

@Composable
fun ModeSelectScreen(onSelect: (Mode) -> Unit, onOpenCalendar: () -> Unit,
                     streakDays: Int, totalDays: Int) {
    val s = L10n.s
    FadeIn {
        Column(
            modifier = Modifier.fillMaxSize().background(BgBrush)
                .statusBarsPadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // Compact header: icon chip + title inline
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(52.dp)
                        .background(AccentCyan.copy(alpha = 0.12f), RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🦴", fontSize = 28.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.appName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                    Text(s.appSubtitle, fontSize = 12.sp, color = MutedColor)
                }
            }

            // Balanced distribution: equal weights above and below let the card
            // block float centered between the header and the footer hint, so
            // tall screens don't clump everything into the top half.
            Spacer(Modifier.weight(1f))

            // Primary actions: the two workouts as full-width horizontal rows
            // (icon chip + title + duration + desc + chevron), stacked like a
            // menu list.
            ModeRow("💪", s.modeGentle, s.modeGentleDesc,
                AccentCyan, durationText(Mode.GENTLE)) { onSelect(Mode.GENTLE) }

            Spacer(Modifier.height(14.dp))

            ModeRow("🔒", s.modeIso, s.modeIsoDesc,
                AccentOrange, durationText(Mode.ISOMETRIC)) { onSelect(Mode.ISOMETRIC) }

            Spacer(Modifier.height(14.dp))

            // Secondary: compact calendar row with live streak data
            Button(
                onClick = onOpenCalendar,
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = DoneGreen.copy(alpha = 0.10f)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("📅", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(s.calendarBtn, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DoneGreen)
                Spacer(Modifier.weight(1f))
                Text(
                    if (totalDays == 0) s.calendarEmpty
                    else s.streakSummary(streakDays, totalDays),
                    fontSize = 12.sp, color = HintColor,
                )
            }

            Spacer(Modifier.weight(1f))

            // Footer hint: anchors the bottom so the leftover space reads as
            // intentional breathing room instead of a hole
            Text(L10n.s.footerHint, fontSize = 12.sp, color = MutedColor)

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Rough total-workout length for the mode-card readout (follows Config). */
private fun durationText(mode: Mode): String {
    val sec = Config.prepareSec + Config.stagesOf(mode)
        .sumOf { st -> st.dirs.size * st.groups * (st.contractSec + st.relaxSec) }
    return L10n.s.approxMinutes((sec + 30) / 60)
}

@Composable
fun ModeRow(emoji: String, title: String, desc: String, color: Color,
            duration: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Press feedback: the row shrinks slightly while held (on top of the ripple)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "rowPress",
    )
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale },
        interactionSource = interaction,
        shape = CardShape,
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.12f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(46.dp)
                    .background(color.copy(alpha = 0.14f), RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 23.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
                    Spacer(Modifier.width(8.dp))
                    // Duration pill: live from the timing config, so it stays
                    // honest after the user edits 计时设置
                    Text(
                        duration,
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = color.copy(alpha = 0.9f),
                        modifier = Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(50.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(desc, fontSize = 11.sp, color = SubTextColor, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 22.sp, color = HintColor)
        }
    }
}

// ===================== Timer Screen =====================

@Composable
fun TimerScreen(mode: Mode, onBack: () -> Unit, checkinsRaw: String,
                onCheckIn: (String) -> Unit, onLanguageChanged: () -> Unit = {},
                vm: TimerViewModel = viewModel()) {
    val s = L10n.s
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    var showCalendar by remember { mutableStateOf(false) }
    var showTips by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    if (showCalendar) {
        // Calendar shown in-place so the ViewModel survives (no progress lost).
        CalendarScreen(raw = checkinsRaw, onBack = { showCalendar = false })
        return
    }

    // System gesture/button back exits the workout (resets it first).
    // Mid-workout (running or paused) ask first — a stray back gesture should
    // not silently discard the progress; idle/done exit directly.
    var confirmBack by remember { mutableStateOf(false) }
    BackHandler {
        if (state.running) confirmBack = true
        else { vm.resetWorkout(); onBack() }
    }
    if (confirmBack) {
        AlertDialog(
            onDismissRequest = { confirmBack = false },
            confirmButton = {
                TextButton(onClick = { confirmBack = false; vm.resetWorkout(); onBack() }) {
                    Text(s.backResetExit, color = AccentOrange, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBack = false }) { Text(s.backStay, color = AccentCyan) }
            },
            title = { Text(s.backTitle, fontWeight = FontWeight.SemiBold, color = TipHeaderColor) },
            text = { Text(s.backBody,
                fontSize = 13.sp, color = SubTextColor) },
            containerColor = Color(0xFF2D3E50),
            shape = TipShape,
        )
    }

    // Only switch mode when it actually changed; the ViewModel survives rotation,
    // so a running workout is not reset by configuration changes.
    LaunchedEffect(mode) { if (vm.state.value.mode != mode) vm.setMode(mode) }

    // Keep the screen awake while a workout runs — a timer app should never
    // sleep mid-set. Cleared when the workout ends or this screen leaves.
    val activityWindow = (LocalContext.current as? Activity)?.window
    DisposableEffect(state.running) {
        if (state.running) {
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Auto check-in: once per completed workout per DAY+MODE — guarded by a key
    // set, so finishing the other mode later the same day still records its bit
    // and finishing just after midnight records the new day (day dedup also
    // lives in CheckInLog).
    val recordedKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(state.phase) {
        val today = LocalDate.now()
        if (state.phase == Phase.DONE && recordedKeys.add("$today#${mode.name}")) {
            val log = CheckInLog.parse(checkinsRaw)
            log.add(today, mode)
            onCheckIn(log.serialize())
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().background(BgBrush)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top spacing (below the status bar, consistent with the calendar page)
        Spacer(Modifier.height(16.dp))

        // Header: single overflow menu (calendar / tips / about)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            HeaderMenuButton(
                onOpenCalendar = { showCalendar = true },
                onOpenTips = { showTips = true },
                onOpenSettings = { showSettings = true },
                onOpenLanguage = { showLanguage = true },
                onOpenAbout = { showAbout = true },
            )
        }
        TipsDialog(mode, showTips) { showTips = false }
        AboutDialog(showAbout) { showAbout = false }
        SettingsDialog(showSettings, onDismiss = { showSettings = false }, onApply = { vm.resetWorkout() })
        LanguageDialog(showLanguage, onPick = { code ->
            L10nStore.save(context, code)
            L10nStore.init(context) // apply immediately
            onLanguageChanged()     // then force a full recomposition
        }, onDismiss = { showLanguage = false })

        Spacer(Modifier.height(8.dp))

        // Phase text (title area, pinned near top); slides between phases
        AnimatedContent(
            targetState = state.phaseText,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }) togetherWith
                    fadeOut(tween(140))
            },
            label = "phaseText",
        ) { text ->
            Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                color = ContentColor, letterSpacing = 2.sp)
        }

        Spacer(Modifier.height(4.dp))

        // Phase hint
        Text(state.phaseHint, fontSize = 14.sp, color = HintColor, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 36.dp))

        // Upper spacer (balanced rhythm: ring sits closer to the hint text)
        Spacer(Modifier.weight(0.55f))

        // Timer ring
        TimerRing(state)

        Spacer(Modifier.height(12.dp))
        AnimatedVisibility(
            visible = state.running || state.phase == Phase.DONE,
            enter = fadeIn(tween(250)) + expandVertically(),
            exit = fadeOut(tween(200)) + shrinkVertically(),
        ) {
            StageProgress(state)
        }

        // Lower spacer (near-even with the upper one; controls stay reachable)
        Spacer(Modifier.weight(0.5f))
        Spacer(Modifier.height(20.dp))

        // Controls
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            val btnText = when {
                !state.running -> s.start
                state.paused -> s.resume
                else -> s.pause
            }
            Button(
                onClick = {
                    vm.speak(btnText)
                    if (!state.running) vm.startWorkout()
                    else vm.togglePause()
                },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = BtnShape,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBtn),
            ) {
                Text(btnText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 1.sp)
            }
            OutlinedButton(
                onClick = {
                    vm.speak(L10n.s.resetCue)
                    vm.resetWorkout()
                },
                modifier = Modifier.weight(1f).height(52.dp)
                    .border(1.5.dp, SubTextColor.copy(alpha = 0.45f), BtnShape),
                shape = BtnShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SubTextColor),
            ) {
                Text(L10n.s.reset, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
        }

        Spacer(Modifier.height(34.dp))
    }

        // Completion overlay when the whole workout is finished
        if (state.phase == Phase.DONE) {
            DoneOverlay(onAgain = { vm.resetWorkout(); vm.startWorkout() }, onBack = { vm.resetWorkout(); onBack() })
        }
    }
}

@Composable
fun TimerRing(state: TimerState) {
    val ringColor = PhaseColors.forPhase(state.phase)

    // 倒计时数字颜色随剩余秒数平滑过渡：prepare 固定黄色；contract/relax 时
    // 剩余 >3 秒为绿、最后 3 秒为红（0.6s 缓冲）；idle/done 保持阶段色。
    val countdownColor by animateColorAsState(
        targetValue = when (state.phase) {
            Phase.PREPARE -> PhaseColors.prepare
            Phase.CONTRACT, Phase.RELAX -> if (state.countdown in 1..3) CountdownWarn else CountdownNormal
            else -> ringColor
        },
        animationSpec = tween(600),
    )

    val progress = when (state.phase) {
        Phase.DONE -> 1f
        Phase.IDLE -> 0f
        else -> {
            val total = when (state.phase) {
                // Stage-switch prepare (si > 0) is longer than the opening one;
                // relax uses the actual run length (shortened when the stage-
                // switch prepare was carved out of it).
                Phase.PREPARE -> if (state.si > 0) Config.stagePrepareSec else Config.prepareSec
                Phase.CONTRACT -> state.contractSec
                Phase.RELAX -> state.relaxTotalSec
                else -> 1
            }.coerceAtLeast(1)
            1f - state.countdown.toFloat() / total
        }
    }

    // Pulse (contract) / breathe (relax) animations are gated: they only run in
    // their phase and are applied in the draw phase via graphicsLayer + lambda
    // readers, so idle/done states cause zero recomposition or invalidation.
    val pulse = remember { Animatable(1f) }
    val breathe = remember { Animatable(1f) }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.CONTRACT -> {
                pulse.snapTo(1f)
                pulse.animateTo(1.03f, infiniteRepeatable(tween(2000, easing = EaseInOut), RepeatMode.Reverse))
            }
            Phase.RELAX -> {
                breathe.snapTo(0.7f)
                breathe.animateTo(1f, infiniteRepeatable(tween(4000, easing = EaseInOut), RepeatMode.Reverse))
            }
            else -> {
                pulse.snapTo(1f)
                breathe.snapTo(1f)
            }
        }
    }

    // Smooth the ring sweep like the web version's CSS transition, but animate
    // in the draw phase: no recomposition while the arc moves.
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(progress) { animatedProgress.animateTo(progress, tween(220)) }

    // Pre-compute static arc params (thicker stroke balances the big digit)
    val strokeW = 11.dp
    val bgRingColor = RingBgColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(330.dp).graphicsLayer {
            // Read animatables here (draw phase) — no recomposition per frame
            scaleX = if (state.phase == Phase.CONTRACT) pulse.value else 1f
            scaleY = if (state.phase == Phase.CONTRACT) pulse.value else 1f
            alpha = if (state.phase == Phase.RELAX) breathe.value else 1f
        },
    ) {
        Spacer(
            Modifier.size(330.dp).drawBehind {
                val sw = strokeW.toPx()
                val pad = sw / 2
                val arcSize = Size(size.width - sw, size.height - sw)
                val tl = Offset(pad, pad)
                drawArc(bgRingColor, -90f, 360f, false, tl, arcSize, style = Stroke(sw))
                drawArc(ringColor, -90f, 360f * animatedProgress.value, false, tl, arcSize,
                    style = Stroke(sw, cap = StrokeCap.Round))
            }
        )

        Text(state.phaseLabel, fontSize = 13.sp, color = HintColor, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp))

        val timeText = when (state.phase) {
            Phase.IDLE -> "${state.contractSec}"
            Phase.DONE -> "✓"
            else -> "${state.countdown}"
        }
        Text(timeText, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = countdownColor)
    }
}

// ===================== Stage Progress =====================

@Composable
fun StageProgress(state: TimerState) {
    val completed = state.completedGroups
    val total = state.totalGroupsAll
    // At DONE the engine keeps si/gi on the last rep (finish() doesn't advance),
    // so paint the whole bar green instead of leaving the last segment "current".
    val barDone = if (state.phase == Phase.DONE) total else completed
    val stages = Config.stagesOf(state.mode)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Dynamic label: reflect the current phase instead of the static stage name
            val s = L10n.s
            val label = when (state.phase) {
                Phase.RELAX -> s.relaxText
                Phase.CONTRACT -> if (state.mode == Mode.ISOMETRIC)
                    s.contractIsoProgress(L10n.stage(state.stageName), L10n.dir(state.handName))
                    else s.contractGentleProgress(L10n.dir(state.handName))
                Phase.DONE -> s.done
                else -> s.stageProgress(L10n.stage(state.stageName))
            }
            Text("$label (${completed + 1}/$total)",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ContentColor)
            Text("⏱ ${state.elapsedText}",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = HintColor)
        }

        Spacer(Modifier.height(6.dp))

        // One segment per group; a wider fixed gap marks each stage boundary so
        // the isometric stages read as regions on the bar (single-stage modes
        // get no boundary spacers and render exactly as before).
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            var segIdx = 0
            stages.forEachIndexed { k, st ->
                if (k > 0) Spacer(Modifier.width(10.dp))
                repeat(st.dirs.size * st.groups) {
                    val i = segIdx++
                    val color = when {
                        i < barDone -> DoneGreen
                        i == barDone -> AccentOrange
                        else -> Color.White.copy(alpha = 0.18f) // brighter track, visible on navy
                    }
                    key(i) {
                        Box(Modifier.weight(1f).height(8.dp).background(color, SegShape))
                    }
                }
            }
        }

        // Overall stage band (multi-stage modes): block widths proportional to
        // each stage's group count, labeled and filled by its own progress —
        // shows where 正向/侧向/弹力带 sit on the bar and how far each has come.
        if (stages.size > 1) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                stages.forEachIndexed { k, st ->
                    val reps = st.dirs.size * st.groups
                    val done = if (state.phase == Phase.DONE) reps
                        else when { k < state.si -> reps; k == state.si -> state.gi; else -> 0 }
                    val stageDone = done >= reps
                    val stageCurrent = !stageDone && state.phase != Phase.DONE && k == state.si
                    val color = when {
                        stageDone -> DoneGreen
                        stageCurrent -> AccentOrange
                        else -> Color.Transparent
                    }
                    val labelColor = when {
                        stageDone -> DoneGreen
                        stageCurrent -> AccentOrange
                        else -> HintColor
                    }
                    // coerce: settings UI clamps groups to 1..20, but a
                    // hand-corrupted stored JSON could yield reps 0 — weight(0)
                    // is invalid, so clamp defensively (fill guarded by done>0).
                    Column(modifier = Modifier.weight(reps.coerceAtLeast(1).toFloat())) {
                        Box(
                            Modifier.fillMaxWidth().height(10.dp)
                                .background(Color.White.copy(alpha = 0.18f), SegShape)
                        ) {
                            if (done > 0) {
                                Box(
                                    Modifier.fillMaxWidth((done.toFloat() / reps).coerceIn(0f, 1f))
                                        .height(10.dp).background(color, SegShape)
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            L10n.stage(st.name),
                            fontSize = 10.sp, lineHeight = 11.sp,
                            fontWeight = if (stageCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = labelColor, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// ===================== Tips / About / Menu =====================

/** Overflow menu anchored at the header's top-right corner (custom card + pop-in). */
@Composable
fun HeaderMenuButton(onOpenCalendar: () -> Unit, onOpenTips: () -> Unit,
                     onOpenSettings: () -> Unit, onOpenLanguage: () -> Unit,
                     onOpenAbout: () -> Unit) {
    val s = L10n.s
    var expanded by remember { mutableStateOf(false) }
    // Micro-interaction: the kebab rotates into a dash while the menu is open
    val rotation by animateFloatAsState(
        if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "menuIcon",
    )
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp)
            .semantics { contentDescription = L10n.s.menuCd }) {
            Text("⋮", fontSize = 20.sp, color = ContentColor,
                modifier = Modifier.alpha(0.85f).graphicsLayer { rotationZ = rotation })
        }
        if (expanded) {
            Dialog(
                onDismissRequest = { expanded = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                // Dim scrim doubles as the outside-tap catcher
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = false },
                ) {
                    AnimatedVisibility(
                        visibleState = appear,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 20.dp),
                        enter = scaleIn(
                            initialScale = 0.8f,
                            transformOrigin = TransformOrigin(1f, 0f), // grows from the kebab corner
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                        ) + fadeIn(),
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D3E50)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        ) {
                            Column(Modifier.width(200.dp).padding(vertical = 6.dp)) {
                                MenuRow("📅", s.menuCalendar, DoneGreen, onClick = { expanded = false; onOpenCalendar() })
                                MenuRow("📋", s.menuTips, TipHeaderColor, onClick = { expanded = false; onOpenTips() })
                                MenuRow("⚙️", s.menuSettings, AccentBlue, onClick = { expanded = false; onOpenSettings() })
                                MenuRow("🌐", s.menuLanguage, AccentBtn, onClick = { expanded = false; onOpenLanguage() })
                                Divider(color = Color.White.copy(alpha = 0.14f),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                                MenuRow("ℹ️", s.menuAbout, AccentCyan, trailing = rememberAppVersionLabel(),
                                    onClick = { expanded = false; onOpenAbout() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One styled menu row: accent-tinted emoji chip + title (+ optional trailing label). */
@Composable
private fun MenuRow(emoji: String, title: String, accent: Color,
                    trailing: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 15.sp, color = ContentColor)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 11.sp, color = MutedColor)
    }
}

/** Compact version label for the menu's 关于 row, e.g. "1.0". */
@Composable
private fun rememberAppVersionLabel(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }
}

/** Exercise tips dialog; shown from the header menu. */
@Composable
fun TipsDialog(mode: Mode, visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val s = L10n.s
    val tips = remember(mode) { if (mode == Mode.GENTLE) s.gentleTips else s.isoTips }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(s.gotIt, color = AccentCyan)
            }
        },
        title = { Text(s.tipsTitle, fontWeight = FontWeight.SemiBold, color = TipHeaderColor) },
        text = {
            Column {
                tips.forEach { tip ->
                    Text("• $tip", fontSize = 13.sp, color = SubTextColor, lineHeight = 20.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        },
        containerColor = Color(0xFF2D3E50),
        shape = TipShape,
    )
}

/** Runtime timing editor: steppers over the shared JSON config, saved via TimingStore. */
@Composable
fun SettingsDialog(visible: Boolean, onDismiss: () -> Unit, onApply: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val s = L10n.s

    // Snapshot of editable values: index 0 = gentle, 1..3 = isometric stages.
    // names/dirs stay fixed (they are the workout script, not tunable timing).
    data class StageEdit(val name: String, val contract: Int, val relax: Int, val groups: Int)
    var prepare by remember { mutableStateOf(Config.prepareSec) }
    var stagePrepare by remember { mutableStateOf(Config.stagePrepareSec) }
    var stages by remember {
        mutableStateOf(
            (Config.stagesOf(Mode.GENTLE) + Config.stagesOf(Mode.ISOMETRIC))
                .map { StageEdit(it.name, it.contractSec, it.relaxSec, it.groups) }
        )
    }
    fun edit(i: Int, transform: (StageEdit) -> StageEdit) {
        stages = stages.mapIndexed { j, s -> if (j == i) transform(s) else s }
    }

    fun save() {
        val gentle = Config.stagesOf(Mode.GENTLE).zip(stages.take(1)) { st, e ->
            st.copy(contractSec = e.contract, relaxSec = e.relax, groups = e.groups)
        }
        val iso = Config.stagesOf(Mode.ISOMETRIC).zip(stages.drop(1)) { st, e ->
            st.copy(contractSec = e.contract, relaxSec = e.relax, groups = e.groups)
        }
        val json = TimingConfig(
            prepareSec = prepare,
            stagePrepareSec = stagePrepare,
            stages = mapOf(Mode.GENTLE to gentle, Mode.ISOMETRIC to iso),
        ).toJson()
        Config.configure(json)
        TimingStore.save(context, json)
        onApply() // reset any running workout so the new timing applies cleanly
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { save() }) { Text(s.save, color = AccentCyan, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancel, color = HintColor) }
        },
        title = { Text(s.settingsTitle, fontWeight = FontWeight.SemiBold, color = TipHeaderColor) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                StepperRow(s.settingPrepare, prepare, 1..10) { prepare = it }
                StepperRow(s.settingStagePrepare, stagePrepare, 1..15) { stagePrepare = it }
                SectionHeader(s.sectionGentle(L10n.stage(stages[0].name)))
                StepperRow(s.settingContract, stages[0].contract, 3..60) { edit(0) { s -> s.copy(contract = it) } }
                StepperRow(s.settingRelax, stages[0].relax, 0..60) { edit(0) { s -> s.copy(relax = it) } }
                StepperRow(s.settingGroups, stages[0].groups, 1..20) { edit(0) { s -> s.copy(groups = it) } }
                (1..3).forEach { i ->
                    SectionHeader(s.sectionIso(L10n.stage(stages[i].name)))
                    StepperRow(s.settingContract, stages[i].contract, 3..60) { edit(i) { s -> s.copy(contract = it) } }
                    StepperRow(s.settingRelax, stages[i].relax, 0..60) { edit(i) { s -> s.copy(relax = it) } }
                    StepperRow(s.settingGroups, stages[i].groups, 1..20) { edit(i) { s -> s.copy(groups = it) } }
                }
                Spacer(Modifier.height(8.dp))
                Text(s.settingsHint, fontSize = 11.sp, color = MutedColor)
                TextButton(onClick = {
                    Config.configure(DEFAULT_TIMING_JSON)
                    TimingStore.clear(context)
                    onApply()
                    onDismiss()
                }) { Text(s.restoreDefaults, color = HintColor, fontSize = 13.sp) }
            }
        },
        containerColor = Color(0xFF2D3E50),
        shape = TipShape,
    )
}

@Composable
private fun SectionHeader(t: String) {
    Text(t, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AccentBlue,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

/** Label on the left, [−] value [+] stepper on the right, clamped to [range]. */
@Composable
private fun StepperRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = SubTextColor, modifier = Modifier.weight(1f))
        StepButton("−", enabled = value > range.first) { onChange(value - 1) }
        Text("$value", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ContentColor,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 36.dp))
        StepButton("+", enabled = value < range.last) { onChange(value + 1) }
    }
}

/** In-app language picker: follow system / 中文 / English. Saved via L10nStore. */
@Composable
fun LanguageDialog(visible: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val current = L10nStore.load(context) // re-read every open: cheap and always fresh
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(L10n.s.cancel, color = HintColor) }
        },
        title = { Text("🌐 ${L10n.s.languageTitle}", fontWeight = FontWeight.SemiBold, color = TipHeaderColor) },
        text = {
            Column {
                LanguageOption(L10n.s.followSystem, current == L10nStore.AUTO) { onPick(L10nStore.AUTO) }
                LanguageOption("中文 · Chinese", current == L10nStore.ZH) { onPick(L10nStore.ZH) }
                LanguageOption("English · 英文", current == L10nStore.EN) { onPick(L10nStore.EN) }
            }
        },
        containerColor = Color(0xFF2D3E50),
        shape = TipShape,
    )
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 15.sp, color = ContentColor)
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .background(
                if (enabled) AccentCyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(glyph, fontSize = 16.sp, color = if (enabled) AccentCyan else MutedColor)
    }
}

/** About dialog with the app version from PackageManager. */
@Suppress("DEPRECATION") // pi.versionCode is only read on the SDK < 28 branch
@Composable
fun AboutDialog(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val s = L10n.s
    val versionText = remember {
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            s.version(pi.versionName ?: "?", code)
        }.getOrDefault(s.versionUnknown)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(s.ok, color = AccentCyan)
            }
        },
        title = { Text(s.aboutTitle, fontWeight = FontWeight.SemiBold, color = TipHeaderColor) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                Text("🦴", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text(s.appName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                Spacer(Modifier.height(4.dp))
                Text(versionText, fontSize = 13.sp, color = HintColor)
                Spacer(Modifier.height(10.dp))
                Text(s.aboutDesc,
                    fontSize = 12.sp, color = SubTextColor, textAlign = TextAlign.Center,
                    lineHeight = 18.sp)
            }
        },
        containerColor = Color(0xFF2D3E50),
        shape = TipShape,
    )
}


// ===================== Completion Overlay =====================

@Composable
fun DoneOverlay(onAgain: () -> Unit, onBack: () -> Unit) {
    // Pop-in animation
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.6f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "pop")

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC0D1B2A)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = TipShape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D3E50)),
            modifier = Modifier.padding(32.dp).graphicsLayer { this.scaleX = scale; this.scaleY = scale }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp)) {
                val s = L10n.s
                Text("🎉", fontSize = 56.sp)
                Spacer(Modifier.height(12.dp))
                Text(s.doneTitle, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = AccentCyan, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text(s.doneBody, fontSize = 14.sp, color = HintColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(s.doneCheckedIn, fontSize = 13.sp, color = DoneGreen, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(onClick = onAgain, shape = BtnShape, colors = ButtonDefaults.buttonColors(containerColor = AccentBtn)) {
                        Text(s.again, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = onBack, shape = BtnShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = SubTextColor)) {
                        Text(s.back, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ===================== Calendar (打卡日历) =====================

@Composable
fun CalendarScreen(raw: String, onBack: () -> Unit) {
    val s = L10n.s
    val log = remember(raw) { CheckInLog.parse(raw) }
    var ym by remember { mutableStateOf(YearMonth.now()) }
    BackHandler { onBack() }
    val today = LocalDate.now()

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().background(BgBrush)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))

        // No visible back button: system gesture/button back exits (BackHandler above)
        Text(s.calTitle, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AccentBlue,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)

        Spacer(Modifier.height(16.dp))

        // ---- Stat strip: one glance = streak / best / month / total ----
        FadeIn {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("${log.currentStreak(today)}", s.statStreak, AccentOrange, Modifier.weight(1f))
                StatTile("${log.longestStreak()}", s.statBest, AccentCyan, Modifier.weight(1f))
                StatTile("${log.countInMonth(ym)}", s.statMonth, DoneGreen, Modifier.weight(1f))
                StatTile("${log.total}", s.statTotal, AccentBlue, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(8.dp))

        // Per-mode check-in counts: month-scoped rows flip with the month nav,
        // cumulative rows span the whole history (mode bitmask lives per day;
        // legacy v1 entries carry no mode and only fill day stats)
        FadeIn(35) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("${log.countModeInMonth(ym, Mode.GENTLE)}", s.statGentleMonth, AccentCyan, Modifier.weight(1f))
                    StatTile("${log.countModeInMonth(ym, Mode.ISOMETRIC)}", s.statIsoMonth, AccentOrange, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("${log.countModeTotal(Mode.GENTLE)}", s.statGentleTotal, AccentCyan, Modifier.weight(1f))
                    StatTile("${log.countModeTotal(Mode.ISOMETRIC)}", s.statIsoTotal, AccentOrange, Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Milestone pills: reached = green tint, else dimmed with days remaining
        FadeIn(70) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                log.milestones().forEach { m -> MilestonePill(m, log.total, Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Month navigation
        FadeIn(140) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { ym = ym.minusMonths(1) }, enabled = ym.isAfter(YearMonth.of(2020, 1)),
                    modifier = Modifier.semantics { contentDescription = L10n.s.prevMonthCd }) {
                    Text("‹", fontSize = 22.sp, color = AccentCyan)
                }
                Spacer(Modifier.weight(1f))
                Text(s.monthTitle(ym.year, ym.monthValue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = ContentColor)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { ym = ym.plusMonths(1) },
                    modifier = Modifier.semantics { contentDescription = L10n.s.nextMonthCd }) {
                    Text("›", fontSize = 22.sp, color = AccentCyan)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Weekday header (Monday first)
        val weekDays = s.weekDays
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            weekDays.forEach { w ->
                Box(Modifier.weight(1f)) {
                    Text(w, fontSize = 13.sp, color = HintColor, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Day grid: leading blanks so the 1st lands on the right column;
        // crossfades when the user flips months
        FadeIn(210) {
            Crossfade(targetState = ym, animationSpec = tween(250), label = "monthGrid") { month ->
                Column {
                    val firstOffset = month.atDay(1).dayOfWeek.value - 1 // 0 = Monday
                    val daysInMonth = month.lengthOfMonth()
                    val totalCells = ((firstOffset + daysInMonth + 6) / 7) * 7
                    repeat(totalCells / 7) { row ->
                        if (row > 0) Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            repeat(7) { col ->
                                val idx = row * 7 + col
                                val dayNumber = idx - firstOffset + 1
                                val isDay = dayNumber in 1..daysInMonth
                                val date = if (isDay) month.atDay(dayNumber) else null
                                val checked = date != null && log.hasInMonth(month, dayNumber)
                                val gentleDone = checked && log.hasModeInMonth(month, dayNumber, Mode.GENTLE)
                                val isoDone = checked && log.hasModeInMonth(month, dayNumber, Mode.ISOMETRIC)
                                val isToday = date == today
                                Box(Modifier.weight(1f)) {
                                    DayCell(isDay = isDay, checked = checked, isToday = isToday, day = dayNumber,
                                        gentle = gentleDone, isometric = isoDone)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(s.calHint, fontSize = 12.sp, color = MutedColor)

        Spacer(Modifier.height(20.dp))

        // Reminder settings grouped in a card
        Card(shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f))) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                ReminderSettingsRow()
            }
        }

        Spacer(Modifier.height(24.dp))
    }
    }
}

// ===================== Calendar building blocks =====================

/** One stat tile: big colored number over a small label. */
@Composable
private fun StatTile(value: String, label: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = MutedColor)
    }
}

/** Milestone pill: green-tinted when reached, dimmed with days remaining otherwise. */
@Composable
private fun MilestonePill(m: Milestone, total: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(
                if (m.reached) DoneGreen.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(50.dp),
            )
            .padding(vertical = 5.dp),
    ) {
        Text(m.emoji, fontSize = 13.sp, modifier = Modifier.alpha(if (m.reached) 1f else 0.45f))
        Spacer(Modifier.width(4.dp))
        Text(
            if (m.reached) L10n.s.milestoneReached(m.days)
            else L10n.s.milestoneRemaining(m.days - total),
            fontSize = 12.sp,
            fontWeight = if (m.reached) FontWeight.SemiBold else FontWeight.Normal,
            color = if (m.reached) DoneGreen else MutedColor,
        )
    }
}

// ===================== Reminder settings (每日提醒) =====================

@Composable
private fun ReminderSettingsRow() {
    val context = LocalContext.current
    val s = L10n.s
    var enabled by remember { mutableStateOf(ReminderScheduler.isEnabled(context)) }
    var time by remember { mutableStateOf(ReminderScheduler.time(context)) }
    var permDenied by remember { mutableStateOf(false) }

    // POST_NOTIFICATIONS is a runtime permission on API 33+; denial keeps the
    // switch off and shows a hint instead of scheduling a silent alarm.
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permDenied = false
            enabled = true
            ReminderScheduler.setEnabled(context, true)
        } else {
            permDenied = true
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(s.reminderTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ContentColor)
            Spacer(Modifier.weight(1f))
            Text(
                "%02d:%02d".format(Locale.ROOT, time.first, time.second),
                fontSize = 15.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val (h, m) = time
                        TimePickerDialog(context, { _, ph, pm ->
                            time = ph to pm
                            // Persist the new time; reschedule only if active.
                            ReminderScheduler.setEnabled(context, enabled, ph, pm)
                        }, h, m, true).show()
                    }
                    .padding(horizontal = 8.dp),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    when {
                        !want -> {
                            enabled = false
                            permDenied = false
                            ReminderScheduler.setEnabled(context, false)
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> {
                            enabled = true
                            ReminderScheduler.setEnabled(context, true)
                        }
                    }
                },
                modifier = Modifier.semantics { contentDescription = L10n.s.reminderSwitchCd },
            )
        }
        Text(
            when {
                permDenied -> s.reminderDenied
                enabled -> s.reminderOn
                else -> s.reminderOff
            },
            fontSize = 12.sp, color = MutedColor,
        )
    }
}

@Composable
fun DayCell(isDay: Boolean, checked: Boolean, isToday: Boolean, day: Int,
            gentle: Boolean = false, isometric: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (!isDay) return@Box
        val shape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .size(34.dp)
                .then(
                    // Today always gets a cyan ring — also on top of a checked
                    // green fill, so "today" never looks like any other day.
                    if (isToday)
                        Modifier.border(1.5.dp, AccentCyan.copy(alpha = 0.8f), shape)
                    else Modifier
                )
                .background(
                    if (checked) DoneGreen.copy(alpha = 0.9f) else Color.Transparent,
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$day",
                fontSize = 14.sp,
                fontWeight = if (checked || isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    checked -> OnDoneGreen   // dark ink on the green fill
                    isToday -> AccentCyan
                    else -> ContentColor
                },
            )
            // Per-mode pins (v2 mask): cyan = gentle, orange = isometric; both
            // pins when the day had both workouts. Legacy days show none.
            if (checked && (gentle || isometric)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                ) {
                    if (gentle) Box(Modifier.size(3.dp).background(AccentCyan, RoundedCornerShape(50.dp)))
                    if (isometric) Box(Modifier.size(3.dp).background(AccentOrange, RoundedCornerShape(50.dp)))
                }
            }
        }
    }
}

