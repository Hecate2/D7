package io.github.hecate2.D7.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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