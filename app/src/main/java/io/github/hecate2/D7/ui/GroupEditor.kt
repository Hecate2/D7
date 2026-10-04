package io.github.hecate2.D7.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import io.github.hecate2.D7.R
import io.github.hecate2.D7.data.GroupRecord
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.databinding.DialogEditGroupBinding
import io.github.hecate2.D7.util.Format
import io.github.hecate2.D7.util.Zones
import java.util.Locale

/**
 * 「编辑照片组」对话框：名称、经纬度、时区三样一起改。照片组列表（长按 → 编辑）与结果页
 * （元信息旁的「编辑」）共用这一份，校验与文案只写一遍。
 *
 * 时区那一行的两个图标键只是替用户填上 IANA 名：一个填本机时区，一个按当前经度推算。
 * 落库的都是 [Zones.parse] 解析出的 id，所以想写别的直接改输入框即可。
 *
 * 界面上没有任何「时区」字样：输入框用数值样例当占位符（+05:30），两个快捷键只画图标，
 * 连不合法的提示也只报格式样例（见 `zone_invalid`）。图标本身说不出会填成什么，所以把
 * 「按下去写进输入框的那个值」塞进 contentDescription，读屏能报出具体时区。
 *
 * 组名与经纬度都是进焦点即全选（布局里的 `selectAllOnFocus`），全选之后再点一下就按
 * Android 原生的行为把光标落到点击处——想改哪一段就点哪一段。**时区那一格故意不开**：
 * `Asia/Kolkata` 这类值常要改的是后半截，一进焦点就全选反而得先取消选区。
 */
fun showGroupEditor(activity: Activity, group: GroupRecord) {
    val view = DialogEditGroupBinding.inflate(activity.layoutInflater)
    view.nameInput.setText(group.name)
    // 六位小数与 GPS 结果同一量级（约 0.1 m），改完不会因为显示精度把坐标磨掉
    view.lonInput.setText(String.format(Locale.US, "%.6f", group.lon))
    view.latInput.setText(String.format(Locale.US, "%.6f", group.lat))
    view.zoneInput.setText(group.zoneId)
    view.lonInput.bindHemisphereSuffix(view.lonSuffix, Format::longitudeSuffix)
    view.latInput.bindHemisphereSuffix(view.latSuffix, Format::latitudeSuffix)
    view.zoneDeviceButton.contentDescription = Zones.system()
    // 「按经度」那条读的是输入框里当前的经度（点下去就是这么算的），所以经度一改就得跟着改，
    // 否则读屏会报一个已经过期的时区。经度读不出来时退回数值样例，也就是「这里要填个样例那样的值」。
    fun syncLongitudeHint() {
        val lon = view.lonInput.text.toString().toDoubleOrNull()
        view.zoneLongitudeButton.contentDescription =
            if (lon == null) activity.getString(R.string.label_timezone_hint) else Zones.fromLongitude(lon)
    }
    view.lonInput.doAfterTextChanged { syncLongitudeHint() }
    syncLongitudeHint()

    view.zoneDeviceButton.setOnClickListener { view.zoneInput.setText(Zones.system()) }
    view.zoneLongitudeButton.setOnClickListener {
        val lon = view.lonInput.text.toString().toDoubleOrNull()
        if (lon == null) {
            toast(activity, activity.getString(R.string.invalid_latlon))
        } else {
            view.zoneInput.setText(Zones.fromLongitude(lon))
        }
    }

    val dialog = AlertDialog.Builder(activity)
        .setTitle(R.string.edit_group_title)
        .setView(view.root)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.confirm, null)
        .create()
    // 默认监听点完必关弹窗；这里被拒绝时保持打开，便于就地修正
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = view.nameInput.text.toString().trim()
            if (name.isEmpty()) {
                view.nameInput.error = activity.getString(R.string.group_name_required)
                return@setOnClickListener
            }
            val lat = view.latInput.text.toString().toDoubleOrNull()
            val lon = view.lonInput.text.toString().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                toast(activity, activity.getString(R.string.invalid_latlon))
                return@setOnClickListener
            }
            val zoneId = Zones.parse(view.zoneInput.text.toString())
            if (zoneId == null) {
                toast(activity, activity.getString(R.string.zone_invalid))
                return@setOnClickListener
            }
            GroupRepository.get(activity).editGroup(group.id, name, lat, lon, zoneId)
            dialog.dismiss()
        }
    }
    dialog.show()
}

private fun toast(activity: Activity, message: String) {
    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
