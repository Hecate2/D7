package io.github.hecate2.sevend.ui.groups

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.hecate2.sevend.R
import io.github.hecate2.sevend.data.GroupRecord
import io.github.hecate2.sevend.data.GroupRepository
import io.github.hecate2.sevend.databinding.ActivityGroupsBinding
import io.github.hecate2.sevend.databinding.DialogGroupNameBinding
import io.github.hecate2.sevend.databinding.DialogNewGroupBinding
import io.github.hecate2.sevend.sensor.FixLocation
import io.github.hecate2.sevend.sensor.LocationProvider
import io.github.hecate2.sevend.ui.Extras
import io.github.hecate2.sevend.ui.capture.CaptureActivity
import io.github.hecate2.sevend.ui.result.ResultActivity
import io.github.hecate2.sevend.util.Format
import io.github.hecate2.sevend.util.Summaries
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * 照片组管理（应用入口）。
 * 卡片列表来自仓库的 StateFlow；点卡片进结果页，长按改名或删除；右上「+ 新建」建组后直接进采集页。
 */
class GroupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupsBinding
    private lateinit var repository: GroupRepository
    private lateinit var adapter: GroupsAdapter
    private val locationProvider by lazy { LocationProvider(this) }

    /** 最近一次 GPS 定位结果，建组时若坐标未再手改则沿用其海拔与时区。 */
    private var lastFix: FixLocation? = null

    /** 定位权限刚获批后要继续执行的定位动作。 */
    private var pendingLocate: (() -> Unit)? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingLocate
            pendingLocate = null
            if (granted) action?.invoke() else toast(getString(R.string.gps_failed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = GroupRepository.get(this)
        adapter = GroupsAdapter(onClick = ::openResult, onLongClick = ::showGroupMenu)
        binding.groupList.layoutManager = LinearLayoutManager(this)
        binding.groupList.adapter = adapter
        binding.newButton.setOnClickListener { showNewGroupDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.groups.collect { groups ->
                    adapter.submit(groups.map { Summaries.cardSummary(it) })
                    binding.emptyView.isVisible = groups.isEmpty()
                }
            }
        }
    }

    private fun openResult(group: GroupRecord) {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(Extras.GROUP_ID, group.id))
    }

    private fun showGroupMenu(group: GroupRecord, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.menu_rename)
            menu.add(0, MENU_DELETE, 1, R.string.menu_delete)
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

                    else -> false
                }
            }
        }.show()
    }

    private fun showRenameDialog(group: GroupRecord) {
        val nameBinding = DialogGroupNameBinding.inflate(layoutInflater)
        nameBinding.nameInput.setText(group.name)
        val dialog = MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_group_title)
            .setMessage(getString(R.string.delete_group_message, group.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> repository.deleteGroup(group.id) }
            .show()
    }

    private fun showNewGroupDialog() {
        val view = DialogNewGroupBinding.inflate(layoutInflater)
        view.latInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateHemisphereHint(view)
        })
        view.gpsButton.setOnClickListener {
            locate(
                onStart = { view.gpsStatus.setText(R.string.gps_locating) },
                onFix = { fix ->
                    view.latInput.setText(String.format(Locale.US, "%.6f", fix.lat))
                    view.lonInput.setText(String.format(Locale.US, "%.6f", fix.lon))
                    view.gpsStatus.text =
                        getString(R.string.gps_done, Format.coordinate(fix.lat, fix.lon))
                },
                onFail = { view.gpsStatus.setText(R.string.gps_failed) },
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
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
        }
        dialog.show()
        updateHemisphereHint(view)
    }

    private fun locate(onStart: () -> Unit, onFix: (FixLocation) -> Unit, onFail: () -> Unit) {
        if (!locationProvider.hasPermission()) {
            pendingLocate = { locate(onStart, onFix, onFail) }
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        locationProvider.lastKnown()?.let {
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
    }
}