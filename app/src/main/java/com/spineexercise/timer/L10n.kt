package com.spineexercise.timer

import java.util.Locale

// ===================== Localization (中英双语) =====================
// All user-facing text (UI labels, dialogs, engine announcements, tips,
// notification) lives here as a Strings bundle per language. Pure Kotlin so
// the engine and unit tests can use it directly.
//
// Language resolution: L10nStore (Android glue) applies either the user's
// in-app override (⋮ → Language) or the system locale at startup, and again
// in ReminderReceiver before posting a notification (process may be cold).

enum class Lang { ZH, EN }

object L10n {
    var lang: Lang = Lang.ZH
        private set

    val s: Strings get() = if (lang == Lang.ZH) ZhStrings else EnStrings

    fun set(l: Lang) { lang = l }

    /** Locale for the TTS engine, kept in step with [lang]. */
    val ttsLocale: Locale
        get() = if (lang == Lang.ZH) Locale.SIMPLIFIED_CHINESE else Locale.US

    // ---- Config-name translation ----
    // Stage names / directions are canonical Chinese strings coming from the
    // timing JSON (the workout script, shared with the Web version). They are
    // localized at display/announcement time via these lookups; unknown names
    // (future config edits) pass through unchanged.

    private val STAGE_EN = mapOf(
        "温和发力" to "Gentle Effort",
        "正向抗阻" to "Front Resistance",
        "侧向抗阻" to "Side Resistance",
        "弹力带训练" to "Resistance Band",
    )

    private val DIR_EN = mapOf(
        "右手" to "right hand",
        "左手" to "left hand",
        "弹力带" to "resistance band",
    )

    /** Localized stage display name (raw config name in ZH). */
    fun stage(zh: String): String = if (lang == Lang.ZH) zh else STAGE_EN[zh] ?: zh

    /** Localized direction name ("右手" → "right hand"). */
    fun dir(zh: String): String = if (lang == Lang.ZH) zh else DIR_EN[zh] ?: zh

    /**
     * Speech form of a stage name, used inside announcements: ZH guarantees a
     * single "训练" suffix ("侧向抗阻" → "侧向抗阻训练"); EN already reads
     * naturally ("Side Resistance").
     */
    fun talk(zh: String): String =
        if (lang == Lang.ZH) (if (zh.endsWith("训练")) zh else "${zh}训练") else stage(zh)
}

// ===================== String bundle =====================

interface Strings {
    // ---- App / mode select ----
    val appName: String                 // 颈椎锻炼
    val appSubtitle: String             // 温和的颈椎锻炼计时器
    val modeGentle: String              // 舒缓锻炼
    val modeGentleDesc: String          // 8s 发力 · 5s 放松 · 左右交替 8 轮
    val modeIso: String                 // 等长抗阻
    val modeIsoDesc: String             // 3 阶段 · 15 组 · 静态持续发力
    val calendarBtn: String             // 打卡日历
    val calendarEmpty: String           // 完成锻炼自动打卡
    val footerHint: String              // 跟随语音节奏 · 发力时保持均匀呼吸
    fun approxMinutes(min: Int): String // 约 N 分钟
    fun streakSummary(streak: Int, total: Int): String

    // ---- Buttons ----
    val start: String
    val pause: String
    val resume: String
    val reset: String

    // ---- Engine announcements (TTS) ----
    val startGentle: String             // 开始，准备
    fun stageStart(talkName: String): String   // X训练开始
    fun stageGetReady(talkName: String): String // X训练，请准备
    fun changeHand(dir: String): String // 请换X手发力
    val relaxCue: String                // 放松
    val continueCue: String             // 继续训练
    val pausedCue: String               // 已暂停
    val resumeCue: String               // 继续锻炼
    val resetCue: String                // 重置
    val finishCue: String               // 恭喜，全部完成，做得好

    // ---- Timer state display (TimerState computed props) ----
    val ready: String                   // 准备就绪 / 等待中
    val waiting: String
    val prepare: String                 // ⏳ 准备
    val countdown: String               // 倒计时
    fun contractText(stage: String): String   // 💪 温和发力
    fun contractLabel(dir: String): String    // 右手发力中
    val relaxing: String                // 放松中
    val relaxText: String               // 🍃 放松
    val done: String                    // 🎉 完成
    val complete: String                // 已完成
    val idleHint: String                // 点击「开始」按钮，跟随节奏锻炼颈椎
    fun nextStageHint(stage: String): String  // 下一阶段：X，请提前换好动作
    val prepareHint: String             // 请就位，倒计时结束后开始发力
    val isoContractHint: String         // 保持稳定，均匀呼吸，切勿憋气
    fun gentleContractHint(dir: String): String // 20%~30% 轻微力量，X手支撑
    val relaxHint: String               // 彻底松开，让肌肉休息
    val doneHint: String                // 做得好！请缓慢起身，避免突然动作
    fun contractIsoProgress(stage: String, dir: String): String // 💪 X · Y
    fun contractGentleProgress(dir: String): String             // 💪 Y发力
    fun stageProgress(stage: String): String    // 📍 X

    // ---- Header menu / language ----
    val menuCd: String                  // 菜单 (content description)
    val menuCalendar: String
    val menuTips: String
    val menuSettings: String
    val menuAbout: String
    val menuLanguage: String            // 语言 / Language
    val languageTitle: String           // 语言 / Language
    val followSystem: String            // 跟随系统 / Follow system

    // ---- Dialogs ----
    val gotIt: String                   // 知道了
    val ok: String                      // 好
    val save: String
    val cancel: String
    val restoreDefaults: String
    val tipsTitle: String               // 📋 锻炼要点
    val settingsTitle: String           // ⚙️ 计时设置
    val aboutTitle: String              // ℹ️ 关于
    val aboutDesc: String
    fun version(v: String, code: Long): String
    val versionUnknown: String

    // ---- Back confirm ----
    val backTitle: String
    val backBody: String
    val backResetExit: String
    val backStay: String

    // ---- Settings editor ----
    val settingPrepare: String
    val settingStagePrepare: String
    val settingContract: String
    val settingRelax: String
    val settingGroups: String
    val settingsHint: String
    fun sectionGentle(stage: String): String
    fun sectionIso(stage: String): String

    // ---- Tips ----
    val gentleTips: List<String>
    val isoTips: List<String>

    // ---- Done overlay ----
    val doneTitle: String
    val doneBody: String
    val doneCheckedIn: String
    val again: String
    val back: String

    // ---- Calendar ----
    val calTitle: String
    val statStreak: String
    val statBest: String
    val statMonth: String
    val statTotal: String
    val statGentleMonth: String
    val statIsoMonth: String
    val statGentleTotal: String
    val statIsoTotal: String
    val calHint: String
    val prevMonthCd: String
    val nextMonthCd: String
    val weekDays: List<String>
    fun monthTitle(year: Int, month: Int): String
    fun milestoneReached(days: Int): String
    fun milestoneRemaining(left: Int): String

    // ---- Reminder ----
    val reminderTitle: String
    val reminderSwitchCd: String
    val reminderDenied: String
    val reminderOn: String
    val reminderOff: String

    // ---- Notification (ReminderReceiver) ----
    val notifTitle: String
    val notifBody: String
    val notifChannelName: String    // 锻炼提醒
    val notifChannelDesc: String    // 每日颈椎锻炼提醒
}

// ===================== 中文 =====================

object ZhStrings : Strings {
    override val appName = "颈椎锻炼"
    override val appSubtitle = "温和的颈椎锻炼计时器"
    override val modeGentle = "舒缓锻炼"
    override val modeGentleDesc = "8s 发力 · 5s 放松 · 左右交替 8 轮"
    override val modeIso = "等长抗阻"
    override val modeIsoDesc = "3 阶段 · 15 组 · 静态持续发力"
    override val calendarBtn = "打卡日历"
    override val calendarEmpty = "完成锻炼自动打卡"
    override val footerHint = "跟随语音节奏 · 发力时保持均匀呼吸"
    override fun approxMinutes(min: Int) = "约 $min 分钟"
    override fun streakSummary(streak: Int, total: Int) = "🔥 连续 $streak 天 · 累计 $total 天"

    override val start = "开始"
    override val pause = "暂停"
    override val resume = "继续"
    override val reset = "重置"

    override val startGentle = "开始，准备"
    override fun stageStart(talkName: String) = "${talkName}开始"
    override fun stageGetReady(talkName: String) = "$talkName，请准备"
    override fun changeHand(dir: String) = "请换${dir}发力"
    override val relaxCue = "放松"
    override val continueCue = "继续训练"
    override val pausedCue = "已暂停"
    override val resumeCue = "继续锻炼"
    override val resetCue = "重置"
    override val finishCue = "恭喜，全部完成，做得好"

    override val ready = "准备就绪"
    override val waiting = "等待中"
    override val prepare = "⏳ 准备"
    override val countdown = "倒计时"
    override fun contractText(stage: String) = "💪 $stage"
    override fun contractLabel(dir: String) = "${dir}发力中"
    override val relaxing = "放松中"
    override val relaxText = "🍃 放松"
    override val done = "🎉 完成"
    override val complete = "已完成"
    override val idleHint = "点击「开始」按钮，跟随节奏锻炼颈椎"
    override fun nextStageHint(stage: String) = "下一阶段：$stage，请提前换好动作"
    override val prepareHint = "请就位，倒计时结束后开始发力"
    override val isoContractHint = "保持稳定，均匀呼吸，切勿憋气"
    override fun gentleContractHint(dir: String) = "20%~30% 轻微力量，${dir}支撑"
    override val relaxHint = "彻底松开，让肌肉休息"
    override val doneHint = "做得好！请缓慢起身，避免突然动作"
    override fun contractIsoProgress(stage: String, dir: String) = "💪 $stage · $dir"
    override fun contractGentleProgress(dir: String) = "💪 ${dir}发力"
    override fun stageProgress(stage: String) = "📍 $stage"

    override val menuCd = "菜单"
    override val menuCalendar = "打卡日历"
    override val menuTips = "锻炼要点"
    override val menuSettings = "计时设置"
    override val menuAbout = "关于"
    override val menuLanguage = "语言 / Language"
    override val languageTitle = "语言 / Language"
    override val followSystem = "跟随系统"

    override val gotIt = "知道了"
    override val ok = "好"
    override val save = "保存"
    override val cancel = "取消"
    override val restoreDefaults = "恢复默认"
    override val tipsTitle = "📋 锻炼要点"
    override val settingsTitle = "⚙️ 计时设置"
    override val aboutTitle = "ℹ️ 关于"
    override val aboutDesc = "温和的等长颈椎锻炼计时器\n舒缓 / 等长抗阻 · 语音引导 · 打卡日历"
    override fun version(v: String, code: Long) = "版本 $v ($code)"
    override val versionUnknown = "版本未知"

    override val backTitle = "放弃本次锻炼？"
    override val backBody = "进行中的锻炼进度和总用时将被重置。"
    override val backResetExit = "重置并返回"
    override val backStay = "继续锻炼"

    override val settingPrepare = "准备倒计时（秒）"
    override val settingStagePrepare = "阶段切换准备（秒）"
    override val settingContract = "发力（秒）"
    override val settingRelax = "放松（秒）"
    override val settingGroups = "组数（每方向）"
    override val settingsHint = "保存后对新开始的锻炼生效；进行中的锻炼会被重置。"
    override fun sectionGentle(stage: String) = "舒缓锻炼 · $stage"
    override fun sectionIso(stage: String) = "等长抗阻 · $stage"

    override val gentleTips = listOf(
        "力度：只需 20%～30% 的轻微力量，绝不能使出全力",
        "支撑手：每个发力回合交替左手/右手，会有语音与文字提示",
        "呼吸：发力与放松时保持均匀顺畅，不要憋气",
        "节奏：准备 3 秒 → 发力 8 秒 → 放松 5 秒，左右交替 8 次",
        "体感：以轻微酸胀感为宜，如有疼痛请立即停止",
    )
    override val isoTips = listOf(
        "阶段1：正向抗阻，左右手交替各3组（共6组），每组15秒、休息15秒",
        "阶段2：侧向抗阻，左右手交替各3组（共6组），每组15秒、休息15秒",
        "阶段3：弹力带训练，3组，每组15秒、休息30秒",
        "阶段切换：新阶段开始前有准备倒计时，请利用该时间换好动作",
        "力度：持续静态发力，保持稳定不晃动，20%～30% 轻微力量",
        "呼吸：全程保持均匀呼吸，切勿憋气",
        "体感：以持续轻微酸胀为宜，如有疼痛请立即停止",
    )

    override val doneTitle = "锻炼完成！"
    override val doneBody = "做得很棒，请缓慢起身，避免突然动作"
    override val doneCheckedIn = "📅 今日打卡已记录"
    override val again = "再练一次"
    override val back = "返回"

    override val calTitle = "打卡日历"
    override val statStreak = "连续"
    override val statBest = "最长"
    override val statMonth = "本月"
    override val statTotal = "累计"
    override val statGentleMonth = "舒缓 · 本月"
    override val statIsoMonth = "等长抗阻 · 本月"
    override val statGentleTotal = "舒缓 · 累计"
    override val statIsoTotal = "等长抗阻 · 累计"
    override val calHint = "完成一次锻炼后，当天自动记录打卡 ✦"
    override val prevMonthCd = "上一个月"
    override val nextMonthCd = "下一个月"
    override val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
    override fun monthTitle(year: Int, month: Int) = "$year 年 $month 月"
    override fun milestoneReached(days: Int) = "${days}天"
    override fun milestoneRemaining(left: Int) = "差${left}天"

    override val reminderTitle = "⏰ 每日提醒"
    override val reminderSwitchCd = "每日提醒开关"
    override val reminderDenied = "未授予通知权限，无法提醒；可在系统设置中开启"
    override val reminderOn = "每天到点提醒，当天已完成锻炼则不打扰"
    override val reminderOff = "开启后每天到点提醒一次（默认 20:00）"

    override val notifTitle = "🦴 颈椎锻炼时间到"
    override val notifBody = "几分钟的温柔锻炼，让颈椎放松一下"
    override val notifChannelName = "锻炼提醒"
    override val notifChannelDesc = "每日颈椎锻炼提醒"
}

// ===================== English =====================

object EnStrings : Strings {
    override val appName = "Neck Exercise"
    override val appSubtitle = "A gentle neck exercise timer"
    override val modeGentle = "Gentle Workout"
    override val modeGentleDesc = "8s push · 5s relax · 8 alternating rounds"
    override val modeIso = "Isometric"
    override val modeIsoDesc = "3 stages · 15 sets · static holds"
    override val calendarBtn = "Check-in Calendar"
    override val calendarEmpty = "Auto check-in after a workout"
    override val footerHint = "Follow the voice · breathe evenly while pushing"
    override fun approxMinutes(min: Int) = "~$min min"
    override fun streakSummary(streak: Int, total: Int) = "🔥 $streak-day streak · $total total"

    override val start = "Start"
    override val pause = "Pause"
    override val resume = "Resume"
    override val reset = "Reset"

    override val startGentle = "Start, get ready"
    override fun stageStart(talkName: String) = "$talkName, begin"
    override fun stageGetReady(talkName: String) = "Next: $talkName, get ready"
    override fun changeHand(dir: String) = "Switch to your $dir"
    override val relaxCue = "Relax"
    override val continueCue = "Continue"
    override val pausedCue = "Paused"
    override val resumeCue = "Resuming"
    override val resetCue = "Reset"
    override val finishCue = "Congratulations, all done, great job"

    override val ready = "Ready"
    override val waiting = "Waiting"
    override val prepare = "⏳ Prepare"
    override val countdown = "Countdown"
    override fun contractText(stage: String) = "💪 $stage"
    override fun contractLabel(dir: String) = "Pushing with $dir"
    override val relaxing = "Relaxing"
    override val relaxText = "🍃 Relax"
    override val done = "🎉 Done"
    override val complete = "Complete"
    override val idleHint = "Tap Start and follow the rhythm"
    override fun nextStageHint(stage: String) = "Next up: $stage — get into position"
    override val prepareHint = "Get set — push when the countdown ends"
    override val isoContractHint = "Stay steady, breathe evenly — never hold your breath"
    override fun gentleContractHint(dir: String) = "20–30% gentle force, support with your $dir"
    override val relaxHint = "Release completely, let the muscles rest"
    override val doneHint = "Great job! Rise slowly, avoid sudden movements"
    override fun contractIsoProgress(stage: String, dir: String) = "💪 $stage · $dir"
    override fun contractGentleProgress(dir: String) = "💪 Push with $dir"
    override fun stageProgress(stage: String) = "📍 $stage"

    override val menuCd = "Menu"
    override val menuCalendar = "Check-in Calendar"
    override val menuTips = "Workout Tips"
    override val menuSettings = "Timing Settings"
    override val menuAbout = "About"
    override val menuLanguage = "Language / 语言"
    override val languageTitle = "Language / 语言"
    override val followSystem = "Follow system"

    override val gotIt = "Got it"
    override val ok = "OK"
    override val save = "Save"
    override val cancel = "Cancel"
    override val restoreDefaults = "Restore defaults"
    override val tipsTitle = "📋 Workout Tips"
    override val settingsTitle = "⚙️ Timing Settings"
    override val aboutTitle = "ℹ️ About"
    override val aboutDesc = "A gentle isometric neck exercise timer\nGentle / Isometric · Voice guidance · Check-in calendar"
    override fun version(v: String, code: Long) = "Version $v ($code)"
    override val versionUnknown = "Unknown version"

    override val backTitle = "Quit this workout?"
    override val backBody = "Progress and elapsed time will be reset."
    override val backResetExit = "Reset & exit"
    override val backStay = "Keep going"

    override val settingPrepare = "Prep countdown (s)"
    override val settingStagePrepare = "Stage-switch prep (s)"
    override val settingContract = "Push (s)"
    override val settingRelax = "Relax (s)"
    override val settingGroups = "Sets per side"
    override val settingsHint = "Applies to new workouts; a running workout is reset."
    override fun sectionGentle(stage: String) = "Gentle Workout" // single stage; ZH shows "舒缓锻炼 · 温和发力"
    override fun sectionIso(stage: String) = "Isometric · $stage"

    override val gentleTips = listOf(
        "Effort: only 20–30% of your strength — never go all out",
        "Supporting hand: alternate left/right each round, with voice and text cues",
        "Breathing: keep it smooth and even during push and relax — don't hold your breath",
        "Rhythm: 3s prepare → 8s push → 5s relax, alternating for 8 rounds",
        "Feel: mild soreness is fine; stop immediately if you feel pain",
    )
    override val isoTips = listOf(
        "Stage 1: Front resistance — alternate hands, 3 sets each (6 total), 15s hold / 15s rest",
        "Stage 2: Side resistance — alternate hands, 3 sets each (6 total), 15s hold / 15s rest",
        "Stage 3: Resistance band — 3 sets, 15s hold / 30s rest",
        "Stage switches: a countdown gives you time to change position before the next stage",
        "Effort: steady static push, stay stable — 20–30% gentle force",
        "Breathing: breathe evenly throughout, never hold your breath",
        "Feel: mild sustained soreness is fine; stop immediately if you feel pain",
    )

    override val doneTitle = "Workout Complete!"
    override val doneBody = "Great job! Rise slowly and avoid sudden movements"
    override val doneCheckedIn = "📅 Checked in for today"
    override val again = "Go again"
    override val back = "Back"

    override val calTitle = "Check-in Calendar"
    override val statStreak = "Streak"
    override val statBest = "Best"
    override val statMonth = "Month"
    override val statTotal = "Total"
    override val statGentleMonth = "Gentle · Month"
    override val statIsoMonth = "Iso · Month"
    override val statGentleTotal = "Gentle · Total"
    override val statIsoTotal = "Iso · Total"
    override val calHint = "Finish a workout and the day checks in automatically ✦"
    override val prevMonthCd = "Previous month"
    override val nextMonthCd = "Next month"
    override val weekDays = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    override fun monthTitle(year: Int, month: Int) =
        "${MONTHS[month - 1]} $year"

    override fun milestoneReached(days: Int) = "$days d"
    override fun milestoneRemaining(left: Int) = "$left to go"

    override val reminderTitle = "⏰ Daily Reminder"
    override val reminderSwitchCd = "Daily reminder switch"
    override val reminderDenied = "Notification permission denied — enable it in system settings"
    override val reminderOn = "Reminds daily at the set time (skipped if already checked in)"
    override val reminderOff = "Once on, a daily reminder fires (default 20:00)"

    override val notifTitle = "🦴 Neck exercise time"
    override val notifBody = "A few gentle minutes to loosen up your neck"
    override val notifChannelName = "Workout Reminder"
    override val notifChannelDesc = "Daily neck exercise reminder"

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}
