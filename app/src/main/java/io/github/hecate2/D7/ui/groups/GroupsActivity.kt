package io.github.hecate2.D7.ui.groups

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hecate2.D7.R
import io.github.hecate2.D7.core.CalcMode
import io.github.hecate2.D7.data.GroupRecord
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.databinding.ActivityGroupsBinding
import io.github.hecate2.D7.databinding.DialogDeleteGroupBinding
import io.github.hecate2.D7.databinding.DialogGroupNameBinding
import io.github.hecate2.D7.databinding.DialogNewGroupBinding
import io.github.hecate2.D7.databinding.ItemGroupBinding
import io.github.hecate2.D7.export.Exporter
import io.github.hecate2.D7.sensor.FixLocation
import io.github.hecate2.D7.sensor.LocationCapability
import io.github.hecate2.D7.sensor.LocationProvider
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.result.ResultActivity
import io.github.hecate2.D7.util.CardSummary
import io.github.hecate2.D7.util.Format
import io.github.hecate2.D7.util.Locales
import io.github.hecate2.D7.util.keepScreenOn
import io.github.hecate2.D7.util.Summaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 照片组管理（应用入口）。
 * 卡片列表来自仓库的 StateFlow；点卡片进结果页，长按改名、删除或导出；右上「+ 新建」建组后直接进采集页。
 */
class GroupsActivity : ComponentActivity() {

    private lateinit var binding: ActivityGroupsBinding
    private lateinit var repository: GroupRepository
    private val locationProvider by lazy { LocationProvider(this) }

    /** 最近一次 GPS 定位结果，建组时若坐标未再手改则沿用其海拔与时区。 */
    private var lastFix: FixLocation? = null

    /** 定位权限刚获批后要继续执行的定位动作。 */
    private var pendingLocate: (() -> Unit)? = null

    /**
     * 是否有导出在飞。这里的入口是长按弹的菜单项，没有按钮可以置灰，
     * 用户连点就会并发跑两份导出（精算 + 写文件都在Dispatchers.IO），
     * 既白耗 CPU 又可能同时写同一个文件名。故直接拒绝重复请求。
     */
    private var exportInFlight = false

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingLocate
            pendingLocate = null
            if (granted) {
                locationProvider.warmUp()
                action?.invoke()
            } else {
                toast(getString(R.string.gps_failed))
            }
        }

    /** 界面语言按用户选择覆写（见 [Locales]）：不引 AppCompat 的做法，minSdk 26 起行为一致。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepScreenOn()

        repository = GroupRepository.get(this)
        binding.newButton.setOnClickListener { showNewGroupDialog() }
        binding.langButton.setOnClickListener { showLanguageDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 卡片汇总含每组一次冬至日照精算（约 0.3ms/组），必须在后台线程算，
                // 否则组数一多，每次采集/改名都会在主线程卡住整个列表。
                repository.groups
                    .map { groups -> withContext(Dispatchers.Default) { groups.map { Summaries.cardSummary(it) } } }
                    .collect { summaries ->
                        renderGroups(summaries)
                        binding.emptyView.isVisible = summaries.isEmpty()
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 前台期间在后台尝试定位（无权限时静默跳过），让建组对话框能秒回结果
        locationProvider.warmUp()
    }

    override fun onStop() {
        super.onStop()
        // 退到后台即注销预热监听，避免 GPS 耗电与上下文泄漏
        locationProvider.shutdown()
    }

    override fun onDestroy() {
        // 定位权限回调可能晚于对话框消亡，清掉以免持有已失效的视图
        pendingLocate = null
        super.onDestroy()
    }

    private fun openResult(group: GroupRecord) {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(Extras.GROUP_ID, group.id))
    }

    /**
     * 点卡片：已经拍过点的看结果；一个点都没有的新组直接进采集页——
     * 空组的结果页没有任何信息量，而新建组后想接着拍还要再点一次「回采集续拍」。
     */
    private fun openGroup(group: GroupRecord) {
        if (group.external.isEmpty() && group.ceiling.isEmpty()) {
            startActivity(Intent(this, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, group.id))
        } else {
            openResult(group)
        }
    }

    /** 卡片列表行数很少，直接逐张 inflate（无需回收复用）。 */
    private fun renderGroups(summaries: List<CardSummary>) {
        binding.groupList.removeAllViews()
        for (summary in summaries) {
            val item = ItemGroupBinding.inflate(layoutInflater, binding.groupList, false)
            bindGroupCard(item, summary)
            binding.groupList.addView(item.root)
        }
    }

    private fun bindGroupCard(item: ItemGroupBinding, summary: CardSummary) {
        val group = summary.group
        val context = item.root.context
        item.name.text = group.name

        val winter = summary.winterMinutes
        if (winter == null) {
            item.winterResult.setText(R.string.group_untested)
            item.winterResult.setTextColor(ContextCompat.getColor(context, R.color.smoke))
        } else {
            item.winterResult.text =
                context.getString(R.string.group_winter, Format.durationShort(context.resources, winter))
            item.winterResult.setTextColor(ContextCompat.getColor(context, R.color.paper))
        }

        val hasPoints = summary.externalCount > 0 || summary.ceilingCount > 0
        val stamp = if (hasPoints) group.updatedAt else group.createdAt
        val date = Format.dateShort(stamp, group.zoneId)
        item.meta.text = if (hasPoints) {
            context.getString(
                R.string.group_meta_captured,
                date,
                Format.coordinate(group.lat, group.lon),
            )
        } else {
            context.getString(R.string.group_meta_unshot, date)
        }

        if (summary.externalCount >= 2) {
            item.externalPill.setBackgroundResource(R.drawable.bg_pill)
            item.externalPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
            val prefix = context.getString(R.string.group_external_prefix, summary.externalCount)
            val percent =
                context.getString(R.string.group_external_percent, summary.externalCoveredPercent)
            item.externalPill.text = SpannableStringBuilder(prefix + percent).apply {
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.paper)),
                    prefix.length,
                    prefix.length + percent.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } else {
            item.externalPill.setBackgroundResource(R.drawable.bg_pill_dashed)
            item.externalPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
            item.externalPill.setText(R.string.group_external_none)
        }

        if (summary.ceilingCount > 0) {
            item.ceilingPill.setBackgroundResource(R.drawable.bg_pill_moon)
            item.ceilingPill.setTextColor(ContextCompat.getColor(context, R.color.moon))
            item.ceilingPill.text =
                context.getString(R.string.group_ceiling_pill, summary.ceilingCount)
        } else {
            item.ceilingPill.setBackgroundResource(R.drawable.bg_pill_dashed)
            item.ceilingPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
            item.ceilingPill.setText(R.string.group_ceiling_none)
        }

        item.root.setOnClickListener { openGroup(group) }
        item.root.setOnLongClickListener { view ->
            showGroupMenu(group, view)
            true
        }
    }

    private fun showGroupMenu(group: GroupRecord, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.menu_rename)
            menu.add(0, MENU_DELETE, 1, R.string.menu_delete)
            menu.add(0, MENU_EXPORT, 2, R.string.menu_export)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RENAME -> {
                        showRenameDialog(group)
                        true
                    }

                    MENU_DELETE -> {
                        confirmDelete(group)
                        true
                    }

                    MENU_EXPORT -> {
                        showExportDialog(group)
                        true
                    }

                    else -> false
                }
            }
        }.show()
    }

    private fun showRenameDialog(group: GroupRecord) {
        val nameBinding = DialogGroupNameBinding.inflate(layoutInflater)
        nameBinding.nameInput.setText(group.name)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_group_title)
            .setView(nameBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameBinding.nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    nameBinding.nameInput.error = getString(R.string.group_name_required)
                } else {
                    repository.renameGroup(group.id, name)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(group: GroupRecord) {
        val photos = (group.external + group.ceiling).mapNotNull { it.photoUri }
        val view = DialogDeleteGroupBinding.inflate(layoutInflater)
        view.deleteMessage.text = getString(R.string.delete_group_message, group.name)
        view.deletePhotos.isVisible = photos.isNotEmpty()
        if (photos.isNotEmpty()) {
            view.deletePhotos.text = getString(R.string.delete_group_with_photos, photos.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_group_title)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteGroup(group, photos, view.deletePhotos.isChecked)
            }
            .show()
    }

    /** 删组；勾选「同时删照片」时先尽力删除相册中的照片（URI 可能已失效），失败不影响删组。 */
    private fun deleteGroup(group: GroupRecord, photos: List<String>, alsoPhotos: Boolean) {
        lifecycleScope.launch {
            if (alsoPhotos && photos.isNotEmpty()) {
                val failed = withContext(Dispatchers.IO) {
                    photos.count { !deletePhoto(Uri.parse(it)) }
                }
                if (failed > 0) toast(getString(R.string.delete_group_photos_failed, failed))
            }
            repository.deleteGroup(group.id)
        }
    }

    /**
     * 删除一张照片，返回是否成功。content URI 走 MediaStore（失败返回 0 而非抛异常，
     * 必须看返回值）；file URI（低版本公共目录或私有目录回退）直接删文件。
     */
    private fun deletePhoto(uri: Uri): Boolean = if (uri.scheme == "file") {
        val file = uri.path?.let(::File) ?: return true
        !file.exists() || file.delete()
    } else {
        runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    private fun showExportDialog(group: GroupRecord) {
        if (group.external.isEmpty() && group.ceiling.isEmpty()) {
            toast(getString(R.string.result_no_data))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_export)
            .setItems(
                arrayOf(
                    getString(R.string.result_export_csv),
                    getString(R.string.result_export_image),
                ),
            ) { _, which -> exportGroup(group, csv = which == 0) }
            .show()
    }

    /** 列表页快速导出：按默认档位与当地冬至日算一次，交给 Exporter。 */
    private fun exportGroup(group: GroupRecord, csv: Boolean) {
        if (exportInFlight) {
            toast(getString(R.string.result_export_busy))
            return
        }
        val mode = Summaries.defaultMode(group)
        val modeLabel = getString(
            when (mode) {
                CalcMode.EXTERNAL_ONLY -> R.string.result_mode_external
                CalcMode.EXTERNAL_AND_CEILING -> R.string.result_mode_both
                CalcMode.CEILING_ONLY -> R.string.result_mode_ceiling
            },
        )
        val year = LocalDate.now(ZoneId.of(group.zoneId)).year
        val date = Summaries.winterDate(year, group.lat)
        exportInFlight = true
        lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val daily = Summaries.evaluate(group, mode, date)
                    if (csv) {
                        Exporter.exportCsv(this@GroupsActivity, group, date, daily, modeLabel)
                    } else {
                        Exporter.exportImage(
                            this@GroupsActivity, group, getString(R.string.result_date_winter),
                            modeLabel, daily,
                            daily.directMinutes, Summaries.gbWindowMinutes(group, mode, year),
                        )
                    }
                }
                if (outcome.error == null) {
                    toast(getString(R.string.result_export_done, outcome.location.orEmpty()))
                } else {
                    toast(getString(R.string.result_export_failed))
                }
            } finally {
                // 协程被生命周期取消时也会走到这里，不会把标志卡在 true
                exportInFlight = false
            }
        }
    }

    /**
     * 语言选择对话框。语言名一律用各自的写法（Deutsch、Русский…）而不翻译，
     * 否则在中文界面里「德语 / 俄语」对想切语言的人毫无用处。
     *
     * 选完直接 [recreate]：配置是在 `attachBaseContext` 里改的，重建一次就把整棵视图树
     * 换成新语言，不需要逐个控件 setText。
     */
    private fun showLanguageDialog() {
        val tags = Locales.choices()
        val labels = tags.map { getString(Locales.labelRes(it)) }.toTypedArray()
        val checked = tags.indexOf(Locales.currentTag(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                if (tags[which] != Locales.currentTag(this)) {
                    Locales.setTag(this, tags[which])
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNewGroupDialog() {
        val view = DialogNewGroupBinding.inflate(layoutInflater)
        view.latInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateHemisphereHint(view)
        })
        // 未点击定位前，状态行每秒刷新定位能力（GPS / 网络 / 融合是否可用 + 当前精度）；点击后交给定位流程
        var liveCapability = true
        val capabilityTicker = object : Runnable {
            override fun run() {
                if (liveCapability) view.gpsStatus.text = capabilityText(locationProvider.capability())
                view.root.postDelayed(this, CAPABILITY_TICK_MS)
            }
        }
        view.gpsButton.setOnClickListener {
            liveCapability = false
            locate(
                onStart = { view.gpsStatus.setText(R.string.gps_locating) },
                onFix = { fix ->
                    view.latInput.setText(String.format(Locale.US, "%.6f", fix.lat))
                    view.lonInput.setText(String.format(Locale.US, "%.6f", fix.lon))
                    view.gpsStatus.text = fixStatusText(fix)
                },
                onFail = { view.gpsStatus.setText(R.string.gps_failed) },
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.new_group_title)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.start_capture, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = view.nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    view.nameInput.error = getString(R.string.group_name_required)
                    return@setOnClickListener
                }
                val lat = view.latInput.text.toString().toDoubleOrNull()
                val lon = view.lonInput.text.toString().toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    toast(getString(R.string.invalid_latlon))
                    return@setOnClickListener
                }
                // 仅当坐标仍与 GPS 结果一致时沿用其海拔与时区，手改后退回系统默认
                val fix = lastFix?.takeIf { abs(it.lat - lat) < 1e-6 && abs(it.lon - lon) < 1e-6 }
                val group = repository.createGroup(
                    name = name,
                    lat = lat,
                    lon = lon,
                    altitude = fix?.altitude ?: 0.0,
                    zoneId = fix?.zoneId ?: ZoneId.systemDefault().id,
                )
                dialog.dismiss()
                startActivity(
                    Intent(this, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, group.id),
                )
            }
            view.root.post(capabilityTicker)
        }
        dialog.setOnDismissListener { view.root.removeCallbacks(capabilityTicker) }
        dialog.show()
        updateHemisphereHint(view)
    }

    /** 「GPS 可用 · 网络定位 可用 · 系统融合 可用 · 当前精度 ±35 m」。 */
    private fun capabilityText(cap: LocationCapability): String {
        if (cap.noneEnabled) return getString(R.string.gps_cap_none)
        val parts = mutableListOf(
            getString(if (cap.gpsEnabled) R.string.gps_cap_gps_on else R.string.gps_cap_gps_off),
            getString(if (cap.networkEnabled) R.string.gps_cap_net_on else R.string.gps_cap_net_off),
        )
        if (cap.fusedAvailable) parts += getString(R.string.gps_cap_fused_on)
        val fix = cap.lastFix
        parts += when {
            fix == null -> getString(R.string.gps_cap_waiting)
            fix.accuracyMeters > 0f -> getString(R.string.gps_cap_accuracy, fix.accuracyMeters.roundToInt())
            else -> getString(R.string.gps_cap_accuracy_unknown)
        }
        return parts.joinToString(" · ")
    }

    private fun fixStatusText(fix: FixLocation): String {
        val coordinate = Format.coordinate(fix.lat, fix.lon)
        return if (fix.accuracyMeters > 0f) {
            getString(R.string.gps_done_accuracy, coordinate, fix.accuracyMeters.roundToInt())
        } else {
            getString(R.string.gps_done, coordinate)
        }
    }

    private fun locate(onStart: () -> Unit, onFix: (FixLocation) -> Unit, onFail: () -> Unit) {
        if (!locationProvider.hasPermission()) {
            pendingLocate = { locate(onStart, onFix, onFail) }
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        // 启动时已预热：命中新鲜缓存（5 分钟内）就秒回，不干等
        locationProvider.lastFresh()?.let {
            lastFix = it
            onFix(it)
            return
        }
        onStart()
        locationProvider.requestSingleUpdate(
            onResult = {
                lastFix = it
                onFix(it)
            },
            onTimeout = onFail,
        )
    }

    private fun updateHemisphereHint(view: DialogNewGroupBinding) {
        val lat = view.latInput.text.toString().toDoubleOrNull()
        view.hemisphereHint.setText(
            when {
                lat == null -> R.string.hemisphere_unknown
                abs(lat) <= 23.5 -> R.string.hemisphere_tropic
                lat > 0 -> R.string.hemisphere_north
                else -> R.string.hemisphere_south
            },
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_DELETE = 2
        const val MENU_EXPORT = 3

        /** 建组对话框中定位能力提示的刷新间隔（毫秒）。 */
        const val CAPABILITY_TICK_MS = 1_000L
    }
}