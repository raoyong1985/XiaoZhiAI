package com.xiaozhi.aiservice.presentation.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileOutputStream

/**
 * 透明截图 Activity
 * 使用 MediaProjection API 对手机当前屏幕截图（需要 Android 11+）
 * 用户需要授权一次屏幕录制权限
 *
 * 启动方式：ScreenshotCaptureActivity.launch(context)
 * 截图结果通过 ScreenshotCaptureActivity.pendingResult 获取，返回截图的文件路径
 */
class ScreenshotCaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScreenshotCapture"
        private const val VIRTUAL_DISPLAY_NAME = "XiaoZhiScreenshot"

        /** 截图结果的延续——MCP 工具通过它等待截图完成，返回文件路径 */
        @JvmStatic
        var pendingResult: CompletableDeferred<String>? = null

        fun launch(context: Context) {
            val intent = Intent(context, ScreenshotCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(Activity.RESULT_OK, result.data!!)
            mediaProjection?.let { startCapture(it) }
        } else {
            Logger.e(TAG, "用户拒绝截图授权")
            pendingResult?.completeExceptionally(Exception("截图需要您授权屏幕录制权限"))
            pendingResult = null
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 透明窗口：不遮挡用户操作
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.attributes.apply {
            width = 1
            height = 1
            alpha = 0.0f
        }

        // 请求截图权限
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startCapture(projection: MediaProjection) {
        try {
            // 获取屏幕实际尺寸
            val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wmContext = getSystemService(WINDOW_SERVICE) as WindowManager
                val display = wmContext.defaultDisplay
                DisplayMetrics().also { display.getRealMetrics(it) }
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                DisplayMetrics().also { display.getRealMetrics(it) }
            }
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            Logger.i(TAG, "屏幕尺寸: ${width}x${height}, dpi=$density")

            // 创建 ImageReader 接收截图帧
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // 创建 VirtualDisplay 镜像当前屏幕
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            // 监听 ImageReader 的帧回调（异步回调，等一帧渲染完成）
            val handler = Handler(Looper.getMainLooper())
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    // 复制 pixels 到 Bitmap
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    buffer.position(0)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // 裁剪掉 rowPadding
                    val resultBitmap = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    } else {
                        bitmap
                    }
                    if (resultBitmap !== bitmap) bitmap.recycle()

                    // 保存为 PNG 文件
                    val screenshotDir = File(cacheDir, "screenshots")
                    screenshotDir.mkdirs()
                    val fileName = "screenshot_${System.currentTimeMillis()}.png"
                    val file = File(screenshotDir, fileName)
                    FileOutputStream(file).use { out ->
                        resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    resultBitmap.recycle()

                    Logger.i(TAG, "截图成功: ${file.absolutePath} (${file.length()} bytes)")
                    pendingResult?.complete(file.absolutePath)
                    pendingResult = null
                } catch (e: Exception) {
                    Logger.e(TAG, "处理截图帧异常: ${e.message}")
                    pendingResult?.completeExceptionally(e)
                    pendingResult = null
                } finally {
                    image.close()
                    cleanup()
                }
            }, handler)

            // 兜底超时：5 秒后如果还没收到帧，报超时
            handler.postDelayed({
                if (pendingResult != null && !pendingResult!!.isCompleted) {
                    Logger.e(TAG, "截图超时（5秒）")
                    pendingResult?.completeExceptionally(Exception("截图超时，请重试"))
                    pendingResult = null
                    cleanup()
                }
            }, 5000)

        } catch (e: Exception) {
            Logger.e(TAG, "创建 VirtualDisplay 失败: ${e.message}")
            pendingResult?.completeExceptionally(Exception("截图失败: ${e.message}"))
            pendingResult = null
            cleanup()
        }
    }

    private fun cleanup() {
        try {
            imageReader?.close()
            imageReader = null
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}
        if (!isFinishing) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        // Activity 被销毁时通知等待方
        pendingResult?.completeExceptionally(Exception("截图界面被关闭"))
        pendingResult = null
    }
}
