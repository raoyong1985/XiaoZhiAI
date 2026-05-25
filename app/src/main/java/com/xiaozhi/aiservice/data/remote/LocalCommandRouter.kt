package com.xiaozhi.aiservice.data.remote

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.utils.Logger
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地命令路由器 — 在语音识别后、发送给服务器LLM之前拦截匹配的命令，
 * 直接调用本地 MCP 工具执行并 TTS 反馈。
 *
 * 匹配规则：关键词匹配（不区分大小写）
 */
object LocalCommandRouter {

    private const val TAG = "LocalCommandRouter"
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var mcpToolRegistry: MCPToolRegistry

    /** 命令映射：关键词列表 → (工具名, 参数字典, TTS反馈模板) */
    val commands = listOf(
        // ===== 手电筒 =====
        CmdRule(
            keywords = listOf("打开手电筒", "开手电", "手电筒开", "开灯"),
            toolName = "toggle_flashlight",
            args = mapOf("enable" to true),
            ttsTemplate = "好的，手电筒已打开"
        ),
        CmdRule(
            keywords = listOf("关闭手电筒", "关手电", "手电筒关", "关灯"),
            toolName = "toggle_flashlight",
            args = mapOf("enable" to false),
            ttsTemplate = "好的，手电筒已关闭"
        ),

        // ===== 截图 =====
        CmdRule(
            keywords = listOf("截屏", "截图", "截个图", "屏幕截图"),
            toolName = "take_screenshot",
            args = emptyMap(),
            ttsTemplate = "截屏已保存到相册"
        ),

        // ===== 音量 =====
        CmdRule(
            keywords = listOf("大点声", "大声点", "音量加大", "音量大", "开大音量"),
            toolName = "set_xiaozhi_volume",
            args = mapOf("level" to 80),
            ttsTemplate = "音量已调到80"
        ),
        CmdRule(
            keywords = listOf("小点声", "小声点", "音量减小", "音量小", "关小音量"),
            toolName = "set_xiaozhi_volume",
            args = mapOf("level" to 30),
            ttsTemplate = "音量已调到30"
        ),
        CmdRule(
            keywords = listOf("最大声", "最大音量", "音量最大"),
            toolName = "set_xiaozhi_volume",
            args = mapOf("level" to 100),
            ttsTemplate = "音量已调到最大"
        ),
        CmdRule(
            keywords = listOf("静音", "关闭声音", "关声音"),
            toolName = "set_xiaozhi_volume",
            args = mapOf("level" to 0),
            ttsTemplate = "已静音"
        ),

        // ===== 响铃模式 =====
        CmdRule(
            keywords = listOf("静音模式", "设为静音"),
            toolName = "set_ringer_mode",
            args = mapOf("mode" to "silent"),
            ttsTemplate = "已设为静音模式"
        ),
        CmdRule(
            keywords = listOf("震动模式", "震动", "振动模式"),
            toolName = "set_ringer_mode",
            args = mapOf("mode" to "vibrate"),
            ttsTemplate = "已设为震动模式"
        ),
        CmdRule(
            keywords = listOf("响铃模式", "标准模式"),
            toolName = "set_ringer_mode",
            args = mapOf("mode" to "normal"),
            ttsTemplate = "已设为响铃模式"
        ),

        // ===== 屏幕亮度 =====
        CmdRule(
            keywords = listOf("调亮屏幕", "屏幕亮一点", "亮度调高"),
            toolName = "screen_brightness",
            args = mapOf("level" to 200),
            ttsTemplate = "亮度已调高"
        ),
        CmdRule(
            keywords = listOf("调暗屏幕", "屏幕暗一点", "亮度调低"),
            toolName = "screen_brightness",
            args = mapOf("level" to 50),
            ttsTemplate = "亮度已调低"
        ),

        // ===== 网络 =====
        CmdRule(
            keywords = listOf("网络状态", "网络信息", "WiFi状态"),
            toolName = "get_network_info",
            args = emptyMap(),
            ttsTemplate = "正在查询网络状态"
        ),
        CmdRule(
            keywords = listOf("IP地址", "公网IP", "我的IP"),
            toolName = "get_public_ip",
            args = emptyMap(),
            ttsTemplate = "正在查询公网IP"
        ),

        // ===== 音量大小（数字模式） =====
        CmdRule(
            keywords = listOf("音量调到", "音量设为"),
            toolName = "set_xiaozhi_volume",
            args = emptyMap(),
            ttsTemplate = "音量已调好",
            extractDigits = true
        ),
    )

    /** 初始化 TextToSpeech + MCP工具注册表 */
    fun init(context: Context, registry: MCPToolRegistry) {
        mcpToolRegistry = registry
        if (tts != null) return
        try {
            tts = TextToSpeech(context) { status ->
                ttsReady = (status == TextToSpeech.SUCCESS)
                if (ttsReady) {
                    tts?.language = Locale.CHINESE
                    Logger.i(TAG, "TTS引擎就绪，语言: ${Locale.CHINESE}")
                } else {
                    Logger.w(TAG, "TTS引擎初始化未成功(status=$status)，仍将尝试播报")
                }
            }
        } catch (e: Exception) {
            // OPPO/MTK 上 com.oplus.ttsaccessibilityengine 需要签名级权限，初始化会失败
            Logger.w(TAG, "TTS引擎初始化失败（部分设备不支持）: ${e.message}")
            tts = null
            ttsReady = false
        }
    }

    /** 播放本地TTS */
    fun speak(text: String) {
        if (tts == null) {
            Logger.w(TAG, "TTS未创建，无法播报: $text")
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        val result = tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Logger.i(TAG, "本地TTS播报: \"$text\" (result=$result, ready=$ttsReady)")
    }

    /** 释放TTS资源 */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    /**
     * 尝试匹配并执行本地命令
     * @param text 语音识别的文本
     * @return 匹配结果（null=未匹配，非null=已处理）
     */
    suspend fun matchAndExecute(text: String): LocalCommandResult? {
        val clean = text.trim().lowercase()

        for (rule in commands) {
            for (kw in rule.keywords) {
                if (clean.contains(kw.lowercase())) {
                    Logger.i(TAG, "匹配到本地命令: $kw → ${rule.toolName}")

                    // 处理数字参数（如"音量调到50"）
                    val resolvedArgs = resolveArgs(text, rule)

                    // 调用MCP工具
                    val (success, result) = withContext(Dispatchers.IO) {
                        mcpToolRegistry.callTool(rule.toolName, resolvedArgs)
                    }

                    val responseText = if (success) {
                        rule.ttsTemplate
                    } else {
                        "${rule.ttsTemplate}，但执行失败: $result"
                    }

                    return LocalCommandResult(
                        matched = true,
                        toolName = rule.toolName,
                        success = success,
                        responseText = responseText,
                        rawResult = result
                    )
                }
            }
        }
        return null
    }

    private fun resolveArgs(text: String, rule: CmdRule): Map<String, Any> {
        if (rule.extractDigits) {
            val digits = text.replace(Regex("[^0-9]"), "")
            val level = if (digits.isNotEmpty()) {
                digits.toInt().coerceIn(0, 100)
            } else {
                return rule.args
            }
            Logger.i(TAG, "提取数字参数: level=$level (原始: $text)")
            return mapOf("level" to level)
        }
        return rule.args
    }

    data class CmdRule(
        val keywords: List<String>,
        val toolName: String,
        val args: Map<String, Any>,
        val ttsTemplate: String,
        val extractDigits: Boolean = false
    )

    data class LocalCommandResult(
        val matched: Boolean,
        val toolName: String,
        val success: Boolean,
        val responseText: String,
        val rawResult: Any
    )
}
