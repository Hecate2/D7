package io.github.hecate2.D7.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hecate2.D7.R
import io.github.hecate2.D7.camera.CameraController
import io.github.hecate2.D7.camera.PhotoMeta
import io.github.hecate2.D7.camera.PhotoStore
import io.github.hecate2.D7.core.Angles
import io.github.hecate2.D7.data.GroupRecord
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.databinding.ActivityCaptureBinding
import io.github.hecate2.D7.sensor.LocationProvider
import io.github.hecate2.D7.sensor.OrientationSensor
import io.github.hecate2.D7.sensor.Pose
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.result.ResultActivity
import io.github.hecate2.D7.ui.setPillSelected
import io.github.hecate2.D7.util.Format
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 采集页：取景器上直接画出太阳参考弧与已拍点连线。
 *
 * 快门 450 毫秒阈值区分短按（直接连线）与长按（经地平线推断段，天花板区不支持）；
 * 删除键长按生效，按下即锁定「十字线右侧、离当前方位最近」的一个候选点，按住只删一个；
 * 完成键跳转结果页。读数取融合瞬时值记录，显示值做轻度低通。
 */
class CaptureActivity : ComponentActivity() {

    companion object {
        private const val LONG_PRESS_MS = 450L
        private const val POSE_THROTTLE_MS = 30L

        // 读数精度（抖动）：最近窗口内极差的阈值与档位
        private const val JITTER_WINDOW = 24
        private const val JITTER_STEADY_DEG = 0.8
        private const val JITTER_OK_DEG = 2.5
        private const val LEVEL_UNKNOWN = -1
        private const val LEVEL_LOW = 1
        private const val LEVEL_MEDIUM = 2
        private const val LEVEL_HIGH = 3
    }

    private lateinit var binding: ActivityCaptureBinding
    private val repository by lazy { GroupRepository.get(this) }
    private lateinit var settings: CaptureSettings
    private lateinit var photoStore: PhotoStore
    private lateinit var camera: CameraController
    private var orientation: OrientationSensor? = null
    private var sensorRunning = false

    private var groupId: String = ""
    private var region = Region.EXTERNAL
    private var regionPoints: List<PointRecord> = emptyList()
    private var latestPose: Pose? = null
    private var lastAccuracy = OrientationSensor.ACCURACY_UNKNOWN
    private var lastUiAt = 0L
    private var capturing = false

    /** 滚转角显示用的低通值（NaN 表示尚未初始化）。 */
    private var smoothRoll = Double.NaN

    private val jitterAz = ArrayDeque<Double>()
    private val jitterEl = ArrayDeque<Double>()
    private var jitterLevel = LEVEL_UNKNOWN

    private val deleteHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera() else showCameraNotice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra(Extras.GROUP_ID).orEmpty()
        val group = repository.get(groupId)
        if (group == null) {
            finish()
            return
        }

        settings = CaptureSettings(this)
        photoStore = PhotoStore(this)
        camera = CameraController(this)

        binding.overlay.latDeg = group.lat
        binding.overlay.focalPxProvider = { w, h ->
            camera.focalPx(w, h, binding.overlay.display?.rotation ?: Surface.ROTATION_0)
        }
        binding.coverageBar.latDeg = group.lat

        applyHemisphere(group)
        updatePlus180Ui()
        applyLineSettings()
        updatePrecisionUi()
        setRegion(Region.EXTERNAL)
        setupShutter()
        setupDelete()

        binding.doneButton.setOnClickListener { openResult() }
        binding.plus180.setOnClickListener { togglePlus180() }
        binding.showLinesButton.setOnClickListener { showLinesDialog() }
        binding.chipExternal.setOnClickListener { setRegion(Region.EXTERNAL) }
        binding.chipCeiling.setOnClickListener { setRegion(Region.CEILING) }

        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(requiredPermissions())
        observeGroup()
    }

    override fun onStart() {
        super.onStart()
        startSensor()
    }

    override fun onStop() {
        super.onStop()
        orientation?.stop()
        sensorRunning = false
        deleteHandler.removeCallbacksAndMessages(null)
        jitterAz.clear()
        jitterEl.clear()
        jitterLevel = LEVEL_UNKNOWN
    }

    override fun onDestroy() {
        super.onDestroy()
        camera.stop()
    }

    // ---------- 数据与界面同步 ----------

    private fun observeGroup() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.groups.collect { groups ->
                    val group = groups.firstOrNull { it.id == groupId }
                    if (group == null) {
                        finish()
                        return@collect
                    }
                    bindGroup(group)
                }
            }
        }
    }

    private fun bindGroup(group: GroupRecord) {
        binding.groupName.text = group.name
        regionPoints = group.regionList(region)
        binding.overlay.points = regionPoints
        binding.coverageBar.submitExternal(group.external)
        updateMeta()
    }

    private fun applyHemisphere(group: GroupRecord) {
        val lat = group.lat
        binding.hintText.text = when {
            abs(lat) <= 23.5 -> getString(R.string.capture_hint_tropic)
            lat >= 0 -> getString(R.string.capture_hint_north)
            else -> getString(R.string.capture_hint_south)
        }
        binding.dirLeft.text =
            getString(if (lat >= 0) R.string.capture_dir_last_east else R.string.capture_dir_last_west)
        binding.dirRight.text =
            getString(if (lat >= 0) R.string.capture_dir_first_west else R.string.capture_dir_first_east)
        binding.coverageLabel.text = getString(
            R.string.capture_coverage,
            getString(if (lat >= 0) R.string.capture_coverage_north else R.string.capture_coverage_south),
        )
    }

    private fun setRegion(next: Region) {
        region = next
        val external = next == Region.EXTERNAL
        binding.chipExternal.setBackgroundResource(
            if (external) R.drawable.bg_chip_on else R.drawable.bg_chip_off,
        )
        binding.chipExternal.setTextColor(
            ContextCompat.getColor(this, if (external) R.color.ink else R.color.paper),
        )
        binding.chipCeiling.setBackgroundResource(
            if (external) R.drawable.bg_chip_off else R.drawable.bg_chip_on,
        )
        binding.chipCeiling.setTextColor(
            ContextCompat.getColor(this, if (external) R.color.paper else R.color.ink),
        )
        regionPoints = repository.get(groupId)?.regionList(next) ?: emptyList()
        binding.overlay.points = regionPoints
    }

    // ---------- 姿态 ----------

    private fun startSensor() {
        if (orientation == null) {
            val group = repository.get(groupId) ?: return
            orientation = OrientationSensor(
                context = this,
                declinationDeg = {
                    LocationProvider.declination(
                        group.lat,
                        group.lon,
                        group.altitude,
                        System.currentTimeMillis(),
                    )
                },
                displayRotation = { binding.overlay.display?.rotation ?: Surface.ROTATION_0 },
                onPose = ::onPose,
            )
        }
        val sensor = orientation ?: return
        if (!sensor.available) {
            if (!binding.notice.isVisible) showNotice(getString(R.string.capture_sensor_missing), false)
            return
        }
        if (!sensorRunning) sensorRunning = sensor.start()
    }

    private fun onPose(pose: Pose) {
        latestPose = pose
        sampleJitter(pose)
        if (pose.accuracy != lastAccuracy) {
            lastAccuracy = pose.accuracy
            updatePrecisionUi()
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastUiAt < POSE_THROTTLE_MS) return
        lastUiAt = now
        val level = computeJitterLevel()
        if (level != jitterLevel) {
            jitterLevel = level
            updatePrecisionUi()
        }
        updateReading(pose)
        binding.overlay.pose = pose
        binding.overlay.aimAzDeg = aimAz(pose, smoothed = true)
        binding.overlay.invalidate()
    }

    /**
     * 显示/记录的方位角：姿态读数为屏幕外法线（机身朝向读数），
     * +180° 药丸默认叠加后即后摄视轴方向（十字线所指）。
     */
    private fun aimAz(pose: Pose, smoothed: Boolean): Double {
        val base = if (smoothed) pose.smoothAzDeg else pose.frontAzDeg
        return Angles.normalize360(if (settings.plus180) base + 180.0 else base)
    }

    private fun aimEl(pose: Pose, smoothed: Boolean): Double {
        val base = if (smoothed) pose.smoothElDeg else pose.frontElDeg
        return if (settings.plus180) -base else base
    }

    private fun updateReading(pose: Pose) {
        binding.readingText.text = getString(
            R.string.capture_reading,
            Format.elevation(aimEl(pose, smoothed = true)),
            Format.azimuth(aimAz(pose, smoothed = true)),
        )
        smoothRoll = if (smoothRoll.isNaN()) pose.rollDeg else smoothRoll * 0.7 + pose.rollDeg * 0.3
        binding.rollText.text = getString(R.string.capture_roll, Format.signedDegree(smoothRoll))
    }

    private fun updateMeta() {
        val group = repository.get(groupId) ?: return
        binding.metaText.text = getString(
            R.string.capture_meta,
            Format.coordinate(group.lat, group.lon),
        )
    }

    // ---------- 精度指示（罗盘校准 + 读数抖动） ----------

    /** 把每个原始样本的机身朝向角放进滑动窗口，供抖动评估。 */
    private fun sampleJitter(pose: Pose) {
        jitterAz.addLast(pose.frontAzDeg)
        jitterEl.addLast(pose.frontElDeg)
        while (jitterAz.size > JITTER_WINDOW) jitterAz.removeFirst()
        while (jitterEl.size > JITTER_WINDOW) jitterEl.removeFirst()
    }

    /** 窗口内方位/仰角相对最新样点的极差（度）取大者定档；样本不足返回未知档。方位差走短弧以处理环绕。 */
    private fun computeJitterLevel(): Int {
        if (jitterAz.size < JITTER_WINDOW) return LEVEL_UNKNOWN
        val baseAz = jitterAz.last()
        val baseEl = jitterEl.last()
        var azMin = 0.0
        var azMax = 0.0
        var elMin = 0.0
        var elMax = 0.0
        for (i in jitterAz.indices) {
            val dAz = Angles.shortArcDelta(baseAz, jitterAz.elementAt(i))
            if (dAz < azMin) azMin = dAz
            if (dAz > azMax) azMax = dAz
            val dEl = jitterEl.elementAt(i) - baseEl
            if (dEl < elMin) elMin = dEl
            if (dEl > elMax) elMax = dEl
        }
        val jitter = maxOf(azMax - azMin, elMax - elMin)
        return when {
            jitter <= JITTER_STEADY_DEG -> LEVEL_HIGH
            jitter <= JITTER_OK_DEG -> LEVEL_MEDIUM
            else -> LEVEL_LOW
        }
    }

    /** 刷新两枚精度药丸：罗盘校准取系统精度回调（未回调前为未知），读数精度取抖动档位。 */
    private fun updatePrecisionUi() {
        val calibText = when (lastAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> R.string.capture_calib_high
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> R.string.capture_calib_medium
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> R.string.capture_calib_low
            SensorManager.SENSOR_STATUS_UNRELIABLE -> R.string.capture_calib_unreliable
            else -> R.string.capture_calib_unknown
        }
        val calibColor = when (lastAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> R.color.precision_high
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> R.color.precision_mid
            SensorManager.SENSOR_STATUS_ACCURACY_LOW,
            SensorManager.SENSOR_STATUS_UNRELIABLE -> R.color.precision_low
            else -> R.color.precision_unknown
        }
        paintPill(binding.calibPill, calibText, calibColor)

        val jitterText = when (jitterLevel) {
            LEVEL_HIGH -> R.string.capture_jitter_high
            LEVEL_MEDIUM -> R.string.capture_jitter_medium
            LEVEL_LOW -> R.string.capture_jitter_low
            else -> R.string.capture_jitter_unknown
        }
        val jitterColor = when (jitterLevel) {
            LEVEL_HIGH -> R.color.precision_high
            LEVEL_MEDIUM -> R.color.precision_mid
            LEVEL_LOW -> R.color.precision_low
            else -> R.color.precision_unknown
        }
        paintPill(binding.jitterPill, jitterText, jitterColor)
    }

    private fun paintPill(view: TextView, textRes: Int, colorRes: Int) {
        view.setText(textRes)
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    // ---------- 快门（短按连线 / 长按经地平线断开） ----------

    private fun setupShutter() {
        var downAt = 0L
        var canceled = false
        binding.shutter.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    canceled = false
                    view.alpha = 0.6f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!canceled && !insideView(view, event)) {
                        canceled = true
                        view.alpha = 1f
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.alpha = 1f
                    if (!canceled) {
                        // 自行处理了按下/抬起，故需补一次 performClick，
                        // 否则 TalkBack 与开关控制等辅助服务认为该按钮不可用
                        view.performClick()
                        onShutter(SystemClock.uptimeMillis() - downAt >= LONG_PRESS_MS)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    canceled = true
                    true
                }

                else -> false
            }
        }
    }

    /** 事件坐标是否仍在视图范围内（滑出按钮即取消本次操作）。 */
    private fun insideView(view: View, event: MotionEvent): Boolean =
        event.x >= 0f && event.y >= 0f && event.x <= view.width && event.y <= view.height

    private fun onShutter(longPress: Boolean) {
        if (capturing) return
        val group = repository.get(groupId) ?: return
        if (region == Region.CEILING && longPress) {
            toast(R.string.capture_ceiling_longpress)
            return
        }
        val pose = latestPose
        if (pose == null) {
            toast(R.string.capture_no_pose)
            return
        }
        // 记录瞬时融合值；仰角裁到需求域 0..90
        val az = aimAz(pose, smoothed = false)
        val el = aimEl(pose, smoothed = false).coerceIn(0.0, 90.0)
        val seq = group.regionList(region).size + 1
        val meta = PhotoMeta(
            groupName = group.name,
            photoFolder = repository.photoFolderFor(group),
            region = region,
            seq = seq,
            azDeg = az,
            elDeg = el,
            gapAfter = longPress,
            lat = group.lat,
            lon = group.lon,
            takenAt = System.currentTimeMillis(),
        )
        capturing = true
        binding.shutter.alpha = 0.4f
        lifecycleScope.launch {
            val imageCapture = camera.imageCapture
            val uri = if (imageCapture != null) photoStore.capture(imageCapture, meta) else null
            repository.appendPoint(
                groupId,
                region,
                PointRecord(az = az, el = el, photoUri = uri?.toString(), takenAt = meta.takenAt),
                viaHorizon = longPress,
            )
            capturing = false
            binding.shutter.alpha = 1f
            if (uri == null && imageCapture != null) toast(R.string.capture_photo_failed)
        }
    }

    // ---------- 删除（长按删十字线右侧最近点） ----------

    private fun setupDelete() {
        var candidate = -1
        val deleteRunnable = Runnable {
            val index = candidate
            candidate = -1
            if (index >= 0) {
                repository.deletePoint(groupId, region, index)
                toast(getString(R.string.capture_deleted, index + 1))
            }
        }
        binding.deleteButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 0.6f
                    candidate = findDeleteCandidate()
                    if (candidate < 0) {
                        toast(R.string.capture_delete_no_candidate)
                    } else {
                        deleteHandler.postDelayed(deleteRunnable, LONG_PRESS_MS)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (candidate >= 0 && !insideView(view, event)) {
                        deleteHandler.removeCallbacks(deleteRunnable)
                        candidate = -1
                        view.alpha = 1f
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.alpha = 1f
                    deleteHandler.removeCallbacks(deleteRunnable)
                    candidate = -1
                    // 同快门：自行处理了触摸，需补 performClick 供辅助服务识别
                    view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    deleteHandler.removeCallbacks(deleteRunnable)
                    candidate = -1
                    true
                }

                else -> false
            }
        }
    }

    /** 当前分区内「十字线右侧、离当前方位最近」的点：顺时针角距落在 (0°, 180°) 内取最小者。 */
    private fun findDeleteCandidate(): Int {
        val group = repository.get(groupId) ?: return -1
        val pose = latestPose ?: return -1
        val azNow = aimAz(pose, smoothed = false)
        var best = -1
        var bestDelta = Double.MAX_VALUE
        group.regionList(region).forEachIndexed { index, point ->
            val delta = Angles.normalize360(point.az - azNow)
            if (delta > 1e-6 && delta < 180.0 - 1e-6 && delta < bestDelta) {
                bestDelta = delta
                best = index
            }
        }
        return best
    }

    // ---------- 设置与顶部按钮 ----------

    private fun togglePlus180() {
        settings.plus180 = !settings.plus180
        updatePlus180Ui()
        latestPose?.let { pose ->
            updateReading(pose)
            binding.overlay.aimAzDeg = aimAz(pose, smoothed = true)
            binding.overlay.invalidate()
        }
    }

    private fun updatePlus180Ui() {
        binding.plus180.setPillSelected(settings.plus180)
    }

    private fun applyLineSettings() {
        binding.overlay.show = settings.lines()
    }

    private fun showLinesDialog() {
        val labels = arrayOf(
            getString(R.string.line_summer),
            getString(R.string.line_equinox),
            getString(R.string.line_winter),
            getString(R.string.line_today),
            getString(R.string.line_segments),
            getString(R.string.line_horizon),
            getString(R.string.line_vertical),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.lines_title)
            .setMultiChoiceItems(labels, settings.lineChecked()) { _, which, checked ->
                settings.setLineChecked(which, checked)
                applyLineSettings()
            }
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun openResult() {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(Extras.GROUP_ID, groupId))
        finish()
    }

    // ---------- 权限与相机 ----------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        binding.notice.isVisible = false
        camera.start(this, binding.preview) { ok ->
            if (!ok) {
                if (!binding.notice.isVisible) showNotice(getString(R.string.capture_camera_failed), false)
            }
        }
    }

    private fun showCameraNotice() {
        showNotice(getString(R.string.capture_camera_needed), true)
    }

    private fun showNotice(text: String, requestOnTap: Boolean) {
        binding.notice.text = text
        binding.notice.isVisible = true
        binding.notice.isClickable = requestOnTap
        binding.notice.setOnClickListener(
            if (requestOnTap) {
                View.OnClickListener { permissionLauncher.launch(requiredPermissions()) }
            } else {
                null
            },
        )
    }

    private fun toast(resId: Int) = toast(getString(resId))

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}