package com.xiaozhi.aiservice.data.remote.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xiaozhi.aiservice.data.remote.XiaoZhiForegroundService
import com.xiaozhi.aiservice.utils.Logger

/**
 * 设备重启/应用升级后重新注册所有未触发的闹钟和提醒
 * 同时启动前台保活 Service
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
        // 重启时拉起前台 Service
        try {
            val fgsIntent = Intent(context, XiaoZhiForegroundService::class.java)
            context.startForegroundService(fgsIntent)
            Logger.i("BootReceiver", "已拉起前台保活 Service")
        } catch (e: Exception) {
            Logger.e("BootReceiver", "拉起前台 Service 失败: ${e.message}")
        }
        try {
            AlarmPersistence.restoreAll(context)
        } catch (e: Exception) {
            Logger.e("BootReceiver", "恢复闹钟失败: ${e.message}")
        }
    }
}
