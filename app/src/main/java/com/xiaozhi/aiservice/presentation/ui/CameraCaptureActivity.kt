package com.xiaozhi.aiservice.presentation.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 透明相机拍照 Activity
 * 使用 CameraX 自动拍照，无需用户按快门
 * 专为"拍张照"语音指令设计：无声、无界面、自动拍
 *
 * 启动方式：CameraCaptureActivity.launch(context)
 * 拍照结果可通过 suspendCancellableCoroutine 等待
 */
class CameraCaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraCapture"
        // ESP32 用 QVGA(320x240) + jpeg_quality=12，Android 端用 1600×1200 配合高画质
        // 4:3 中等分辨率，现代手机 12MP 摄像头下采样到 ~2MP，比 VGA 保留更多细节
        private const val MAX_WIDTH = 1600
        private const val MAX_HEIGHT = 1200
        private const val JPEG_QUALITY = 85  // 50→85：减少 JPEG 压缩 artifact，文件约 +50%

        /** 拍照结果的延续——MCP 工具通过它等待拍照完成 */
        @JvmStatic
        var pendingResult: CompletableDeferred<String>? = null

        /**
         * 启动相机拍照
         * @param context 上下文
         */
        fun launch(context: Context) {
            val intent = Intent(context, CameraCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置窗口为透明
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        window.setBackgroundDrawableResource(android.R.color.transparent)

        // 设置窗口尺寸为 1x1，完全不可见
        window.attributes.apply {
            width = 1
            height = 1
            alpha = 0.0f
        }

        startCameraCapture()
    }

    private fun startCameraCapture() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(windowManager.defaultDisplay.rotation)
                    .build()

                // 不需要 Preview，只需要 ImageCapture
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this@CameraCaptureActivity,
                    androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                    imageCapture
                )

                // 自动拍照
                imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        processImage(imageProxy)
                        imageProxy.close()
                        // unbindAll/finish 必须在主线程调用
                        ContextCompat.getMainExecutor(this@CameraCaptureActivity).execute {
                            cameraProvider.unbindAll()
                            finish()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Logger.e(TAG, "拍照失败: ${exception.message}")
                        pendingResult?.completeExceptionally(Exception("拍照失败: ${exception.message}"))
                        pendingResult = null
                        ContextCompat.getMainExecutor(this@CameraCaptureActivity).execute {
                            cameraProvider.unbindAll()
                            finish()
                        }
                    }
                })
            } catch (e: Exception) {
                Logger.e(TAG, "相机初始化失败: ${e.message}")
                pendingResult?.completeExceptionally(Exception("相机初始化失败: ${e.message}"))
                pendingResult = null
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 处理拍照结果：压缩、转 Base64、传给 pendingResult
     */
    private fun processImage(imageProxy: ImageProxy) {
        try {
            // 解码 JPEG → 缩放 → 重编码 JPEG（控制大小）
            val jpegBytes = imageProxy.planes[0].buffer.run {
                ByteArray(remaining()).also { get(it) }
            }
            val srcBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: throw Exception("无法解码照片")

            // 保持宽高比缩放到 MAX_WIDTH x MAX_HEIGHT
            val scale = minOf(
                MAX_WIDTH.toFloat() / srcBitmap.width,
                MAX_HEIGHT.toFloat() / srcBitmap.height,
                1.0f
            )
            val dstBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(
                    srcBitmap,
                    (srcBitmap.width * scale).toInt(),
                    (srcBitmap.height * scale).toInt(),
                    true
                )
            } else srcBitmap

            val bos = ByteArrayOutputStream()
            dstBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            val base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

            if (dstBitmap !== srcBitmap) dstBitmap.recycle()
            srcBitmap.recycle()

            Logger.i(TAG, "拍照成功: ${base64.length} chars base64 (${bos.size()} bytes JPEG)")
            pendingResult?.complete(base64)
            pendingResult = null
        } catch (e: Exception) {
            Logger.e(TAG, "处理照片异常: ${e.message}")
            pendingResult?.completeExceptionally(e)
            pendingResult = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        // 确保不会悬空
        pendingResult?.completeExceptionally(Exception("相机界面被销毁"))
        pendingResult = null
    }
}
