package com.xiaozhi.aiservice.data.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat
import com.xiaozhi.aiservice.utils.Logger

/**
 * 网络变化监听器
 *
 * Android N+ 使用 NetworkCallback（比 CONNECTIVITY_ACTION 更及时准确）
 * 检测到网络恢复时，触发 WebSocket 重连
 */
class ConnectivityReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ConnectivityRcvr"

        /**
         * 注册网络变化监听
         * Android N+ 用 NetworkCallback，低版本用 BroadcastReceiver
         */
        fun register(context: Context) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    private var wasDisconnected = false

                    override fun onAvailable(network: Network) {
                        Logger.i(TAG, "网络可用: $network")
                        if (wasDisconnected) {
                            // 之前断过现在恢复了→触发重连
                            XiaoZhiForegroundService.instance?.onNetworkRestored()
                        }
                        wasDisconnected = false
                    }

                    override fun onLost(network: Network) {
                        Logger.i(TAG, "网络丢失: $network")
                        wasDisconnected = true
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities
                    ) {
                        val hasInternet = capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET
                        )
                        if (hasInternet && wasDisconnected) {
                            Logger.i(TAG, "网络能力变更且有互联网→触发重连")
                            XiaoZhiForegroundService.instance?.onNetworkRestored()
                            wasDisconnected = false
                        }
                    }
                }

                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)

                Logger.i(TAG, "NetworkCallback 已注册 (API ${Build.VERSION.SDK_INT})")
            } else {
                // Android 6-：用 BroadcastReceiver 方式
                // 注册逻辑在 XiaoZhiApplication 中处理
                Logger.i(TAG, "低版本 API，使用 BroadcastReceiver 模式")
            }
        }
    }

    /**
     * BroadcastReceiver 回调（仅用于 Android 6- 低版本）
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            if (activeNetwork?.isConnected == true) {
                Logger.i(TAG, "BroadcastReceiver 检测到网络恢复")
                XiaoZhiForegroundService.instance?.onNetworkRestored()
            }
        }
    }
}
