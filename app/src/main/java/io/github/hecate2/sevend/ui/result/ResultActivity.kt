package io.github.hecate2.sevend.ui.result

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.hecate2.sevend.R
import io.github.hecate2.sevend.core.Angles
import io.github.hecate2.sevend.core.CalcMode
import io.github.hecate2.sevend.core.DailySunlight
import io.github.hecate2.sevend.core.SunlightEvaluator
import io.github.hecate2.sevend.data.GroupRecord
import io.github.hecate2.sevend.data.GroupRepository
import io.github.hecate2.sevend.data.PointRecord
import io.github.hecate2.sevend.data.Region
import io.github.hecate2.sevend.databinding.ActivityResultBinding
import io.github.hecate2.sevend.databinding.DialogPointAnglesBinding
import io.github.hecate2.sevend.databinding.DialogPointsBulkBinding
import io.github.hecate2.sevend.export.Exporter
import io.github.hecate2.sevend.ui.Extras
import io.github.hecate2.sevend.ui.capture.CaptureActivity
import io.github.hecate2.sevend.util.Format
import io.github.hecate2.sevend.util.Summaries
import io.github.hecate2.sevend.util.toShotPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 结果页：日期药丸与计算档 → 主结果卡、当天时间线、国标辅助卡、全年曲线 → 点列编辑（删点、切换连线、单点/批量角度编辑）→ 导出与续拍。
 * 计算在后台协程执行，数据变化（续拍、改点）经仓库 StateFlow 自动触发重算。
 */
class ResultActivity : AppCompatActivity() {

    private enum class Preset { WINTER, DAHAN, EQUINOX, SUMMER, CUSTOM }

    private lateinit var binding: ActivityResultBinding
    private lateinit var repository: GroupRepository
    private lateinit var adapter: PointAdapter

    private var group: GroupRecord? = null
    private var preset = Preset.WINTER
    private var customDate: LocalDate? = null
    private var mode = CalcMode.EXTERNAL_ONLY

    private var computeJob: Job? = null
    private var curveCacheKey: String? = null
    private var curveCache: List<Double>? = null

    private var daily: DailySunlight? = null
    private var winterMinutes: Int? = null
    private var gbWindowMinutes: Int? = null
    private var selectedDateValue: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = GroupRepository.get(this)
        adapter = PointAdapter(
            scope = lifecycleScope,
            onToggleSegment = ::toggleSegment,
            onDelete = ::deletePoint,
            onEdit = ::editPoint,
        )
        binding.pointList.layoutManager = LinearLayoutManager(this)
        binding.pointList.adapter = adapter

        binding.pillWinter.setOnClickListener { choosePreset(Preset.WINTER) }
        binding.pillDahan.setOnClickListener { choosePreset(Preset.DAHAN) }
        binding.pillEquinox.setOnClickListener { choosePreset(Preset.EQUINOX) }
        binding.pillSummer.setOnClickListener { choosePreset(Preset.SUMMER) }
        binding.pillCustom.setOnClickListener { pickCustomDate() }
        binding.chipExternalOnly.setOnClickListener { chooseMode(CalcMode.EXTERNAL_ONLY) }
        binding.chipBoth.setOnClickListener { chooseMode(CalcMode.EXTERNAL_AND_CEILING) }
        binding.chipCeilingOnly.setOnClickListener { chooseMode(CalcMode.CEILING_ONLY) }
        binding.exportCsvButton.setOnClickListener { export(csv = true) }
        binding.exportImageButton.setOnClickListener { export(csv = false) }
        binding.captureAgainButton.setOnClickListener { openCapture() }
        binding.editPoints.setOnClickListener { editPointsBulk() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.groups.collect { groups -> onGroups(groups) }
            }
        }
    }

    private fun onGroups(groups: List<GroupRecord>) {
        val id = intent.getStringExtra(Extras.GROUP_ID).orEmpty()
        val g = groups.firstOrNull { it.id == id }
        if (g == null) {
            finish()
            return
        }
        group = g
        if (mode != CalcMode.EXTERNAL_ONLY && g.ceiling.isEmpty()) {
            mode = CalcMode.EXTERNAL_ONLY
        }
        binding.titleResult.text = getString(R.string.result_title, g.name)
        binding.resultMeta.text = getString(
            R.string.result_meta,
            Format.coordinate(g.lat, g.lon),
            Format.dateShort(g.createdAt, g.zoneId),
            zoneDisplay(g.zoneId),
        )
        updatePills()
        updateChips(g)
        rebuildPoints(g)
        recompute()
    }

    // ---------------- 选择与状态 ----------------

    private fun choosePreset(next: Preset) {
        if (preset == next) return
        preset = next
        updatePills()
        recompute()
    }

    private fun pickCustomDate() {
        val g = group ?: return
        val initial = selectedDate(g)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                customDate = LocalDate.of(year, month + 1, day)
                preset = Preset.CUSTOM
                updatePills()
                recompute()
            },
            initial.year, initial.monthValue - 1, initial.dayOfMonth,
        ).show()
    }

    private fun chooseMode(next: CalcMode) {
        val g = group ?: return
        if (next != CalcMode.EXTERNAL_ONLY && g.ceiling.isEmpty()) {
            toast(getString(R.string.result_mode_ceiling_locked))
            return
        }
        if (mode == next) return
        mode = next
        updateChips(g)
        recompute()
    }

    private fun updatePills() {
        val pills = mapOf(
            binding.pillWinter to Preset.WINTER,
            binding.pillDahan to Preset.DAHAN,
            binding.pillEquinox to Preset.EQUINOX,
            binding.pillSummer to Preset.SUMMER,
            binding.pillCustom to Preset.CUSTOM,
        )
        for ((view, value) in pills) {
            val selected = preset == value
            view.setBackgroundResource(
                if (selected) R.drawable.bg_pill_filled else R.drawable.bg_pill,
            )
            view.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.ink else R.color.smoke),
            )
        }
    }

    private fun updateChips(g: GroupRecord) {
        val ceilingEmpty = g.ceiling.isEmpty()
        val chips = listOf(
            binding.chipExternalOnly to CalcMode.EXTERNAL_ONLY,
            binding.chipBoth to CalcMode.EXTERNAL_AND_CEILING,
            binding.chipCeilingOnly to CalcMode.CEILING_ONLY,
        )
        for ((view, value) in chips) {
            val selected = mode == value
            val locked = value != CalcMode.EXTERNAL_ONLY && ceilingEmpty
            view.setBackgroundResource(
                if (selected) R.drawable.bg_pill_filled else R.drawable.bg_pill,
            )
            view.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.ink else R.color.smoke),
            )
            view.alpha = if (locked) 0.35f else 1f
        }
    }

    private fun selectedDate(g: GroupRecord): LocalDate {
        val year = LocalDate.now(ZoneId.of(g.zoneId)).year
        return when (preset) {
            Preset.WINTER -> Summaries.winterDate(year, g.lat)
            Preset.DAHAN -> Summaries.dahanDate(year, g.lat)
            Preset.EQUINOX -> LocalDate.of(year, 3, 21)
            Preset.SUMMER -> if (g.lat >= 0) LocalDate.of(year, 6, 21) else LocalDate.of(year, 12, 21)
            Preset.CUSTOM -> customDate ?: Summaries.winterDate(year, g.lat)
        }
    }

    private fun modeLabel(): String = getString(
        when (mode) {
            CalcMode.EXTERNAL_ONLY -> R.string.result_mode_external
            CalcMode.EXTERNAL_AND_CEILING -> R.string.result_mode_both
            CalcMode.CEILING_ONLY -> R.string.result_mode_ceiling
        },
    )

    private fun dateLabel(date: LocalDate): String = when (preset) {
        Preset.WINTER -> getString(R.string.result_date_winter)
        Preset.DAHAN -> getString(R.string.result_date_dahan)
        Preset.EQUINOX -> getString(R.string.result_date_equinox)
        Preset.SUMMER -> getString(R.string.result_date_summer)
        Preset.CUSTOM -> Format.monthDay(date)
    }

    private fun zoneDisplay(zoneId: String): String =
        if (zoneId == "Asia/Shanghai") getString(R.string.result_timezone_beijing) else zoneId

    // ---------------- 计算 ----------------

    private data class Computed(
        val daily: DailySunlight,
        val winterMinutes: Int,
        val gbWindowMinutes: Int,
        val curve: List<Double>,
        val curveKey: String,
    )

    private fun recompute() {
        val g = group ?: return
        computeJob?.cancel()
        binding.mainValue.text = getString(R.string.result_computing)
        val date = selectedDate(g)
        val modeNow = mode
        val year = LocalDate.now(ZoneId.of(g.zoneId)).year
        val winterDate = Summaries.winterDate(year, g.lat)
        val key = curveKey(g, modeNow, year)
        computeJob = lifecycleScope.launch {
            val computed = withContext(Dispatchers.Default) {
                val external = g.external.toShotPoints()
                val ceiling = g.ceiling.toShotPoints()
                val dailyNow = Summaries.evaluate(g, modeNow, date)
                val winter = if (date == winterDate) {
                    dailyNow.directMinutes
                } else {
                    Summaries.evaluate(g, modeNow, winterDate).directMinutes
                }
                val gb = Summaries.gbWindowMinutes(g, modeNow, year)
                val curve = curveCache?.takeIf { key == curveCacheKey }
                    ?: SunlightEvaluator.yearlyCurve(
                        g.lat, g.lon, g.zoneId, external, ceiling, modeNow, year,
                    )
                Computed(dailyNow, winter, gb, curve, key)
            }
            curveCache = computed.curve
            curveCacheKey = computed.curveKey
            daily = computed.daily
            winterMinutes = computed.winterMinutes
            gbWindowMinutes = computed.gbWindowMinutes
            selectedDateValue = date
            renderComputed(g, date, computed)
        }
    }

    private fun curveKey(g: GroupRecord, mode: CalcMode, year: Int): String = buildString {
        append(mode.name).append('|').append(year)
        for (p in g.external) append('|').append(p.az).append(',').append(p.el).append(',').append(p.gapAfter)
        append('#')
        for (p in g.ceiling) append('|').append(p.az).append(',').append(p.el)
    }

    private fun renderComputed(g: GroupRecord, date: LocalDate, computed: Computed) {
        val d = computed.daily
        binding.mainLabel.text = getString(R.string.result_main_label, dateLabel(date), modeLabel())
        binding.mainValue.text = Format.durationLong(resources, d.directMinutes)
        binding.gapNote.isVisible = d.allFromGap
        val sunrise = d.sunriseMinute
        binding.sunLine.text = when {
            sunrise == null && d.directMinutes == 0 -> getString(R.string.result_polar_night)
            sunrise == null -> getString(R.string.result_polar_day)
            else -> getString(
                R.string.result_sunrise_sunset,
                Format.clockMinute(sunrise),
                Format.clockMinute(d.sunsetMinute ?: 0),
            )
        }
        binding.dayTimeline.submit(d.sunriseMinute, d.sunsetMinute, d.visibleIntervals)

        binding.gbValue.text = Format.durationLong(resources, computed.gbWindowMinutes)
        val reached = computed.gbWindowMinutes >= 120
        binding.gbNote.setText(if (reached) R.string.result_gb_ok else R.string.result_gb_low)
        binding.gbNote.setTextColor(
            ContextCompat.getColor(this, if (reached) R.color.paper else R.color.moon),
        )

        val year = LocalDate.now(ZoneId.of(g.zoneId)).year
        val marker = if (date.year == year) date.dayOfYear - 1 else -1
        binding.yearCurve.submit(computed.curve, marker)
    }

    // ---------------- 点列 ----------------

    private fun rebuildPoints(g: GroupRecord) {
        val rows = ArrayList<PointAdapter.Row>(g.external.size + g.ceiling.size)
        g.external.forEachIndexed { i, p ->
            rows.add(
                PointAdapter.Row(
                    region = Region.EXTERNAL,
                    index = i,
                    point = p,
                    hasPrev = i > 0,
                    segViaHorizon = i > 0 && g.external[i - 1].gapAfter,
                    toggleEnabled = i > 0,
                ),
            )
        }
        g.ceiling.forEachIndexed { i, p ->
            rows.add(
                PointAdapter.Row(
                    region = Region.CEILING,
                    index = i,
                    point = p,
                    hasPrev = i > 0,
                    segViaHorizon = false,
                    toggleEnabled = false,
                ),
            )
        }
        adapter.submit(rows)
        binding.pointsCount.text = getString(
            R.string.result_points_count, g.external.size, g.ceiling.size,
        )
    }

    /** 切换该点与左邻点（拍摄序前一点）的连线模式，作用于前一点的 gapAfter。 */
    private fun toggleSegment(externalIndex: Int) {
        val g = group ?: return
        if (externalIndex !in 1 until g.external.size) return
        val prev = g.external[externalIndex - 1]
        repository.setSegmentViaHorizon(g.id, Region.EXTERNAL, externalIndex - 1, !prev.gapAfter)
    }

    private fun deletePoint(region: Region, index: Int) {
        val g = group ?: return
        repository.deletePoint(g.id, region, index)
    }

    // ---------------- 角度编辑（强行改值；允许无图点） ----------------

    /** 单点编辑：改写方位与仰角，照片与拍摄时间保留。 */
    private fun editPoint(region: Region, index: Int) {
        val g = group ?: return
        val point = g.regionList(region).getOrNull(index) ?: return
        val view = DialogPointAnglesBinding.inflate(layoutInflater)
        view.azInput.setText(String.format(Locale.US, "%.1f", point.az))
        view.elInput.setText(String.format(Locale.US, "%.1f", point.el))
        val regionTag = getString(
            if (region == Region.EXTERNAL) R.string.result_region_external
            else R.string.result_region_ceiling,
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.point_edit_title, index + 1, regionTag))
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val az = view.azInput.text.toString().trim().toDoubleOrNull()
                val el = view.elInput.text.toString().trim().toDoubleOrNull()
                if (az == null || el == null || el !in 0.0..90.0) {
                    toast(getString(R.string.point_edit_invalid))
                    return@setPositiveButton
                }
                repository.updatePointAngles(g.id, region, index, az, el)
            }
            .show()
    }

    /**
     * 批量编辑：两区各一段文本，逐行「方位角 仰角 [horizon]」。
     * 行数不变时按序继承原照片与拍摄时间，增删行则新点为无图点；可先整体偏移再确认。
     */
    private fun editPointsBulk() {
        val g = group ?: return
        val view = DialogPointsBulkBinding.inflate(layoutInflater)
        val initial = mapOf(
            Region.EXTERNAL to renderBulk(g.external.map(::recordToRow)),
            Region.CEILING to renderBulk(g.ceiling.map(::recordToRow)),
        )
        val texts = HashMap(initial)
        var current = Region.EXTERNAL
        view.bulkInput.setText(texts.getValue(current))
        paintBulkChips(view, current)

        fun selectRegion(next: Region) {
            if (next == current) return
            texts[current] = view.bulkInput.text.toString()
            current = next
            view.bulkInput.setText(texts.getValue(current))
            paintBulkChips(view, current)
        }
        view.chipExternal.setOnClickListener { selectRegion(Region.EXTERNAL) }
        view.chipCeiling.setOnClickListener { selectRegion(Region.CEILING) }

        view.applyOffset.setOnClickListener {
            val (rows, errLine) = parseBulk(view.bulkInput.text.toString())
            if (rows == null) {
                toast(getString(R.string.bulk_edit_parse_error, errLine))
                return@setOnClickListener
            }
            val dAz = view.azOffsetInput.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
            val dEl = view.elOffsetInput.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
            if (dAz == null || dEl == null) {
                toast(getString(R.string.bulk_edit_offset_invalid))
                return@setOnClickListener
            }
            view.bulkInput.setText(
                renderBulk(
                    rows.map { r ->
                        BulkRow(
                            Angles.normalize360(r.az + dAz),
                            (r.el + dEl).coerceIn(0.0, 90.0),
                            r.horizon,
                        )
                    },
                ),
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.bulk_edit_title)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                texts[current] = view.bulkInput.text.toString()
                val pending = ArrayList<Pair<Region, List<PointRecord>>>()
                for (region in listOf(Region.EXTERNAL, Region.CEILING)) {
                    val text = texts.getValue(region)
                    if (text == initial.getValue(region)) continue
                    val (rows, errLine) = parseBulk(text)
                    if (rows == null) {
                        toast(getString(R.string.bulk_edit_parse_error, errLine))
                        return@setPositiveButton
                    }
                    pending.add(region to rowsToRecords(rows, g.regionList(region)))
                }
                for ((region, points) in pending) {
                    repository.setRegionPoints(g.id, region, points)
                }
            }
            .show()
    }

    private fun paintBulkChips(view: DialogPointsBulkBinding, selected: Region) {
        val chips = mapOf(
            view.chipExternal to Region.EXTERNAL,
            view.chipCeiling to Region.CEILING,
        )
        for ((chip, region) in chips) {
            val on = region == selected
            chip.setBackgroundResource(if (on) R.drawable.bg_pill_filled else R.drawable.bg_pill)
            chip.setTextColor(ContextCompat.getColor(this, if (on) R.color.ink else R.color.smoke))
        }
    }

    /** 批量文本的一行；horizon 表示该点与下一点之间经地平线。 */
    private data class BulkRow(val az: Double, val el: Double, val horizon: Boolean)

    private fun recordToRow(p: PointRecord): BulkRow = BulkRow(p.az, p.el, p.gapAfter)

    private fun renderBulk(rows: List<BulkRow>): String = rows.joinToString("\n") { r ->
        val base = String.format(Locale.US, "%.1f %.1f", r.az, r.el)
        if (r.horizon) "$base horizon" else base
    }

    /** 解析批量文本；失败时 first 为 null、second 为出错行号（1 基）。 */
    private fun parseBulk(text: String): Pair<List<BulkRow>?, Int> {
        val rows = ArrayList<BulkRow>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            val az = parts.getOrNull(0)?.toDoubleOrNull()
            val el = parts.getOrNull(1)?.toDoubleOrNull()
            if (az == null || el == null || parts.size > 3) return null to (i + 1)
            val horizon = when {
                parts.size == 2 -> false
                else -> when (parts[2].lowercase()) {
                    "horizon", "h", "0" -> true
                    else -> return null to (i + 1)
                }
            }
            rows.add(BulkRow(az, el, horizon))
        }
        return rows to 0
    }

    /** 文本行转点记录：行数不变时按序继承原照片与拍摄时间，否则为无图点。 */
    private fun rowsToRecords(rows: List<BulkRow>, old: List<PointRecord>): List<PointRecord> {
        val inherit = rows.size == old.size
        return rows.mapIndexed { i, r ->
            val az = Angles.normalize360(r.az)
            val el = r.el.coerceIn(0.0, 90.0)
            val base = if (inherit) old[i].copy(az = az, el = el) else PointRecord(az, el)
            base.copy(gapAfter = r.horizon)
        }
    }

    // ---------------- 导出与续拍 ----------------

    private fun export(csv: Boolean) {
        val g = group ?: return
        if (g.external.isEmpty() && g.ceiling.isEmpty()) {
            toast(getString(R.string.result_no_data))
            return
        }
        val d = daily ?: return
        val date = selectedDateValue ?: selectedDate(g)
        binding.exportCsvButton.isEnabled = false
        binding.exportImageButton.isEnabled = false
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (csv) {
                    Exporter.exportCsv(this@ResultActivity, g, date, d, modeLabel())
                } else {
                    Exporter.exportImage(
                        this@ResultActivity, g, dateLabel(date), modeLabel(),
                        d, winterMinutes, gbWindowMinutes,
                    )
                }
            }
            binding.exportCsvButton.isEnabled = true
            binding.exportImageButton.isEnabled = true
            if (outcome.error == null) {
                toast(getString(R.string.result_export_done, outcome.location.orEmpty()))
            } else {
                toast(getString(R.string.result_export_failed))
            }
        }
    }

    private fun openCapture() {
        val g = group ?: return
        startActivity(
            Intent(this, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, g.id),
        )
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}