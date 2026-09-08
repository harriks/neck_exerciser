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
        │   └── mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
        └── java/com/spineexercise/timer/
            ├── Model.kt           # 数据模型 + 配置
            ├── TimerViewModel.kt  # 核心逻辑 + 音效/TTS
            └── MainActivity.kt    # Compose UI
```

## 3. 功能规格

### 3.1 锻炼模式

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

### 3.2 状态机

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

### 3.3 计时规则

- 仅首轮包含 prepare 阶段（3 秒倒计时）
- relax 结束后 repCount++，判断是否完成
- 等长抗阻模式 relax 为 0 时跳过 relax 直接进入下一组
- 每秒 tick，倒计时归零时切换阶段
- 总用时从点"开始"计时，暂停时暂停，重置归零

### 3.4 语音提示

| 事件 | 语音内容 | 触发时机 |
|------|---------|---------|
| 点击开始 | "开始，准备" | startWorkout |
| 首次进入发力 | "用右手/用左手" | 首个 contract |
| 后续切换发力 | "换右手/换左手" | contract 切换 |
| 进入放松 | "放松" | relax 开始 |
| 暂停 | "已暂停" | togglePause |
| 完成 | "完成，做得好" | finishWorkout |

### 3.5 音效

| 事件 | 频率 | 时长 |
|------|------|------|
| 开始 | 880Hz sine | 0.15s |
| 切换阶段 | 660Hz sine | 0.2s |
| 最后 3 秒滴声 | 440Hz square | 0.08s |
| 完成 | 880→1100→1320Hz 三连音 | 递增 |

最后 3 秒滴声在 contract 和 relax 阶段都会触发。

### 3.6 按钮逻辑

只保留 2 个按钮：

| 状态 | 主按钮 | 重置按钮 |
|------|--------|---------|
| 未开始 | 开始 | 重置 |
| 运行中 | 暂停 | 重置 |
| 已暂停 | 继续 | 重置 |
| 已完成 | 重新开始 | 重置 |

### 3.7 界面布局

#### 模式选择页
- 🦴 图标 + 标题「颈椎锻炼」
- 两个模式卡片：舒缓锻炼 / 等长抗阻

#### 计时页面
- **顶部**：← 返回 + 📋 锻炼要点（点击弹窗）
- **标题下方**：阶段名称（大字）+ 动作提示
- **中央**：圆环倒计时（内含 phaseLabel：左手发力中 / 放松中）
- **圆环下方**：阶段进度条 + 总用时（⏱ 0:00）
- **底部**：开始/暂停/继续 + 重置 两个按钮

#### 信息显示位置
- **圆环内上方 phaseLabel**：`左手发力中` / `放松中`
- **圆环内大字**：倒计时秒数
- **阶段进度 stageName**：`📍 正向抗阻 (1/15)` 全局组数进度
- **进度条**：彩色方块，已完成绿色、当前橙色、未完成暗色
- **总用时**：进度条右侧显示 `⏱ 0:00`

#### 锻炼要点弹窗
- 标题栏右侧 📋 图标，半透明
- 点击弹出 AlertDialog / Modal
- 内容为当前模式的锻炼要点列表

### 3.8 调试模式

Web 版底部有 Debug 加速开关（⚡ 10x），勾选后 tick 从 1000ms 变为 100ms，加速 10 倍。
Android 版无此功能（可用 Android Studio 模拟器加速）。

## 4. 技术实现

### 4.1 Web 版

- 单文件 `spine_exercise_timer.html`
- 原生 HTML + CSS + JavaScript，无框架
- 数据驱动：`STAGES` 配置定义所有模式参数
- 圆环用 SVG `stroke-dashoffset` 实现
- 音效用 Web Audio API（OscillatorNode）
- 语音用 SpeechSynthesis API
- 进度条用 DOM 动态创建，`stageBarDirty` 标记减少重建
- 颜色/形状等常量提取为顶层变量，避免重复创建

### 4.2 Android 版

- Jetpack Compose 声明式 UI
- Kotlin + AndroidViewModel + StateFlow
- 圆环用 Canvas 绘制
- 脉冲/呼吸动画用 `rememberInfiniteTransition`
- 音效用 ToneGenerator
- 语音用 TextToSpeech
- TimerState 预计算显示字段（stageName/handName/contractSec 等），避免每次读取遍历 Config
- `buildState()` 工厂函数统一构建状态

### 4.3 状态管理

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

## 5. 架构（2026-09 重构后）

- **WorkoutEngine.kt**：纯 Kotlin 状态机（无 Android 依赖）
  - 锚点计时：每个阶段记录绝对结束时间戳，倒计时/总用时零漂移
  - 事件输出：语音/音效以 `EngineEvent`（Speak/Sfx）形式发出，由 ViewModel 播放
  - `TimerState` + `buildState()` 工厂也在此文件；`completedGroups` 已预计算
- **TimerViewModel.kt**：只负责桥接 —— 200ms tick 驱动 engine，将事件转为
  ToneGenerator/TTS 调用；时钟用 `SystemClock.elapsedRealtime()`（单调时钟）
- **Model.kt**：`Config.stagesOf()/stageOf()` 取代了 `!!` 访问
- **Colors.kt**：`PhaseColors` 从 Model.kt 迁出，Model 保持纯 Kotlin 可单测
- **MainActivity.kt**：`rememberSaveable` 保存所选模式；旋转屏幕不再重置锻炼
  （`LaunchedEffect(mode)` 仅在模式变化时 setMode；返回按钮负责 reset）
- **单元测试**：`app/src/test/.../WorkoutEngineTest.kt`（14 个用例，JUnit4）

## 6. 已知限制

- Web 版依赖浏览器 Web Audio API，部分浏览器首次需要用户交互才能播放
- Android 版项目路径不能包含中文字符（已通过 `android.overridePathCheck=true` 绕过）
- Android 版 Gradle 构建需要网络下载依赖
- 图标为矢量绘制的颈椎图形，非专业设计

## 7. 运行单元测试

Gradle 测试 worker 在非 ASCII（中文）项目路径下无法加载测试类（编译不受影响）。
使用仓库根目录的 `run-tests.ps1`，它会将源码同步到 %TEMP% 下的 ASCII 路径运行：

```
powershell -File run-tests.ps1        # 运行后清理临时目录
powershell -File run-tests.ps1 -Keep # 保留临时目录便于调试
```
