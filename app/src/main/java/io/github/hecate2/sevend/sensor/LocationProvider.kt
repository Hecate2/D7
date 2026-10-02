package io.github.hecate2.sevend.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import java.time.ZoneId

/** 一次定位结果快照。 */
data class FixLocation(
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val zoneId: String,
)

/**
 * 一次性 GPS 定位：只使用 LocationManager 的 GPS provider，
 * 不申请网络权限、不引入融合定位 SDK。
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /** 最近一次已知位置（可能为空）。 */
    @SuppressLint("MissingPermission")
    fun lastKnown(): FixLocation? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return location?.toFix()
    }

    /** 请求单次定位；成功回调在主线程。 */
    @SuppressLint("MissingPermission")
    fun requestSingleUpdate(onResult: (FixLocation) -> Unit, onTimeout: () -> Unit = {}) {
        if (!hasPermission()) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                onResult(location.toFix())
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                manager.removeUpdates(this)
                onTimeout()
            }
        }
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            onTimeout()
        } catch (_: IllegalArgumentException) {
            onTimeout()
        }
    }

    private fun Location.toFix(): FixLocation = FixLocation(
        lat = latitude,
        lon = longitude,
        altitude = if (hasAltitude()) altitude else 0.0,
        zoneId = ZoneId.systemDefault().id,
    )

    companion object {
        /** 当地磁偏角（度，东偏为正）。内置世界地磁模型，无需联网。 */
        fun declination(lat: Double, lon: Double, altitude: Double, timeMillis: Long): Double =
            GeomagneticField(lat.toFloat(), lon.toFloat(), altitude.toFloat(), timeMillis).declination.toDouble()
    }
}