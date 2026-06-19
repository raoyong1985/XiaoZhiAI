package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.content.pm.PackageManager
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.utils.Logger
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

fun registerCameraTool(registry: MCPToolRegistry) {
    if (registry.hasTool("take_photo")) return

    registry.registerTool(
        name = "take_photo",
        description = "使用相机拍照并分析照片内容，支持识别物体、场景、文字等。当用户说「帮我看看」「看看这是什么」「帮我认一下」「识别这个」「拍张照」「看看」「这是什么东西」「这是什么」「帮我拍」等请求识别、查看周围环境时，调用此工具。调用后手机将自动打开相机并拍照，用户无需按任何按钮",
        inputSchema = jsonSchema(),
        readOnly = true
    ) { _ ->
        val context = registry.context

        // 检查相机权限
        val permCheck = checkPermissionWithGuide(
            Manifest.permission.CAMERA, permissionName = "相机"
        )
        if (permCheck != null) return@registerTool permCheck

        // 并发保护：如果上一次拍照还没回来，拒绝本次请求（避免 pendingResult 静态字段被覆盖导致第一次永久挂起）
        val existing = com.xiaozhi.aiservice.presentation.ui.CameraCaptureActivity.pendingResult
        if (existing != null && !existing.isCompleted) {
            throw Exception("正在拍照中，请稍候再试")
        }

        // 启动拍照 Activity 并等待结果
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        com.xiaozhi.aiservice.presentation.ui.CameraCaptureActivity.pendingResult = deferred
        com.xiaozhi.aiservice.presentation.ui.CameraCaptureActivity.launch(context)

        val base64Result = kotlinx.coroutines.withTimeoutOrNull(30_000L) { deferred.await() }
            ?: throw Exception("拍照超时（30秒），请重试")

        // 保存图片到文件，插入聊天气泡
        val jpegBytes: ByteArray
        try {
            jpegBytes = android.util.Base64.decode(base64Result, android.util.Base64.DEFAULT)
            val photoDir = File(context.cacheDir, "photos")
            photoDir.mkdirs()
            val photoFile = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(photoFile).use { it.write(jpegBytes) }
            // 清理旧照片缓存，保留最近 20 张
            cleanupPhotoCache(photoDir)

            val contactId = registry.connectionRepository.get().currentContactId
            if (contactId.isNotEmpty()) {
                registry.messageRepository.addSentMessage(
                    contactId,
                    com.xiaozhi.aiservice.domain.model.ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        content = photoFile.absolutePath,
                        type = com.xiaozhi.aiservice.domain.model.MessageType.IMAGE,
                        direction = com.xiaozhi.aiservice.domain.model.MessageDirection.RECEIVED,
                        status = com.xiaozhi.aiservice.domain.model.MessageStatus.SENT,
                        timestamp = System.currentTimeMillis()
                    )
                )
                Logger.i("MCPToolRegistry", "照片已添加到聊天: ${photoFile.absolutePath}")
            }
        } catch (e: Exception) {
            Logger.e("MCPToolRegistry", "保存照片或插入消息失败: ${e.message}")
            throw e
        }

        // 发送到视觉 API 分析（传原始 JPEG 字节，用 multipart/form-data）
        val description = analyzeWithVision(jpegBytes)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("description", description)
        }
    }
}

/**
 * 发送图片到视觉 API 分析（multipart/form-data 方式，与 ESP32 一致）
 */
private suspend fun analyzeWithVision(jpegData: ByteArray): String {
    val backend = com.xiaozhi.aiservice.di.GlobalServices.xiaozhiBackend
    val visionUrl = backend.visionUrl
    if (visionUrl.isNullOrBlank()) return "照片已拍好，但视觉AI分析服务未配置"

    try {
        val boundary = "----AndroidCameraBoundary"
        val token = backend.visionToken

        // 构造 multipart body（OkHttp 4.x 兼容写法）
        val bodyBuilder = okhttp3.MultipartBody.Builder()
            .setType("multipart/form-data".toMediaTypeOrNull()!!)
            .addFormDataPart("question", "请详细描述这张图片中的内容，包括物体、场景、文字、颜色等")
            .addFormDataPart("file", "camera.jpg",
                jpegData.toRequestBody("image/jpeg".toMediaTypeOrNull()))

        val request = okhttp3.Request.Builder()
            .url(visionUrl)
            .header("Device-Id", AppContext.deviceId)
            .header("Client-Id", AppContext.clientId)
            .apply {
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .post(bodyBuilder.build())
            .build()

        // 共享 httpClient 的连接池，newBuilder() 只覆盖 timeouts（OkHttp 共享 dispatcher + connectionPool）
        val client = httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute()
        }

        val body = response.body?.string() ?: "空响应"
        if (!response.isSuccessful) return "照片已拍好，但视觉分析服务暂时不可用（HTTP ${response.code}），请稍后再试"

        // 解析返回的 JSON
        val json = try {
            com.google.gson.JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return body  // 直接返回服务器原文
        }
        return json.get("description")?.asString
            ?: json.get("result")?.asString
            ?: json.get("text")?.asString
            ?: json.get("message")?.asString
            ?: body  // 无法解析则返回原文
    } catch (e: Exception) {
        return "照片已拍好，但视觉分析请求出错: ${e.message}"
    }
}

/**
 * 清理照片缓存，保留最近 20 张
 */
private fun cleanupPhotoCache(dir: File) {
    val maxKeep = 20
    val files = dir.listFiles()?.filter { it.name.startsWith("photo_") && it.name.endsWith(".jpg") }
        ?: return
    if (files.size <= maxKeep) return
    files.sortedBy { it.lastModified() }
        .dropLast(maxKeep)
        .forEach { it.delete() }
}
