package io.github.hecate2.D7.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalCameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import io.github.hecate2.D7.sensor.ScreenRotation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.tan

/**
 * CameraX 绑定：后摄预览 + 拍照，并给出叠加层投影用的焦距像素（针孔模型）。
 * 视场角只影响叠加层与实景的贴合度，不影响记录的角度。
 *
 * 另外管两件取景时的事：手电筒（点亮前先记住系统当时的状态）与对焦。
 */
class CameraController(private val context: Context) {

    var imageCapture: ImageCapture? = null
        private set

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var focalMm = 0f
    private var sensorWidthMm = 0f
    private var sensorHeightMm = 0f
    private var sensorOrientation = 0

    /** 在飞的一次性测光对焦，用于避免叠加并供拍照前取消。 */
    private var pendingFocus: ListenableFuture<FocusMeteringResult>? = null

    /**
     * 绑定前读到的系统手电筒状态，null 表示没读到（无闪光灯或回调超时）。
     * 只用于启动时把灯恢复原样，之后不再关心。
     */
    var torchStateBeforeBind: Boolean? = null
        private set

    /** 自动对焦是否开启；默认开，由对焦键长按切换。 */
    var autoFocusEnabled: Boolean = true
        private set

    /**
     * 仅供仪器测试：覆盖闪光灯能力；null 表示用真实值。
     *
     * 模拟器与真机都报有闪光灯，「无闪光灯则隐藏手电筒位次」那一侧分支在两者上
     * 都走不到，没有这个口子就只能写一条恒真的断言。
     */
    @androidx.annotation.VisibleForTesting
    var flashUnitOverride: Boolean? = null

    /** 本机后摄是否有闪光灯；相机未就绪时为 false。 */
    val hasFlashUnit: Boolean
        get() = flashUnitOverride ?: (camera?.cameraInfo?.hasFlashUnit() ?: false)

    /** 相机是否已绑定成功；[hasFlashUnit] 等能力只在绑定后才有意义。 */
    val isBound: Boolean
        get() = camera != null

    /** 绑定相机；回调在主线程，false 表示相机不可用（分屏下的兜底：暂停预览但保留传感器读数）。 */
    fun start(owner: LifecycleOwner, previewView: PreviewView, onReady: (Boolean) -> Unit) {
        // 必须在绑定之前读：一旦本进程独占相机，系统回调会改报「不可用」，状态就永远读不到了
        torchStateBeforeBind = readTorchState()
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
                    camera = cameraProvider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    readCharacteristics(cameraProvider)
                    imageCapture = capture
                    // CameraX 绑定时会发 FLASH_MODE=OFF 把系统手电筒灭掉，这里恢复原状
                    if (torchStateBeforeBind == true) setTorch(true)
                    // 绑定前用户可能已经长按关掉了自动对焦，这时才把它落到硬件上
                    camera?.let { applyAutoFocus(it, autoFocusEnabled) }
                    onReady(true)
                } catch (_: Exception) {
                    camera = null
                    imageCapture = null
                    onReady(false)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun stop() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
    }

    /**
     * 读取系统当前手电筒状态。
     *
     * CameraX 自己维护的 [androidx.camera.core.CameraInfo.getTorchState] 初值恒为「关」，
     * 且绑定时就会发 FLASH_MODE=OFF 把灯灭掉，因此启动前的手电筒状态只能从这里读。
     * 注册回调会立刻回调一次当前值，给它一个很短的等待窗口；超时就当作没读到。
     *
     * 回调走的是专用后台线程而不是主线程：注册本身发生在主线程（Activity onCreate），
     * 若回调也派发到主线程，这里等待就会与它互相等死。
     */
    private fun readTorchState(): Boolean? {
        // 用的是接收 Executor 的重载（Handler 重载要 23 但没有主线程外的派发方式），API 28 起才有
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.getOrNull() == true
        } ?: return null
        val ready = CountDownLatch(1)
        val seen = BooleanArray(1)
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) {
                    seen[0] = enabled
                    ready.countDown()
                }
            }

            override fun onTorchModeUnavailable(id: String) {
                if (id == cameraId) ready.countDown()
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        return try {
            manager.registerTorchCallback(executor, callback)
            if (ready.await(TORCH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) seen[0] else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { manager.unregisterTorchCallback(callback) }
            executor.shutdown()
        }
    }

    /** 开关手电筒；相机未就绪或本机无闪光灯时静默忽略。 */
    fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    /**
     * 对准取景中心（准星）做一次对焦，AF/AE 一起动作。
     *
     * 用 [SurfaceOrientedMeteringPointFactory] 而不是预览工厂：这里要的就是画面正中那个点，
     * 与用户当前把准星压在哪儿无关。设了自动撤销，锁住后自行回到连续自动对焦，
     * 不必额外发取消。
     *
     * 上一次对焦还没结束就不再叠加新的：CameraX 里新的测光动作会顶掉前一个，
     * 而一个迟迟不收敛的测光会把随后的拍照堵住（实测表现为快门按下去不出点）。
     * 所以这里只允许一个在飞的测光动作，定时循环撞上了就跳过这一轮。
     */
    fun focusAtCenter(viewW: Int, viewH: Int): Boolean {
        val cam = camera ?: return false
        if (viewW <= 0 || viewH <= 0) return false
        if (pendingFocus?.isDone == false) return false
        val capture = imageCapture
        val factory = if (capture != null) {
            SurfaceOrientedMeteringPointFactory(viewW / 2f, viewH / 2f, capture)
        } else {
            SurfaceOrientedMeteringPointFactory(viewW / 2f, viewH / 2f)
        }
        val action = FocusMeteringAction.Builder(factory.createPoint(viewW / 2f, viewH / 2f))
            .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
            .build()
        pendingFocus = cam.cameraControl.startFocusAndMetering(action)
        return true
    }

    /**
     * 掐掉在飞的测光对焦。拍照前必调：快门优先，不能让它排在一次测光后面等。
     */
    fun cancelFocusMetering() {
        camera?.cameraControl?.cancelFocusAndMetering()
        pendingFocus = null
    }

    /**
     * 开关自动对焦。
     *
     * 相机尚未绑定时只记状态、等 [start] 绑定后再落到硬件上：采集页一打开就能按，
     * 而相机就绪要晚一两拍，若此时直接 return，用户的长按会被静默吞掉，
     * 按钮颜色与实际行为也对不上。
     */
    fun setAutoFocusEnabled(enabled: Boolean) {
        autoFocusEnabled = enabled
        val cam = camera ?: return
        applyAutoFocus(cam, enabled)
    }

    /**
     * CameraX 没有公开的「冻结/恢复连续自动对焦」开关，走 camera2 interop 改
     * CONTROL_AF_MODE：关时写 AF_MODE=OFF（镜头停在当前位置，即锁焦），
     * 开时清掉全部 capture request 覆盖项，回到 CameraX 自己的默认（连续自动对焦）。
     * 应用没有别的 request 覆盖项，所以 clearCaptureRequestOptions() 不会误伤手电筒等。
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun applyAutoFocus(cam: Camera, enabled: Boolean) {
        val interop = Camera2CameraControl.from(cam.cameraControl)
        if (enabled) {
            interop.clearCaptureRequestOptions()
        } else {
            interop.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF,
                    )
                    .build(),
            )
        }
    }

    private companion object {
        /** 读手电筒状态的等待上限；注册回调通常一两帧内就回来。 */
        const val TORCH_PROBE_TIMEOUT_MS = 400L

        /** 单次对焦的自动撤销时长：锁住后自行回到连续自动对焦。 */
        const val FOCUS_AUTO_CANCEL_SECONDS = 3L
    }

    /**
     * 读数特征：焦距、传感器物理尺寸与传感器朝向。
     *
     * CameraX 把相机信息与 camera2 互操作都标为实验 API（无更稳定的只读入口），
     * 故在此显式 opt-in：只用只读特征查询，不涉及预览/编码等易变路径。
     * 用 androidx 的 OptIn 而非 kotlin.OptIn：CameraX 的实验标记是 Java 侧
     * androidx.annotation.experimental.RequiresOptIn，lint 只认前者。
     */
    @androidx.annotation.OptIn(
        markerClass = [
            ExperimentalCameraInfo::class,
            ExperimentalCamera2Interop::class,
        ],
    )
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
        val swap = ScreenRotation.sensorAxesSwapped(sensorOrientation, displayRotation)
        val widthMm = if (swap) sensorHeightMm else sensorWidthMm
        val heightMm = if (swap) sensorWidthMm else sensorHeightMm
        return focalMm * max(viewW / widthMm, viewH / heightMm)
    }
}