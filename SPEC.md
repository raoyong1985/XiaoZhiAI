# XiaoZhiAI - 智能语音助手 Android 应用规格文档

## 1. 项目概述

**项目名称**: XiaoZhiAI
**项目类型**: Android 原生应用 (Kotlin)
**核心功能**: 让安卓手机像ESP32设备一样连接小智后台，实现语音对话功能
**目标用户**: 希望使用手机而非ESP32设备与小智AI对话的用户

## 2. 核心需求

### 2.1 功能定位
- **替代ESP32设备**：安卓手机模拟ESP32的行为模式
- **连接小智后台**：通过WebSocket与后台服务器通信
- **语音对话**：麦克风录音 → 发送到后台 → 接收并播放回复音频
- **文字对话**：可选的文字输入/输出模式
- **AUTO模式**：AI回复后自动重启监听，实现连续对话
- **MCP协议**：支持Model Context Protocol工具调用

### 2.2 对话流程（模拟ESP32）

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   Android   │────▶│  小智后台    │────▶│  AI服务     │
│   手机端    │◀────│  服务器      │◀────│  (ASR/TTS)  │
└─────────────┘     └──────────────┘     └─────────────┘
       │                    │                    │
   麦克风录音           WebSocket           音频流
   音频播放            JSON+二进制          处理
```

### 2.3 协议对接（参考xiaozhi-esp32）

**WebSocket连接流程**:
1. 连接服务器 `wss://api.tenclass.net/xiaozhi/v1/`
2. 发送 `{"type":"hello","device_id":"android","client_id":"xxx","protocol_version":"1"}` 
3. 接收 `{"type":"hello","session_id":"xxx","features":["tts","stt","mcp"]}`
4. 开始音频会话

**消息类型**:
- `hello` - 建立会话
- `start` - 开始录音
- `stop` - 停止录音
- `audio` - 音频数据 (二进制帧，Opus编码)
- `stt` - 语音识别结果
- `llm` - LLM文本回复
- `tts` - TTS音频数据
- `tts_state` - TTS播放状态 (`start`/`stop`/`sentence_start`)
- `abort` - 中断当前会话
- `mcp` - MCP工具调用请求/响应

**音频格式**:
- 采样率: 16000 Hz
- 格式: 16-bit PCM
- 编码: Opus (libopus native)
- 帧长: 60ms (960 samples)
- 通道: 单声道

### 2.4 对话模式

**MANUAL模式**:
- 用户按住按钮录音，松开发送
- 适合需手动控制的场景

**AUTO模式**:
- 用户说话后VAD自动检测停止
- AI回复完成后自动重启监听
- 实现连续对话，无需手动操作
- 状态流转: `IDLE → LISTENING → SPEAKING → IDLE → LISTENING...`

## 3. 技术架构

### 3.1 架构类型
Clean Architecture + MVVM

### 3.2 技术栈
| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 |
| 状态管理 | ViewModel + StateFlow |
| 依赖注入 | 手动DI (AppContainer.kt) |
| 网络 | OkHttp WebSocket |
| 音频 | AudioRecord + AudioTrack |
| 编码 | Opus (native libopus via JNI) |
| 数据存储 | DataStore Preferences |
| 日志 | 条件日志 (BuildConfig.ENABLE_DEBUG_LOG) |

### 3.3 项目结构
```
com.xiaozhi.aiservice/
├── di/
│   └── AppContainer.kt      # 手动依赖注入容器
├── data/
│   ├── audio/
│   │   ├── AudioService.kt  # 音频录制/播放服务
│   │   ├── OpusEncoder.kt   # Opus编码器 (MediaCodec)
│   │   └── NativeOpusDecoder.kt # Opus解码器 (native libopus)
│   ├── remote/
│   │   ├── XiaoZhiBackendService.kt # WebSocket通信
│   │   └── MCPTool.kt      # MCP协议工具定义
│   └── repository/
│       ├── ConnectionRepositoryImpl.kt # 连接与状态管理
│       └── MessageRepositoryImpl.kt   # 消息仓库
├── domain/
│   ├── model/
│   │   ├── Models.kt        # 数据模型 (AiState, ChatMode等)
│   │   └── BotContact.kt    # 智能体联系人
│   └── repository/          # 仓库接口
├── presentation/
│   ├── ui/
│   │   ├── chat/            # 聊天界面
│   │   ├── contacts/        # 联系人/智能体列表
│   │   └── MainActivity.kt  # 主Activity
│   ├── viewmodel/
│   │   └── ChatViewModel.kt # 视图模型
│   └── theme/               # 主题样式
└── utils/                   # 工具类
```

## 4. 功能列表

### 4.1 核心功能
| 功能 | 描述 | 优先级 | 状态 |
|------|------|--------|------|
| 连接后台 | WebSocket连接小智服务器 | P0 | ✅ |
| 语音录制 | 麦克风录音，PCM格式 | P0 | ✅ |
| 音频发送 | 实时发送Opus音频数据到后台 | P0 | ✅ |
| 音频接收 | 接收并播放TTS音频 | P0 | ✅ |
| 语音播放 | AudioTrack播放PCM音频 | P0 | ✅ |
| 对话显示 | 聊天气泡显示对话内容 | P1 | ✅ |
| 文字输入 | 可选文字模式输入 | P2 | ✅ |
| AUTO模式 | 连续对话，自动重启监听 | P0 | ✅ |
| MCP协议 | 工具调用支持 | P1 | ✅ |
| 条件日志 | Release版本不输出调试日志 | P1 | ✅ |

### 4.2 界面功能
| 界面 | 功能 |
|------|------|
| 主对话页 | 语音对讲按钮、对话列表、文字输入 |
| 联系人页 | 智能体列表、选择对话对象 |
| 设置页 | 服务器地址、音频参数（预留） |

### 4.3 MCP工具列表
| 工具 | 描述 |
|------|------|
| `get_device_info` | 获取设备信息 (型号、系统版本等) |
| `get_current_time` | 获取当前时间 |
| `get_battery_level` | 获取电池电量 |
| `calculate` | 简单数学计算 |

## 5. UI设计

### 5.1 整体风格
- 微信对讲风格
- 单页面设计
- 底部大按钮控制

### 5.2 颜色方案
- 主色: #07C160 (微信绿)
- 背景: #EDEDED
- 气泡(我): #95EC69
- 气泡(AI): #FFFFFF

### 5.3 核心交互
- **按住说话**: 长按底部按钮录音 (MANUAL模式)
- **松开发送**: 松开按钮发送录音
- **自动监听**: AUTO模式下自动重启录音
- **自动播放**: 收到AI回复自动播放
- **中断播放**: 点击停止按钮中断TTS播放

## 6. 数据流

### 6.1 语音发送流程 (MANUAL模式)
```
麦克风 → AudioRecord → PCM Buffer → OpusEncoder → WebSocket.send(binary)
```

### 6.2 语音发送流程 (AUTO模式)
```
VAD检测 → AudioRecord → PCM Buffer → OpusEncoder → WebSocket.send(binary)
AI回复完成 → 自动触发 startListening() → 重新开始录音
```

### 6.3 语音接收流程  
```
WebSocket.onMessage(binary) → NativeOpusDecoder → PCM → AudioTrack.play() → 扬声器
```

### 6.4 状态管理
- `AiState.IDLE` - 空闲，等待用户操作
- `AiState.LISTENING` - 正在录音 (MANUAL: 按住按钮, AUTO: VAD检测)
- `AiState.SPEAKING` - AI正在说话，播放TTS音频
- `AiState.PROCESSING` - 处理中 (ASR/TTS处理)

### 6.5 AUTO模式状态机关键逻辑
```kotlin
// TTS开始时，如果在LISTENING状态 (AUTO模式)
if (newState == SPEAKING && oldState == LISTENING && isAutoMode) {
    stopRecording()  // 停止录音，防止回声
    // 不发送abort，服务器已知状态
}

// AI说完后，如果是AUTO模式则立即重启监听
if (newState == IDLE && isAutoMode) {
    startListening()  // 立即重启，无需延迟
}
```

## 7. 构建配置

### 7.1 版本信息
- 当前版本: 1.0.32
- versionCode: 5
- 最低SDK: 26 (Android 8.0)
- 目标SDK: 34 (Android 14)

### 7.2 关键配置
- `usesCleartextTraffic="false"` - 禁用明文流量
- ProGuard规则: 保留JNI、DataStore、协程等相关类
- NDK: 支持 arm64-v8a 和 armeabi-v7a
- BuildConfig: 启用 `ENABLE_DEBUG_LOG` 条件日志

## 8. 已知问题与修复

### 8.1 AUTO模式头字丢失问题
**问题**: AI回复后重启监听时，说话头一两个字丢失
**原因**: OpusEncoder初始化延迟 (100-300ms)、AudioRecord预热时间
**修复**: 
- AI说完后立即重启监听 (去掉1秒延迟)
- 优化状态机，确保录音在TTS开始前停止
- 状态更新顺序: 保存oldState → 更新State → 执行动作

### 8.2 回声问题
**问题**: AI播放声音被麦克风采集发往STT
**修复**: TTS开始时 (`LISTENING→SPEAKING`) 立即停止录音和编码器

## 9. 后续扩展

- 视频通话界面（预留）
- 对话历史持久化 (DataStore已实现)
- MCP工具扩展 (天气、日历、智能家居等)
- 语音唤醒 (Wake Word Detection)
