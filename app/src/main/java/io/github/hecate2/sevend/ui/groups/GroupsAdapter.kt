package io.github.hecate2.sevend.ui.groups

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.hecate2.sevend.R
import io.github.hecate2.sevend.data.GroupRecord
import io.github.hecate2.sevend.databinding.ItemGroupBinding
import io.github.hecate2.sevend.util.CardSummary
import io.github.hecate2.sevend.util.Format

/**
 * 照片组卡片列表。卡片显示组名、冬至结论、采集日期与经纬度、
 * 外部区点数与覆盖率、天花板区状态；点卡片进结果页，长按弹菜单。
 */
class GroupsAdapter(
    private val onClick: (GroupRecord) -> Unit,
    private val onLongClick: (GroupRecord, View) -> Unit,
) : RecyclerView.Adapter<GroupsAdapter.Holder>() {

    private var items: List<CardSummary> = emptyList()

    fun submit(list: List<CardSummary>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(summary: CardSummary) {
            val group = summary.group
            val context = binding.root.context
            binding.name.text = group.name

            val winter = summary.winterMinutes
            if (winter == null) {
                binding.winterResult.setText(R.string.group_untested)
                binding.winterResult.setTextColor(ContextCompat.getColor(context, R.color.smoke))
            } else {
                binding.winterResult.text =
                    context.getString(R.string.group_winter, Format.durationShort(winter))
                binding.winterResult.setTextColor(ContextCompat.getColor(context, R.color.paper))
            }

            val hasPoints = summary.externalCount > 0 || summary.ceilingCount > 0
            val stamp = if (hasPoints) group.updatedAt else group.createdAt
            val date = Format.dateShort(stamp, group.zoneId)
            binding.meta.text = if (hasPoints) {
                context.getString(
                    R.string.group_meta_captured,
                    date,
                    Format.coordinate(group.lat, group.lon),
                )
            } else {
                context.getString(R.string.group_meta_unshot, date)
            }

            if (summary.externalCount >= 2) {
                binding.externalPill.setBackgroundResource(R.drawable.bg_pill)
                binding.externalPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
                val prefix = context.getString(R.string.group_external_prefix, summary.externalCount)
                val percent =
                    context.getString(R.string.group_external_percent, summary.externalCoveredPercent)
                binding.externalPill.text = SpannableStringBuilder(prefix + percent).apply {
                    setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, R.color.paper)),
                        prefix.length,
                        prefix.length + percent.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            } else {
                binding.externalPill.setBackgroundResource(R.drawable.bg_pill_dashed)
                binding.externalPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
                binding.externalPill.setText(R.string.group_external_none)
            }

            if (summary.ceilingCount > 0) {
                binding.ceilingPill.setBackgroundResource(R.drawable.bg_pill_moon)
                binding.ceilingPill.setTextColor(ContextCompat.getColor(context, R.color.moon))
                binding.ceilingPill.text =
                    context.getString(R.string.group_ceiling_pill, summary.ceilingCount)
            } else {
                binding.ceilingPill.setBackgroundResource(R.drawable.bg_pill_dashed)
                binding.ceilingPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
                binding.ceilingPill.setText(R.string.group_ceiling_none)
            }

            binding.root.setOnClickListener { onClick(group) }
            binding.root.setOnLongClickListener { view ->
                onLongClick(group, view)
                true
            }
        }
    }
}