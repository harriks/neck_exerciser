# 颈椎锻炼计时器 - 技术规格

## 1. 项目概览

| 属性 | 值 |
|------|-----|
| 项目名称 | 颈椎锻炼 |
| 项目类型 | Web 应用 + Android 原生应用 |
| Web 入口 | `spine_exercise_timer.html`（单文件，浏览器直接打开） |
| Android 入口 | `android/SpineExerciseTimer/`（Jetpack Compose + Kotlin） |
| 外部依赖 | 无（Web Audio API / SpeechSynthesis API / Android ToneGenerator / TTS） |

## 2. 项目结构

```
颈椎锻炼器/
├── CLAUDE.md                    # 本文件
├── spine_exercise_timer.html    # Web 版（单文件，HTML+CSS+JS）
├── timing_config.json           # 共享时长配置（参考副本，与代码内嵌 JSON 同构）

└── android/SpineExerciseTimer/  # Android 版
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    ├── gradle/wrapper/
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/strings.xml
        │   ├── values/colors.xml
        │   ├── values/ic_launcher_background.xml
        │   ├── drawable/ic_launcher_foreground.xml
        │   ├── mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
        │   └── raw/timing_config.json      # 时长配置（打包资源，参考副本）
        └── java/com/spineexercise/timer/
            ├── Model.kt           # 数据模型 + Config
            ├── TimingConfig.kt    # JSON 时长配置 + 手写解析器（纯 Kotlin）
            ├── WorkoutEngine.kt   # 纯 Kotlin 状态机（锚点计时）
            ├── TimerViewModel.kt  # 桥接：tick 驱动引擎 + 音效/TTS
            ├── CheckIn.kt         # 打卡记录（纯 Kotlin，紧凑序列化 v1:base36 epochDay）
            ├── CheckInStore.kt    # 打卡持久化（SharedPreferences，单一数据源）
            ├── ReminderPolicy.kt  # 提醒决策纯函数（shouldNotify / nextTriggerAt，可单测）
            ├── ReminderScheduler.kt  # 闹钟调度 + 通知渠道（Android 胶水）
            ├── ReminderReceiver.kt / BootReceiver.kt  # 通知发送 / 开机重排
            ├── Colors.kt          # 阶段颜色
## 3. 时长配置（JSON）

所有倒计时时间（准备/发力/放松/组数）集中在一处 JSON 配置，**改时长只需改 JSON，无需动逻辑代码**：

- **Android**：`app/src/main/java/com/spineexercise/timer/TimingConfig.kt` 顶部的
  `DEFAULT_TIMING_JSON` 字符串（启动时经手写解析器载入 `Config`）。
  `app/src/main/res/raw/timing_config.json` 与项目根 `timing_config.json` 为同构参考副本。
  用户也可在计时页 ⋮ 菜单「计时设置」中运行时编辑：`toJson()` 序列化后经
  `TimingStore`（SharedPreferences `"spine_timing"`）持久化，启动时优先于内嵌 JSON。
- **Web**：`spine_exercise_timer.html` `<script>` 顶部的 `const TIMING_CONFIG = {...}`
  （file:// 双击打开无法 fetch 外部 JSON，故内嵌；`STAGES`/`PREPARE_SEC` 由它派生）。

JSON 结构（三处保持一致，见项目根 `CLAUDE.md` 第 3 节）：
`prepareSec`（准备倒计时）、`modes.{gentle,isometric}[]` 的
`name`（播报名）、`dirs[]`（方向）、`contractSec`（发力）、`relaxSec`（放松）、`groups`（每方向组数）。
修改后需重新构建 APK（Android）或刷新浏览器（Web）。


            └── MainActivity.kt    # Compose UI
```

## 4. 功能规格

### 4.1 锻炼模式

进入 App 后先选择模式，选好后进入纯计时界面。

#### 舒缓锻炼（gentle）

| 参数 | 值 |
|------|-----|
| 发力时长 | 8 秒 |
| 放松时长 | 5 秒 |
| 总次数 | 8 次（左右手交替，各 4 次） |
| 力度 | 20%~30% 轻微力量 |
| 总时长 | 约 1 分 44 秒 |

#### 等长抗阻（isometric）

3 个阶段，共 15 组：

| 阶段 | 名称 | 方向 | 按压 | 休息 | 每方向组数 | 交替方式 |
|------|------|------|------|------|-----------|---------|
| 1 | 正向抗阻 | 右手按压前额 / 左手按压前额 | 15s | 15s | 3 组 | 左右手交替 |
| 2 | 侧向抗阻 | 右手按压右耳上侧 / 左手按压左耳上侧 | 15s | 15s | 3 组 | 左右手交替 |
| 3 | 弹力带训练 | 弹力带 | 15s | 30s | 3 组 | 单方向 |

**交替模式**：右手→左手→右手→左手→右手→左手（每只手各 3 次，共 6 次算 3 组）
**阶段4 已移除**（原肩膀放松）

### 4.2 状态机

```
idle ──[开始]──> prepare(3s) ──> contract ──> relax ──> contract ──> ... ──> done
                                    ↑                      |
                                    |   [还有下一组]        |
                                    +──────────────────────+
```

| phase | 含义 | 圆环颜色 | 动画 |
|-------|------|----------|------|
| `idle` | 空闲等待 | 灰色 `#607d8b` | 无 |
| `prepare` | 准备倒计时 3 秒 | 黄色 `#ffca28` | 无 |
| `contract` | 发力中 | 橙色 `#ff7043` | 脉冲缩放 |
| `relax` | 放松中 | 蓝色 `#4fc3f7` | 呼吸透明度 |
| `done` | 锻炼完成 | 绿色 `#9ccc65` | 无 |

### 4.3 计时规则

- 仅首轮包含 prepare 阶段（3 秒倒计时）
- relax 结束后 repCount++，判断是否完成
- 等长抗阻模式 relax 为 0 时跳过 relax 直接进入下一组
- 每秒 tick，倒计时归零时切换阶段
- 总用时从点"开始"计时，暂停时暂停，重置归零

### 4.4 语音提示

| 事件 | 语音内容 | 触发时机 |
|------|---------|---------|
| 点击开始 | "开始，准备"（等长抗阻："X训练开始"） | startWorkout |
| 首次进入发力 | "请换右手发力/请换左手发力" | 首个 contract |
| 后续切换发力 | "请换右手发力/请换左手发力" | contract 切换 |
| 进入放松 | "放松" | relax 开始 |
| 暂停 | "已暂停" | togglePause |
| 完成 | "完成，做得好"（等长抗阻："恭喜，全部完成，做得好"） | finishWorkout |

**等长抗阻阶段切换播报**（合并为一条语音，因 TTS 会清空队列）：
- 进入第 2 阶段首个发力：`侧向抗阻训练开始`
- 进入第 3 阶段（弹力带）：`弹力带训练开始`
- 弹力带阶段内后续组播报「继续训练」，并保留切换提示音
- 阶段名已含“训练”时不再重复拼接（“弹力带训练”→“弹力带训练开始”）

### 4.5 音效

| 事件 | 频率 | 时长 |
|------|------|------|
| 开始 | 880Hz sine | 0.15s |
| 切换阶段 | 660Hz sine | 0.2s |
| 最后 3 秒滴声 | 440Hz square | 0.08s |
| 完成 | 880→1100→1320Hz 三连音 | 递增 |

最后 3 秒滴声在 contract 和 relax 阶段都会触发。

### 4.6 按钮逻辑

只保留 2 个按钮：

| 状态 | 主按钮 | 重置按钮 |
|------|--------|---------|
| 未开始 | 开始 | 重置 |
| 运行中 | 暂停 | 重置 |
| 已暂停 | 继续 | 重置 |
| 已完成 | 重新开始 | 重置 |

### 4.7 界面布局

#### 模式选择页
- 🦴 图标 + 标题「颈椎锻炼」
- 两个模式卡片：舒缓锻炼 / 等长抗阻

#### 计时页面
- **顶部**：右上角 ⋮ 菜单（打卡日历 / 锻炼要点 / 关于[含版本号]，弹出动效+遮罩）
- **标题下方**：阶段名称（大字）+ 动作提示
- **中央**：圆环倒计时（内含 phaseLabel：左手发力中 / 放松中）
- **圆环下方**：阶段进度条 + 总用时（⏱ 0:00）
- **底部**：开始/暂停/继续 + 重置 两个按钮

#### 信息显示位置
- **圆环内上方 phaseLabel**：`左手发力中` / `放松中`
- **倒计时数字**：) contract/relax 时剩余 >3 秒为绿色、最后 3 秒为红色，0.6s 平滑过渡)
- **圆环内大字**：倒计时秒数
- **阶段进度 stageName**：`📍 正向抗阻 (1/15)` 全局组数进度
- **进度条**：彩色方块，已完成绿色、当前橙色、未完成暗色
- **总用时**：进度条右侧显示 `⏱ 0:00`

#### 锻炼要点弹窗
- 标题栏右侧 📋 图标，半透明
- 点击弹出 AlertDialog / Modal
- 内容为当前模式的锻炼要点列表

### 4.8 调试模式

Web 版底部有 Debug 加速开关（⚡ 10x），勾选后 tick 从 1000ms 变为 100ms，加速 10 倍。
Android 版无此功能（可用 Android Studio 模拟器加速）。

## 5. 技术实现

### 5.1 Web 版

- 单文件 `spine_exercise_timer.html`
- 原生 HTML + CSS + JavaScript，无框架
- 数据驱动：`TIMING_CONFIG` JSON 常量定义所有时长参数，`STAGES`/`PREPARE_SEC` 由它派生
- 圆环用 SVG `stroke-dashoffset` 实现
- 音效用 Web Audio API（OscillatorNode）
- 语音用 SpeechSynthesis API
- 进度条用 DOM 动态创建，`stageBarDirty` 标记减少重建
- 颜色/形状等常量提取为顶层变量，避免重复创建

### 5.2 Android 版

- Jetpack Compose 声明式 UI
- Kotlin + AndroidViewModel + StateFlow
- 圆环用 Canvas 绘制
- 脉冲/呼吸动画用 `rememberInfiniteTransition`
- 音效用 ToneGenerator
- 语音用 TextToSpeech
- TimerState 预计算显示字段（stageName/handName/contractSec 等），避免每次读取遍历 Config
- `buildState()` 工厂函数统一构建状态

### 5.3 状态管理

状态机核心变量：

```
mode: 模式（gentle/isometric）
phase: 阶段（idle/prepare/contract/relax/done）
si: stageIndex — 当前阶段索引
di: directionIndex — 当前方向索引
gi: groupCount — 当前已完成组数
repCount: 总重复计数
countdown: 当前倒计时秒数
elapsed: 总用时秒数
running: 是否运行中
paused: 是否暂停
```

`advanceGroup()` 函数处理组/阶段推进逻辑：
- 多方向交替模式：di 轮流切换，两方向都完成后 gi++
- 单方向模式：gi++ 直到 groups 后切阶段
- singleSide 标记：做完一个方向的全部组才切方向（已移除）

## 6. 架构（2026-09 重构后）

- **WorkoutEngine.kt**：纯 Kotlin 状态机（无 Android 依赖）
  - 锚点计时：每个阶段记录绝对结束时间戳，倒计时/总用时零漂移
  - 事件输出：语音/音效以 `EngineEvent`（Speak/Sfx）形式发出，由 ViewModel 播放
  - 推进统一走 `advanceGroup()`：舒缓=单阶段配置，与等长抗阻共用同一路径，零模式分支
  - `TimerState` + `buildState()` 工厂也在此文件；`completedGroups` 已预计算
- **TimerViewModel.kt**：只负责桥接 —— 200ms tick 驱动 engine，将事件转为
  ToneGenerator/TTS 调用；时钟用 `SystemClock.elapsedRealtime()`（单调时钟）
- **Model.kt**：`Config` 的时长来源改为 `TimingConfig`（JSON 配置），
  `Config.prepareSec/configure(json)` 暴露给引擎与 UI；`stagesOf()/stageOf()` 取代了 `!!` 访问
- **TimingConfig.kt**：JSON 时长配置 + 手写迷你解析器（纯 Kotlin、零依赖、可单测）
- **Colors.kt**：`PhaseColors` 从 Model.kt 迁出，Model 保持纯 Kotlin 可单测
- **MainActivity.kt**：`rememberSaveable` 保存所选模式；旋转屏幕不再重置锻炼
  （`LaunchedEffect(mode)` 仅在模式变化时 setMode；返回按钮负责 reset）
- **计时设置**：计时页 ⋮ 菜单「计时设置」运行时编辑时长/组数（`TimingConfig.toJson()` 序列化 +
  `TimingStore` 持久化，启动时优先于内嵌 JSON；保存热生效并重置进行中锻炼）
- **屏幕常亮**：锻炼运行期间 `FLAG_KEEP_SCREEN_ON` 保持屏幕唤醒，结束/离开计时页自动清除
- **打卡日历**：`CheckIn.kt` 纯 Kotlin 打卡日志（TreeSet 去重 + 紧凑 `v1:base36` 序列化）；
  持久化经 `CheckInStore.kt`（SharedPreferences `"spine_checkins"`，单一数据源，
  `AppNavigation` 写穿保存——冷启动/划掉任务后历史不丢）；完成锻炼进 `Phase.DONE` 时当天打卡
  （`CheckInLog` 按天去重防双计）；首页/计时页「📅」进入 `CalendarScreen`
  （月历视图 + 🔥 连续/最长 streak + 里程碑徽章行 7🥉/30🥈/100🥇/365👑）
- **每日提醒**：`ReminderPolicy.kt` 纯函数（`shouldNotify` 当天已打卡则跳过；
  `nextTriggerAt` 已过时刻滚动到明天）+ `ReminderScheduler.kt`
  （`setInexactRepeating` 非精确重复闹钟，免 SCHEDULE_EXACT_ALARM 权限）+
  `ReminderReceiver`（发送通知）/ `BootReceiver`（开机重排，闹钟不跨重启）；
  设置入口在日历页底部（Switch + TimePickerDialog；API 33+ 运行时请求 POST_NOTIFICATIONS，
  拒绝则开关回退并提示）；非精确闹钟允许分钟级偏差，DST 变更可偏移 1 小时直至下次重排
- **单元测试**：`app/src/test/.../WorkoutEngineTest.kt` + `CheckInLogTest.kt` +
  `ReminderPolicyTest.kt`（JUnit4，共 44 例）

## 7. 已知限制

- Web 版依赖浏览器 Web Audio API，部分浏览器首次需要用户交互才能播放
- Android 版 Gradle 构建需要网络下载依赖
- 图标为矢量绘制的颈椎图形，非专业设计

## 8. 构建与测试

项目路径现为全 ASCII（`F:\deepseek_harness\neck_exerciser\android\SpineExerciseTimer`），
Gradle 可直接在当前目录运行。此前「同步到 %TEMP% + 重定向构建目录」的绕法已移除，
`app/build.gradle.kts` 不再重定向构建目录，产物在项目内 `app/build/`。

三个等价用法（在 `android/SpineExerciseTimer/` 目录内执行）：

```powershell
.\run-tests.ps1                    # 单元测试（JUnit4），产物在 app\build\test-results
.\build-release.ps1                # 签名 release APK
gradle.bat ':app:assembleRelease'  # 或直接用 Gradle 打 release
```

签名配置读取 `local.properties`（SPINE_STORE_FILE/PWD、SPINE_KEY_ALIAS/PWD）。
`build-release.ps1` 会把 `app\build\outputs\apk\release\app-release.apk`
复制为项目目录下的 `app-release.apk`（ASCII 文件名，避免脚本编码问题）。
