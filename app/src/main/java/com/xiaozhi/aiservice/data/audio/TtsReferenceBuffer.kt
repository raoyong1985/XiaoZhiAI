package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger

/**
 * TTS 播放参考信号环形缓冲。
 *
 * 存储最近 ~2 秒的 TTS 解码 PCM（16kHz 16-bit mono），
 * 供 SoftAec 作为回声消除的参考信号（far-end signal）。
 *
 * 参考 ESP32 AFE 的 reference signal buffer 设计。
 */
object TtsReferenceBuffer {

    private const val TAG = "TtsRefBuffer"
    private const val SAMPLE_RATE = 16000
    private const val BYTES_PER_SAMPLE = 2
    /** 缓冲时长（ms） */
    private const val BUFFER_MS = 2000
    private const val BUFFER_SIZE = SAMPLE_RATE * BYTES_PER_SAMPLE * BUFFER_MS / 1000  // 64000

    private val buffer = ByteArray(BUFFER_SIZE)
    private var writePos = 0
    private var availableBytes = 0
    private var lastWriteTimestamp = 0L

    /**
     * 写入 TTS 解码后的 PCM 数据。
     * 由 NativeOpusDecoder.decodePacket() 的 onPcm 回调调用。
     */
    @Synchronized
    fun write(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        lastWriteTimestamp = System.currentTimeMillis()
        val len = pcm.size
        if (len >= BUFFER_SIZE) {
            // 数据比缓冲还大，保留末尾
            System.arraycopy(pcm, len - BUFFER_SIZE, buffer, 0, BUFFER_SIZE)
            writePos = 0
            availableBytes = BUFFER_SIZE
        } else {
            val space = BUFFER_SIZE - writePos
            if (len <= space) {
                System.arraycopy(pcm, 0, buffer, writePos, len)
            } else {
                System.arraycopy(pcm, 0, buffer, writePos, space)
                System.arraycopy(pcm, space, buffer, 0, len - space)
            }
            writePos = (writePos + len) % BUFFER_SIZE
            availableBytes = minOf(availableBytes + len, BUFFER_SIZE)
        }
    }

    /**
     * 读取最近 N ms 的参考 PCM 数据（降采样匹配）。
     * 返回与 micPcm 长度一致的参考段（从缓冲末尾对齐）。
     *
     * @param targetSize  需要的字节数（应与 mic PCM 帧大小一致）
     * @return 参考 PCM byteArray，如果缓冲数据不足返回 null
     */
    @Synchronized
    fun readLatest(targetSize: Int): ByteArray? {
        if (availableBytes < targetSize) return null
        val result = ByteArray(targetSize)
        if (availableBytes < BUFFER_SIZE) {
            // 未绕回：数据从 0 开始连续
            System.arraycopy(buffer, availableBytes - targetSize, result, 0, targetSize)
        } else {
            val readStart = (writePos - targetSize + BUFFER_SIZE) % BUFFER_SIZE
            val tail = BUFFER_SIZE - readStart
            if (targetSize <= tail) {
                System.arraycopy(buffer, readStart, result, 0, targetSize)
            } else {
                System.arraycopy(buffer, readStart, result, 0, tail)
                System.arraycopy(buffer, 0, result, tail, targetSize - tail)
            }
        }
        return result
    }

    /**
     * 参考信号是否新鲜（最近 5 秒内有 TTS 播放）。
     * 超过 5 秒无新数据则视为无参考信号。
     */
    @Synchronized
    fun hasFreshReference(): Boolean {
        if (availableBytes == 0) return false
        return System.currentTimeMillis() - lastWriteTimestamp < 5000
    }

    @Synchronized
    fun clear() {
        writePos = 0
        availableBytes = 0
        lastWriteTimestamp = 0L
    }

    fun size(): Int = synchronized(this) { availableBytes }

    fun isEmpty(): Boolean = synchronized(this) { availableBytes == 0 }
}
