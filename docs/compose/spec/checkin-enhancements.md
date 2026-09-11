---
feature: checkin-enhancements
status: delivered
updated: 2026-09-11
branch: feature/checkin-enhancements
commits: 53c6c93..663c388
---

# 打卡日历增强：持久化 + streak + 里程碑 + 每日提醒

## Report

**What was built** — 打卡日历从"会话内存"升级为"可靠长期记录"：打卡历史经
`CheckInStore`（SharedPreferences，`v1:base36` 紧凑格式不变）持久化，冷启动/划掉任务不再丢失；
日历页新增 🔥 连续/最长 streak（当天未锻炼不断签的宽限规则）与 7🥉/30🥈/100🥇/365👑 里程碑徽章行；
新增每日提醒——`ReminderPolicy` 纯函数决策（当天已打卡则跳过、时刻已过滚动到明天）+
非精确重复闹钟（免 SCHEDULE_EXACT_ALARM 权限）+ 通知接收器 + 开机重排 + 日历页设置区
（Switch + TimePickerDialog + API 33+ 运行时权限请求，拒绝回退）。
评审后追加修复：自动打卡守卫由布尔改为按日期（`recordedOn`，修复跨午夜漏记）、
提醒 PendingIntent 加 SINGLE_TOP、共享 PREFS_NAME 常量、Locale.ROOT 时间标签、
渠道前置创建。年度热力图与 Web 同步按用户决定排除（见 S3）。

**Verification** —
- `.\run-tests.ps1` — PASS：44 测试 0 失败（CheckInLog 17 / WorkoutEngine 18 / TimingConfig 4 / ReminderPolicy 5）
- `:app:assembleDebug` — PASS（评审修复后复跑通过）
- 模拟器 e2e（Pixel_7，API 34）：冷启动历史保留 PASS；日历统计与徽章数值一致 PASS
  （连续 2 天 · 本月 2 天 · 累计 2 天；差值 5/28/98/363）；未打卡日广播出通知 PASS
  （id=2001 channel=reminder）；已打卡日广播被门控拦截 PASS；
  完整跑完舒缓锻炼（107s）自动写穿打卡 PASS（prefs 由 `v1:fz6` → `v1:fz6,fz7`）
- 独立评审子代理复跑测试并全量核对：三结论 PASS / PASS-with-notes / PASS-with-notes，0 critical；
  所列 minor/nit 已在 663c388 修复（BootReceiver OEM 兼容性建议真机复验一次，无代码改动）

**Journey log** —
- `git worktree add` 被沙箱拦截（共享注册表变更）→ 改为主检出直接建特性分支，master 不动；
  今日未提交的日历工作（53c6c93）作为基线提交随分支携带。
- Android 14 上 shell（uid 2000）不能向非导出 receiver 广播；`run-as <pkg> am broadcast --user 0 -n`
  以应用自身身份可直达（需 `--user 0`，否则 user -2 SecurityException）。
- 直改 debug 应用 prefs 文件后必须 force-stop + 重启进程：SharedPreferences 有进程内缓存。
- 53c6c93（基线导入）不计入本特性评审范围；评审 diff 用 `53c6c93..f5269d5`。

## [S1] Problem

打卡历史仅存于 `rememberSaveable`（Activity savedInstanceState），用户从最近任务划掉 App 或冷启动后**全部打卡记录丢失**——对以长期记录为目的的日历功能是致命缺陷。同时缺少坚持激励（连续打卡）、成就反馈（里程碑）与每日锻炼提醒。

## [S2] Design

### D1 持久化（P0）
- 新文件 `CheckInStore.kt`：`object`，SharedPreferences 文件 `"spine_checkins"`，
  `load(context): String` / `save(context, raw)`（`apply()` 异步写）。序列化沿用 `v1:<base36 epochDay>` 格式，无迁移。
- `AppNavigation`：`checkinsRaw` 由 `rememberSaveable` 改为
  `remember { mutableStateOf(CheckInStore.load(LocalContext.current)) }`（单一数据源，消除双写分歧）；
  `onCheckIn` 回调写穿：先更新 state 再 `CheckInStore.save`。
  自动打卡守卫按日期（`recordedOn: LocalDate?`）而非布尔——跨午夜后再完成仍能记录新的一天。

### D2 streak / 里程碑（纯 Kotlin，下沉 `CheckInLog`）
- `currentStreak(today: LocalDate): Int` — 宽限规则：今天已打卡从今天回数；今天未打、昨天已打从昨天回数；再往前断档即 0。
- `longestStreak(): Int` — 升序遍历 epochDay 集合取最大连续段。
- 日期查询复用既有 `has(date: LocalDate)`（设计初稿的 `hasDate` 与其同义，实现不另设别名）、
  新增 `countInYear(year: Int): Int`（年视图为 S3 范围外，先备查询 + 单测）。
- `data class Milestone(days: Int, emoji: String, reached: Boolean)`，`milestones()` 阈值 7🥉/30🥈/100🥇/365👑，`reached = total >= days`。
- UI 只渲染，不参与计算（与 WorkoutEngine/TimingConfig 架构一致）。

### D3 日历页展示
- 统计区两行：`🔥 连续 N 天 · 最长 M 天` 与 `本月 X 天 · 累计 Y 天`。
- 里程碑徽章行：达成 = 高亮全彩，未达成 = 置灰 + 「差 N 天」。
- `CalendarScreen` 内 `remember(raw) { CheckInLog.parse(raw) }` 补 key 防陈旧。
- 复用现有颜色常量（DoneGreen/AccentCyan/MutedColor/SegDimColor），零新调色板。

### D4 每日提醒
- 纯逻辑 `ReminderPolicy.kt`（可单测）：
  - `shouldNotify(enabled: Boolean, checkedInToday: Boolean): Boolean = enabled && !checkedInToday`
  - `nextTriggerAt(hour: Int, minute: Int, now: LocalDateTime): LocalDateTime` — 今日该时刻已过则排明天。
- `ReminderScheduler.kt`（Android 胶水）：创建 NotificationChannel `"reminder"`；
  `schedule()` 用 `AlarmManager.setInexactRepeating(RTC_WAKEUP, …, INTERVAL_DAY, …)`（**非精确闹钟，免 SCHEDULE_EXACT_ALARM 权限**）；`cancel()`；开关状态与时间存 SharedPreferences（`reminder_enabled` / `reminder_hour` / `reminder_minute`，同一 prefs 文件）。
- `ReminderReceiver.kt`：触发时读 prefs + `CheckInStore` → `ReminderPolicy.shouldNotify` 通过才发通知
  （先查 `areNotificationsEnabled()`；点按 PendingIntent 打开 MainActivity，FLAG_IMMUTABLE）。
- `BootReceiver.kt`：BOOT_COMPLETED 后若已开启则重排闹钟（闹钟不跨重启）。
- Manifest：`POST_NOTIFICATIONS` + `RECEIVE_BOOT_COMPLETED` 权限；两个 receiver `exported="false"`
  （BOOT_COMPLETED 为受保护系统广播，系统投递不受 exported 限制；若构建/lint 报错则按报错调整并记录）。
- 设置 UI（`CalendarScreen` 底部「⏰ 每日提醒」区）：`Switch` + 当前时间文本（点击弹平台 `TimePickerDialog`）；
  API 33+ 开启时经 `rememberLauncherForActivityResult(RequestPermission)` 请求 POST_NOTIFICATIONS，
  拒绝则开关回退并提示；开关/时间变化即时重排或取消闹钟。

### 决策记录
- streak 宽限规则（当天未锻炼不断签）采用业界惯例（Duolingo 式）。
- 提醒跳过已打卡日：提醒应鼓励而非骚扰。
- 非精确重复闹钟：日常提醒允许分钟级偏差，换取零特殊权限。
- **Workspace 覆盖**：沙箱禁止本会话 `git worktree add`（共享注册表变更被拦截）→
  改为在主检出直接建 `feature/checkin-enhancements` 分支实施，master 提交（6a7e365）不动。

## [S3] Out of Scope

年度热力图（用户明确排除）；Web 版同步（仅 Android）；精确闹钟权限（SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM）；
打卡补签/手动编辑；云端同步；每 workout 细粒度统计。

## Tasks

- [x] T1: CheckInLog 纯函数扩展 + CheckInLogTest 新增用例（streak 宽限/断档/空、longestStreak 多段、countInYear 跨年边界、milestones 0/7/366） — acceptance: `.\run-tests.ps1` 全绿（covers: S2 D2）
- [x] T2: CheckInStore + AppNavigation 持久化集成 — acceptance: 完成锻炼打卡 → 划掉 App 重启历史仍在（covers: S2 D1; depends: 无）
- [x] T3: CalendarScreen 统计两行 + 徽章行 — acceptance: 模拟器显示与数据一致的 streak/最长/本月/累计与徽章状态（covers: S2 D3; depends: T1, T2）
- [x] T4: ReminderPolicy 纯函数 + 单测（nextTriggerAt 已过/未过/跨天边界） — acceptance: run-tests 全绿（covers: S2 D4; depends: 无）
- [x] T5: ReminderScheduler + ReminderReceiver + BootReceiver + manifest 权限/渠道 — acceptance: assembleDebug 通过；广播触发能出通知，当天已打卡则不出（covers: S2 D4; depends: T4, T2）
- [x] T6: 提醒设置 UI（Switch + TimePickerDialog + POST_NOTIFICATIONS 运行时请求） — acceptance: 开启→授权→到点收通知；关闭→取消；拒绝权限→开关回退（covers: S2 D4; depends: T5, T3）
- [x] T7: 文档同步（android/CLAUDE.md §6 + 根 CLAUDE.md） — acceptance: 两文档与实现一致（covers: S2 D1-D4; depends: T1-T6）
