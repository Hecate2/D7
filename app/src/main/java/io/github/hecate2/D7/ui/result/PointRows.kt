package io.github.hecate2.D7.ui.result

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import io.github.hecate2.D7.R
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.databinding.ItemPointBinding
import io.github.hecate2.D7.util.Format
import io.github.hecate2.D7.util.decodeDownsampled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 结果页点列：按拍摄顺序把每行渲染进容器（LinearLayout + addView，无需回收复用）。
 * 每行显示分区、序号、缩略图、方位与仰角、与左邻点的连线模式、删除按钮；
 * 点整行进单点编辑，点连线药丸切换经地平线/直接连线（仅外部区且存在左邻点时可切）。
 *
 * 缩略图按 URI 缓存于 [thumbs]，避免每次刷新都重新解码 JPEG。
 */
class PointRows(
    private val container: LinearLayout,
    private val scope: CoroutineScope,
    private val onToggleSegment: (Int) -> Unit,
    private val onDelete: (Region, Int) -> Unit,
    private val onEdit: (Region, Int) -> Unit,
    private val onShowPhoto: (String) -> Unit,
) {

    /** 一行。[segViaHorizon] 表示本点与左邻点之间的连线是否为经地平线推断段。 */
    data class Row(
        val region: Region,
        /** 分区内 0 基下标。 */
        val index: Int,
        val point: PointRecord,
        val segViaHorizon: Boolean,
    ) {
        /** 仅外部区且非首点，才存在左邻点、连线模式才可改。 */
        val toggleable: Boolean get() = region == Region.EXTERNAL && index > 0
    }

    /** 缩略图缓存（按字节计），避免刷新列表时反复解码 JPEG。 */
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun submit(rows: List<Row>) {
        val inflater = LayoutInflater.from(container.context)
        container.removeAllViews()
        for (row in rows) {
            val binding = ItemPointBinding.inflate(inflater, container, false)
            bind(binding, row)
            container.addView(binding.root)
        }
    }

    private fun bind(binding: ItemPointBinding, row: Row) {
        val context = binding.root.context
        val regionTag = Format.region(context.resources, row.region)
        binding.title.text = context.getString(
            R.string.result_point_title,
            regionTag, row.index + 1,
            Format.degreeInt(row.point.az), Format.degreeInt(row.point.el),
        )

        val pill = binding.segment
        if (row.toggleable) {
            pill.isEnabled = true
            pill.alpha = 1f
            pill.setTextColor(
                ContextCompat.getColor(context, if (row.segViaHorizon) R.color.moon else R.color.smoke),
            )
            pill.setBackgroundResource(
                if (row.segViaHorizon) R.drawable.bg_pill_moon else R.drawable.bg_pill,
            )
            pill.text = context.getString(
                if (row.segViaHorizon) R.string.result_seg_horizon else R.string.result_seg_direct,
            )
            pill.setOnClickListener { onToggleSegment(row.index) }
        } else {
            pill.setOnClickListener(null)
            pill.isEnabled = false
            pill.alpha = 0.45f
            pill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
            pill.setBackgroundResource(R.drawable.bg_pill)
            pill.text = context.getString(R.string.result_seg_direct)
        }

        binding.delete.setOnClickListener { onDelete(row.region, row.index) }
        binding.root.setOnClickListener { onEdit(row.region, row.index) }

        bindThumb(binding.thumb, row.point.photoUri)
        // 缩略图点开看大图。没有照片时不挂监听，也不设 clickable：否则它会吃掉
        // 本该落到整行的点击（无图点仍应该能点开角度编辑）。
        val photo = row.point.photoUri
        binding.thumb.setOnClickListener(if (photo == null) null else View.OnClickListener { onShowPhoto(photo) })
        binding.thumb.isClickable = photo != null
    }

    /** 缩略图：缓存命中直接贴图，未命中在 IO 线程解码后回主线程贴图。 */
    private fun bindThumb(thumb: ImageView, uri: String?) {
        thumb.tag = uri
        if (uri == null) {
            thumb.setImageDrawable(null)
            return
        }
        thumbs.get(uri)?.let {
            thumb.setImageBitmap(it)
            return
        }
        thumb.setImageDrawable(null)
        val context = thumb.context
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeDownsampled(context, Uri.parse(uri), THUMB_PX)
            } ?: return@launch
            thumbs.put(uri, bitmap)
            if (thumb.tag == uri) thumb.setImageBitmap(bitmap)
        }
    }

    private companion object {
        /** 缩略图缓存上限（KB），足够放满整页点列。 */
        const val THUMB_CACHE_KB = 2048

        /** 缩略图最长边（像素）。 */
        const val THUMB_PX = 96
    }
}