package io.github.hecate2.D7

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.hecate2.D7.data.GroupRepository

/** 仪器测试公共助手：清库、轮询等待、往相册插入/查询测试图片。 */
object TestSupport {

    /** 清空全部照片组（同时落盘），保证用例之间互不影响。 */
    fun deleteAllGroups(repository: GroupRepository) {
        repository.groups.value.forEach { repository.deleteGroup(it.id) }
    }

    /**
     * 轮询等待条件成立。**条件请返回可空值**：[waitFor] 见到非 null 就返回，
     * 而 Boolean 永远非 null，直接传 `{ x == 1 }` 会第一轮就返回 false 而不等待。
     * 只判条件的场合用 [waitUntil]。
     */
    fun <T : Any> waitFor(timeoutMs: Long = 5000, condition: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            condition()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("等待条件超时（${timeoutMs}ms）")
    }

    /** 轮询等待布尔条件变 true；超时抛 AssertionError。 */
    fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        waitFor(timeoutMs) { condition().takeIf { it } }
    }

    /** 往 Pictures/D7/<组名>/ 插入一张测试图片，返回其 MediaStore URI。 */
    fun insertTestImage(resolver: ContentResolver, groupName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "D7_test_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/D7/$groupName",
            )
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("MediaStore 插入测试图片失败")
        resolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3)) }
        return uri
    }

    /** 查询该 URI 在相册中是否仍存在。 */
    fun mediaExists(resolver: ContentResolver, uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
}