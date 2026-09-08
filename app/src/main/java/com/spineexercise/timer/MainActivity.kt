package com.spineexercise.timer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

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
private val TipHeaderColor = Color(0xFFFFAB91)
private val SegDimColor = Color.White.copy(alpha = 0.1f)

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
        onBackground = Color(0xFFE8EEF2), onSurface = Color(0xFFE8EEF2),
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// ===================== Navigation =====================

@Composable
fun AppNavigation() {
    var selectedMode by remember { mutableStateOf<Mode?>(null) }
    if (selectedMode == null) ModeSelectScreen { selectedMode = it }
    else TimerScreen(mode = selectedMode!!, onBack = { selectedMode = null })
}

// ===================== Mode Select Screen =====================

@Composable
fun ModeSelectScreen(onSelect: (Mode) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(BgBrush).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.3f))

        Text("🦴", fontSize = 72.sp)
        Spacer(Modifier.height(20.dp))
        Text("颈椎锻炼", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
        Text("选择锻炼模式开始", fontSize = 15.sp, color = HintColor,
            modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.weight(0.4f))

        ModeCard("💪", "舒缓锻炼", "温和发力 8s / 放松 5s / 左右交替 8 次",
            "20%~30% 轻微力量，适合日常放松", AccentCyan) { onSelect(Mode.GENTLE) }
        Spacer(Modifier.height(16.dp))
        ModeCard("🔒", "等长抗阻", "正向抗阻 → 侧向抗阻 → 弹力带训练",
            "3个阶段，共15组，静态持续发力", AccentOrange) { onSelect(Mode.ISOMETRIC) }

        Spacer(Modifier.weight(0.3f))
    }
}

@Composable
fun ModeCard(emoji: String, title: String, desc: String, tips: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = CardShape,
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
fun TimerScreen(mode: Mode, onBack: () -> Unit, vm: TimerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.setMode(mode) }
    DisposableEffect(Unit) { onDispose { vm.resetWorkout() } }

    Column(
        modifier = Modifier.fillMaxSize().background(BgBrush)
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top spacing
        Spacer(Modifier.height(48.dp))

        // Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("← 返回", fontSize = 14.sp, color = HintColor) }
            Spacer(Modifier.weight(1f))
            TipsButton(mode)
        }

        Spacer(Modifier.height(8.dp))

        // Phase text (between header and ring, upper third)
        Spacer(Modifier.height(48.dp))
        Text(state.phaseText, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFFE8EEF2), letterSpacing = 2.sp)

        Spacer(Modifier.height(4.dp))

        // Phase hint
        Text(state.phaseHint, fontSize = 14.sp, color = HintColor, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 36.dp))

        // Upper spacer (ring closer to center)
        Spacer(Modifier.weight(1f))

        // Timer ring
        TimerRing(state)

        Spacer(Modifier.height(8.dp))

        // Stage progress
        if (state.running || state.phase == Phase.DONE) {
            StageProgress(state)
        }

        // Lower spacer (push controls down)
        Spacer(Modifier.weight(1f))

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
                onClick = { vm.resetWorkout() },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = BtnShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SubTextColor),
            ) {
                Text("重置", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
        }

        Spacer(Modifier.height(34.dp))
    }
}

@Composable
fun TimerRing(state: TimerState) {
    val ringColor = PhaseColors.forPhase(state.phase)

    val progress = when (state.phase) {
        Phase.DONE -> 1f
        Phase.IDLE -> 0f
        else -> {
            val total = when (state.phase) {
                Phase.PREPARE -> 3
                Phase.CONTRACT -> state.contractSec
                Phase.RELAX -> state.relaxSec
                else -> 1
            }.coerceAtLeast(1)
            1f - state.countdown.toFloat() / total
        }
    }

    // Infinite transitions must be at fixed call site
    val infinite = rememberInfiniteTransition(label = "ring")
    val pulseVal by infinite.animateFloat(1f, 1.03f,
        infiniteRepeatable(tween(2000, easing = EaseInOut), RepeatMode.Reverse), label = "p")
    val breatheVal by infinite.animateFloat(0.7f, 1f,
        infiniteRepeatable(tween(4000, easing = EaseInOut), RepeatMode.Reverse), label = "b")

    val pulseScale = if (state.phase == Phase.CONTRACT) pulseVal else 1f
    val breatheAlpha = if (state.phase == Phase.RELAX) breatheVal else 1f

    // Pre-compute static arc params
    val strokeW = 8.dp
    val bgRingColor = RingBgColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(280.dp).scale(pulseScale).alpha(breatheAlpha),
    ) {
        // Canvas only recomposes when progress or ringColor changes
        val curProgress = progress
        val curColor = ringColor
        Canvas(Modifier.size(280.dp)) {
            val sw = strokeW.toPx()
            val pad = sw / 2
            val arcSize = Size(size.width - sw, size.height - sw)
            val tl = Offset(pad, pad)
            drawArc(bgRingColor, -90f, 360f, false, tl, arcSize, style = Stroke(sw))
            drawArc(curColor, -90f, 360f * curProgress, false, tl, arcSize,
                style = Stroke(sw, cap = StrokeCap.Round))
        }

        Text(state.phaseLabel, fontSize = 13.sp, color = HintColor, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp))

        val timeText = when (state.phase) {
            Phase.IDLE -> "${state.contractSec}"
            Phase.DONE -> "✓"
            else -> "${state.countdown}"
        }
        Text(timeText, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = ringColor)
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
            Text("📍 ${state.stageName} (${completed + 1}/$total)",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EEF2))
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

// ===================== Tips =====================

@Composable
fun TipsButton(mode: Mode) {
    val tips = remember(mode) { Config.tips[mode] ?: emptyList() }
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }, modifier = Modifier.size(32.dp)) {
        Text("📋", fontSize = 16.sp, modifier = Modifier.alpha(0.6f))
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
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
}
