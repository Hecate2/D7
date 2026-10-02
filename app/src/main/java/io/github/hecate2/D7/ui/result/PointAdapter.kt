package io.github.hecate2.D7.ui.result

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.hecate2.D7.R
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.databinding.ItemPointBinding
import io.github.hecate2.D7.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 结果页点列：每行一个拍摄点（分区标记、序号、缩略图、方位、仰角、与左邻点的连线模式、删除）。
 * 连线模式作用于该点与拍摄序前一点之间的线段（数据上是前一点的 gapAfter），天花板点不可切换。
 */
class PointAdapter(
    private val scope: CoroutineScope,
    private val onToggleSegment: (Int) -> Unit,
    private val onDelete: (Region, Int) -> Unit,
    private val onEdit: (Region, Int) -> Unit,
) : RecyclerView.Adapter<PointAdapter.Holder>() {

    data class Row(
        val region: Region,
        /** 分区内 0 基下标。 */
        val index: Int,
        val point: PointRecord,
        /** 是否存在拍摄序前一点（首点无连线可改）。 */
        val hasPrev: Boolean,
        /** 与左邻点的连线是否为经地平线推断段。 */
        val segViaHorizon: Boolean,
        /** 是否允许切换（仅外部区且存在左邻点）。 */
        val toggleEnabled: Boolean,
    )

    private val rows = ArrayList<Row>()

    fun submit(items: List<Row>) {
        rows.clear()
        rows.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPointBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val b = holder.binding
        val ctx = b.root.context

        val regionTag = ctx.getString(
            if (row.region == Region.EXTERNAL) R.string.result_region_external
            else R.string.result_region_ceiling,
        )
        b.title.text = ctx.getString(
            R.string.result_point_title,
            regionTag, row.index + 1,
            Format.degreeInt(row.point.az), Format.degreeInt(row.point.el),
        )

        val pill = b.segment
        if (row.toggleEnabled) {
            pill.isClickable = true
            pill.isEnabled = true
            pill.alpha = 1f
            pill.setTextColor(
                ContextCompat.getColor(ctx, if (row.segViaHorizon) R.color.moon else R.color.smoke),
            )
            pill.setBackgroundResource(
                if (row.segViaHorizon) R.drawable.bg_pill_moon else R.drawable.bg_pill,
            )
            pill.text = ctx.getString(
                if (row.segViaHorizon) R.string.result_seg_horizon else R.string.result_seg_direct,
            )
            pill.setOnClickListener { onToggleSegment(row.index) }
        } else {
            pill.setOnClickListener(null)
            pill.isClickable = false
            pill.isEnabled = false
            pill.alpha = 0.45f
            pill.setTextColor(ContextCompat.getColor(ctx, R.color.smoke))
            pill.setBackgroundResource(R.drawable.bg_pill)
            pill.text = ctx.getString(R.string.result_seg_direct)
        }

        b.delete.setOnClickListener { onDelete(row.region, row.index) }
        b.root.setOnClickListener { onEdit(row.region, row.index) }

        val uriStr = row.point.photoUri
        b.thumb.tag = uriStr
        b.thumb.setImageDrawable(null)
        if (uriStr != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    loadThumbnail(ctx, Uri.parse(uriStr), THUMB_PX)
                }
                if (b.thumb.tag == uriStr) b.thumb.setImageBitmap(bitmap)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.binding.thumb.tag = null
        holder.binding.thumb.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    class Holder(val binding: ItemPointBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val THUMB_PX = 96

        /** 缩略图降采样解码；URI 失效（照片被外部清理）时返回 null，显示占位框。 */
        fun loadThumbnail(context: Context, uri: Uri, targetPx: Int): Bitmap? = try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }
        } catch (_: Exception) {
            null
        }
    }
}