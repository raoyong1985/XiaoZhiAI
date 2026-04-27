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
1. 连接服务器 `ws://server:8080`
2. 发送 `{"type":"hello","device_id":"android"}` 
3. 接收 `{"type":"hello","session_id":"xxx"}`
4. 开始音频会话

**消息类型**:
- `hello` - 建立会话
- `start` - 开始录音
- `stop` - 停止录音  
- `audio` - 音频数据 (二进制帧)
- `stt` - 语音识别结果
- `tts` - TTS音频数据
- `tts_state` - TTS播放状态
- `abort` - 中断

**音频格式**:
- 采样率: 16000 Hz
- 格式: 16-bit PCM
- 编码: Opus (可选)
- 帧长: 60ms (960 samples)

## 3. 技术架构

### 3.1 架构类型
Clean Architecture + MVVM

### 3.2 技术栈
| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 |
| 状态管理 | ViewModel + StateFlow |
| 依赖注入 | Hilt |
| 网络 | OkHttp WebSocket |
| 音频 | AudioRecord + AudioTrack |
| 编码 | Opus (libopus) |

### 3.3 项目结构
```
com.xiaozhi.aiservice/
├── data/
│   ├── audio/           # 音频录制/播放
│   ├── remote/          # WebSocket通信
│   └── repository/      # 数据仓库
├── domain/
│   ├── model/           # 数据模型
│   └── repository/      # 仓库接口
├── presentation/
│   ├── ui/              # Compose界面
│   ├── viewmodel/       # 视图模型
│   └── theme/           # 主题样式
└── di/                  # Hilt模块
```

## 4. 功能列表

### 4.1 核心功能
| 功能 | 描述 | 优先级 |
|------|------|--------|
| 连接后台 | WebSocket连接小智服务器 | P0 |
| 语音录制 | 麦克风录音，PCM格式 | P0 |
| 音频发送 | 实时发送音频数据到后台 | P0 |
| 音频接收 | 接收并播放TTS音频 | P0 |
| 语音播放 | AudioTrack播放PCM音频 | P0 |
| 对话显示 | 聊天气泡显示对话内容 | P1 |
| 文字输入 | 可选文字模式输入 | P2 |

### 4.2 界面功能
| 界面 | 功能 |
|------|------|
| 主对话页 | 语音对讲按钮、对话列表 |
| 设置页 | 服务器地址、音频参数 |

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
- **按住说话**: 长按底部按钮录音
- **松开发送**: 松开按钮发送录音
- **自动播放**: 收到AI回复自动播放

## 6. 数据流

### 6.1 语音发送流程
```
麦克风 → AudioRecord → PCM Buffer → WebSocket.send(binary)
```

### 6.2 语音接收流程  
```
WebSocket.onMessage(binary) → AudioTrack.play() → 扬声器
```

### 6.3 状态管理
- `AiState.IDLE` - 空闲
- `AiState.LISTENING` - 正在录音
- `AiState.SPEAKING` - 正在播放AI回复
- `AiState.PROCESSING` - 处理中

## 7. 后续扩展

- 视频通话界面（预留）
- 多设备管理
- 对话历史
