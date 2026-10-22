# XiaoZhiAI — Android 端项目结构与约定

> 版本: v1.5.49 | 包名: `com.xiaozhi.aiservice` | minSdk: 26 | targetSdk: 34
> Android + Kotlin + Jetpack Compose | Clean Architecture

---

## 一、项目概述

让安卓手机像 ESP32 设备一样连接小智后台，实现语音对话。核心链路：

```
麦克风录音 → Opus编码 → WebSocket发送 → 服务器ASR/LLM/TTS → Opus接收 → AudioTrack播放
```

### 核心功能

- 语音对话（Opus 编解码，16kHz 上行 / 24kHz 下行）
- 文字对话（可选的文本输入/输出模式）
- AUTO / REALTIME 两种对话模式
- 群聊接龙（多 bot 依次对话转发）
- 多智能体（多个联系人独立连接）
- 57+ MCP 工具（拍照、定位、网络、闹钟、笔记、二维码等）
- 唤醒词识别（FSMN VAD + 自定义唤醒）
- 暗色主题（Material You Monet）

---

## 二、目录结构

```
XiaoZhiAI/
├── app/
│   ├── build.gradle                  # 版本 1.5.49, compileSdk 34, AGP+Hilt+Compose
│   ├── CMakeLists.txt                # Native (C++) 构建: Opus 编解码
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml   # 权限（14项）、Activity、Service、Receiver 声明
│           ├── assets/
│           │   └── fsmn_vad.onnx     # FSMN VAD 模型 (阿里达摩院, 500KB)
│           ├── cpp/
│           │   └── include/opus/     # Opus 编解码 C 头文件
│           ├── java/com/xiaozhi/aiservice/
│           │   ├── XiaoZhiApplication.kt     # Application 入口
│           │   │
│           │   ├── domain/            # 领域层（纯 Kotlin，无 Android 依赖）
│           │   │   ├── model/         # 领域实体
│           │   │   │   ├── Models.kt          # ChatMessage, BotContact, GroupChat, MCP 等
│           │   │   │   ├── RelayState.kt      # 群聊接龙状态
│           │   │   │   └── BotConnection.kt   # WebSocket 连接状态枚举
│           │   │   ├── repository/    # Repository 接口
│           │   │   │   ├── Repositories.kt    # MessageRepository, ContactsRepository, DeviceRepository
│           │   │   │   ├── GroupChatRepository.kt
│           │   │   │   └── BotConnectionPoolRepository.kt
│           │   │   ├── audio/
│           │   │   │   └── AudioPlaybackManager.kt  # 播放管理器接口
│           │   │   ├── usecase/       # 用例层
│           │   │   │   ├── relay/     # 群聊接龙用例
│           │   │   │   │   ├── RelayEngine.kt          # 中心化状态机 (核心, 678行)
│           │   │   │   │   ├── RelayConfig.kt          # 接龙配置常量
│           │   │   │   │   ├── SpeakerScorer.kt        # 说话人评分+选择
│           │   │   │   │   ├── AudioForwardHandler.kt  # 音频转发
│           │   │   │   │   ├── DeadloopDetector.kt     # 死循环检测
│           │   │   │   │   ├── DeduplicationUtil.kt    # 回复去重
│           │   │   │   │   └── RelayValidator.kt       # 接龙校验
│           │   │   │   └── group/
│           │   │   │       └── GroupChatUseCase.kt     # 群聊CRUD用例
│           │   │   └── PlatformLogger.kt   # 日志抽象
│           │   │
│           │   ├── data/              # 数据层（领域接口实现）
│           │   │   ├── remote/        # 网络通信
│           │   │   │   ├── XiaoZhiBackendService.kt     # WebSocket 主服务 (状态机~700行)
│           │   │   │   ├── BotConnectionPoolImpl.kt     # Bot连接池
│           │   │   │   ├── LocalCommandRouter.kt        # 本地命令路由
│           │   │   │   ├── MCPToolRegistry.kt           # MCP 工具注册中心
│           │   │   │   ├── McpResultFormat.kt           # MCP 结果格式化
│           │   │   │   └── mcp/        # 8个 MCP 工具文件
│           │   │   │       ├── McpHelpers.kt            # MCP 工具基础设施
│           │   │   │       ├── McpToolsBasic.kt         # 基础工具 (11个)
│           │   │   │       ├── McpToolsIntent.kt        # Intent工具 (10个)
│           │   │   │       ├── McpToolsNetwork.kt       # 网络工具 (9个)
│           │   │   │       ├── McpToolsLocation.kt      # 定位工具 (2个)
│           │   │   │       ├── McpToolsPhoto.kt         # 拍照工具 (2个)
│           │   │   │       ├── McpToolsExtra.kt         # 额外工具 (14个: 笔记/闹钟/股市/短信等)
│           │   │   │       ├── McpToolsXiaozhi.kt       # 小智工具 (~5个)
│           │   │   │       ├── McpToolsTheme.kt         # 主题工具
│           │   │   │       ├── AlarmReceiver.kt         # 闹钟广播接收
│           │   │   │       └── ReminderReceiver.kt      # 提醒广播接收
│           │   │   ├── repository/    # Repository 实现
│           │   │   │   ├── ConnectionRepositoryImpl.kt  # 连接状态机+音频管线+VAD+唤醒词
│           │   │   │   ├── MessageRepositoryImpl.kt     # DataStore 消息持久化
│           │   │   │   ├── ContactsRepositoryImpl.kt    # DataStore 联系人持久化
│           │   │   │   ├── GroupChatRepositoryImpl.kt   # DataStore 群聊持久化
│           │   │   │   └── DeviceRepositoryImpl.kt      # 内存状态
│           │   │   ├── audio/         # 音频管线
│           │   │   │   ├── AudioService.kt              # 音频服务
│           │   │   │   ├── AudioRecorder.kt             # 录音 (AudioRecord)
│           │   │   │   ├── AudioPlaybackManagerImpl.kt  # 播放实现 (AudioTrack)
│           │   │   │   ├── AudioEnhancer.kt             # 音频增强
│           │   │   │   ├── NativeOpusDecoder.kt         # Opus → PCM 解码 (JNI)
│           │   │   │   ├── NativeOpusEncoder.kt         # PCM → Opus 编码 (JNI)
│           │   │   │   ├── OpusEncoder.kt               # Opus 编码器接口
│           │   │   │   ├── SileroVAD.kt                 # Silero VAD (ONNX Runtime)
│           │   │   │   ├── FsmnVAD.kt                   # FSMN VAD (阿里达摩院, 当前在用)
│           │   │   │   ├── FsmnFbank.kt                 # FBank 特征提取 (FFT+Mel+Log+LFR)
│           │   │   │   ├── MfccExtractor.kt             # MFCC 特征提取 (唤醒词)
│           │   │   │   └── WakeWordDetector.kt          # 唤醒词检测器
│           │   │   ├── settings/      # 持久化配置
│           │   │   │   ├── Settings.kt                  # DataStore 配置
│           │   │   │   └── DeviceSettings.kt            # 设备配置
│           │   │   └── logging/
│           │   │       └── AndroidLogger.kt             # Android Logcat 日志实现
│           │   │
│           │   ├── presentation/     # 表现层
│           │   │   ├── viewmodel/    # ViewModel
│           │   │   │   ├── ChatViewModel.kt             # 单聊 VM (176行 ✅)
│           │   │   │   ├── GroupChatViewModel.kt        # 群聊 VM (~485行, 含 Controller)
│           │   │   │   ├── ContactsViewModel.kt         # 联系人 VM (72行 ✅)
│           │   │   │   └── BotEvent.kt                  # Bot 事件模型
│           │   │   ├── ui/           # Compose 界面
│           │   │   │   ├── MainActivity.kt              # 主 Activity
│           │   │   │   ├── CameraCaptureActivity.kt     # 拍照 Activity (CameraX)
│           │   │   │   ├── QrScannerActivity.kt         # 扫码 Activity (CameraX+ZXing)
│           │   │   │   ├── ScreenshotCaptureActivity.kt # 截图 Activity (MediaProjection)
│           │   │   │   ├── chat/
│           │   │   │   │   └── ChatScreen.kt            # 聊天界面 (单聊)
│           │   │   │   ├── contacts/
│           │   │   │   │   ├── ContactsScreen.kt        # 联系人列表
│           │   │   │   │   ├── GroupChatListScreen.kt   # 群聊列表
│           │   │   │   │   ├── GroupChatScreen.kt       # 群聊聊天界面
│           │   │   │   │   └── CreateGroupScreen.kt     # 创建群聊
│           │   │   │   └── components/
│           │   │   │       ├── ChatMessageItem.kt       # 聊天气泡组件
│           │   │   │       ├── ChatMessageList.kt       # 消息列表
│           │   │   │       ├── InputBar.kt              # 输入栏
│           │   │   │       ├── AvatarView.kt            # 头像组件
│           │   │   │       └── EmptyStateView.kt        # 空状态
│           │   │   └── theme/
│           │   │       ├── Theme.kt                     # 主题 (AppShapes 圆角体系)
│           │   │       └── ThemeManager.kt              # 主题管理器
│           │   │
│           │   ├── di/               # 依赖注入
│           │   │   ├── AppModule.kt                     # Hilt Module
│           │   │   ├── AppContext.kt                    # 应用上下文
│           │   │   ├── GlobalServices.kt                # 全局服务
│           │   │   └── ServicesEntryPoint.kt            # 服务入口
│           │   │
│           │   └── utils/            # 工具类
│           │       ├── DeviceIdHelper.kt                # 设备ID生成
│           │       ├── Logger.kt                        # 日志工具类
│           │       └── WebViewSpeaker.kt                # WebView TTS (OPPO 降级方案)
│           │
│           └── res/                  # 资源文件
├── GROUP_CHAT_WORKFLOW.md           # 群聊工作流文档
├── SPEC.md                          # 完整规格文档
└── README.md                        # 功能说明
```

---

## 三、架构约定

### 3.1 三层分离

```
presentation/  ──→  domain/  ←──  data/
(Compose+VM)      (纯Kotlin)    (网络/音频/持久化)
```

- `domain/` 绝对无 Android 依赖（纯 Kotlin）
- `presentation/` 只依赖 `domain/` 的接口（不直接 import `data/` 具体类）
- 违例处理：**业务逻辑必须放在 `domain/usecase/`**，不能留在 ViewModel

### 3.2 群聊核心架构 (v1.5.46+)

```
GroupChatViewModel  (UI状态+生命周期)
        │
        ▼
GroupChatController  (中心化状态机, 678行)
  ├─ state: GroupState {IDLE/CONNECTING/ACTIVE/TERMINATED/ERROR}
  ├─ mode: ChatMode {PINGPONG, RELAY}
  ├─ API: start / terminate / onUserVoice / onUserText / onBotEvent/onBotAudio
  └─ 3条路径: 正常 / ws=-1兜底 / 非预期bot完成重试
        │
        ▼
RelayEngine → SpeakerScorer → BotConnectionPool → XiaoZhiBackendService×N
```

**ForwardStrategy 决策表**：

| isLocal | 路径 | 行为 |
|---------|------|------|
| false（官方/远端） | remote-audio | 音频优先（Opus 重发），失败才兜底文字 |
| true（本地/自建） | local-json | 文本/JSON 路径，含 `speaker` 标识 |
| true & >150包 | local-long-audio | 超长走 JSON 避免缓冲区爆 |

### 3.3 MCP 工具架构

```
McpHelpers (基础设施: HTTP client, 缓存清理, 权限检查)
    │
    └── McpTools*.kt (8个文件, 57+工具)
            │
            └── MCPToolRegistry (注册所有工具)
                    │
                    └── XiaoZhiBackendService (响应服务器 mcp/tools/call)
```

**工具气泡方向**：AI 执行的动作（拍照/截图/生成二维码）→ `MessageDirection.RECEIVED`（AI 侧），扫码结果不走气泡。

### 3.4 音频管线

```
AudioRecord → OpusEncoder(JNI) → WebSocket → 服务器
                                                    
AudioTrack ← OpusDecoder(JNI)  ← WebSocket ← 服务器

VAD: FSMN VAD (FBank提取 → ONNX Runtime推理)
唤醒词: MfccExtractor → OpenWakeWord (NN模型)
```

### 3.5 协议层

```
OTA注册 (HTTP POST) → 获取token → WebSocket连接
  ├─ 控制消息: JSON (type/stt/tts/llm/mcp/abort/hello/listen)
  ├─ 音频数据: 二进制帧 (前置 1~4 字节协议头 + Opus payload)
  └─ 协议头剥离: BP3/BP2/1字节协议 自动检测
```

---

## 四、构建与运行

### 环境要求

- Android Studio Hedgehog+ (AGP 8.x)
- JDK 17
- Gradle 8.x (Wrapper 已配)
- CMake 3.22.1 (Opus JNI)
- NDK (arm64-v8a + armeabi-v7a)

### 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建 (minify + shrink)
./gradlew assembleRelease

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 无 build 直接安装
./gradlew installDebug
```

### 调试

```bash
# 过滤小智日志
adb logcat -s XiaoZhiAI XiaoZhiWS XiaoZhiVAD XiaoZhiAudio ChatVM GroupChatVM MCP

# 清除缓冲区后看启动日志
adb logcat -c && adb logcat -s XiaoZhiAI:* XiaoZhiWS:*

# WebSocket 连接状态
adb logcat -s XiaoZhiWS:* | grep -E "(连接中|已连接|失败|token)"

# 查看活动
adb shell dumpsys activity activities | grep xiaozhi

# 查看 WakeLock
adb shell dumpsys power | grep "WAKE_LOCK" | grep XiaoZhi
```

---

## 五、关键约定与陷阱

### 代码规范

| 约定 | 说明 |
|------|------|
| 命名 | `camelCase` Kotlin 风格，Repository 类名以 `Impl` 结尾 |
| 日志 | `AndroidLogger` 实例化传入 tag，不在日志字符串拼接敏感数据 |
| 协程 | ViewModel 用 `viewModelScope`，UseCase 构造函数传 `CoroutineScope` |
| 状态流 | `StateFlow` 做 UI 状态，`Channel` 做一次性事件 |
| 圆角 | 统一用 `AppShapes` (menu=12dp, image=12dp, bubble=12dp, dialog=16dp, iconBg=28dp) |
| 气泡 | 必须有 1dp 边框，边框比底色深/亮 ~10%，时间戳颜色按背景自适应 |

### 高频陷阱

1. **`derivedStateOf` 无 key 捕获 stale 引用** — `remember` 必须传 `messages.size` 等 key
2. **`synchronized` 块不能含 suspend** — JVM 锁线程绑定，suspend 换线程会死锁
3. **`when` 内 `continue` 跳过分支体内后续代码** — 用 `if/else if/else` 替代
4. **StateFlow 保留旧值** — 新录音前必须清空 `_textMessage`，或用 `Channel`
5. **`ws=N` 是 `waitingSpeakerIndex` 不是 WebSocket 状态** — 看真实连接用 `XiaoZhiWS` tag
6. **本地命令反馈泄漏到服务器** — `from_local=true` 的 listen detect 消息服务器可能不认
7. **Compose `detectTransformGestures` 在 `foundation.gestures` 不在 `ui.input.pointer`**
8. **WakeLock 必加超时兜底**：`acquire(30_000L)` + 循环续期
9. **CameraX ImageAnalysis 输出 YUV_420_888 不是 JPEG** — 扫码用 `PlanarYUVLuminanceSource`
10. **`okhttp3.RequestBody.create()` deprecated** — 用 `toRequestBody(mediaType)`

### 编译注意事项

- 国内网络：`settings.gradle` 已配阿里云镜像 `maven.aliyun.com/repository/public`
- 签名：debug 用 `debug.keystore`（`storePassword='android'`）
- 移除注释：QQ 音乐播放器代码（存在注释中但未使用）
- ProGuard 注意：keep Opus/ONNX JNI 类、MCP 工具类、`BuildConfig` 字段

---

## 六、版本演进

| 版本 | 要点 |
|------|------|
| v1.5.xx | 群聊架构持续迭代（RelayEngine → Controller 中心化） |
| v1.5.38 | Android 11+ visibility 修复 + open_app 包名映射 + 闹钟持久化 |
| v1.5.39 | WakeLock 30s 续期 + 唤醒词直接验证 |
| v1.5.40 | 拍照画质 JPEG_QUALITY 50→85 |
| v1.5.41 | markDead 死代码复活 (bot 连接池) |
| v1.5.42 | 群聊 3+ 人接龙修复 (grace 窗口 800→2000ms) |
| v1.5.45 | 群聊规格审查 (分流/终止API/SYSTEM消息/日志格式) |
| **v1.5.46** | **群聊完全重写** (-444行)：7文件→GroupChatController 中心化状态机 |
| v1.5.47 | STT 双重写入修复 + grace 窗口日志降频 + 本地命令反馈泄漏修复 |
| v1.5.49 | 当前版本，MCP 工具 57+ 个，含小布对标新工具 |
| — | Phase0 重构：提取 RelayEngine/DeduplicationUtil 等 usecase |

---

## 七、相关文档

- `SPEC.md` — 完整规格（协议、消息类型、对话流程）
- `GROUP_CHAT_WORKFLOW.md` — 群聊接龙工作流（含调试检查点）
- `README.md` — 功能说明（面向使用者）

---

*此文件供 AI 代理阅读，帮助理解项目结构和编码约定。修改代码前请先读此文件。*
