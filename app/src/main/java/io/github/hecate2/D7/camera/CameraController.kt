package io.github.hecate2.D7.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.tan

/**
 * CameraX 绑定：后摄预览 + 拍照，并给出叠加层投影用的焦距像素（针孔模型）。
 * 视场角只影响叠加层与实景的贴合度，不影响记录的角度。
 */
class CameraController(private val context: Context) {

    var imageCapture: ImageCapture? = null
        private set

    private var provider: ProcessCameraProvider? = null
    private var focalMm = 0f
    private var sensorWidthMm = 0f
    private var sensorHeightMm = 0f
    private var sensorOrientation = 0

    /** 绑定相机；回调在主线程，false 表示相机不可用（分屏下的兜底：暂停预览但保留传感器读数）。 */
    fun start(owner: LifecycleOwner, previewView: PreviewView, onReady: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val cameraProvider = try {
                    future.get()
                } catch (_: Exception) {
                    onReady(false)
                    return@addListener
                }
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder().build()
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    readCharacteristics(cameraProvider)
                    imageCapture = capture
                    onReady(true)
                } catch (_: Exception) {
                    imageCapture = null
                    onReady(false)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun stop() {
        provider?.unbindAll()
        imageCapture = null
    }

    /** 读数特征：焦距、传感器物理尺寸与传感器朝向。 */
    private fun readCharacteristics(cameraProvider: ProcessCameraProvider) {
        try {
            val info = cameraProvider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            val camera2 = Camera2CameraInfo.from(info)
            camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
                ?.let { focalMm = it }
            camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.let {
                    sensorWidthMm = it.width
                    sensorHeightMm = it.height
                }
            camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
                ?.let { sensorOrientation = it }
        } catch (_: Exception) {
            // 取不到特征时 focalPx 退回半视场角假设
        }
    }

    /**
     * 叠加层焦距（像素）：focal_mm × 填充比例。
     * 传感器尺寸先按显示旋转换轴（内容相对传感器转 90/270 度时宽高互换），
     * 再取 max(viewW/宽mm, viewH/高mm) 对应 PreviewView 的 FILL_CENTER 裁剪。
     * 特征缺失时退回 65 度水平视场假设（半视场角 32.5 度）。
     */
    fun focalPx(viewW: Int, viewH: Int, displayRotation: Int): Float {
        if (viewW <= 0 || viewH <= 0) return 1f
        if (focalMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) {
            return (viewW / 2f) / tan(32.5 * PI / 180.0).toFloat()
        }
        // 设备相对自然方向的顺时针物理转角
        val physicalRotation = when (displayRotation) {
            Surface.ROTATION_90 -> -90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 90
            else -> 0
        }
        val needed = ((sensorOrientation + physicalRotation) % 360 + 360) % 360
        val swap = needed == 90 || needed == 270
        val widthMm = if (swap) sensorHeightMm else sensorWidthMm
        val heightMm = if (swap) sensorWidthMm else sensorHeightMm
        return focalMm * max(viewW / widthMm, viewH / heightMm)
    }
}