package com.xiaozhi.aiservice.data.camera

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraService"

/**
 * 拍照服务 - 封装CameraX拍照和图片上传
 * 类比ESP32的Esp32Camera::Explain方法
 * 
 * 使用方式:
 * val cameraService = CameraService(activity)  // 需要Activity作为LifecycleOwner
 * cameraService.initialize()
 * cameraService.captureAndExplain(...)
 */
class CameraService(private val activity: Activity) {

    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 初始化相机
     */
    suspend fun initialize(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(activity.windowManager.defaultDisplay.rotation)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        val capture = imageCapture!!
                        val camera = cameraProvider.bindToLifecycle(
                            activity as androidx.lifecycle.LifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            capture as UseCase
                        )
                        Log.i(TAG, "相机初始化成功")
                        cont.resume(true)
                    } catch (e: Exception) {
                        Log.w(TAG, "设备没有可用相机: ${e.message}")
                        imageCapture = null
                        cont.resume(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "相机初始化失败: ${e.message}")
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(activity))
        } catch (e: Exception) {
            Log.e(TAG, "相机初始化异常: ${e.message}")
            cont.resume(false)
        }
    }

    /**
     * 拍照并上传到服务器
     * @param question 问题文本，会跟随图片一起上传
     * @param serverUrl 服务器URL (用于获取explain_url的MCP响应)
     * @param deviceId 设备ID
     * @param clientId 客户端ID
     * @param authToken 认证Token
     * @return 服务器返回的识别结果
     */
    suspend fun captureAndExplain(
        question: String,
        serverUrl: String,
        deviceId: String,
        clientId: String,
        authToken: String
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resume(Result.failure(Exception("相机未初始化")))
            return@suspendCancellableCoroutine
        }

        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.i(TAG, "拍照成功，开始编码...")
                    
                    val jpegData = imageProxyToJpeg(image)
                    image.close()
                    
                    if (jpegData == null) {
                        cont.resume(Result.failure(Exception("图片编码失败")))
                        return
                    }
                    
                    Log.i(TAG, "JPEG编码成功，大小: ${jpegData.size} bytes")
                    
                    uploadImage(
                        jpegData = jpegData,
                        question = question,
                        serverUrl = serverUrl,
                        deviceId = deviceId,
                        clientId = clientId,
                        authToken = authToken
                    ) { result ->
                        cont.resume(result)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${exception.message}")
                    cont.resume(Result.failure(exception))
                }
            }
        )
    }

    /**
     * 将ImageProxy转换为JPEG字节数组
     */
    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            val rotationDegrees = image.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val matrix = android.graphics.Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                val outputStream = ByteArrayOutputStream()
                rotatedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outputStream)
                bitmap.recycle()
                rotatedBitmap.recycle()
                outputStream.toByteArray()
            } else {
                bytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "图片转换失败: ${e.message}")
            null
        }
    }

    /**
     * 上传图片到服务器 (multipart/form-data，类比ESP32的实现)
     */
    private fun uploadImage(
        jpegData: ByteArray,
        question: String,
        serverUrl: String,
        deviceId: String,
        clientId: String,
        authToken: String,
        callback: (Result<String>) -> Unit
    ) {
        Thread {
            try {
                val url = java.net.URL(serverUrl)
                val explainUrl = "${url.protocol}://${url.host}/api/explain"
                
                Log.i(TAG, "上传图片到: $explainUrl")

                val boundary = "----ESP32_CAMERA_BOUNDARY"
                
                val bodyBuilder = MultipartBody.Builder(boundary)
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("question", question)
                    .addFormDataPart(
                        "file",
                        "camera.jpg",
                        RequestBody.create("image/jpeg".toMediaTypeOrNull(), jpegData)
                    )

                val requestBody = bodyBuilder.build()

                val request = Request.Builder()
                    .url(explainUrl)
                    .post(requestBody)
                    .addHeader("Device-Id", deviceId)
                    .addHeader("Client-Id", clientId)
                    .addHeader("Authorization", "Bearer $authToken")
                    .addHeader("Content-Type", "multipart/form-data; boundary=$boundary")
                    .build()

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                Log.i(TAG, "上传响应: ${response.code} - ${responseBody.take(200)}")

                if (response.isSuccessful) {
                    callback(Result.success(responseBody))
                } else {
                    callback(Result.failure(Exception("上传失败: ${response.code}")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传异常: ${e.message}")
                callback(Result.failure(e))
            }
        }.start()
    }

    /**
     * 释放资源
     */
    fun release() {
        imageCapture = null
        executor.shutdown()
    }
}
