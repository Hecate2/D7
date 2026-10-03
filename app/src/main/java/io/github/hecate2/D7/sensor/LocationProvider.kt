package io.github.hecate2.D7.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.time.ZoneId

/** 一次定位结果快照。 */
data class FixLocation(
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val zoneId: String,
    /** 水平精度（米）；0 表示未知。 */
    val accuracyMeters: Float,
    /** 定位时刻（UTC 毫秒）。 */
    val timeMillis: Long,
)

/** 设备定位能力快照：供界面显示「GPS / 网络 / 系统融合是否可用」与当前精度。 */
data class LocationCapability(
    val gpsEnabled: Boolean,
    val networkEnabled: Boolean,
    /** 系统融合定位（fused provider，API 31+）是否可用。 */
    val fusedAvailable: Boolean,
    /** 5 分钟内的最近定位；为空表示尚未取得可用结果。 */
    val lastFix: FixLocation?,
) {
    /** GPS、网络、融合全部不可用（如系统定位开关被关）。 */
    val noneEnabled: Boolean get() = !gpsEnabled && !networkEnabled && !fusedAvailable
}

/**
 * 系统融合定位：只用 Android 自带的 [LocationManager]，对多个 provider（系统融合 / 网络 / GPS）
 * 并发取位，不引入任何第三方定位 SDK（零额外体积、零 key）。
 *
 * - [warmUp]：应用启动即调用；对可用 provider 挂低频持续监听，静默维护内存里的最新结果。
 *   无定位权限、无可用 provider 时安静跳过，不打扰用户也不崩溃。
 * - [lastFresh]：取新鲜缓存（默认 5 分钟内），命中即秒回，用户不必干等。
 * - [requestSingleUpdate]：多 provider 并发请求；精度够好立即返回，超时快速失败（默认 6 秒）。
 * - [capability]：查询 GPS / 网络 / 融合的开关状态与当前精度，供界面展示。
 */
class LocationProvider(private val context: Context) {

    /** 后台预热维护的最新定位（含 lastKnown 种子）。 */
    private var latest: FixLocation? = null

    /** 预热监听器；非空表示已挂上持续监听。 */
    private var warmListener: LocationListener? = null

    private val manager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /** 启动即尝试后台定位；无权限时静默降级为空操作。可重复调用（拿到权限后再调一次即可生效）。 */
    @SuppressLint("MissingPermission")
    fun warmUp() {
        if (warmListener != null || !hasPermission()) return
        seedLastKnown()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = absorb(location)

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        var registered = false
        for (provider in candidates()) {
            try {
                manager.requestLocationUpdates(provider, WARM_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        if (registered) warmListener = listener
    }

    /**
     * 注销预热监听：界面退到后台时调用，防止 GPS 持续耗电与 LocationManager 持有
     * Activity 上下文造成泄漏；之后可再次 [warmUp]。
     */
    fun shutdown() {
        val listener = warmListener ?: return
        warmListener = null
        try {
            manager.removeUpdates(listener)
        } catch (_: Exception) {
        }
    }

    /** 新鲜缓存：默认只认 5 分钟内的定位。 */
    fun lastFresh(maxAgeMillis: Long = FRESH_AGE_MS): FixLocation? {
        val fix = latest ?: return null
        return fix.takeIf { System.currentTimeMillis() - it.timeMillis <= maxAgeMillis }
    }

    /**
     * 单次定位：对全部可用 provider 并发请求。
     * 精度达标（≤ [GOOD_ACCURACY_M] 米）立即回调；已有结果且等满 [SOFT_WAIT_MS] 就采用最好者；
     * 其余情形等到 [timeoutMillis] 超时，超时若有结果则仍回调，否则回调失败。
     * 无权限或没有任何 provider 可注册时立即失败。
     */
    @SuppressLint("MissingPermission")
    fun requestSingleUpdate(
        onResult: (FixLocation) -> Unit,
        onTimeout: () -> Unit = {},
        timeoutMillis: Long = 6_000L,
    ) {
        if (!hasPermission()) {
            onTimeout()
            return
        }
        val callHandler = Handler(Looper.getMainLooper())
        var finished = false
        var best: FixLocation? = null
        lateinit var listener: LocationListener
        fun finish(action: () -> Unit) {
            if (finished) return
            finished = true
            callHandler.removeCallbacksAndMessages(null)
            try {
                manager.removeUpdates(listener)
            } catch (_: Exception) {
            }
            action()
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                absorb(location)
                val fix = location.toFix()
                if (best == null || isBetter(fix, best!!)) best = fix
                if (fix.accuracyMeters in 0.1f..GOOD_ACCURACY_M) finish { onResult(fix) }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        var registered = false
        for (provider in candidates()) {
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        if (!registered) {
            onTimeout()
            return
        }
        callHandler.postDelayed(
            {
                val fix = best ?: return@postDelayed
                finish { onResult(fix) }
            },
            SOFT_WAIT_MS,
        )
        callHandler.postDelayed(
            {
                val fix = best
                finish { if (fix != null) onResult(fix) else onTimeout() }
            },
            timeoutMillis,
        )
    }

    /** 当前定位能力（provider 开关与新鲜结果），用于界面显示。 */
    fun capability(): LocationCapability {
        fun enabled(provider: String): Boolean = try {
            manager.isProviderEnabled(provider)
        } catch (_: IllegalArgumentException) {
            false
        }
        return LocationCapability(
            gpsEnabled = enabled(LocationManager.GPS_PROVIDER),
            networkEnabled = enabled(LocationManager.NETWORK_PROVIDER),
            fusedAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                enabled(LocationManager.FUSED_PROVIDER),
            lastFix = lastFresh(),
        )
    }

    /** 优先系统融合（API 31+），再网络、再 GPS；不存在的 provider 注册时会抛异常并被忽略。 */
    private fun candidates(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list += LocationManager.FUSED_PROVIDER
        list += LocationManager.NETWORK_PROVIDER
        list += LocationManager.GPS_PROVIDER
        return list
    }

    /** 用 lastKnown 尽快给缓存一个种子；旧数据不会覆盖更新记录。 */
    @SuppressLint("MissingPermission")
    private fun seedLastKnown() {
        for (provider in candidates()) {
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (location != null) absorb(location)
        }
    }

    /** 采用更新的定位；精度未知（0）视为比已知精度差。 */
    private fun isBetter(candidate: FixLocation, current: FixLocation): Boolean = when {
        candidate.accuracyMeters <= 0f -> false
        current.accuracyMeters <= 0f -> true
        else -> candidate.accuracyMeters < current.accuracyMeters
    }

    private fun absorb(location: Location) {
        val current = latest
        if (current == null || location.time >= current.timeMillis) latest = location.toFix()
    }

    private fun Location.toFix(): FixLocation = FixLocation(
        lat = latitude,
        lon = longitude,
        altitude = if (hasAltitude()) altitude else 0.0,
        zoneId = ZoneId.systemDefault().id,
        accuracyMeters = if (hasAccuracy()) accuracy else 0f,
        timeMillis = time,
    )

    companion object {
        /** 预热监听的刷新间隔（毫秒）。 */
        private const val WARM_INTERVAL_MS = 10_000L

        /** 缓存有效期：5 分钟内的定位可直接采用。 */
        private const val FRESH_AGE_MS = 5 * 60_000L

        /** 精度好于该值（米）即认为够好，立即返回。 */
        private const val GOOD_ACCURACY_M = 100f

        /** 软等待：已有结果且等满该时长（毫秒），就用当前最好结果。 */
        private const val SOFT_WAIT_MS = 2_500L

        /**
         * 当地磁偏角（度，东偏为正）。内置世界地磁模型，无需联网。
         *
         * [GeomagneticField] 的构造函数每次都要跑一遍完整的球谐展开（几十项级数），
         * 而采集页以传感器频率（约 20Hz）调用本函数——同一组坐标下结果在半小时内
         * 变化远小于传感器误差，故按坐标 + 时效做一层缓存，逐帧不再新建对象。
         */
        @Synchronized
        fun declination(lat: Double, lon: Double, altitude: Double, timeMillis: Long): Double {
            val cache = declinationCache
            if (cache.lat == lat && cache.lon == lon && cache.alt == altitude &&
                timeMillis - cache.at in 0 until DECLINATION_TTL_MS
            ) {
                return cache.value
            }
            val value = GeomagneticField(
                lat.toFloat(), lon.toFloat(), altitude.toFloat(), timeMillis,
            ).declination.toDouble()
            cache.lat = lat
            cache.lon = lon
            cache.alt = altitude
            cache.at = timeMillis
            cache.value = value
            return value
        }

        /**
         * 磁偏角缓存有效期（毫秒）。取 30 秒：既把约 20Hz 的传感器回调压到每 30 秒
         * 才重算一次，又保证跨采集会话时不会长时间复用旧值（换城市测新房子时尤其重要）。
         */
        private const val DECLINATION_TTL_MS = 30_000L

        private val declinationCache = DeclinationCache()

        /** 可变缓存盒；四个字段必须一起读写，故只在 @Synchronized 的 declination 里触碰。 */
        private class DeclinationCache {
            var lat = Double.NaN
            var lon = Double.NaN
            var alt = Double.NaN
            var at = 0L
            var value = 0.0
        }
    }
}