package com.xiaozhi.aiservice.presentation.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.xiaozhi.aiservice.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 二维码扫描 Activity
 * 显示相机预览 + 扫码框，自动识别二维码后关闭并返回结果
 */
class QrScannerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QrScanner"
        private const val FRAME_SKIP = 3  // 每隔 N 帧分析一次，降低负载

        @JvmStatic
        var pendingResult: CompletableDeferred<String>? = null

        fun launch(context: Context) {
            val intent = Intent(context, QrScannerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(true)
    private val frameCount = AtomicInteger(0)
    private val reader by lazy {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            put(DecodeHintType.TRY_HARDER, true)
        }
        MultiFormatReader().apply { setHints(hints) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 使用 FrameLayout 包裹预览 + 覆盖层
        val rootLayout = android.widget.FrameLayout(this)
        rootLayout.setBackgroundColor(Color.BLACK)

        // CameraX PreviewView
        val previewView = androidx.camera.view.PreviewView(this)
        previewView.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(previewView)

        // 扫码框覆盖层
        val overlayView = object : View(this) {
            private val framePaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
            }
            private val cornerPaint = Paint().apply {
                color = Color.parseColor("#00BCD4")
                style = Paint.Style.STROKE
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
            }
            private val dimPaint = Paint().apply {
                color = Color.parseColor("#80000000")
            }
            private val hintPaint = Paint().apply {
                color = Color.WHITE
                textSize = 48f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val frameSize = minOf(w, h) * 0.6f
                val left = (w - frameSize) / 2f
                val top = (h - frameSize) / 2f
                val frame = RectF(left, top, left + frameSize, top + frameSize)

                // 暗化外部区域
                canvas.drawRect(0f, 0f, w, top, dimPaint)
                canvas.drawRect(0f, top + frameSize, w, h, dimPaint)
                canvas.drawRect(0f, top, left, top + frameSize, dimPaint)
                canvas.drawRect(left + frameSize, top, w, top + frameSize, dimPaint)

                // 扫码框
                canvas.drawRoundRect(frame, 16f, 16f, framePaint)

                // 四角
                val cornerLen = frameSize * 0.12f
                canvas.drawLine(left, top + cornerLen, left, top, cornerPaint)
                canvas.drawLine(left, top, left + cornerLen, top, cornerPaint)
                canvas.drawLine(left + frameSize - cornerLen, top, left + frameSize, top, cornerPaint)
                canvas.drawLine(left + frameSize, top, left + frameSize, top + cornerLen, cornerPaint)
                canvas.drawLine(left, top + frameSize - cornerLen, left, top + frameSize, cornerPaint)
                canvas.drawLine(left, top + frameSize, left + cornerLen, top + frameSize, cornerPaint)
                canvas.drawLine(left + frameSize - cornerLen, top + frameSize, left + frameSize, top + frameSize, cornerPaint)
                canvas.drawLine(left + frameSize, top + frameSize - cornerLen, left + frameSize, top + frameSize, cornerPaint)

                // 底部提示文字
                canvas.drawText("对准二维码", w / 2f, top + frameSize + 100f, hintPaint)
            }
        }
        overlayView.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlayView.setBackgroundColor(Color.TRANSPARENT)
        rootLayout.addView(overlayView)

        setContentView(rootLayout)
        startCamera(previewView)
    }

    @Suppress("DEPRECATION")
    private fun startCamera(previewView: androidx.camera.view.PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                // ImageAnalysis for QR scanning
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (scanning.get()) {
                                analyzeFrame(imageProxy)
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this@QrScannerActivity,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Logger.e(TAG, "相机启动失败: ${e.message}")
                pendingResult?.completeExceptionally(Exception("相机启动失败: ${e.message}"))
                pendingResult = null
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!scanning.get()) {
            imageProxy.close()
            return
        }

        // 跳帧：每 FRAME_SKIP 帧才分析一次
        if (frameCount.incrementAndGet() % FRAME_SKIP != 0) {
            imageProxy.close()
            return
        }

        try {
            val format = imageProxy.format
            if (format != android.graphics.ImageFormat.YUV_420_888 && format != android.graphics.ImageFormat.NV21) {
                imageProxy.close()
                return
            }

            val yPlane = imageProxy.planes[0]
            val yBuffer = yPlane.buffer
            val yBytes = ByteArray(yBuffer.remaining())
            yBuffer.get(yBytes)

            val imgWidth = imageProxy.width
            val imgHeight = imageProxy.height
            val yRowStride = yPlane.rowStride

            // 处理行步长（部分设备 Y 平面每行末尾有 padding）
            val luminanceData = if (yRowStride == imgWidth) {
                yBytes
            } else {
                ByteArray(imgWidth * imgHeight).also { result ->
                    for (row in 0 until imgHeight) {
                        val srcPos = row * yRowStride
                        val dstPos = row * imgWidth
                        System.arraycopy(yBytes, srcPos, result, dstPos, imgWidth)
                    }
                }
            }

            // 只分析扫码框区域的像素（中心 60% 区域），缩小数据量且排除无关背景
            val cropW = (imgWidth * 0.6f).toInt()
            val cropH = (imgHeight * 0.6f).toInt()
            val cropX = (imgWidth - cropW) / 2
            val cropY = (imgHeight - cropH) / 2

            val source = PlanarYUVLuminanceSource(
                luminanceData, imgWidth, imgHeight,
                cropX, cropY, cropW, cropH, false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val result = reader.decodeWithState(binaryBitmap)
            reader.reset()

            if (result != null) {
                scanning.set(false)
                runOnUiThread {
                    pendingResult?.complete(result.text)
                    pendingResult = null
                    finish()
                }
            }
        } catch (_: Exception) {
            reader.reset()
        }

        imageProxy.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanning.set(false)
        cameraExecutor.shutdown()
        pendingResult?.completeExceptionally(Exception("扫码界面被关闭"))
        pendingResult = null
    }
}
