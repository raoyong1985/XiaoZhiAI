package com.xiaozhi.aiservice.data.remote.mcp

import android.Manifest
import android.content.pm.PackageManager
import com.google.gson.JsonObject
import com.xiaozhi.aiservice.data.remote.MCPToolRegistry
import com.xiaozhi.aiservice.di.AppContainer
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

fun registerCameraTool() {
    if (MCPToolRegistry.hasTool("take_photo")) return

    MCPToolRegistry.registerTool(
        name = "take_photo",
        description = "使用相机拍照并分析照片内容，支持识别物体、场景、文字等。调用后手机将自动打开相机并拍照，用户无需按任何按钮",
        inputSchema = jsonSchema(),
        readOnly = true
    ) { _ ->
        val context = AppContainer.context

        // 检查相机权限
        val permCheck = checkPermissionWithGuide(
            Manifest.permission.CAMERA, permissionName = "相机"
        )
        if (permCheck != null) return@registerTool permCheck

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

            val contactId = (AppContainer.connectionRepository as com.xiaozhi.aiservice.data.repository.ConnectionRepositoryImpl).currentContactId
            if (contactId.isNotEmpty()) {
                AppContainer.messageRepository.addSentMessage(
                    contactId,
                    com.xiaozhi.aiservice.domain.model.ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        content = photoFile.absolutePath,
                        type = com.xiaozhi.aiservice.domain.model.MessageType.IMAGE,
                        direction = com.xiaozhi.aiservice.domain.model.MessageDirection.SENT,
                        status = com.xiaozhi.aiservice.domain.model.MessageStatus.SENT,
                        timestamp = System.currentTimeMillis()
                    )
                )
                android.util.Log.i("MCPToolRegistry", "照片已添加到聊天: ${photoFile.absolutePath}")
            }
        } catch (e: Exception) {
            android.util.Log.e("MCPToolRegistry", "保存照片或插入消息失败: ${e.message}")
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
    val visionUrl = com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService.visionUrl
    if (visionUrl.isNullOrBlank()) return "照片已拍好，但视觉AI分析服务未配置"

    try {
        val boundary = "----AndroidCameraBoundary"
        val token = com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService.visionToken

        // 构造 multipart body
        val bodyBuilder = okhttp3.MultipartBody.Builder()
            .setType("multipart/form-data".toMediaTypeOrNull()!!)
            .addFormDataPart("question", "请详细描述这张图片中的内容，包括物体、场景、文字、颜色等")
            .addFormDataPart("file", "camera.jpg",
                okhttp3.RequestBody.create("image/jpeg".toMediaTypeOrNull(), jpegData))

        val request = okhttp3.Request.Builder()
            .url(visionUrl)
            .header("Device-Id", com.xiaozhi.aiservice.di.AppContainer.deviceId)
            .header("Client-Id", com.xiaozhi.aiservice.di.AppContainer.clientId)
            .apply {
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .post(bodyBuilder.build())
            .build()

        val client = OkHttpClient.Builder()
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
