package io.github.hecate2.D7.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** 组名转安全的文件名/目录名片段：替换非法字符、限长 24、空名回落为 group。 */
fun sanitizeGroupName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(24).ifBlank { "group" }

/**
 * 删除一张照片，返回是否成功。content URI 走 MediaStore（失败返回 0 而非抛异常，
 * 必须看返回值）；file URI（低版本公共目录或私有目录回退）直接删文件。
 *
 * 「照片已经不在了」也归入失败：`delete` 对「本来就没有这条」与「没权限」一律返回 0，
 * 分不开。调用方要是想报错，得自己接受会有假警报——用户在相册里手动清过图就撞上。
 * 删组与删单点共用这一份，语义必须一致，所以这里不猜、只如实返回。
 */
fun deletePhoto(context: Context, uri: Uri): Boolean = if (uri.scheme == "file") {
    val file = uri.path?.let(::File) ?: return true
    !file.exists() || file.delete()
} else {
    runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}

/**
 * 删除一个拍摄点带的照片。**uri 为空是常态，不是异常**：「不拍照」模式录的点、结果页
 * 批量编辑新增的点、继承不到照片的点，photoUri 本来就是空的，直接跳过。
 *
 * 删不掉不提示，理由见 [deletePhoto]；调用方在自己的作用域里 launch 它即可。
 */
suspend fun deletePointPhoto(context: Context, uri: String?) {
    if (uri.isNullOrEmpty()) return
    val target = Uri.parse(uri)
    runCatching { withContext(Dispatchers.IO) { deletePhoto(context, target) } }
}

/**
 * 降采样解码一张图，最长边不超过 [maxPx]。URI 失效（照片被外部清理、条目已不在相册）
 * 时返回 null，由调用方决定显示占位框还是提示。
 *
 * 缩略图与点开看大图共用这一份：两处的差别只有 [maxPx]。按**最长边**降采样而不是宽度：
 * 竖拍照片的高远大于宽，只看宽度会解出一张高得多的图，大图尤其吃亏。
 *
 * 必须在 IO 线程调用：读流与解码都是真 IO 与 CPU。
 */
fun decodeDownsampled(context: Context, uri: Uri, maxPx: Int): Bitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) {
        null
    } else {
        var sample = 1
        while (longest / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
} catch (_: Exception) {
    null
}

/**
 * Android 10+ 的 MediaStore 发布流程：insert（IS_PENDING=1）→ 写入 → 解除 pending；
 * 任一步失败则删除条目并返回 null。
 */
fun publishPendingMediaStore(
    context: Context,
    collection: Uri,
    displayName: String,
    mime: String,
    relativeDir: String,
    write: (OutputStream) -> Unit,
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
        resolver.openOutputStream(uri)?.use(write) ?: run {
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