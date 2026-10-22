# XiaoZhiAI — 安卓小智客户端

[![CI](https://github.com/raoyong1985/XiaoZhiAI/actions/workflows/ci.yml/badge.svg)](https://github.com/raoyong1985/XiaoZhiAI/actions/workflows/ci.yml)

把「小智」语音助手跑在 Android 手机上：WebSocket 长连接 + Opus 双向音频 + 流式 TTS + 设备端 MCP 工具 + 多智能体群聊接龙。

- 最低支持 Android 8.0（API 26），目标 API 34
- Kotlin + Jetpack Compose + Hilt + OkHttp + ONNX Runtime（VAD 全程端侧）
- 纯客户端，不含任何服务端实现；服务端地址由用户在「联系人」里配置

---

## 功能

| 模块 | 说明 |
|---|---|
| 语音对话 | WebSocket 双向流；上行 Opus 20ms 帧，下行 Opus 60ms 帧；支持手动说话与 VAD 自动断句 |
| 语音唤醒 | 已整体移除（v1.5.77 起），避免常驻麦克风与误唤醒 |
| 流式 TTS | 服务端按句下发音频，客户端边收边播；文字按播放进度上屏，句间插入停顿 |
| 回声消除 | 硬件 AEC（若设备支持）+ 软件 NLMS（以 TTS 参考信号对齐），防止自问自答 |
| 群聊接龙 | 多智能体轮流发言，按音量/情绪打分选择下一位，支持乒乓模式与打断 |
| 设备端 MCP | 43 个工具：闹钟、笔记、音乐、截图、蓝牙、位置、应用控制、网页搜索等 |
| 本地命令 | 不联网即可执行的指令（音量、亮度、打开应用等），带冷却期防回声循环 |
| 前台服务 | 保活、锁屏唤醒、断电重启自启 |

## 环境要求

| 项 | 要求 |
|---|---|
| JDK | **必须 17**。JDK 21 会让 kapt 直接失败：`'com.sun.tools.javac.util.Context' class can't be found ('tools.jar' is absent)`；改了 `JAVA_HOME` 仍报同一句，说明 Gradle 守护进程复用了旧 JDK，先 `./gradlew --stop` |
| Android SDK | compileSdk 34、NDK（Opus / ONNX 原生库） |
| 网络 | 首次构建需联网（`androidx.compose.compiler:compiler:1.5.14` 等依赖不在本地缓存，`--offline` 会失败） |

## 构建与安装

```bash
# 调试包（签名用 app/debug.keystore）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug

# 发布包（签名用 app/release.keystore + app/keystore.properties，均已 gitignore）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleRelease

adb install -r app/build/outputs/apk/release/app-release.apk
```

> 换签名会导致 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。若设备上已装发布包，升包必须继续用 `release.keystore`，否则只能卸载重装（会丢聊天记录）。

## 测试

```bash
./gradlew testDebugUnitTest
```

## 架构

```
presentation/   Compose UI + ViewModel（ChatViewModel / GroupChatViewModel）
   │
domain/         usecase：relay（接龙引擎）、group（连接池）；model；repository 接口
   │
data/           remote（WebSocket、协议解析、MCP 工具）｜audio（录制/解码/播放/AEC/VAD）｜repository 实现
```

关键文件：

| 文件 | 职责 |
|---|---|
| `data/remote/XiaoZhiBackendService.kt` | WebSocket 生命周期、协议分发（hello/tts/stt/llm/mcp/alert）、重连退避 |
| `data/repository/ConnectionRepositoryImpl.kt` | 会话状态机（IDLE/LISTENING/SPEAKING）、音频流与文字流的桥接 |
| `data/audio/AudioService.kt` | AudioRecord、VAD、软/硬 AEC、静音检测 |
| `data/audio/NativeOpusDecoder.kt` | Opus 解码 + AudioTrack 播放线程 + 队列与句间停顿 |
| `data/audio/NativeOpusEncoder.kt` | 上行 Opus 编码（JNI） |
| `domain/usecase/relay/RelayEngine.kt` | 群聊接龙：选人、转发、超时、重试上限 |
| `data/remote/MCPToolRegistry.kt` | 设备端 MCP 工具注册（同名覆盖去重） |

## 音频链路要点

下行 TTS 是本项目最容易出问题的地方，几个已踩过的坑已固化在代码里：

1. **服务端生成快于实时播放** → 队列必须有上限（`MAX_BACKLOG_MS`），否则延迟累积到十几秒，上一轮尾音会拖进下一轮。
2. **文字必须跟随播放进度**：`queuedMillis()` 用「已写入 AudioTrack 字节 − playbackHead 已播字节」计算真实积压，而不是只看解码队列长度（播放线程会把队列一次抽干写进 AudioTrack，队列长度恒为 0）。
3. **句间停顿按需插入**：只有确实有音频在排队时才插 80ms 静音；上一句已播完时再插会让下一句第一个字听起来「卡一下」。
4. **开播前预缓冲 120ms**：空闲恢复后立刻 `play()` 会在第二包写入前播空，吃掉第一包尾部。
5. **空闲保活按实际间隔补静音**：固定写 66ms 而每 400ms 写一次，会导致每秒一次 `AudioTrack underrun → restart`（周期性咔哒）。
6. **base64 与二进制双路音频按「批」判重**：给二进制副本 300ms 抢先窗口，逐帧 cancel 会让 base64-only 服务端只剩最后一帧。
7. **音频包必须单消费者串行发射**：每包 `launch{}` 一个协程会在 IO 线程池乱序，解码后 PCM 顺序错乱（咔嗒/吞字）。

## 本次更新（v1.5.78）

**音频**

- 句间停顿改为按需 80ms；开播预缓冲 120ms；空闲保活按实际间隔补静音
- 队列积压上限 3s，`queuedMillis()` 改为按真实未播字节计算
- base64 音频改为整批 + 代际校验判重，修复 base64-only 服务端只播最后一帧
- 音频下发改为单消费者 Channel，保证 Opus 包顺序
- 修复编码器尾帧：不足一帧补零到整帧（原来每次停止录音都丢尾音并报 `opus_encode failed`）
- 修复编码器停止时的 use-after-free 风险（线程未退出不销毁 native 句柄）
- 录音启动失败回滚 `isRecording`；软件 AEC 开关不再被 `stopRecording` 清掉

**群聊**

- 连接状态聚合跟随连接池成员变化（`membership` StateFlow），地球图标不再一直显示错误颜色；语义改为「全部在线=绿 / 部分在线=黄 / 未连接=灰」
- 修复成功后 `waitingSpeakerIndex` 被误清空导致的串话
- 修复重试计数被提前清零导致的无限重连重发
- 修复群聊解码器 key 缺少采样率后缀、每次按麦克风都 `releaseAll()` 的问题

**稳定性**

- `registerReceiver` 改用 `ContextCompat`（三参重载是 API 33+，minSdk 26 直接调用会在 Android 8~12 崩）
- 解码器 LRU 淘汰时显式 `release()`，不再泄漏 AudioTrack / 播放线程 / native 句柄
- 重连标志 `isReconnecting` 复位，修复「第二次重连失败后永久不再重连」
- 服务端协议级错误（如 MCP 工具重名）不再弹给用户，只记日志
- 设备端 `web_search` 改名 `device_web_search`，避免与服务端内置工具重名

## 已知问题

- 服务端以 `1000/1001` 正常关闭时客户端不会自动重连（需手动返回重进）
- 发送文本后若服务端不回 TTS，输入框可能停留在「等待回复」状态
- 部分机型不支持 `VOICE_RECOGNITION` 音源时会自动降级，降级失败后需重启 App
- 群聊中某个 bot 长期掉线时，需要退出重进才能刷新连接池

## 隐私

仓库**不包含**任何 API Key、账号口令、聊天记录、设备标识或数据库备份：

- `app/*.keystore`、`app/keystore.properties`、`local.properties` 均在 `.gitignore`
- `BAK/`（数据库 dump、datastore 备份）不入库
- 服务端地址、设备 ID 由用户在应用内填写，不写死在代码里

## 相关文档

- [AGENTS.md](AGENTS.md) — 开发规范与协作约定
- [SPEC.md](SPEC.md) — 功能规格
- [GROUP_CHAT_WORKFLOW.md](GROUP_CHAT_WORKFLOW.md) — 群聊接龙流程
- [audio_pipeline_analysis.md](audio_pipeline_analysis.md) — 音频链路分析
