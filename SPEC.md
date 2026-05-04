# XiaoZhiAI - 智能语音助手 Android 应用规格文档

## 1. 项目概述

**项目名称**: XiaoZhiAI
**项目类型**: Android 原生应用 (Kotlin + Jetpack Compose)
**核心功能**: 让安卓手机像 ESP32 设备一样连接小智后台，实现语音对话功能
**目标用户**: 希望使用手机而非 ESP32 设备与小智 AI 对话的用户

## 2. 核心需求

### 2.1 功能定位
- **替代 ESP32 设备**：安卓手机模拟 ESP32 的行为模式
- **连接小智后台**：通过 WebSocket 与后台服务器通信
- **语音对话**：麦克风录音 → Opus 编码 → 发送到后台 → 接收 Opus 音频 → 解码播放
- **文字对话**：可选的文字输入/输出模式
- **AUTO 模式**：AI 回复后自动重启监听，实现连续对话
- **REALTIME 模式**：实时模式（默认）
- **MCP 协议**：支持 Model Context Protocol 工具调用（20+ 工具）
- **群聊接龙**：支持多个智能体依次对话转发
- **多智能体**：支持添加多个智能体联系人，独立连接

### 2.2 对话流程（模拟 ESP32）

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   Android   │────▶│  小智后台    │────▶│  AI 服务    │
│   手机端    │◀────│  服务器      │◀────│  (ASR/TTS)  │
└─────────────┘     └──────────────┘     └─────────────┘
       │                    │                    │
   麦克风录音           WebSocket           音频流
   Opus 编码           JSON+二进制          处理
   AudioTrack 播放      协议头剥离
```

### 2.3 协议对接（参考 xiaozhi-esp32）

**连接流程**:
1. **OTA 注册（HTTP POST）**：向 OTA 地址注册设备信息，获取 WebSocket token
   ```
   POST https://api.tenclass.net/xiaozhi/ota/
   Headers: Device-Id, Client-Id
   Body: {"version":2,"language":"zh","mac_address":"XX:XX:XX:XX:XX:XX","uuid":"xxx","chip_model_name":"android","application":{"name":"xiaozhi-android","version":"1.0.0"}}
   ```
2. **建立 WebSocket 连接**（携带 Headers）
   ```
   Headers:
     Protocol-Version: 1
     Device-Id: XX:XX:XX:XX:XX:XX
     Client-Id: <UUID>
     Authorization: Bearer <ws_token>  (OTA 注册返回)
   ```
3. **发送 hello 握手**：
   ```json
   {"type":"hello","version":1,"transport":"websocket","features":{"mcp":true},"audio_params":{"format":"opus","sample_rate":16000,"channels":1,"frame_duration":60}}
   ```
4. **接收 hello 响应**：
   ```json
   {"type":"hello","session_id":"xxx","audio_params":{"sample_rate":24000,"channels":1}}
   ```
5. 开始音频会话

**消息类型**:
| 类型 | 方向 | 说明 |
|------|------|------|
| `hello` | 双向 | 建立会话（含 `audio_params` 协商） |
| `listen` | 发送 | 控制监听状态 `state: start/stop/detect`，`mode: auto/manual/realtime` |
| `stt` | 接收 | 语音识别结果（用户说的话） |
| `tts` | 接收 | TTS 状态 (`state: start/stop/sentence_start/sentence_end`)，含文本和可选的 base64 音频 |
| `llm` | 接收 | 情感状态 (`emotion`) |
| `mcp` | 双向 | MCP 工具调用请求/响应 (`initialize`, `tools/list`, `tools/call`) |
| `abort` | 双向 | 中断当前会话 |
| `alert` | 接收 | 服务器弹窗通知 |
| `activation` | 双向 | 设备激活（服务器下发 challenge，客户端回复） |

**二进制帧协议**:
Opus 音频数据通过二进制 WebSocket 帧传输，自动检测并剥离协议头：
- **BinaryProtocol3** (ESP32 风格): `[type(1)][reserved(1)][payload_size(2 大端)][payload]`
- **小智 AI 1 字节协议**: `[type(1)][payload]`
- 剥离后得到纯 Opus payload

**音频参数**:
- 录音采样率: 16000 Hz
- 服务器采样率: 24000 Hz（通过 hello 协商）
- 客户端输出采样率: 16000 Hz（重采样）
- 格式: 16-bit PCM
- 编码: Opus（编码用 Android MediaCodec，解码用 native libopus JNI）
- 帧长: 60ms（服务器 24kHz 下 1440 samples）
- 通道: 单声道

### 2.4 对话模式

**MANUAL 模式**:
- 用户按住按钮录音，松开发送
- 适合需手动控制的场景

**AUTO 模式**:
- 用户说话后 VAD 自动检测停止
- AI 回复完成后自动重启监听
- 实现连续对话，无需手动操作
- 状态流转: `IDLE → LISTENING → SPEAKING → IDLE → LISTENING...`

**REALTIME 模式**（默认）:
- 点击开始录音，再次点击停止
- VAD 自动检测静音停止录音
- 适合需要手动控制的连续对话

## 3. 技术架构

### 3.1 架构类型
Clean Architecture + MVVM

### 3.2 技术栈
| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 |
| 状态管理 | ViewModel + StateFlow + SharedFlow |
| 依赖注入 | 手动 DI (AppContainer.kt) |
| 网络 | OkHttp WebSocket + HTTP |
| 录音 | AudioRecord (VOICE_RECOGNITION, 降级 MIC) |
| Opus 编码 | Android MediaCodec (MIMETYPE_AUDIO_OPUS) |
| Opus 解码 | Native libopus via JNI (CMake + NDK) |
| 音频播放 | AudioTrack (STREAM_MUSIC) |
| 数据存储 | DataStore Preferences (Gson 序列化) |
| 日志 | 条件日志 (BuildConfig.ENABLE_DEBUG_LOG) |
| 定位 | FusedLocationProviderClient (Google Play Services) |
| JSON | Gson |

### 3.3 项目结构
```
com.xiaozhi.aiservice/
├── XiaoZhiApplication.kt           # Application 入口
├── di/
│   └── AppContainer.kt             # 手动依赖注入容器
├── data/
│   ├── audio/
│   │   ├── AudioService.kt         # 音频录制/VAD 静音检测/音频焦点
│   │   ├── OpusEncoder.kt          # Opus 编码器 (MediaCodec)
│   │   └── NativeOpusDecoder.kt    # Opus 解码器 (native libopus + AudioTrack)
│   ├── remote/
│   │   ├── XiaoZhiBackendService.kt # WebSocket 通信 + OTA 注册
│   │   └── MCPTool.kt             # MCP 协议工具定义和注册（20+ 工具）
│   └── repository/
│       ├── ConnectionRepositoryImpl.kt  # 连接与状态管理（状态机）
│       ├── MessageRepositoryImpl.kt     # 消息持久化
│       ├── ContactsRepositoryImpl.kt    # 联系人持久化
│       ├── GroupChatRepositoryImpl.kt   # 群聊持久化
│       └── DeviceRepositoryImpl.kt      # 设备管理（预留）
├── domain/
│   ├── model/
│   │   ├── Models.kt              # 数据模型 (AiState, ChatMessage, BotContact 等)
│   │   └── BotContact.kt          # BotContact 领域模型
│   └── repository/
│       ├── Repositories.kt        # 仓库接口定义（连接/消息/设备/联系人）
│       └── GroupChatRepository.kt # 群聊仓库接口
├── presentation/
│   ├── ui/
│   │   ├── MainActivity.kt        # 主 Activity + 权限申请 + 导航
│   │   ├── chat/
│   │   │   └── ChatScreen.kt      # 聊天界面（气泡/录音/文字输入）
│   │   └── contacts/
│   │       ├── ContactsScreen.kt  # 联系人列表/添加/编辑
│   │       ├── GroupChatScreen.kt # 群聊对话界面
│   │       ├── GroupChatListScreen.kt  # 群聊列表
│   │       └── CreateGroupScreen.kt    # 创建群聊
│   ├── viewmodel/
│   │   ├── ChatViewModel.kt       # 单聊 ViewModel
│   │   ├── ContactsViewModel.kt   # 联系人 ViewModel
│   │   └── GroupChatViewModel.kt  # 群聊 ViewModel（多 bot 接龙）
│   └── theme/
│       ├── Theme.kt               # 主题配色
│       └── Type.kt                # 字体样式
└── utils/
    ├── Logger.kt                  # 条件日志包装
    └── DeviceIdHelper.kt          # 设备 ID 生成 (SHA256)
```

### 3.4 项目结构补充说明
- `AppContainer` 管理多个 `XiaoZhiBackendService` 实例（每个联系人/群聊独立连接）
- 群聊功能通过 `GroupChatViewModel` 实现多智能体接龙（音频转发 + 文字转发）
- `DeviceRepositoryImpl` 为预留实现，移动端不需扫描设备

## 4. 功能列表

### 4.1 核心功能
| 功能 | 描述 | 优先级 | 状态 |
|------|------|--------|------|
| OTA 注册 | HTTP POST 注册设备，获取 WebSocket token | P0 | ✅ |
| 连接后台 | WebSocket 连接小智服务器（带认证 Headers） | P0 | ✅ |
| 语音录制 | 麦克风录音，VOICE_RECOGNITION 音源，16kHz PCM | P0 | ✅ |
| VAD 静音检测 | RMS 能量检测，自动停止录音（阈值 700，超时 3s） | P0 | ✅ |
| Opus 编码 | MediaCodec 将 PCM 编码为 Opus（32kbps） | P0 | ✅ |
| 音频发送 | 实时发送 Opus 音频数据到后台 | P0 | ✅ |
| 音频接收 | 接收 Opus 音频并解码播放 | P0 | ✅ |
| 协议头剥离 | 自动检测并剥离 BinaryProtocol3 / 1 字节协议头 | P0 | ✅ |
| 音频重采样 | 服务器 24kHz → 客户端 16kHz 线性插值 | P0 | ✅ |
| 音频限幅器 | 软限幅防止爆音 | P0 | ✅ |
| 语音播放 | AudioTrack（STREAM_MUSIC）+ 预热 3 帧防卡顿 | P0 | ✅ |
| 字节缓冲池 | 复用 ByteArray 减少 GC 压力 | P1 | ✅ |
| 音频焦点 | 播放时请求 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK | P1 | ✅ |
| 对话显示 | 聊天气泡显示对话内容 | P1 | ✅ |
| 文字输入 | 可选文字模式输入 | P2 | ✅ |
| AUTO 模式 | 连续对话，自动重启监听 | P0 | ✅ |
| REALTIME 模式 | 实时模式（默认语音模式） | P0 | ✅ |
| MANUAL 模式 | 手动模式 | P0 | ✅ |
| MCP 协议 | 20+ 工具调用支持 | P1 | ✅ |
| 条件日志 | Release 版本不输出调试日志 | P1 | ✅ |
| 设备激活 | 支持服务器 challenge-response 激活 | P1 | ✅ |
| 自动重连 | 指数退避重连（1s~2min），无限重试 | P1 | ✅ |
| 多联系人 | 添加/编辑/删除多个智能体联系人 | P1 | ✅ |
| 群聊接龙 | 多智能体音频/文字自动转发对话 | P2 | ✅ |
| 消息持久化 | DataStore 持久化聊天记录 | P2 | ✅ |
| 聊天记录导出 | 分享聊天记录为文本 | P2 | ✅ |
| 服务器 Alert | 显示服务器弹窗通知 | P1 | ✅ |
| AI 情感状态 | 显示 AI 情感 (happy/sad/angry 等) | P2 | ✅ |

### 4.2 界面功能
| 界面 | 功能 |
|------|------|
| 联系人页 | 智能体列表、添加/编辑/删除联系人、切换语音/文字模式、清空/导出聊天记录 |
| 主对话页 | 语音对讲按钮、对话列表、文字输入、连接状态指示、AI 状态动画、Alert 弹窗 |
| 群聊列表页 | 群聊列表、创建群聊、删除群聊、修改群聊名称 |
| 群聊对话页 | 多智能体接龙对话、文字输入、语音输入 |
| 创建群聊页 | 选择多个智能体组成群聊、设置群聊名称 |

### 4.3 MCP 工具列表
| 工具 | 描述 |
|------|------|
| `get_device_info` | 获取设备基本信息 (厂商、型号、系统版本等) |
| `get_current_time` | 获取当前系统时间（支持自定义格式） |
| `get_battery_level` | 获取电池电量百分比 |
| `calculate` | 四则运算表达式求值 |
| `get_weather` | 获取指定城市或当前位置天气 (Open-Meteo API) |
| `get_network_info` | 获取网络状态 (WiFi/移动网络、SSID、信号强度、IP) |
| `set_alarm` | 设置闹钟提醒 |
| `get_location` | 获取设备当前位置（经纬度，需定位权限） |
| `send_notification` | 发送通知栏提醒 |
| `get_calendar` | 获取日历事件列表（需日历读取权限） |
| `web_search` | 网页搜索 (DuckDuckGo HTML 解析) |
| `open_app` | 通过包名打开应用 |
| `open_url` | 在浏览器中打开链接 |
| `set_volume` | 调节媒体/闹钟/通知/通话音量 |
| `set_timer` | 设置倒计时器 |
| `get_memory_info` | 获取内存和存储空间使用情况 |
| `vibrate` | 手机震动（支持多种模式） |
| `screen_brightness` | 获取/设置屏幕亮度 |
| `get_public_ip` | 获取公网 IP 和所在地 |
| `get_news` | 获取新闻头条（支持分类和国家） |
| `random_number` | 生成随机整数或浮点数 |
| `encode_decode` | Base64/URL 编码解码 |
| `get_orientation` | 获取屏幕方向和旋转角度 |
| `get_installed_apps` | 列出已安装应用（支持关键词搜索） |
| `dict_lookup` | 中英文词典查询（FreeDictionary API + 有道词典） |
| `joke` | 随机中/英文笑话 |

## 5. UI 设计

### 5.1 整体风格
- 微信对讲风格
- 多页面设计（联系人列表 + 对话页 + 群聊页）
- 底部按钮控制录音/文字输入

### 5.2 颜色方案
- 主色: Material3 动态主题色
- 背景: MaterialTheme.colorScheme.background
- 录制中: #F44336 (红色)

### 5.3 核心交互
- **点击录音**: 点击底部麦克风按钮录音（默认 REALTIME 模式）
- **再次点击停止**: 停止录音并发送
- **VAD 自动停止**: AUTO 模式下 VAD 检测到静音自动停止
- **自动监听**: AUTO 模式下 AI 回复完成自动重启录音
- **自动播放**: 收到 AI 回复自动播放
- **中断播放**: 点击停止按钮中断 TTS 播放并发送 abort
- **文字模式**: 切换为文字输入模式，发送文本消息（state=detect）

## 6. 数据流

### 6.1 语音发送流程
```
麦克风 → AudioRecord(VOICE_RECOGNITION, 16kHz) → PCM Buffer
  → ByteArray Pool → OpusEncoder(MediaCodec) → WebSocket.send(binary)
```

### 6.2 语音发送流程（AUTO 模式）
```
VAD 检测(RMS > 700, 持续 3s 静音) → AudioRecord → PCM → OpusEncoder → WebSocket.send(binary)
AI 回复完成(SPEAKING → IDLE) → 自动触发 startListening() → 复用/新建编码器 → 重新开始录音
```

### 6.3 语音接收流程
```
WebSocket.onMessage(binary) → stripProtocolHeader() → NativeOpusDecoder
  → 24kHz PCM → 重采样(线性插值, 24kHz→16kHz) → 软限幅器(AGC)
  → AudioTrack.play(STREAM_MUSIC, 16kHz mono) → 扬声器
```

### 6.4 VAD 静音检测参数
| 参数 | 值 | 说明 |
|------|-----|------|
| SILENCE_RMS_THRESHOLD | 700 | 静音 RMS 能量阈值 |
| SILENCE_TIMEOUT_MS | 3000ms | 静音超时自动停止 |
| SILENCE_CONSECUTIVE_BUFFERS | 2 | 连续静音缓冲帧数 |
| SPEECH_CONSECUTIVE_BUFFERS | 2 | 连续语音缓冲帧数 |
| MIN_SPEECH_DURATION_MS | 300ms | 最短语音持续时间 |

### 6.5 状态管理
- `AiState.IDLE` - 空闲，等待用户操作
- `AiState.LISTENING` - 正在录音 (MANUAL: 点击按钮, AUTO: VAD 检测)
- `AiState.SPEAKING` - AI 正在说话，播放 TTS 音频
- `AiState.PROCESSING` - 处理中 (ASR/TTS 处理)

### 6.6 状态机关键逻辑
```
// TTS 开始时，如果在 LISTENING 状态（AUTO 模式）
if (newState == SPEAKING && oldState == LISTENING && isAutoMode) {
    stopRecording()  // 停止录音，防止回声
    stopEncoder()
    // 不发送 abort，服务器已知状态
}

// AI 开始说话时，如果我们在录音（手动模式）
if (newState == SPEAKING && weStartedRecording && !autoModeCase) {
    sendAbort("playback_start")  // 发送 abort
    stopRecording()
}

// AI 说完后，如果是 AUTO 模式则立即重启监听
if (newState == IDLE && oldState == SPEAKING && isAutoMode) {
    startListening()  // 复用或新建编码器，无需延迟
}
```

### 6.7 状态机互斥锁
- 使用 `stateMutex`（Kotlin Mutex）防止 VAD 回调与 WebSocket 消息同时修改状态
- 所有 `startListening()` 和 `stopListening()` 操作在锁内原子执行

## 7. 构建配置

### 7.1 版本信息
- 当前版本: 1.0.55
- versionCode: 22
- 最低 SDK: 26 (Android 8.0)
- 目标 SDK: 34 (Android 14)
- 编译 SDK: 34
- Kotlin: 1.9.20
- Compose BOM: 2023.10.01
- AGP: 8.2.0

### 7.2 关键配置
- `usesCleartextTraffic="false"` - 禁用明文流量
- ProGuard 规则: 保留 JNI、DataStore、协程等相关类
- NDK: CMake 3.22.1, C++17, `c++_shared`, 支持 arm64-v8a 和 armeabi-v7a
- BuildConfig: `ENABLE_DEBUG_LOG` 条件日志（release=false, debug=true）

### 7.3 运行时权限
| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 录音（必需） |
| `INTERNET` | 网络连接 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `POST_NOTIFICATIONS` | 通知推送 (Android 13+) |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | 蓝牙设备发现 (Android 12+) |
| `ACCESS_FINE_LOCATION` | 定位服务（天气/位置工具需要） |
| `READ_CALENDAR` | 日历读取（日历工具需要） |

### 7.4 依赖库
- OkHttp 4.12.0（WebSocket + HTTP 客户端）
- Gson 2.10.1（JSON 解析）
- Google Play Services Location 21.1.0（FusedLocationProvider）
- DataStore Preferences 1.0.0（持久化存储）
- Coil 2.5.0（图片加载）
- Coroutines 1.7.3（异步）

## 8. 已知问题与修复

### 8.1 AUTO 模式头字丢失问题
**问题**: AI 回复后重启监听时，说话头一两个字丢失
**原因**: OpusEncoder 初始化延迟 (100-300ms)、AudioRecord 预热时间
**修复**:
- AI 说完后立即重启监听（去掉 1 秒延迟）
- 复用已有的 OpusEncoder 实例（避免重新初始化）
- 优化状态机，确保录音在 TTS 开始前停止
- 状态更新顺序: 保存 oldState → 更新 State → 执行动作

### 8.2 回声问题
**问题**: AI 播放声音被麦克风采集发往 STT
**修复**: TTS 开始时 (`LISTENING→SPEAKING`) 立即停止录音和编码器

### 8.3 音频卡顿问题
**问题**: 网络抖动下音频播放卡顿
**修复**: AudioTrack 使用 16 倍 minBuffer（约 960ms），预热 3 帧静音

## 9. 后续扩展

- 视频通话界面（预留）
- MCP 工具扩展（智能家居、日历创建等）
- 语音唤醒 (Wake Word Detection)
- 设置页面（当前为预留，服务器地址等在联系人编辑中配置）
- DeviceRepositoryImpl 设备扫描功能（当前为空实现）
