package com.xiaozhi.aiservice.data.remote.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
        // ⚠️ Android 12+ 限制后台启动前台服务。
        // BOOT_COMPLETED / MY_PACKAGE_REPLACED 属于后台广播，
        // 直接 startForegroundService 会抛 ForegroundServiceStartNotAllowedException。
        // 兜底：延迟 2s+try-catch 以防竞态。
        try {
            val fgsIntent = Intent(context, XiaoZhiForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val handler = android.os.Handler(context.mainLooper)
                handler.postDelayed({
                    try {
                        context.startForegroundService(fgsIntent)
                    } catch (e2: Exception) {
                        Logger.e("BootReceiver", "延迟启动前台 Service 仍失败: ${e2.message}")
                    }
                }, 2000L)
            } else {
                context.startForegroundService(fgsIntent)
            }
        } catch (e: Exception) {
            Logger.e("BootReceiver", "启动前台 Service 失败: ${e.message}")
        }
        try {
            AlarmPersistence.restoreAll(context)
        } catch (e: Exception) {
            Logger.e("BootReceiver", "恢复闹钟失败: ${e.message}")
        }
    }
}
