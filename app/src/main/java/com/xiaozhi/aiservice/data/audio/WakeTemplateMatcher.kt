package com.xiaozhi.aiservice.data.audio

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 唤醒模板编解码工具。
 *
 * v1.5.75 精简：原 WakeTemplateMatcher 的模板匹配逻辑已由 KwsEngine（DTW + MFCC）替代，
 * 仅保留模板序列化工具函数。
 */
object WakeTemplateMatcher {

    private const val N_MFCC = 40

    fun floatsToBase64(features: FloatArray): String {
        val baos = ByteArrayOutputStream(N_MFCC * 4)
        val dos = DataOutputStream(baos)
        for (f in features) dos.writeFloat(f)
        dos.close()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun base64ToFloats(base64: String): FloatArray {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val features = FloatArray(bytes.size / 4)
        for (i in features.indices) features[i] = dis.readFloat()
        dis.close()
        return features
    }

    fun extractTemplateFromPcm(pcmBytes: ByteArray): String {
        val features = MfccExtractor().extractTemplate(pcmBytes)
        return floatsToBase64(features)
    }
}
