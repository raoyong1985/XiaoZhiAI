package com.xiaozhi.aiservice.data.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Edge TTS (Microsoft Edge 在线语音合成) 封装。
 *
 * 不依赖 Android 系统 TTS 引擎，用 WebSocket 连接微软 Edge 的在线 TTS 服务。
 * 所有 Android 手机只要能上网就能用。
 */
class EdgeTtsSynthesizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "EdgeTts"
        private const val WSS_BASE = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WIN_EPOCH = 11644473600L
        private const val CHROMIUM_VER = "143"

        // 支持 16kHz 输出，避免重采样
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 合成语音并返回 16kHz 16-bit PCM 数据。
     */
    suspend fun synthesize(text: String, voice: String = "zh-CN-XiaoxiaoNeural"): ByteArray? =
        synthesize(text, -1, voice)

    /**
     * 合成语音并返回 16kHz 16-bit PCM 数据。
     * 支持在指定字符位置后插入停顿（用于重复唤醒词如"悠悠|悠悠"）。

     * @param text 合成文本
     * @param splitAfter 在此字符索引后插入停顿（-1 = 不停顿）
     * @param voice TTS 语音
     * @param rate 语速调整百分比（如 -15 = 慢速 15%，+10 = 快速 10%）
     */
    suspend fun synthesize(
        text: String,
        splitAfter: Int,
        voice: String = "zh-CN-XiaoxiaoNeural",
        rate: Int = 0
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                Logger.w(TAG, "开始 Edge TTS 合成: 「$text」voice=$voice splitAfter=$splitAfter rate=$rate")
                val mp3Bytes = doWsSynthesize(text, voice, splitAfter, rate) ?: return@withContext null
                Logger.w(TAG, "Edge TTS MP3 下载完成: ${mp3Bytes.size} bytes")
                val pcmBytes = decodeMp3ToPcm(mp3Bytes) ?: return@withContext null
                Logger.w(TAG, "Edge TTS PCM 解码完成: ${pcmBytes.size} bytes @16kHz")
                pcmBytes
            } catch (e: Exception) {
                Logger.e(TAG, "Edge TTS 合成失败: ${e.message}")
                null
            }
        }

    /**
     * WebSocket 协议实现：连接 → 发配置 → 发SSML → 收音频
     */
    private suspend fun doWsSynthesize(
        text: String,
        voice: String,
        splitAfter: Int = -1,
        rate: Int = 0
    ): ByteArray? =
        suspendCancellableCoroutine { cont ->
            try {
                val connId = UUID.randomUUID().toString().replace("-", "")
                val secMsGec = generateSecMsGec()
                val wssUrl = "$WSS_BASE?TrustedClientToken=$TOKEN" +
                        "&ConnectionId=$connId" +
                        "&Sec-MS-GEC=$secMsGec" +
                        "&Sec-MS-GEC-Version=1-$CHROMIUM_VER.0.3650.75"

                val muid = java.security.SecureRandom().let { r ->
                    ByteArray(16).also { r.nextBytes(it) }
                }.joinToString("") { "%02x".format(it) }.uppercase()

                val request = Request.Builder()
                    .url(wssUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROMIUM_VER}.0.0.0 " +
                            "Safari/537.36 Edg/${CHROMIUM_VER}.0.0.0")
                    .addHeader("Pragma", "no-cache")
                    .addHeader("Cache-Control", "no-cache")
                    .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .addHeader("Cookie", "muid=$muid;")
                    .build()

                val audioChunks = mutableListOf<ByteArray>()

                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    private var turnEndReceived = false

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Logger.w(TAG, "WebSocket 已连接")
                        // 连续发送 config + SSML，不等待服务器响应（edge-tts 协议要求）
                        webSocket.send(buildConfigMessage())
                        val ssml = buildSsmlMessage(text, voice, splitAfter, rate)
                        webSocket.send(ssml)
                        Logger.w(TAG, "Config + SSML 已连续发送")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val path = extractHeader(text, "Path")
                        Logger.w(TAG, "收到文本消息: path=$path, preview=${text.take(80)}")

                        if (path == "turn.end") {
                            turnEndReceived = true
                            Logger.w(TAG, "收到 turn.end")
                            scope.launch {
                                delay(500)
                                webSocket.close(1000, "完成")
                            }
                        } else if (path == "audio.metadata") {
                            Logger.w(TAG, "收到 audio.metadata")
                        } else if (path == "turn.start") {
                            Logger.w(TAG, "收到 turn.start")
                        } else if (path == "response") {
                            Logger.w(TAG, "收到 response（忽略，SSML 已提前发送）")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        if (bytes.size < 2) return
                        val rawData = bytes.toByteArray()
                        val headerLength = ((rawData[0].toInt() and 0xFF) shl 8) or
                                (rawData[1].toInt() and 0xFF)
                        if (headerLength < 2 || headerLength > rawData.size) return

                        // 解析 headers（Python edge-tts 协议：bytes[2..headerLength) 是 headers）
                        val headerStr = String(rawData.copyOfRange(2, headerLength), Charsets.UTF_8)
                        val path = extractHeaderValue(headerStr, "Path")
                        val contentType = extractHeaderValue(headerStr, "Content-Type")

                        if (path == "audio" && contentType == "audio/mpeg") {
                            val audioData = rawData.copyOfRange(headerLength + 2, rawData.size)
                            if (audioData.isNotEmpty()) {
                                audioChunks.add(audioData)
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Logger.w(TAG, "WebSocket 关闭中: code=$code reason=$reason")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Logger.w(TAG, "WebSocket 已关闭: code=$code reason=$reason")
                        if (audioChunks.isNotEmpty()) {
                            val totalSize = audioChunks.sumOf { it.size }
                            val allAudio = ByteArray(totalSize)
                            var offset = 0
                            for (chunk in audioChunks) {
                                System.arraycopy(chunk, 0, allAudio, offset, chunk.size)
                                offset += chunk.size
                            }
                            Logger.w(TAG, "收集到音频: ${allAudio.size} bytes, turnEnd=$turnEndReceived")
                            if (cont.isActive) cont.resume(allAudio)
                        } else {
                            Logger.e(TAG, "Edge TTS 未收到音频数据")
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Logger.e(TAG, "WebSocket 失败: ${t.message}")
                        // 检查是否已有部分音频数据
                        if (audioChunks.isNotEmpty()) {
                            val totalSize = audioChunks.sumOf { it.size }
                            val allAudio = ByteArray(totalSize)
                            var offset = 0
                            for (chunk in audioChunks) {
                                System.arraycopy(chunk, 0, allAudio, offset, chunk.size)
                                offset += chunk.size
                            }
                            if (cont.isActive) cont.resume(allAudio)
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                })

                // 超时保护
                scope.launch {
                    delay(30000)
                    if (cont.isActive) {
                        Logger.w(TAG, "Edge TTS 超时 (30s)")
                        ws.close(1002, "timeout")
                        if (audioChunks.isNotEmpty()) {
                            val totalSize = audioChunks.sumOf { it.size }
                            val allAudio = ByteArray(totalSize)
                            var offset = 0
                            for (chunk in audioChunks) {
                                System.arraycopy(chunk, 0, allAudio, offset, chunk.size)
                                offset += chunk.size
                            }
                            cont.resume(allAudio)
                        } else {
                            cont.resume(null)
                        }
                    }
                }

                cont.invokeOnCancellation {
                    ws.close(1001, "cancelled")
                }

            } catch (e: Exception) {
                Logger.e(TAG, "Edge TTS 连接异常: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * 用 Android MediaCodec 解码 MP3 → PCM (16kHz 16-bit mono)
     */
    private fun decodeMp3ToPcm(mp3Data: ByteArray): ByteArray? {
        return try {
            val tempFile = File.createTempFile("edgetts_", ".mp3")
            tempFile.deleteOnExit()
            FileOutputStream(tempFile).use { it.write(mp3Data) }

            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)

            val trackFormat = extractor.getTrackFormat(0)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(0)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val pcmChunks = mutableListOf<ByteArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                // Feed input
                if (!sawInputEOS) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        val pcm = ByteArray(bufferInfo.size)
                        outputBuffer!!.position(bufferInfo.offset)
                        outputBuffer.get(pcm, 0, bufferInfo.size)
                        pcmChunks.add(pcm)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()
            tempFile.delete()

            if (pcmChunks.isEmpty()) return null

            // 合并所有 PCM 块
            val totalSize = pcmChunks.sumOf { it.size }
            val result = ByteArray(totalSize)
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }

            // MP3 解码出来可能是 24kHz，如果是则重采样到 16kHz
            val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (sampleRate == 24000) {
                Logger.w(TAG, "MP3 是 24kHz，重采样到 16kHz")
                resample24kTo16k(result)
            } else {
                result
            }
        } catch (e: Exception) {
            Logger.e(TAG, "MP3 解码失败: ${e.message}")
            null
        }
    }

    /**
     * 24kHz → 16kHz 线性重采样（简单抽取：每3个取2个）
     */
    private fun resample24kTo16k(pcm24k: ByteArray): ByteArray {
        val sampleCount24k = pcm24k.size / 2
        val sampleCount16k = sampleCount24k * 2 / 3
        val pcm16k = ByteArray(sampleCount16k * 2)
        for (i in 0 until sampleCount16k) {
            // 24kHz 中对应位置：取最近的 24kHz 样本
            val srcIndex = (i * 3) / 2
            val clampedIndex = srcIndex.coerceIn(0, sampleCount24k - 1)
            pcm16k[i * 2] = pcm24k[clampedIndex * 2]
            pcm16k[i * 2 + 1] = pcm24k[clampedIndex * 2 + 1]
        }
        return pcm16k
    }

    /**
     * 生成 Sec-MS-GEC 令牌 (SHA256(timestamp_ns + token))
     * 与 Python edge-tts 库算法一致
     */
    private fun generateSecMsGec(): String {
        val now = System.currentTimeMillis() / 1000
        val ticks = (now + WIN_EPOCH)
        val rounded = ticks - (ticks % 300)
        // 转换为 Windows 文件时间 100ns 间隔（×1e7）
        val nsTicks = (rounded.toDouble() * 10_000_000).toLong()
        val strToHash = "${nsTicks}$TOKEN"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02x".format(it) }.uppercase()
    }

    /**
     * 构建 WebSocket 配置消息
     */
    private fun buildConfigMessage(): String {
        val timestamp = java.text.SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)Z'",
            Locale.US
        ).format(java.util.Date())
        return "X-Timestamp:$timestamp\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}\r\n"
    }

    /**
     * 构建 SSML 请求消息
     */
    private fun buildSsmlMessage(text: String, voice: String, splitAfter: Int = -1, rate: Int = 0): String {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val timestamp = java.text.SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)Z'",
            Locale.US
        ).format(java.util.Date())
        // 转换为 Edge TTS 全称格式：zh-CN-XiaoxiaoNeural → Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)
        val fullVoiceName = expandVoiceName(voice)
        val lang = if (voice.startsWith("zh-")) "zh-CN" else "en-US"
        val rateStr = if (rate == 0) "+0%" else "%+d%%".format(rate)
        val ssml = if (splitAfter >= 0 && splitAfter < text.length - 1) {
            val part1 = escapeXml(text.substring(0, splitAfter + 1))
            val part2 = escapeXml(text.substring(splitAfter + 1))
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
                    "<voice name='$fullVoiceName'>" +
                    "<prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>$part1</prosody>" +
                    "<break time='300ms'/>" +
                    "<prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>$part2</prosody>" +
                    "</voice></speak>"
        } else {
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
                    "<voice name='$fullVoiceName'>" +
                    "<prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>${escapeXml(text)}</prosody>" +
                    "</voice></speak>"
        }
        return "X-RequestId:$requestId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:$timestamp\r\n" +
                "Path:ssml\r\n\r\n" +
                ssml
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * 将短语音名转为 Edge TTS 全称：zh-CN-XiaoxiaoNeural → Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)
     */
    private fun expandVoiceName(voice: String): String {
        // 如果已经是全称格式，直接返回
        if (voice.startsWith("Microsoft Server Speech")) return voice
        // 解析短名：lang-region-NameNeural
        val regex = Regex("""^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$""")
        val match = regex.find(voice) ?: return voice
        val lang = match.groupValues[1]
        val region = match.groupValues[2]
        val name = match.groupValues[3]
        return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
    }

    /**
     * 从文本消息中提取 header 值
     */
    private fun extractHeader(text: String, header: String): String? {
        return text.lines().firstOrNull { it.startsWith("$header:") }
            ?.removePrefix("$header:")
            ?.trim()
    }

    /**
     * 从 header 块中提取指定 header 的值
     */
    private fun extractHeaderValue(headers: String, name: String): String {
        return headers.lines().firstOrNull { it.trimStart().startsWith("$name:") }
            ?.substringAfter("$name:")
            ?.trim() ?: ""
    }
}
