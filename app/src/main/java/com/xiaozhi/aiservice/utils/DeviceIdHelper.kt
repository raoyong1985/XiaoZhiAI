package com.xiaozhi.aiservice.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * 设备标识生成工具
 * 匹配官方小智AI Android版的设备ID生成逻辑
 */
object DeviceIdHelper {

    /**
     * 获取设备ID (64字符SHA256十六进制字符串)
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val sha256Hash = generateDeviceHash(context)
        return bytesToHexString(sha256Hash)
    }

    /**
     * 获取设备MAC地址 (XX:XX:XX:XX:XX:XX格式)
     */
    fun getDeviceMac(context: Context): String {
        val sha256Hash = generateDeviceHash(context)
        // 取前6字节
        val macBytes = sha256Hash.copyOf(6)
        return macBytes.joinToString(":") { String.format("%02x", it) }
    }

    /**
     * 获取设备UUID
     */
    fun getDeviceUuid(context: Context): String {
        val sha256Hash = generateDeviceHash(context)
        // 取前16字节
        val uuidBytes = sha256Hash.copyOf(16)
        // 设置版本和变体位
        uuidBytes[6] = (uuidBytes[6].toInt() and 0x0f or 0x40).toByte()
        uuidBytes[8] = (uuidBytes[8].toInt() and 0x3f or 0x80).toByte()

        val buffer = ByteBuffer.wrap(uuidBytes)
        val mostSignificant = buffer.long
        val leastSignificant = buffer.long
        return UUID(mostSignificant, leastSignificant).toString()
    }

    /**
     * 生成设备哈希 (匹配官方c函数逻辑)
     * SHA256(SHA256(设备信息#) + "|" + android_id + "|" + SHA256(版本信息%))
     */
    private fun generateDeviceHash(context: Context): ByteArray {
        // 设备信息
        val deviceInfo = listOf(
            Build.MANUFACTURER,
            Build.BOARD,
            Build.BRAND,
            Build.HARDWARE,
            Build.PRODUCT,
            Build.DEVICE,
            Build.TAGS,
            Build.TYPE
        ).joinToString("#")

        val deviceInfoHashHex = sha256(deviceInfo)

        // android_id
        val androidId = Settings.Secure.getString(context.contentResolver, "android_id") ?: "null_android_id"

        // 版本信息
        val versionInfo = listOf(
            Build.VERSION.RELEASE,
            Build.VERSION.INCREMENTAL,
            Build.TIME.toString(),
            Build.FINGERPRINT
        ).joinToString("%")

        val versionInfoHashHex = sha256(versionInfo)

        // 最终哈希: SHA256(deviceInfoHashHex + "|" + androidId + "|" + versionInfoHashHex)
        val combined = "$deviceInfoHashHex|$androidId|$versionInfoHashHex"
        return hexStringToBytes(sha256(combined))
    }

    /**
     * SHA256哈希
     */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHexString(hashBytes)
    }

    /**
     * 字节数组转十六进制字符串
     */
    private fun bytesToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    /**
     * 十六进制字符串转字节数组
     */
    private fun hexStringToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
