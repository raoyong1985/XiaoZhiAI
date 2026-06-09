package com.xiaozhi.aiservice.data.remote

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.audio.AudioService
import com.xiaozhi.aiservice.data.remote.mcp.registerBasicTools
import com.xiaozhi.aiservice.data.remote.mcp.registerCameraTool
import com.xiaozhi.aiservice.data.remote.mcp.registerExtraTools
import com.xiaozhi.aiservice.data.remote.mcp.registerIntentTools
import com.xiaozhi.aiservice.data.remote.mcp.registerLocationTools
import com.xiaozhi.aiservice.data.remote.mcp.registerNetworkTools
import com.xiaozhi.aiservice.data.remote.mcp.registerThemeTools
import com.xiaozhi.aiservice.data.remote.mcp.registerXiaozhiTools
import com.xiaozhi.aiservice.domain.repository.ConnectionRepository
import com.xiaozhi.aiservice.domain.repository.MessageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean = true,
    val userOnly: Boolean = false,
    val handler: suspend (Map<String, Any>) -> JsonObject
)

@Singleton
class MCPToolRegistry @Inject constructor(
    @ApplicationContext val context: Context,
    val audioService: AudioService,
    val connectionRepository: Lazy<ConnectionRepository>,
    val messageRepository: MessageRepository
) {
    private val tools = mutableListOf<MCPTool>()
    private var cachedToolList: JsonArray? = null
    val toolScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        registerBasicTools(this)
        registerLocationTools(this)
        registerNetworkTools(this)
        registerIntentTools(this)
        registerXiaozhiTools(this)
        registerCameraTool(this)
        registerExtraTools(this)
        registerThemeTools(this)
    }

    fun registerTool(
        name: String,
        description: String,
        inputSchema: JsonObject,
        readOnly: Boolean = true,
        userOnly: Boolean = false,
        handler: suspend (Map<String, Any>) -> JsonObject
    ) {
        tools.add(MCPTool(name, description, inputSchema, readOnly, userOnly, handler))
        cachedToolList = null
    }

    fun hasTool(name: String): Boolean = tools.any { it.name == name }

    fun getToolList(): JsonArray {
        if (cachedToolList != null) return cachedToolList!!
        val arr = JsonArray()
        tools.filter { !it.userOnly }.forEach { tool ->
            arr.add(toToolJson(tool))
        }
        cachedToolList = arr
        return arr
    }

    fun getAllToolList(): JsonArray {
        val arr = JsonArray()
        tools.forEach { tool ->
            arr.add(toToolJson(tool))
        }
        return arr
    }

    private fun toToolJson(tool: MCPTool): JsonObject = JsonObject().apply {
        addProperty("name", tool.name)
        addProperty("description", tool.description)
        add("inputSchema", tool.inputSchema)
        add("annotations", JsonObject().apply {
            addProperty("readOnly", tool.readOnly)
            addProperty("userOnly", tool.userOnly)
        })
    }

    suspend fun callTool(name: String, arguments: Map<String, Any>): Pair<Boolean, Any> {
        val tool = tools.find { it.name == name } ?: return Pair(false, "工具 $name 不存在")
        if (tool.userOnly) return Pair(false, "工具 $name 是用户专属工具，AI 不可调用")
        return try {
            Pair(true, tool.handler.invoke(arguments))
        } catch (e: Exception) {
            Pair(false, e.message ?: "执行失败")
        }
    }
}
