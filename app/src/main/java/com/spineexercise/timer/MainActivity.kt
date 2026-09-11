package com.spineexercise.timer

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
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

/** Entrance helper: fade + slide-up; [delayMs] staggers siblings into a cascade. */
@Composable
private fun FadeSlideIn(delayMs: Int = 0, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 3 },
    ) { content() }
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

    if (showCalendar) {
        CalendarScreen(
            raw = checkinsRaw,
            onBack = { showCalendar = false },
        )
    } else if (selectedMode == null) {
        ModeSelectScreen(onSelect = { selectedMode = it }, onOpenCalendar = { showCalendar = true })
    } else {
        TimerScreen(
            mode = selectedMode!!,
            onBack = { selectedMode = null },
            checkinsRaw = checkinsRaw,
            onCheckIn = { raw ->
                checkinsRaw = raw
                CheckInStore.save(context, raw) // write-through: survives relaunch
            },
        )
    }
}

// ===================== Mode Select Screen =====================

@Composable
fun ModeSelectScreen(onSelect: (Mode) -> Unit, onOpenCalendar: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(BgBrush).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.3f))

        FadeSlideIn {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🦴", fontSize = 72.sp)
                Spacer(Modifier.height(20.dp))
                Text("颈椎锻炼", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                Text("选择锻炼模式开始", fontSize = 15.sp, color = HintColor,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }

        Spacer(Modifier.weight(0.4f))

        FadeSlideIn(90) {
            ModeCard("💪", "舒缓锻炼", "温和发力 8s / 放松 5s / 左右交替 8 次",
                "20%~30% 轻微力量，适合日常放松", AccentCyan) { onSelect(Mode.GENTLE) }
        }
        Spacer(Modifier.height(16.dp))
        FadeSlideIn(180) {
            ModeCard("🔒", "等长抗阻", "正向抗阻 → 侧向抗阻 → 弹力带训练",
                "3个阶段，共15组，静态持续发力", AccentOrange) { onSelect(Mode.ISOMETRIC) }
        }

        Spacer(Modifier.height(16.dp))
        FadeSlideIn(270) {
            ModeCard("📅", "打卡日历", "查看本月训练打卡情况",
                "每次完成锻炼自动帮你记下当天", DoneGreen) { onOpenCalendar() }
        }

        Spacer(Modifier.weight(0.3f))
    }
}

@Composable
fun ModeCard(emoji: String, title: String, desc: String, tips: String, color: Color, onClick: () -> Unit) {
    // Press feedback: card shrinks slightly while held (on top of the ripple)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "cardPress",
    )
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale },
        interactionSource = interaction,
        shape = CardShape,
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.12f)),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(emoji, fontSize = 36.sp)
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(6.dp))
            Text(desc, fontSize = 13.sp, color = SubTextColor, textAlign = TextAlign.Center)
            Text(tips, fontSize = 12.sp, color = MutedColor, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ===================== Timer Screen =====================

@Composable
fun TimerScreen(mode: Mode, onBack: () -> Unit, checkinsRaw: String,
                onCheckIn: (String) -> Unit, vm: TimerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    var showCalendar by remember { mutableStateOf(false) }
    var showTips by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    if (showCalendar) {
        // Calendar shown in-place so the ViewModel survives (no progress lost).
        CalendarScreen(raw = checkinsRaw, onBack = { showCalendar = false })
        return
    }

    // System gesture/button back exits the workout (resets it first)
    BackHandler { vm.resetWorkout(); onBack() }

    // Only switch mode when it actually changed; the ViewModel survives rotation,
    // so a running workout is not reset by configuration changes.
    LaunchedEffect(mode) { if (vm.state.value.mode != mode) vm.setMode(mode) }

    // Auto check-in: once per completed workout per DAY — guarded by the last
    // recorded date, not a boolean, so finishing a workout just after midnight
    // still records the new day (dedup by day also lives in CheckInLog).
    var recordedOn by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(state.phase) {
        val today = LocalDate.now()
        if (state.phase == Phase.DONE && recordedOn != today) {
            recordedOn = today
            val log = CheckInLog.parse(checkinsRaw)
            log.add(today)
            onCheckIn(log.serialize())
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().background(BgBrush)
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top spacing: title near top with 48dp margin
        Spacer(Modifier.height(48.dp))

        // Header: single overflow menu (calendar / tips / about)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            HeaderMenuButton(
                onOpenCalendar = { showCalendar = true },
                onOpenTips = { showTips = true },
                onOpenAbout = { showAbout = true },
            )
        }
        TipsDialog(mode, showTips) { showTips = false }
        AboutDialog(showAbout) { showAbout = false }

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

        // Upper spacer (smaller weight pushes ring slightly above center)
        Spacer(Modifier.weight(0.85f))

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

        // Lower spacer (larger weight keeps ring above center, pushes controls down)
        Spacer(Modifier.weight(1.15f))
        Spacer(Modifier.height(20.dp))

        // Controls
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            val btnText = when {
                !state.running -> "开始"
                state.paused -> "继续"
                else -> "暂停"
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
                    vm.speak("重置")
                    vm.resetWorkout()
                },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = BtnShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SubTextColor),
            ) {
                Text("重置", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
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
                Phase.PREPARE -> Config.prepareSec
                Phase.CONTRACT -> state.contractSec
                Phase.RELAX -> state.relaxSec
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

    // Pre-compute static arc params
    val strokeW = 8.dp
    val bgRingColor = RingBgColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(280.dp).graphicsLayer {
            // Read animatables here (draw phase) — no recomposition per frame
            scaleX = if (state.phase == Phase.CONTRACT) pulse.value else 1f
            scaleY = if (state.phase == Phase.CONTRACT) pulse.value else 1f
            alpha = if (state.phase == Phase.RELAX) breathe.value else 1f
        },
    ) {
        Spacer(
            Modifier.size(280.dp).drawBehind {
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
        Text(timeText, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = countdownColor)
    }
}

// ===================== Stage Progress =====================

@Composable
fun StageProgress(state: TimerState) {
    val completed = state.completedGroups
    val total = state.totalGroupsAll

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Dynamic label: reflect the current phase instead of the static stage name
            val label = when (state.phase) {
                Phase.RELAX -> "🍃 放松"
                Phase.CONTRACT -> if (state.mode == Mode.ISOMETRIC) "💪 ${state.stageName} · ${state.handName}"
                    else "💪 ${state.handName}发力"
                Phase.DONE -> "🎉 完成"
                else -> "📍 ${state.stageName}"
            }
            Text("$label (${completed + 1}/$total)",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ContentColor)
            Text("⏱ ${state.elapsedText}",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = HintColor)
        }

        Spacer(Modifier.height(6.dp))

        // Use stable keys to avoid rebuilding unchanged segments
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            repeat(total) { i ->
                val color = when {
                    i < completed -> DoneGreen
                    i == completed -> AccentOrange
                    else -> SegDimColor
                }
                key(i) {
                    Box(Modifier.weight(1f).height(6.dp).background(color, SegShape))
                }
            }
        }
    }
}

// ===================== Tips / About / Menu =====================

/** Overflow menu anchored at the header's top-right corner (custom card + pop-in). */
@Composable
fun HeaderMenuButton(onOpenCalendar: () -> Unit, onOpenTips: () -> Unit, onOpenAbout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Micro-interaction: the kebab rotates into a dash while the menu is open
    val rotation by animateFloatAsState(
        if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "menuIcon",
    )
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp)) {
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
                                MenuRow("📅", "打卡日历", DoneGreen, onClick = { expanded = false; onOpenCalendar() })
                                MenuRow("📋", "锻炼要点", TipHeaderColor, onClick = { expanded = false; onOpenTips() })
                                Divider(color = Color.White.copy(alpha = 0.14f),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                                MenuRow("ℹ️", "关于", AccentCyan, trailing = rememberAppVersionLabel(),
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
    val tips = remember(mode) { Config.tips[mode] ?: emptyList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = AccentCyan)
            }
        },
        title = { Text("📋 锻炼要点", fontWeight = FontWeight.SemiBold, color = TipHeaderColor) },
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

/** About dialog with the app version from PackageManager. */
@Composable
fun AboutDialog(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val versionText = remember {
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "版本 ${pi.versionName} (${code})"
        }.getOrDefault("版本未知")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("好", color = AccentCyan)
            }
        },
        title = { Text("ℹ️ 关于", fontWeight = FontWeight.SemiBold, color = TipHeaderColor) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                Text("🦴", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text("颈椎锻炼", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                Spacer(Modifier.height(4.dp))
                Text(versionText, fontSize = 13.sp, color = HintColor)
                Spacer(Modifier.height(10.dp))
                Text("温和的等长颈椎锻炼计时器\n舒缓 / 等长抗阻 · 语音引导 · 打卡日历",
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
                Text("🎉", fontSize = 56.sp)
                Spacer(Modifier.height(12.dp))
                Text("锻炼完成！", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = AccentCyan, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text("做得很棒，请缓慢起身，避免突然动作", fontSize = 14.sp, color = HintColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("📅 今日打卡已记录", fontSize = 13.sp, color = DoneGreen, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(onClick = onAgain, shape = BtnShape, colors = ButtonDefaults.buttonColors(containerColor = AccentBtn)) {
                        Text("再练一次", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = onBack, shape = BtnShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = SubTextColor)) {
                        Text("返回", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ===================== Calendar (打卡日历) =====================

@Composable
fun CalendarScreen(raw: String, onBack: () -> Unit) {
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
        Text("打卡日历", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AccentBlue,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)

        Spacer(Modifier.height(16.dp))

        // ---- Stat strip: one glance = streak / best / month / total ----
        FadeSlideIn {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("${log.currentStreak(today)}", "连续", AccentOrange, Modifier.weight(1f))
                StatTile("${log.longestStreak()}", "最长", AccentCyan, Modifier.weight(1f))
                StatTile("${log.countInMonth(ym)}", "本月", DoneGreen, Modifier.weight(1f))
                StatTile("${log.total}", "累计", AccentBlue, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(10.dp))

        // Milestone pills: reached = green tint, else dimmed with days remaining
        FadeSlideIn(70) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                log.milestones().forEach { m -> MilestonePill(m, log.total, Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Month navigation
        FadeSlideIn(140) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { ym = ym.minusMonths(1) }, enabled = ym.isAfter(YearMonth.of(2020, 1))) {
                    Text("‹", fontSize = 22.sp, color = AccentCyan)
                }
                Spacer(Modifier.weight(1f))
                Text("${ym.year} 年 ${ym.monthValue} 月", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = ContentColor)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { ym = ym.plusMonths(1) }) {
                    Text("›", fontSize = 22.sp, color = AccentCyan)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Weekday header (Monday first)
        val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
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
        FadeSlideIn(210) {
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
                                val isToday = date == today
                                Box(Modifier.weight(1f)) {
                                    DayCell(isDay = isDay, checked = checked, isToday = isToday, day = dayNumber)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("完成一次锻炼后，当天自动记录打卡 ✦", fontSize = 12.sp, color = MutedColor)

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
            if (m.reached) "${m.days}天" else "差${m.days - total}天",
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
            Text("⏰ 每日提醒", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ContentColor)
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
            )
        }
        Text(
            when {
                permDenied -> "未授予通知权限，无法提醒；可在系统设置中开启"
                enabled -> "每天到点提醒，当天已完成锻炼则不打扰"
                else -> "开启后每天到点提醒一次（默认 20:00）"
            },
            fontSize = 12.sp, color = MutedColor,
        )
    }
}

@Composable
fun DayCell(isDay: Boolean, checked: Boolean, isToday: Boolean, day: Int) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (!isDay) return@Box
        val shape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .size(34.dp)
                .then(
                    if (!checked && isToday)
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
        }
    }
}

