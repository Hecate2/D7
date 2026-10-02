package io.github.hecate2.D7.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream

/** 组名转安全的文件名/目录名片段：替换非法字符、限长 24、空名回落为 group。 */
fun sanitizeGroupName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(24).ifBlank { "group" }

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