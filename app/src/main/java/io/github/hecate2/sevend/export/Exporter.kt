package io.github.hecate2.sevend.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.hecate2.sevend.R
import io.github.hecate2.sevend.core.Angles
import io.github.hecate2.sevend.core.DailySunlight
import io.github.hecate2.sevend.core.Solar
import io.github.hecate2.sevend.data.GroupRecord
import io.github.hecate2.sevend.data.PointRecord
import io.github.hecate2.sevend.util.Format
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 数据导出（自救通道）：CSV 存 Downloads/7D/，参考图 PNG 存相册 Pictures/7D/。
 *
 * Android 10 及以上走 MediaStore；9 及以下写公共目录后扫描（无权限或失败时回退应用私有目录）。
 */
object Exporter {

    /** location 为可读的保存位置，error 非空即失败。 */
    data class Outcome(val location: String?, val error: String?)

    // ---------------- CSV ----------------

    /**
     * 导出 CSV：组信息、点列（分区、序号、方位、仰角、与左邻点连线、拍摄时间）与
     * 所选日期的逐分钟可见性。
     */
    fun exportCsv(
        context: Context,
        group: GroupRecord,
        date: LocalDate,
        daily: DailySunlight,
        modeLabel: String,
    ): Outcome {
        val text = "\uFEFF" + buildCsv(context, group, modeLabel, date, daily)
        val name = fileName(context, group.name, "csv")
        return publish(
            context = context,
            bytes = text.toByteArray(Charsets.UTF_8),
            displayName = name,
            mime = "text/csv",
            mediaRelativeDir = "${Environment.DIRECTORY_DOWNLOADS}/7D",
            mediaCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            },
            legacySubDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "7D",
            ),
            locationLabel = context.getString(R.string.export_location_downloads),
            scan = false,
        )
    }

    private fun buildCsv(
        context: Context,
        group: GroupRecord,
        modeLabel: String,
        date: LocalDate,
        daily: DailySunlight,
    ): String {
        val res = context.resources
        val sb = StringBuilder()
        sb.append(res.getString(R.string.export_csv_title)).append('\n')
        sb.append(res.getString(R.string.export_csv_row_group_name)).append(',')
            .append(escape(group.name)).append('\n')
        sb.append(res.getString(R.string.export_csv_row_latitude)).append(',')
            .append(group.lat).append('\n')
        sb.append(res.getString(R.string.export_csv_row_longitude)).append(',')
            .append(group.lon).append('\n')
        sb.append(res.getString(R.string.export_csv_row_timezone)).append(',')
            .append(group.zoneId).append('\n')
        sb.append(res.getString(R.string.export_csv_row_mode)).append(',')
            .append(escape(modeLabel)).append('\n')
        sb.append(res.getString(R.string.export_csv_row_date)).append(',')
            .append(date).append('\n')
        sb.append(res.getString(R.string.export_csv_row_direct_duration)).append(',')
            .append(Format.durationLong(res, daily.directMinutes)).append('\n')
        sb.append(res.getString(R.string.export_csv_row_direct_minutes)).append(',')
            .append(daily.directMinutes).append('\n')
        val polarDay = res.getString(R.string.export_polar_day_short)
        sb.append(res.getString(R.string.export_csv_row_sunrise)).append(',')
            .append(daily.sunriseMinute?.let(Format::clockMinute) ?: polarDay).append('\n')
        sb.append(res.getString(R.string.export_csv_row_sunset)).append(',')
            .append(daily.sunsetMinute?.let(Format::clockMinute) ?: polarDay).append('\n')
        sb.append(res.getString(R.string.export_csv_row_all_from_gap)).append(',')
            .append(res.getString(if (daily.allFromGap) R.string.export_yes else R.string.export_no))
            .append('\n')
        sb.append('\n')

        sb.append(res.getString(R.string.export_csv_section_points)).append('\n')
        sb.append(res.getString(R.string.export_csv_points_header)).append('\n')
        appendPoints(res, sb, res.getString(R.string.export_region_external), group.external, group.zoneId)
        appendPoints(res, sb, res.getString(R.string.export_region_ceiling), group.ceiling, group.zoneId)
        sb.append('\n')

        sb.append(res.getString(R.string.export_csv_section_visibility)).append('\n')
        sb.append(res.getString(R.string.export_csv_visibility_header)).append('\n')
        for (m in 0 until MINUTES_PER_DAY) {
            val visible = daily.visibleIntervals.any { m >= it.first && m <= it.last }
            sb.append(m).append(',').append(Format.clockMinute(m))
                .append(',').append(if (visible) '1' else '0').append('\n')
        }
        return sb.toString()
    }

    private fun appendPoints(
        res: Resources,
        sb: StringBuilder,
        region: String,
        points: List<PointRecord>,
        zoneId: String,
    ) {
        points.forEachIndexed { i, p ->
            val seg = when {
                i == 0 -> ""
                points[i - 1].gapAfter -> res.getString(R.string.export_seg_via_horizon)
                else -> res.getString(R.string.export_seg_direct)
            }
            sb.append(region).append(',').append(i + 1)
                .append(',').append(String.format(Locale.US, "%.1f", p.az))
                .append(',').append(String.format(Locale.US, "%.1f", p.el))
                .append(',').append(seg)
                .append(',').append(if (p.takenAt > 0) Format.dateTime(p.takenAt, zoneId) else "")
                .append('\n')
        }
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    // ---------------- 图片 ----------------

    /**
     * 导出参考图：天际线极坐标图（含太阳轨迹三色弧）+ 关键结论，PNG 存相册。
     */
    fun exportImage(
        context: Context,
        group: GroupRecord,
        dateLabel: String,
        modeLabel: String,
        daily: DailySunlight,
        winterMinutes: Int?,
        gbWindowMinutes: Int?,
    ): Outcome {
        val bitmap = renderImage(context, group, dateLabel, modeLabel, daily, winterMinutes, gbWindowMinutes)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        val name = fileName(context, group.name, "png")
        return publish(
            context = context,
            bytes = bytes,
            displayName = name,
            mime = "image/png",
            mediaRelativeDir = "${Environment.DIRECTORY_PICTURES}/7D",
            mediaCollection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            legacySubDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "7D",
            ),
            locationLabel = context.getString(R.string.export_location_pictures),
            scan = true,
        )
    }

    private fun renderImage(
        context: Context,
        group: GroupRecord,
        dateLabel: String,
        modeLabel: String,
        daily: DailySunlight,
        winterMinutes: Int?,
        gbWindowMinutes: Int?,
    ): Bitmap {
        val w = 1080
        val h = 1400
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF000000.toInt())

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 42f
            isFakeBoldText = true
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8E8E93.toInt()
            textSize = 28f
        }
        val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCAC2D1.toInt()
            textSize = 28f
        }
        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 42f
            isFakeBoldText = true
        }

        val res = context.resources
        canvas.drawText(res.getString(R.string.export_png_title, group.name), 56f, 100f, titlePaint)
        canvas.drawText(
            "${Format.coordinate(group.lat, group.lon)} · $modeLabel · ${Format.dateTime(group.updatedAt, group.zoneId)}",
            56f, 150f, textPaint,
        )

        drawPolarChart(res, canvas, 540f, 580f, 340f, group)

        var y = 1010f
        val directLine = res.getString(
            R.string.export_png_direct, dateLabel, Format.durationLong(res, daily.directMinutes),
        )
        canvas.drawText(directLine, 56f, y, bigPaint)
        if (daily.allFromGap) {
            canvas.drawText(
                res.getString(R.string.export_png_gap_note),
                56f + bigPaint.measureText(directLine) + 8f, y, moonPaint,
            )
        }
        y += 56f
        val sunrise = daily.sunriseMinute
        val sunLine = when {
            sunrise == null && daily.directMinutes == 0 -> res.getString(R.string.result_polar_night)
            sunrise == null -> res.getString(R.string.result_polar_day)
            else -> res.getString(
                R.string.result_sunrise_sunset,
                Format.clockMinute(sunrise),
                Format.clockMinute(daily.sunsetMinute ?: 0),
            )
        }
        canvas.drawText(sunLine, 56f, y, textPaint)
        y += 52f
        if (winterMinutes != null) {
            canvas.drawText(
                res.getString(R.string.export_png_winter, Format.durationLong(res, winterMinutes)),
                56f, y, textPaint,
            )
            y += 52f
        }
        if (gbWindowMinutes != null) {
            canvas.drawText(
                res.getString(R.string.export_png_gb, Format.durationLong(res, gbWindowMinutes)),
                56f, y, textPaint,
            )
            y += 52f
        }

        textPaint.textSize = 24f
        canvas.drawText(res.getString(R.string.export_png_footer), 56f, h - 40f, textPaint)
        return bitmap
    }

    private fun drawPolarChart(
        res: Resources,
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        group: GroupRecord,
    ) {
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x3A8E8E93
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8E8E93.toInt()
            textSize = 26f
        }

        // 主方向半圆（北半球为南半圆 90–270 度）的浅底
        val sector = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF121214.toInt()
            style = Paint.Style.FILL
        }
        if (group.lat >= 0) {
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 0f, 180f, true, sector)
        } else {
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 180f, 180f, true, sector)
        }

        // 仰角环：0（地平）、30、60 度
        for (ring in intArrayOf(0, 30, 60)) {
            val r = radius * (90 - ring) / 90f
            canvas.drawCircle(cx, cy, r, grid)
        }
        // 方位辐条
        for (az in intArrayOf(0, 90, 180, 270)) {
            val rad = (az - 90) * PI / 180.0
            canvas.drawLine(
                cx, cy,
                cx + (radius * cos(rad)).toFloat(), cy + (radius * sin(rad)).toFloat(),
                grid,
            )
        }
        label.textAlign = Paint.Align.CENTER
        canvas.drawText(res.getString(R.string.compass_north), cx, cy - radius - 12f, label)
        canvas.drawText(res.getString(R.string.compass_south), cx, cy + radius + 34f, label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(res.getString(R.string.compass_west), cx - radius - 8f, cy + 10f, label)
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(res.getString(R.string.compass_east), cx + radius + 8f, cy + 10f, label)

        // 太阳轨迹三色弧：当地冬至、春秋分、当地夏至
        val winterDecl = if (group.lat >= 0) -23.44 else 23.44
        drawSunArc(canvas, cx, cy, radius, group.lat, winterDecl, 0xFF378ADD.toInt())
        drawSunArc(canvas, cx, cy, radius, group.lat, 0.0, 0xFFE24B4A.toInt())
        drawSunArc(canvas, cx, cy, radius, group.lat, -winterDecl, 0xFFEF9F27.toInt())

        // 天际线：外部白色实线与点、天花板月灰虚线
        drawSkyline(canvas, cx, cy, radius, group.external, 0xFFFFFFFF.toInt(), dashed = false)
        drawSkyline(canvas, cx, cy, radius, group.ceiling, 0xFFCAC2D1.toInt(), dashed = true)
    }

    private fun drawSunArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        latDeg: Double,
        declDeg: Double,
        color: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 3f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        val path = android.graphics.Path()
        var started = false
        var ha = -180
        while (ha <= 180) {
            val pos = Solar.positionFrom(latDeg, declDeg, ha.toDouble(), refraction = false)
            if (pos.elevationDeg < Solar.SUNRISE_THRESHOLD_DEG) {
                started = false
            } else {
                val point = project(cx, cy, radius, pos.azimuthDeg, pos.elevationDeg)
                if (!started) {
                    path.moveTo(point[0], point[1])
                    started = true
                } else {
                    path.lineTo(point[0], point[1])
                }
            }
            ha += 2
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSkyline(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        points: List<io.github.hecate2.sevend.data.PointRecord>,
        color: Int,
        dashed: Boolean,
    ) {
        if (points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = if (dashed) 4f else 6f
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            if (dashed) pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val d = Angles.shortArcDelta(a.az, b.az)
            if (d == 0.0) continue
            val steps = ceil(kotlin.math.abs(d) / 2.0).toInt().coerceAtLeast(1)
            skylinePath.reset()
            for (k in 0..steps) {
                val t = k.toDouble() / steps
                val az = Angles.normalize360(a.az + d * t)
                val el = when {
                    a.gapAfter && k in 1 until steps -> 0.0
                    else -> a.el + (b.el - a.el) * t
                }
                val p = project(cx, cy, radius, az, el.coerceIn(0.0, 90.0))
                if (k == 0) skylinePath.moveTo(p[0], p[1]) else skylinePath.lineTo(p[0], p[1])
            }
            canvas.drawPath(skylinePath, paint)
        }
        for (p in points) {
            val xy = project(cx, cy, radius, p.az, p.el.coerceIn(0.0, 90.0))
            canvas.drawCircle(xy[0], xy[1], 5f, dot)
        }
    }

    private fun project(cx: Float, cy: Float, radius: Float, azDeg: Double, elDeg: Double): FloatArray {
        val r = radius * (90.0 - elDeg.coerceIn(0.0, 90.0)) / 90.0
        val rad = (azDeg - 90.0) * PI / 180.0
        return floatArrayOf(
            cx + (r * cos(rad)).toFloat(),
            cy + (r * sin(rad)).toFloat(),
        )
    }

    private val skylinePath = android.graphics.Path()

    // ---------------- 落盘 ----------------

    private fun publish(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mime: String,
        mediaRelativeDir: String,
        mediaCollection: Uri,
        legacySubDir: File,
        locationLabel: String,
        scan: Boolean,
    ): Outcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishMediaStore(context, bytes, displayName, mime, mediaRelativeDir, mediaCollection)
                ?.let { return Outcome("$locationLabel/$displayName", null) }
        } else if (hasWritePermission(context)) {
            try {
                if (legacySubDir.exists() || legacySubDir.mkdirs()) {
                    val dest = File(legacySubDir, displayName)
                    dest.writeBytes(bytes)
                    if (scan) {
                        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
                    }
                    return Outcome("$locationLabel/$displayName", null)
                }
            } catch (_: Exception) {
                // 落入应用私有目录回退
            }
        }
        return fallbackToAppDir(context, bytes, displayName)
    }

    /** 返回非空即成功。 */
    private fun publishMediaStore(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mime: String,
        relativeDir: String,
        collection: Uri,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun fallbackToAppDir(context: Context, bytes: ByteArray, displayName: String): Outcome = try {
        val dir = File(context.getExternalFilesDir(null), "7D")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("mkdir failed")
        File(dir, displayName).writeBytes(bytes)
        Outcome("Android/data/${context.packageName}/files/7D/$displayName", null)
    } catch (_: Exception) {
        Outcome(null, "save failed")
    }

    private fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun fileName(context: Context, groupName: String, ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return context.getString(R.string.export_file_name, sanitize(groupName), stamp, ext)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(24).ifBlank { "group" }

    private const val MINUTES_PER_DAY = 1440
}