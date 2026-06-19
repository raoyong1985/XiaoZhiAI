package com.xiaozhi.aiservice.data.remote.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xiaozhi.aiservice.utils.Logger

/**
 * 设备重启/应用升级后重新注册所有未触发的闹钟和提醒
 *
 * 触发场景：
 * - BOOT_COMPLETED — 设备开机
 * - MY_PACKAGE_REPLACED — 应用被升级替换
 * - LOCKED_BOOT_COMPLETED — 设备在用户解锁前完成启动（API 24+）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Logger.i("BootReceiver", "恢复闹钟和提醒: action=$action")
        try {
            AlarmPersistence.restoreAll(context)
        } catch (e: Exception) {
            Logger.e("BootReceiver", "恢复闹钟失败: ${e.message}")
        }
        try {
            ReminderPersistence.restoreAll(context)
        } catch (e: Exception) {
            Logger.e("BootReceiver", "恢复提醒失败: ${e.message}")
        }
    }
}
