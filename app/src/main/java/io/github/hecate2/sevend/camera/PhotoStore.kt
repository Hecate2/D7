package io.github.hecate2.sevend.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import io.github.hecate2.sevend.data.Region
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** 一次拍摄的元数据，随照片写入 EXIF 并同时进入组数据。 */
data class PhotoMeta(
    val groupName: String,
    val region: Region,
    /** 分区内拍摄序号，从 1 起。 */
    val seq: Int,
    val azDeg: Double,
    val elDeg: Double,
    val gapAfter: Boolean,
    val lat: Double,
    val lon: Double,
    val takenAt: Long,
)

/**
 * 照片管线：快门 → cacheDir 临时文件 → EXIF 写入角度元数据与 GPS →
 * 发布到系统相册 Pictures/7D/<组名>/（Android 10 及以上走 MediaStore IS_PENDING 流程，
 * Android 9 及以下写公共目录后扫描；失败回退应用私有 captures/）。
 * 照片留在系统相册是本应用的核心交互，不在私有目录另存副本。
 */
class PhotoStore(private val context: Context) {

    /** 拍照并发布，返回照片的 Uri；失败返回 null（角度数据仍会被记录）。 */
    suspend fun capture(imageCapture: ImageCapture, meta: PhotoMeta): Uri? {
        val temp = File(context.cacheDir, "shot_${meta.takenAt}_${meta.seq}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(temp).build()
        val saved = suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }
        if (!saved) {
            temp.delete()
            return null
        }
        writeExif(temp, meta)
        val uri = publish(temp, meta)
        temp.delete()
        return uri
    }

    private fun writeExif(file: File, meta: PhotoMeta) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val json = JSONObject().apply {
                put("app", "7D")
                put("group", meta.groupName)
                put("region", meta.region.name)
                put("seq", meta.seq)
                put("az", meta.azDeg)
                put("el", meta.elDeg)
                put("gap", meta.gapAfter)
                put("takenAt", meta.takenAt)
            }
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "7D " + json.toString())
            exif.setLatLong(meta.lat, meta.lon)
            exif.setAttribute(
                ExifInterface.TAG_DATETIME,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(meta.takenAt)),
            )
            exif.saveAttributes()
        } catch (_: Exception) {
            // EXIF 写失败不影响照片与组数据
        }
    }

    private fun publish(temp: File, meta: PhotoMeta): Uri? {
        val displayName = buildDisplayName(meta)
        val folder = sanitize(meta.groupName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(temp, displayName, folder)
        } else {
            publishLegacy(temp, displayName, folder)
        }
    }

    private fun publishToMediaStore(temp: File, displayName: String, folder: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/7D/$folder",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                resolver.delete(uri, null, null)
                return null
            }
            out.use { sink -> temp.inputStream().use { it.copyTo(sink) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Android 9 及以下：写公共 Pictures 目录并用媒体扫描登记；无权限或失败回退应用私有目录。 */
    private fun publishLegacy(temp: File, displayName: String, folder: String): Uri? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            try {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "7D/$folder",
                )
                if (publicDir.exists() || publicDir.mkdirs()) {
                    val dest = File(publicDir, displayName)
                    temp.copyTo(dest, overwrite = true)
                    var scanned: Uri? = null
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(dest.absolutePath),
                        arrayOf("image/jpeg"),
                    ) { _, uri -> scanned = uri }
                    return scanned ?: Uri.fromFile(dest)
                }
            } catch (_: Exception) {
                // 落入私有目录回退
            }
        }
        return try {
            val privateDir = File(context.filesDir, "captures/$folder")
            if (!privateDir.exists() && !privateDir.mkdirs()) return null
            val dest = File(privateDir, displayName)
            temp.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildDisplayName(meta: PhotoMeta): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(meta.takenAt))
        return "7D_${sanitize(meta.groupName)}_${meta.region.name}_${meta.seq}_$stamp.jpg"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(24).ifBlank { "group" }
}