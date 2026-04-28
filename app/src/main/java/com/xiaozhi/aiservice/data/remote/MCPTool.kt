package com.xiaozhi.aiservice.data.remote

import android.content.Context
import android.os.BatteryManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.di.AppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MCP 工具定义
 */
data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: (Map<String, Any>) -> Result<JsonObject>
)

/**
 * MCP 工具注册表
 */
object MCPToolRegistry {
    private val tools = mutableListOf<MCPTool>()

    init {
        registerBuiltInTools()
    }

    private fun registerBuiltInTools() {
        // 获取设备信息
        registerTool(
            name = "get_device_info",
            description = "获取Android设备的基本信息，包括厂商、型号、系统版本等",
            inputSchema = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
                add("required", JsonArray())
            }
        ) { _ ->
            Result.success(JsonObject().apply {
                addProperty("manufacturer", android.os.Build.MANUFACTURER)
                addProperty("model", android.os.Build.MODEL)
                addProperty("android_version", android.os.Build.VERSION.RELEASE)
                addProperty("sdk_int", android.os.Build.VERSION.SDK_INT)
                addProperty("brand", android.os.Build.BRAND)
                addProperty("device", android.os.Build.DEVICE)
            })
        }

        // 获取当前时间
        registerTool(
            name = "get_current_time",
            description = "获取当前系统时间，支持指定时间格式",
            inputSchema = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("format", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "时间格式，如 yyyy-MM-dd HH:mm:ss，默认 yyyy-MM-dd HH:mm:ss")
                    })
                })
                add("required", JsonArray())
            }
        ) { args ->
            val format = args["format"] as? String ?: "yyyy-MM-dd HH:mm:ss"
            val dateFormat = SimpleDateFormat(format, Locale.getDefault())
            Result.success(JsonObject().apply {
                addProperty("time", dateFormat.format(Date()))
                addProperty("timestamp", System.currentTimeMillis() / 1000)
            })
        }

        // 获取电池电量
        registerTool(
            name = "get_battery_level",
            description = "获取当前设备的电池电量百分比",
            inputSchema = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
                add("required", JsonArray())
            }
        ) { _ ->
            val context = AppContainer.context
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            Result.success(JsonObject().apply {
                if (level >= 0) {
                    addProperty("level", level)
                    addProperty("status", "ok")
                } else {
                    addProperty("level", -1)
                    addProperty("status", "unavailable")
                }
            })
        }

        // 简单的计算工具
        registerTool(
            name = "calculate",
            description = "执行简单的四则运算，支持加减乘除，如 1+2*3",
            inputSchema = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("expression", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "数学表达式")
                    })
                })
                add("required", JsonArray().apply {
                    add(com.google.gson.JsonPrimitive("expression"))
                })
            }
        ) { args ->
            val expr = args["expression"] as? String ?: ""
            try {
                val result = evaluateSimpleExpression(expr)
                Result.success(JsonObject().apply {
                    addProperty("expression", expr)
                    addProperty("result", result)
                })
            } catch (e: Exception) {
                Result.failure(Exception("计算失败: ${e.message}"))
            }
        }
    }

    private fun evaluateSimpleExpression(expr: String): Double {
        val numbers = mutableListOf<Double>()
        val operators = mutableListOf<Char>()
        var currentNum = ""
        for (ch in expr) {
            if (ch in "+-*/") {
                if (currentNum.isNotEmpty()) {
                    numbers.add(currentNum.toDouble())
                    currentNum = ""
                }
                operators.add(ch)
            } else if (ch.isDigit() || ch == '.') {
                currentNum += ch
            }
        }
        if (currentNum.isNotEmpty()) numbers.add(currentNum.toDouble())

        var i = 0
        while (i < operators.size) {
            if (operators[i] == '*' || operators[i] == '/') {
                val left = numbers[i]
                val right = numbers[i + 1]
                numbers[i] = if (operators[i] == '*') left * right else left / right
                numbers.removeAt(i + 1)
                operators.removeAt(i)
            } else { i++ }
        }

        var result = numbers[0]
        for (j in operators.indices) {
            result = if (operators[j] == '+') result + numbers[j + 1] else result - numbers[j + 1]
        }
        return result
    }

    fun registerTool(
        name: String,
        description: String,
        inputSchema: JsonObject,
        handler: (Map<String, Any>) -> Result<JsonObject>
    ) { tools.add(MCPTool(name, description, inputSchema, handler)) }

    fun getToolList(): JsonArray {
        val arr = JsonArray()
        tools.forEach { tool ->
            arr.add(JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("inputSchema", tool.inputSchema)
            })
        }
        return arr
    }

    fun callTool(name: String, arguments: Map<String, Any>): JsonObject {
        val tool = tools.find { it.name == name }
        if (tool == null) {
            return JsonObject().apply {
                addProperty("isError", true)
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "工具 $name 不存在")
                    })
                })
            }
        }
        val result = tool.handler(arguments)
        return if (result.isSuccess) {
            result.getOrNull() ?: JsonObject()
        } else {
            JsonObject().apply {
                addProperty("isError", true)
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", result.exceptionOrNull()?.message ?: "执行失败")
                    })
                })
            }
        }
    }
}
