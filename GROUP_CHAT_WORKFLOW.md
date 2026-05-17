# 安卓小智群聊工作流文档

> 版本: v1.0.91 | 文件: GroupChatViewModel.kt (583行)

---

## 一、整体架构

```
┌─────────────────────────────────────────────────────┐
│                  GroupChatViewModel                  │
│                                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
│  │  botA连接    │  │  botB连接    │  │  botC连接    │  │
│  │ (WebSocket)  │  │ (WebSocket)  │  │ (WebSocket)  │  │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  │
│         │                │                │          │
│         ▼                ▼                ▼          │
│  ┌─────────────────────────────────────────────────┐ │
│  │         事件通道 (Channel<BotEvent>)             │ │
│  │  TtsText / Message / AiState                    │ │
│  └─────────────────────────────────────────────────┘ │
│         │                │                │          │
│         ▼                ▼                ▼          │
│  ┌─────────────────────────────────────────────────┐ │
│  │          共享解码器 (NativeOpusDecoder)           │ │
│  │          所有bot共用1个AudioTrack                │ │
│  └─────────────────────────────────────────────────┘ │
│                                                     │
│  接龙状态: botIds, currentBotIndex, forwardRound     │
│  currentSpeakerBotId(防插话), audioForwardBuffer    │
└─────────────────────────────────────────────────────┘
```

## 二、核心数据结构

| 字段 | 类型 | 用途 |
|------|------|------|
| `botConnections` | `ConcurrentHashMap<String, XiaoZhiBackendService>` | 每个bot一条WebSocket连接 |
| `sharedDecoder` | `NativeOpusDecoder?` | **所有bot共用的1个解码器+AudioTrack** |
| `botIds` | `List<String>` | 当前群聊的bot顺序列表 |
| `currentBotIndex` | `Int` | 当前说话的bot索引 |
| `currentSpeakerBotId` | `String` | 当前音频通道持有者botId（防插话） |
| `isRelayActive` | `Boolean` | 是否在接龙链中 |
| `audioForwardBuffer` | `MutableList<ByteArray>` | 当前bot的Opus音频缓存，用于转发 |
| `bufferedBotId` | `String` | audioForwardBuffer里是谁的音频 |

## 三、完整流程

### 3.1 连接群聊 (`connectGroup`)

```
connectGroup(groupId)
  │
  ├─ 清理旧状态: disconnect所有bot、取消observeJob、releaseDecoder
  │
  ├─ 对每个botId:
  │    ├─ 创建 XiaoZhiBackendService (WebSocket连接)
  │    ├─ 调用 observeBot(botId, groupId, name, service)
  │    │     ├─ 监听 ttsText → 事件通道
  │    │     ├─ 监听 chatMessages → 事件通道
  │    │     ├─ 监听 aiState → 事件通道
  │    │     └─ 监听 audioData → 解码播放/缓存
  │    └─ service.connect() → WebSocket连接服务器
  │
  └─ botIds = g.botIds  ← 异步完成
```

### 3.2 语音输入 (`startVoiceInput`)

```
用户点击语音按钮
  │
  ├─ 前置检查: _isRecording || botIds.isEmpty → return
  ├─ 向所有bot发 sendAbort("user_interrupt")
  ├─ releaseDecoder() → 清理解码器
  ├─ resetState() → 重置接龙状态
  ├─ isRelayActive = true  ← 启动接龙链
  ├─ firstBot.sendListenStart("auto")
  │
  ├─ OpusEncoder初始化 → 录制PCM → 编码Opus → 发送到firstBot的WebSocket
  │
  ├─ VAD检测静音 → 自动触发 stopInput()
  └─ 8秒超时 → 自动触发 stopInput()
```

### 3.3 第一个bot发言

```
用户说完 → VAD → stopInput()
  │
  ├─ 停止录音、放弃音频焦点
  │
  ├─ firstBot收到用户语音 → 服务器处理 → 返回tts相关消息
  │     │
  │     ├─ ttsText 流: "今天天气真好..."
  │     │     ↓
  │     │  事件通道 → 拼接 replyBuf
  │     │
  │     ├─ aiState: "speaking"
  │     │     ↓
  │     │  事件通道 → hasSpoken=true
  │     │            → currentSpeakerBotId = firstBot.botId  ← 锁定音频通道
  │     │            → 预创建共享解码器（如果不存在）
  │     │            → 停止录音、取消STT收集器
  │     │
  │     └─ audioData: 二进制Opus音频包
  │           ↓
  │        音频处理器:
  │          ① 检查 currentSpeakerBotId → 当前bot? → 是 → 解码播放
  │          ② 共享解码器.decodePacket(data) → 解码Opus→PCM
  │          ③ AudioTrack写入 → 扬声器出声
  │          ④ audioForwardBuffer.add(data)  ← 缓存起来准备转发
  │
  ├─ ttsText 继续拼接...
  │
  └─ aiState: "idle"
        ↓
     事件通道 → hasSpoken=false
               → currentSpeakerBotId 不清（等下一个bot覆盖）
               → 检查结束语（再见/拜拜等）→ 是则停止接龙
               → triggerNext(name, reply)  ← 转发给下一个bot
```

### 3.4 转发到下一个bot (`triggerNext`)

```
triggerNext(prevName, reply)
  │
  ├─ 前置检查: botIds<=1 || isRecording → 停止接龙
  ├─ 检查结束语 → 停止接龙
  │
  ├─ pickCandidates(prevIdx, reply)
  │     └─ 随机选一个不是自己的bot（排除prevIdx）
  │
  ├─ 对每个候选:
  │     │
  │     ├─ 音频转发 (forwardAudio)
  │     │     ├─ svc.sendListenStart("auto") → 让bot进入监听模式
  │     │     ├─ 逐包发送缓存的Opus音频（前10包间隔60ms，之后每20包间隔10ms）
  │     │     ├─ delay(500) → 等音频发送完
  │     │     └─ svc.sendListenStopOnly()
  │     │
  │     ├─ 等待10s确认bot开始说话
  │     │     └─ svc.aiState.first { it == "speaking" }
  │     │           ↓
  │     │        成功:
  │     │          ├─ audioForwardBuffer.clear() ← 清缓存
  │     │          ├─ forwardRound++
  │     │          └─ 返回（接龙链继续）
  │     │
  │     └─ 音频转发超时 → 文字兜底:
  │           ├─ svc.sendTextContent(text) (取原问题前8字)
  │           └─ 等待10s确认bot开始说话
  │
  └─ 所有候选失败 → 接龙结束
```

### 3.5 接龙继续

```
下一个bot (botB) 发言
  │
  ├─ aiState: "speaking"
  │     ↓
  │   currentSpeakerBotId = botB.botId  ← 覆盖上一个bot的锁
  │   共享解码器已存在 → 复用
  │
  ├─ audioData 到达
  │     ↓
  │   botId == currentSpeakerBotId → 解码播放
  │   audioForwardBuffer.add(data) → 缓存
  │
  ├─ aiState: "idle"
  │     ↓
  │   triggerNext → 转发给botC...
  │
  └─ 如此循环，直到:
       ├─ 检测到结束语 → 停止
       ├─ 死循环检测触发 → 停止
       └─ 所有候选失败 → 停止
```

## 四、防插话机制

```
┌──────────────┐
│ 防插话逻辑    │
├──────────────┤
│              │
│  currentSpeakerBotId  │
│       ↓              │
│  botA说话时锁定       │
│  → botB音频到达       │
│  → botId != 锁定      │
│  → 不解码不播放       │
│  → 仅存转发缓存       │
│  → 文字正常显示       │
│              │
│  botA说完 → 不清锁   │
│  （等最后几包播完）    │
│              │
│  botB开始说话        │
│  → 覆盖锁定为botB    │
│  → botB音频正常播放   │
└──────────────┘
```

关键设计：
- **tts.stop 不清锁**：让botA最后几包音频自然播完
- **下一个bot的speaking事件覆盖锁**：botB真正开始时才换人
- **非当前bot的音频只缓存不播放**：插话者只显示文字

## 五、解码器生命周期

```
┌──────────────────────────────────────────┐
│ 解码器（1个NativeOpusDecoder+1个AudioTrack）│
├──────────────────────────────────────────┤
│                                          │
│  创建时机:                                │
│  ├─ aiState="speaking" → 预创建           │
│  └─ audioData到达时（兜底）                │
│                                          │
│  销毁时机:                                │
│  ├─ startVoiceInput → 重新录音前          │
│  ├─ connectGroup → 切换群聊               │
│  ├─ deleteGroup → 删除群聊               │
│  └─ onCleared → ViewModel销毁            │
│                                          │
│  复用: 所有bot共享，永不释放直到下次录音    │
└──────────────────────────────────────────┘
```

## 六、文字兜底机制

当音频转发失败或超时时，使用文字兜底：

```
音频转发失败/超时
  │
  ├─ buildForwardText(reply)
  │     └─ 取原问题(originalQuery)前8字
  │        或取当前回复(reply)前8字
  │
  ├─ svc.sendTextContent(text)
  │     └─ 发送到目标bot的WebSocket
  │
  └─ 等待10s确认bot开始说话
        └─ 成功 → 接龙继续
        └─ 失败 → 尝试下一个候选
```

## 七、关键差异：旧版 vs 新版

| 项目 | 旧版 | 新版 |
|------|------|------|
| 解码器 | 每个bot独立，交叉释放 | **1个共享** |
| 状态管理 | 15字段+RelayState不可变类 | **6个简单var** |
| 音频过滤 | waitingSpeakerIndex+speakerMutex | **currentSpeakerBotId** |
| 候选策略 | 智能打分(提及/未发言/随机) | **随机排除自己** |
| 去重 | botId+content复合key | 同上 |
| 死循环检测 | 3轮触发+计数器 | 同上 |
| 文件大小 | 815行 | **583行** |

## 八、数据流示意图

```
用户语音 ──→ firstBot.WebSocket ──→ 服务器
                                        │
                                        ├─ ttsText ──→ 事件通道 ──→ 显示文字
                                        ├─ aiState ──→ 事件通道 ──→ 状态管理
                                        └─ audioData ─→ 共享解码器 ─→ AudioTrack → 扬声器
                                                           │
                                                           └─ 缓存到audioForwardBuffer

botA结束 → triggerNext
              │
              ├─ 读取audioForwardBuffer
              ├─ 发送到botB.WebSocket
              └─ 清缓存

botB发言 ──→ 同一个共享解码器 ──→ AudioTrack
              │
              └─ 缓存到audioForwardBuffer（覆盖botA的）

...循环直到结束语/所有候选失败
```
