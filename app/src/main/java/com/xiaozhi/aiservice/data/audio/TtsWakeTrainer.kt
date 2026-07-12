package com.xiaozhi.aiservice.data.audio

import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TTS 自动唤醒词模板训练器。
 *
 * 用 Edge TTS（微软在线语音合成）合成唤醒词音频并提取 MFCC 模板，
 * 不依赖 Android 系统 TTS 引擎，所有能联网的 Android 设备都能用。
 */
class TtsWakeTrainer {

    companion object {
        private const val TAG = "TtsWakeTrainer"
        private const val MIN_PCM_SIZE = 3200  // 100ms @16kHz 16bit
    }

    private val mfccExtractor = MfccExtractor()
    private val edgeTts = EdgeTtsSynthesizer()

    /**
     * 用 Edge TTS 在线合成唤醒词音频并提取 MFCC 模板。
     */
    suspend fun train(wakeWord: String): String? = withContext(Dispatchers.IO) {
        if (wakeWord.isBlank()) {
            Logger.w(TAG, "唤醒词为空，跳过训练")
            return@withContext null
        }
        Logger.w(TAG, "Edge TTS 唤醒词训练开始: 「$wakeWord」")

        try {
            val pcmBytes = edgeTts.synthesize(wakeWord)
            if (pcmBytes == null || pcmBytes.size < MIN_PCM_SIZE) {
                Logger.e(TAG, "Edge TTS 合成失败: PCM=${pcmBytes?.size ?: 0}bytes")
                return@withContext null
            }

            Logger.w(TAG, "Edge TTS 合成成功: ${pcmBytes.size} bytes PCM")
            val features = mfccExtractor.extractTemplate(pcmBytes)
            val base64 = WakeTemplateMatcher.floatsToBase64(features)
            Logger.w(TAG, "唤醒词模板训练完成: base64=${base64.length}chars")
            return@withContext base64
        } catch (e: Exception) {
            Logger.e(TAG, "Edge TTS 训练异常: ${e.message}")
            null
        }
    }
}
