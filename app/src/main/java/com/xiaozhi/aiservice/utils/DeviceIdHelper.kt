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
    private const val PREFS_NAME = "device_id_prefs"
    private const val KEY_DEVICE_MAC = "device_mac"
    private const val KEY_DEVICE_UUID = "device_uuid"

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
     * 首次生成后持久化存储，确保重装/重新编译后ID不变
     */
    fun getDeviceMac(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_MAC, null)?.let { return it }

        val sha256Hash = generateDeviceHash(context)
        val macBytes = sha256Hash.copyOf(6)
        val mac = macBytes.joinToString(":") { String.format("%02x", it) }

        prefs.edit().putString(KEY_DEVICE_MAC, mac).apply()
        return mac
    }

    /**
     * 获取设备UUID
     * 首次生成后持久化存储，确保重装/重新编译后ID不变
     */
    fun getDeviceUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_UUID, null)?.let { return it }

        val sha256Hash = generateDeviceHash(context)
        val uuidBytes = sha256Hash.copyOf(16)
        // 设置版本和变体位
        uuidBytes[6] = (uuidBytes[6].toInt() and 0x0f or 0x40).toByte()
        uuidBytes[8] = (uuidBytes[8].toInt() and 0x3f or 0x80).toByte()

        val buffer = ByteBuffer.wrap(uuidBytes)
        val mostSignificant = buffer.long
        val leastSignificant = buffer.long
        val uuid = UUID(mostSignificant, leastSignificant).toString()

        prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
        return uuid
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

    /**
     * 为指定联系人生成唯一的MAC地址（随机）
     * 每个联系人独立，重启不变，删除重建则重新生成
     */
    fun generateContactMac(): String {
        val bytes = ByteArray(6)
        java.security.SecureRandom().nextBytes(bytes)
        // 设置本地管理位 + 清除广播位 (byte[0] & ~1)
        bytes[0] = (bytes[0].toInt() and 0xfe or 0x02).toByte()
        return bytes.joinToString(":") { String.format("%02x", it) }
    }

    /**
     * 为指定联系人生成唯一的客户端UUID（基于联系人ID生成，稳定）
     * 基于contactId生成，重启不变，删除重建（不同ID）则不同
     */
    fun generateContactUuid(contactId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(contactId.toByteArray(Charsets.UTF_8))
        val uuidBytes = hashBytes.copyOf(16)
        // 设置版本(4)和变体位
        uuidBytes[6] = (uuidBytes[6].toInt() and 0x0f or 0x40).toByte()
        uuidBytes[8] = (uuidBytes[8].toInt() and 0x3f or 0x80).toByte()
        val buffer = ByteBuffer.wrap(uuidBytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}
